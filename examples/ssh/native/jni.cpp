// The app's JNI surface (Native.kt).
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
#include <clocale>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>

#include <android/log.h>

#include "android_transport.hpp"
#include "glasses.hpp"
#include "keys.hpp"
#include "mosh_client.hpp"
#include "screen.hpp"
#include "terminal.hpp"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "scouter", __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;
jclass g_native = nullptr; // scouterkit.ssh.Native
jmethodID g_changed = nullptr;
jmethodID g_gesture = nullptr; // Native.gesture(arm, gesture)
// Lock order: g_glasses_mutex, g_mutex, g_term_mutex, g_screen_mutex.
std::mutex g_glasses_mutex; // guards the glasses' objects (Init holds it up to 30 s)
std::mutex g_link_mutex; // guards g_transport for the link callbacks
std::shared_ptr<ssh::AndroidTransport> g_transport;
bool g_glasses = false;
ssh::GlassesConfig g_config;
int g_angle = 20;
bool g_ignore_worn = false;
std::vector<uint8_t> g_font;
std::mutex g_mutex; // guards g_mosh
std::unique_ptr<ssh::MoshClient> g_mosh;
std::atomic<ssh::MoshClient *> g_mosh_live{nullptr}; // between Start and Stop, for its own thread
std::mutex g_term_mutex;                          // guards g_term and g_force
std::shared_ptr<ssh::GlassesTerminal> g_term;
bool g_force = false;
// The phone's screen on the glasses instead of the terminal (screenStart), while wanted.
std::mutex g_screen_mutex; // guards g_screen and g_screen_wanted
std::shared_ptr<ssh::ScreenMirror> g_screen;
bool g_screen_wanted = false;
// Guarded by g_glasses_mutex: the display off (nothing shows, the panels are off), each eye's battery band
// beside the terminal, and whether Apply switched the panels off.
bool g_blank = false;
bool g_battery = true;
// Voice: lines shown in the terminal's top rows (above a rule) while voice is in use; empty: none.
std::mutex g_band_mutex;
std::vector<std::string> g_band;
int g_top = 0, g_bottom = 0; // pixels left empty above and below the terminal (terminalMargins)
bool g_panels_off = false;

// The JNIEnv of a native thread, attached on first use and detached when the
// thread ends.
JNIEnv *Env() {
  thread_local struct Attached {
    JNIEnv *env = nullptr;
    ~Attached() {
      if (env) g_vm->DetachCurrentThread();
    }
  } attached;
  if (!attached.env) {
    JavaVMAttachArgs args{JNI_VERSION_1_6, "scouter-mosh", nullptr};
    g_vm->AttachCurrentThread(&attached.env, &args);
  }
  return attached.env;
}

std::string Text(JNIEnv *env, jstring s) {
  if (!s) return {};
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string out(c);
  env->ReleaseStringUTFChars(s, c);
  return out;
}

template <typename T> void Put(std::string &out, T v) { out.append(reinterpret_cast<const char *>(&v), sizeof v); }

void Changed() { Env()->CallStaticVoidMethod(g_native, g_changed); }

// A TouchBar gesture to the app (Native.gesture), from any native thread.
void Gesture(int arm, uint32_t gesture) { Env()->CallStaticVoidMethod(g_native, g_gesture, jint(arm), jint(gesture)); }

// The arms' events while the terminal does not run (the phone's screen, the display off), for the TouchBar's
// gestures; while it runs, its own threads read them (one reader at a time).
class InputPump {
public:
  void Start() {
    if (thread_.joinable()) return;
    stop_ = false;
    thread_ = std::thread([this] { Run(); });
  }
  void Stop() {
    stop_ = true;
    if (thread_.joinable()) thread_.join();
  }

private:
  void Run() {
    while (!stop_) {
      bool any = false;
      for (int arm = 0; arm < 2; arm++) {
        scouter_kit::g1::Arm &a = arm == 0 ? scouter_kit::g1::left : scouter_kit::g1::right;
        if (!a.GetState().present) continue;
        scouter_kit::g1::Event e;
        while (!stop_ && a.NextEvent(&e, 0)) {
          any = true;
          if (e.type == scouter_kit::g1::EVENT_INPUT && e.trigger == scouter_kit::g1::TRIGGER_TOUCH) Gesture(arm, e.a);
        }
      }
      if (!any) std::this_thread::sleep_for(std::chrono::milliseconds(40));
    }
  }
  std::atomic<bool> stop_{false};
  std::thread thread_;
};
InputPump g_pump;

std::shared_ptr<ssh::AndroidTransport> Link() {
  std::lock_guard<std::mutex> lock(g_link_mutex);
  return g_transport;
}

void SetLink(std::shared_ptr<ssh::AndroidTransport> t) {
  std::lock_guard<std::mutex> lock(g_link_mutex);
  g_transport = std::move(t);
}

// UTF-8 text split into its characters.
std::vector<std::string> Chars(const std::string &s) {
  std::vector<std::string> out;
  for (size_t i = 0; i < s.size();) {
    unsigned char c = static_cast<unsigned char>(s[i]);
    size_t n = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 1;
    out.push_back(s.substr(i, n));
    i += n;
  }
  return out;
}

// The screen with the voice band over its top rows: the band's lines (bold), then a rule. The rows under it
// are hidden while it shows; the cursor too, when it is there.
std::shared_ptr<const ssh::Snapshot> WithBand(std::shared_ptr<const ssh::Snapshot> s) {
  std::vector<std::string> band;
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    band = g_band;
  }
  if (!s || band.empty() || s->rows < 4 || s->cols < 8) return s;
  auto out = std::make_shared<ssh::Snapshot>(*s);
  int lines = std::min<int>(int(band.size()), out->rows / 3);
  for (int r = 0; r <= lines; r++) {
    std::vector<std::string> chars = r < lines ? Chars(band[size_t(r)]) : std::vector<std::string>();
    for (int c = 0; c < out->cols; c++) {
      ssh::Cell cell;
      if (r < lines) {
        if (size_t(c) < chars.size()) cell.text = chars[size_t(c)];
        cell.bold = true;
      } else {
        cell.text = "\u2500";
        cell.faint = true;
      }
      out->cells[size_t(r) * out->cols + c] = cell;
    }
  }
  if (out->cursor_row <= lines) out->cursor_visible = false;
  return out;
}

// The terminal on the arms, with the latest screen (g_glasses_mutex held).
std::string StartTerminal() {
  auto term = std::make_shared<ssh::GlassesTerminal>();
  std::string error;
  ssh::GlassesTerminal::Config config;
  config.dx = g_config.dx;
  config.dy = g_config.dy;
  config.angle = g_angle;
  config.ignore_worn = g_ignore_worn;
  config.battery = g_battery;
  config.top = g_top;
  config.bottom = g_bottom;
  config.on_touch = [](int arm, uint32_t gesture) { Gesture(arm, gesture); };
  if (!term->Start(config, g_font, Changed, &error)) return error;
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  term->Force(g_force);
  if (s) term->Show(WithBand(s));
  g_term = term;
  return {};
}

void StopTerminal(bool keep = false) {
  std::shared_ptr<ssh::GlassesTerminal> term;
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    term.swap(g_term);
  }
  if (term) term->Stop(keep);
}

bool ScreenWanted() {
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  return g_screen_wanted;
}

void Panels(bool on) {
  for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::left, &scouter_kit::g1::right})
    if (a->GetState().connected) a->SetPanel(on);
}

// The mirror's object on the arms (g_glasses_mutex held, nothing else drawing).
std::string StartScreen() {
  auto screen = std::make_shared<ssh::ScreenMirror>();
  std::string error;
  if (!screen->Start(g_config.dx, g_config.dy, &error)) {
    LOG("screen: %s", error.c_str());
    return error;
  }
  LOG("screen: on the glasses");
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  g_screen = screen;
  return {};
}

// Returns whether a mirror ran; waits for a frame in progress.
bool StopScreen() {
  std::shared_ptr<ssh::ScreenMirror> screen;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    screen.swap(g_screen);
  }
  if (screen) screen->Stop();
  return screen != nullptr;
}

enum class Show { kNone, kTerminal, kScreen };

Show Current() {
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    if (g_term) return Show::kTerminal;
  }
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  return g_screen ? Show::kScreen : Show::kNone;
}

// What the glasses show, from the wishes (g_glasses_mutex held): nothing while the display is off, else the
// phone's screen while wanted, else the terminal. A mirror that cannot start gives the terminal (and the
// error). The input pump runs while the terminal does not.
std::string Apply() {
  if (!g_glasses) return {};
  Show want = g_blank ? Show::kNone : ScreenWanted() ? Show::kScreen : Show::kTerminal;
  Show now = Current();
  std::string error;
  if (now != want) {
    if (now == Show::kTerminal) StopTerminal(false);
    if (now == Show::kScreen) StopScreen();
    if (want == Show::kNone) {
      scouter_kit::gray::Clear();
      scouter_kit::Flush();
      Panels(false);
      g_panels_off = true;
    } else {
      g_pump.Stop();
      if (g_panels_off) Panels(true);
      g_panels_off = false;
      if (want == Show::kScreen) {
        error = StartScreen();
        if (!error.empty()) {
          std::lock_guard<std::mutex> lock(g_screen_mutex);
          g_screen_wanted = false;
          want = Show::kTerminal;
        }
      }
      if (want == Show::kTerminal) {
        std::string e = StartTerminal();
        if (error.empty()) error = e;
      }
    }
  } else if (want == Show::kNone && !g_panels_off) {
    Panels(false);
    g_panels_off = true;
  }
  if (Current() == Show::kTerminal) g_pump.Stop();
  else g_pump.Start();
  return error;
}

} // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
  g_vm = vm;
  setlocale(LC_ALL, "C.UTF-8"); // mosh's UTF-8 parser uses mbrtowc
  JNIEnv *env = nullptr;
  vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
  jclass c = env->FindClass("scouterkit/ssh/Native");
  g_native = static_cast<jclass>(env->NewGlobalRef(c));
  g_changed = env->GetStaticMethodID(c, "changed", "()V");
  g_gesture = env->GetStaticMethodID(c, "gesture", "(II)V");
  return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_version(JNIEnv *env, jclass) {
  return env->NewStringUTF("scouter_ssh 0.9, mosh 1.4.0");
}

// ------------------------------------------------ glasses

// Init through the link (null: the emulated arms), then the terminal. Blocks up to 30 s; an error or null.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_glassesStart(JNIEnv *env, jclass, jobject link, jstring left,
                                                                   jstring right, jint dx, jint dy, jint brightness,
                                                                   jint angle, jbyteArray font, jboolean ignore_worn,
                                                                   jboolean battery) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_glasses) return env->NewStringUTF("already started");
  g_config = {Text(env, left), Text(env, right), dx, dy, brightness};
  g_angle = angle;
  g_ignore_worn = ignore_worn;
  g_battery = battery;
  g_panels_off = false;
  g_font.resize(size_t(env->GetArrayLength(font)));
  env->GetByteArrayRegion(font, 0, jsize(g_font.size()), reinterpret_cast<jbyte *>(g_font.data()));
  if (link) SetLink(std::make_shared<ssh::AndroidTransport>(g_vm, env->NewGlobalRef(link)));
  std::string error;
  if (!ssh::GlassesStart(g_config, Link().get(), &error)) {
    SetLink(nullptr);
    return env->NewStringUTF(error.c_str());
  }
  // The TouchBar's gestures come as INPUT events (to the terminal's threads or the input pump).
  for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::left, &scouter_kit::g1::right})
    if (a->GetState().present) a->ReportInputs(uint16_t(1u << scouter_kit::g1::TRIGGER_TOUCH));
  g_glasses = true;
  error = Apply();
  return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_glassesStop(JNIEnv *, jclass, jboolean keep) {
  g_pump.Stop();
  StopScreen();
  StopTerminal(keep);
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_glasses) ssh::GlassesStop();
  g_glasses = false;
  SetLink(nullptr);
}


JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_force(JNIEnv *, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_term_mutex);
  g_force = on;
  if (g_term) g_term->Force(on);
}

// The emulated arm's panel, 640 x 480 levels 0..15 (emulator builds; null otherwise).
JNIEXPORT jbyteArray JNICALL Java_scouterkit_ssh_Native_emulatedPanel(JNIEnv *env, jclass, jint arm) {
#ifdef SCOUTER_G1_EMULATOR
  const uint8_t *p = scouter_kit::g1::emulator::Panel(arm);
  if (!p) return nullptr;
  jbyteArray out = env->NewByteArray(640 * 480);
  env->SetByteArrayRegion(out, 0, 640 * 480, reinterpret_cast<const jbyte *>(p));
  return out;
#else
  (void)env;
  (void)arm;
  return nullptr;
#endif
}

// The terminal's read-back check (T3), a few lines; blocks while the arms answer.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_check(JNIEnv *env, jclass) {
  std::shared_ptr<ssh::GlassesTerminal> term;
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    term = g_term;
  }
  return env->NewStringUTF(term ? term->Check().c_str() : "no terminal on the glasses\n");
}

// A sensor value of an emulated arm, such as PITCH (emulator builds).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_emulatedProperty(JNIEnv *, jclass, jint arm, jint id, jint value) {
#ifdef SCOUTER_G1_EMULATOR
  scouter_kit::g1::emulator::SetProperty(arm, scouter_kit::g1::Property(id), uint32_t(value));
#else
  (void)arm, (void)id, (void)value;
#endif
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_linkUp(JNIEnv *, jclass, jint arm, jint mtu) {
  if (auto t = Link()) t->LinkUp(arm, mtu);
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_linkDown(JNIEnv *, jclass, jint arm) {
  if (auto t = Link()) t->LinkDown(arm);
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_linkData(JNIEnv *env, jclass, jint arm, jbyteArray data) {
  auto t = Link();
  if (!t) return;
  jsize n = env->GetArrayLength(data);
  jbyte *b = env->GetByteArrayElements(data, nullptr);
  t->Data(arm, reinterpret_cast<const uint8_t *>(b), size_t(n));
  env->ReleaseByteArrayElements(data, b, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_linkFound(JNIEnv *env, jclass, jint arm, jstring address) {
  if (auto t = Link()) t->Found(arm, Text(env, address));
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_linkProblem(JNIEnv *env, jclass, jint code, jstring text) {
  if (auto t = Link()) t->Problem(scouter_kit::g1::InitError(code), Text(env, text));
}

// ------------------------------------------------ mosh

JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_moshStart(JNIEnv *env, jclass, jstring ip, jint port,
                                                                jstring key, jint cols, jint rows) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_mosh) return env->NewStringUTF("already started");
  auto mosh = std::make_unique<ssh::MoshClient>([] {
    if (ssh::MoshClient *m = g_mosh_live.load()) {
      std::lock_guard<std::mutex> lock(g_term_mutex);
      if (g_term)
        if (auto s = m->Latest()) g_term->Show(WithBand(s));
    }
    Changed();
  });
  g_mosh_live = mosh.get();
  std::string error;
  if (!mosh->Start(Text(env, ip), port, Text(env, key), cols, rows, &error)) {
    g_mosh_live = nullptr;
    return env->NewStringUTF(error.c_str());
  }
  g_mosh = std::move(mosh);
  return nullptr;
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_moshPause(JNIEnv *, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_mosh) g_mosh->Pause(on);
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_moshStop(JNIEnv *, jclass) {
  std::unique_ptr<ssh::MoshClient> mosh;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    mosh = std::move(g_mosh);
  }
  if (mosh) mosh->Stop();
  g_mosh_live = nullptr;
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_resize(JNIEnv *, jclass, jint cols, jint rows) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_mosh) g_mosh->Resize(cols, rows);
}

// A key from keyer (R17): encoded with the screen's cursor-key mode, then to mosh.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_keyEvent(JNIEnv *, jclass, jint kind, jint mods, jint value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_mosh) return;
  auto s = g_mosh->Latest();
  std::string bytes = ssh::EncodeKey(kind, uint8_t(mods), uint32_t(value), s && s->application_cursor_keys);
  if (!bytes.empty()) g_mosh->Keys(bytes);
}

// ------------------------------------------------ the phone's screen

// The glasses show the phone's screen (screenFrame) instead of the terminal, now or as soon as they are
// started. An error or null; on an error the terminal stays.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_screenStart(JNIEnv *env, jclass) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  {
    std::lock_guard<std::mutex> l(g_screen_mutex);
    g_screen_wanted = true;
  }
  std::string error = Apply();
  return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

// Back to the terminal (with the latest screen of the mosh session).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_screenStop(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  {
    std::lock_guard<std::mutex> l(g_screen_mutex);
    g_screen_wanted = false;
  }
  Apply();
}

// The display off (nothing shows, the panels off; the TouchBar still reports) or on again.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_blank(JNIEnv *, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  g_blank = on;
  Apply();
}

// Each eye's battery band beside the terminal, at once (a running terminal starts again).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_setBattery(JNIEnv *, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_battery == bool(on)) return;
  g_battery = on;
  if (!g_glasses || Current() != Show::kTerminal) return;
  StopTerminal(false);
  StartTerminal();
}

// Pixels left empty above and below the terminal on the glasses, at once (a running terminal starts again).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_terminalMargins(JNIEnv *, jclass, jint top, jint bottom) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_top == top && g_bottom == bottom) return;
  g_top = top;
  g_bottom = bottom;
  if (!g_glasses || Current() != Show::kTerminal) return;
  StopTerminal(false);
  StartTerminal();
}

// ------------------------------------------------ voice

} // extern "C"

namespace {
// The arm with the microphone (on the G1 the right one), or null.
scouter_kit::g1::Arm *MicArm() {
  for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::right, &scouter_kit::g1::left}) {
    auto st = a->GetState();
    if (st.present && st.connected && st.microphone) return a;
  }
  return nullptr;
}

// Words wrapped into lines of at most `cols` characters; the last `max` lines (the newest words).
std::vector<std::string> Wrap(const std::string &text, int cols, int max) {
  std::vector<std::string> lines;
  std::string line;
  size_t len = 0;
  std::string word;
  auto flush = [&] {
    if (!line.empty()) lines.push_back(line);
    line.clear();
    len = 0;
  };
  auto put = [&](const std::string &w) {
    size_t n = Chars(w).size();
    if (len && len + 1 + n > size_t(cols)) flush();
    if (len) {
      line += ' ';
      len++;
    }
    line += w;
    len += n;
  };
  for (char ch : text + " ") {
    if (ch == ' ' || ch == '\n') {
      if (!word.empty()) put(word);
      word.clear();
      if (ch == '\n') flush();
    } else {
      word += ch;
    }
  }
  flush();
  if (int(lines.size()) > max) lines.erase(lines.begin(), lines.end() - max);
  return lines;
}
} // namespace

extern "C" {

// The voice band over the terminal's top rows: the text wrapped into at most `lines` lines (the newest words);
// empty: the band goes. The terminal shows the change at once.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_voiceBand(JNIEnv *env, jclass, jstring text, jint lines) {
  std::string t = Text(env, text);
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  int cols = s ? s->cols : 80;
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    g_band = t.empty() ? std::vector<std::string>() : Wrap(t, cols, std::clamp(int(lines), 1, 4));
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  if (g_term && s) g_term->Show(WithBand(s));
}

// The glasses' microphone on (the arm that has one): false without glasses, such an arm, or if it refused.
JNIEXPORT jboolean JNICALL Java_scouterkit_ssh_Native_micStart(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (!g_glasses) return JNI_FALSE;
  scouter_kit::g1::Arm *a = MicArm();
  return a && a->MicStart(scouter_kit::g1::MIC_32KBPS) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_micStop(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (!g_glasses) return;
  for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::right, &scouter_kit::g1::left})
    if (a->GetState().present && a->GetMicState().on) a->MicStop();
}

// What the microphone heard since the last call (16 kHz mono, -1 ... 1), as much as fits in out; it does not
// wait. -1: the microphone is off (stopped, or the link dropped).
JNIEXPORT jint JNICALL Java_scouterkit_ssh_Native_micRead(JNIEnv *env, jclass, jfloatArray out) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (!g_glasses) return -1;
  scouter_kit::g1::Arm *arm = nullptr;
  for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::right, &scouter_kit::g1::left})
    if (a->GetState().present && a->GetMicState().on) arm = a;
  if (!arm) return -1;
  jsize cap = env->GetArrayLength(out);
  std::vector<float> got;
  scouter_kit::g1::MicFrame f;
  while (jsize(got.size()) + scouter_kit::g1::kMicFrameSamples <= cap && arm->MicRead(&f, 0))
    for (int16_t v : f.pcm) got.push_back(float(v) / 32768.0f);
  if (!got.empty()) env->SetFloatArrayRegion(out, 0, jsize(got.size()), got.data());
  return jint(got.size());
}

// Both arms' brightness (0 ... 42), at once.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_brightness(JNIEnv *, jclass, jint level) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  g_config.brightness = level;
  if (!g_glasses) return;
  scouter_kit::g1::left.SetBrightness(level);
  scouter_kit::g1::right.SetBrightness(level);
}

// Why the mirror keeps the panels off ("in the case", "folded"), or empty.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_screenWhy(JNIEnv *env, jclass) {
  std::shared_ptr<ssh::ScreenMirror> screen;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    screen = g_screen;
  }
  return env->NewStringUTF(screen ? screen->why().c_str() : "");
}

// Each arm's battery: left percent, right percent, left charging, right charging (-1: unknown). Blocks while
// the arms answer.
JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_battery(JNIEnv *env, jclass) {
  jint v[4] = {-1, -1, -1, -1};
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_glasses) {
    int arm = 0;
    for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::left, &scouter_kit::g1::right}) {
      int mv = 0, pct = -1;
      bool charging = false;
      if (a->GetState().connected && a->Battery(&mv, &pct, &charging)) {
        v[arm] = pct;
        v[arm + 2] = charging ? 1 : 0;
      }
      arm++;
    }
  }
  jintArray out = env->NewIntArray(4);
  env->SetIntArrayRegion(out, 0, 4, v);
  return out;
}

} // extern "C"

namespace {

// Settings.screenOptions: fit, pan x, pan y, zoom, rotate, place x, y, w, h, invert %, levels, contrast,
// sharpen, hide in the case, hide folded, gamma (x100), threshold, steady, dither, detail (0 auto), budget (KB).
ssh::ScreenOptions Options(JNIEnv *env, jintArray options) {
  jint v[21] = {0, 50, 0, 100, 0, 0, 0, 100, 100, 100, 2, 0, 0, 1, 0, 100, 128, 1, 0, 1, 6};
  jsize n = std::min<jsize>(env->GetArrayLength(options), 21);
  env->GetIntArrayRegion(options, 0, n, v);
  ssh::ScreenOptions o;
  o.fit = std::clamp(int(v[0]), 0, 2);
  o.pan_x = v[1];
  o.pan_y = v[2];
  o.zoom = std::clamp(int(v[3]), 100, 400);
  o.rotate = v[4];
  o.out_x = v[5];
  o.out_y = v[6];
  o.out_w = v[7];
  o.out_h = v[8];
  o.invert = std::clamp(int(v[9]), 0, 100);
  o.gamma = std::clamp(int(v[15]), 20, 500);
  o.threshold = std::clamp(int(v[16]), 1, 254);
  o.steady = v[17] != 0;
  o.dither = v[18] != 0;
  o.levels = v[10] == 2 || v[10] == 4 || v[10] == 8 ? int(v[10]) : 16;
  o.contrast = std::clamp(int(v[11]), 0, 100);
  o.sharpen = v[12] != 0;
  o.hide_case = v[13] != 0;
  o.hide_folded = v[14] != 0 && !g_ignore_worn;
  o.detail = std::clamp(int(v[19]), 0, 4);
  o.budget = std::clamp(int(v[20]), 1, 64);
  return o;
}

std::shared_ptr<ssh::ScreenMirror> Mirror() {
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  return g_screen;
}

// The ImageReader plane's pixels, or null (the last row may lack its padding).
const uint8_t *Pixels(JNIEnv *env, jobject buffer, jint w, jint h, jint stride) {
  auto *p = static_cast<const uint8_t *>(env->GetDirectBufferAddress(buffer));
  jlong capacity = env->GetDirectBufferCapacity(buffer);
  if (!p || w <= 0 || h <= 0 || stride < w * 4 || capacity < jlong(stride) * (h - 1) + jlong(w) * 4) return nullptr;
  return p;
}

} // namespace

extern "C" {

// One captured frame: a direct buffer of RGBA_8888 rows `stride` bytes apart (an ImageReader plane). Blocks
// while the previous frame is not on the display yet. ScreenMirror's kShown, kSame, kHidden, kStopped, kBusy
// or kLater.
JNIEXPORT jint JNICALL Java_scouterkit_ssh_Native_screenFrame(JNIEnv *env, jclass, jobject buffer, jint w, jint h,
                                                               jint stride, jintArray options) {
  std::shared_ptr<ssh::ScreenMirror> screen = Mirror();
  if (!screen) return ssh::ScreenMirror::kStopped;
  const uint8_t *p = Pixels(env, buffer, w, h, stride);
  if (!p) return ssh::ScreenMirror::kSame;
  return screen->Frame(p, w, h, stride, Options(env, options));
}

// What the glasses show (the last picture sent), else what they would show for this frame (the glasses off,
// hidden, or showing the terminal), into out (at least 640 x 480 bytes, 0 ... 255, row by row). info: width,
// height, sent (1) or not (0). False for a bad frame.
JNIEXPORT jboolean JNICALL Java_scouterkit_ssh_Native_screenPreview(JNIEnv *env, jclass, jobject buffer, jint w,
                                                                     jint h, jint stride, jintArray options, jint dx,
                                                                     jint dy, jbyteArray out, jintArray info) {
  std::vector<uint8_t> pic;
  int pw = 0, ph = 0, sent = 0;
  if (auto screen = Mirror(); screen && screen->Preview(&pic, &pw, &ph)) {
    sent = 1;
  } else {
    const uint8_t *p = Pixels(env, buffer, w, h, stride);
    if (!p) return JNI_FALSE;
    ssh::PictureSize(dx, dy, &pw, &ph);
    ssh::Picture(p, w, h, stride, Options(env, options), pw, ph, &pic);
  }
  if (env->GetArrayLength(out) < jsize(pic.size()) || env->GetArrayLength(info) < 3) return JNI_FALSE;
  env->SetByteArrayRegion(out, 0, jsize(pic.size()), reinterpret_cast<const jbyte *>(pic.data()));
  jint v[3] = {pw, ph, sent};
  env->SetIntArrayRegion(info, 0, 3, v);
  return JNI_TRUE;
}

// The mirror's numbers (ScreenMirror::Stats): frames, last frame's bytes, bytes a second, detail, busy, w, h,
// x, y, path levels (0: none), panels off; all 0 without a mirror.
JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_screenStats(JNIEnv *env, jclass) {
  jint v[11] = {};
  if (auto screen = Mirror()) {
    ssh::ScreenMirror::Stats s = screen->stats();
    jint t[11] = {s.frames, s.bytes, s.rate, s.detail, s.busy, s.w, s.h, s.x, s.y, s.path, s.hidden};
    std::copy(t, t + 11, v);
  }
  jintArray a = env->NewIntArray(11);
  env->SetIntArrayRegion(a, 0, 11, v);
  return a;
}

// Each eye's panel read back against what was sent (blocks for a second or more): a line per eye.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_screenCheck(JNIEnv *env, jclass) {
  auto screen = Mirror();
  return env->NewStringUTF(screen ? screen->Check().c_str() : "the phone's screen is not on the glasses\n");
}

// heard, ended, silent ms, then the arms: connected left, right, the terminal shown on an eye, and the phone's
// screen: mirrored (1), frames shown; the display off (1).
JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_status(JNIEnv *env, jclass) {
  jint v[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
  {
    std::unique_lock<std::mutex> glasses(g_glasses_mutex, std::try_to_lock); // not while Init runs
    if (glasses.owns_lock() && g_glasses) {
      v[3] = scouter_kit::g1::left.GetState().connected;
      v[4] = scouter_kit::g1::right.GetState().connected;
      v[8] = g_blank;
    }
  }
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) {
      ssh::Status s = g_mosh->GetStatus();
      v[0] = s.heard;
      v[1] = s.ended;
      v[2] = jint(std::min<int64_t>(s.silent_ms, INT32_MAX));
    }
  }
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    if (g_term) v[5] = g_term->Shown();
  }
  std::shared_ptr<ssh::ScreenMirror> screen;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    screen = g_screen;
  }
  if (screen) {
    v[6] = 1;
    v[7] = screen->frames();
  }
  jintArray out = env->NewIntArray(9);
  env->SetIntArrayRegion(out, 0, 9, v);
  return out;
}

// The mosh session's last error and the terminal's title.
JNIEXPORT jobjectArray JNICALL Java_scouterkit_ssh_Native_texts(JNIEnv *env, jclass) {
  std::string error, title;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) {
      error = g_mosh->GetStatus().error;
      if (auto s = g_mosh->Latest()) title = s->title;
    }
  }
  jobjectArray out = env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
  env->SetObjectArrayElement(out, 0, env->NewStringUTF(error.c_str()));
  env->SetObjectArrayElement(out, 1, env->NewStringUTF(title.c_str()));
  return out;
}

// The latest screen for the phone's mirror (TerminalView.parse): little-endian u16 cols, rows, cursor row,
// cursor column, u8 flags (1 cursor visible, 2 application cursor keys), u16 title length and the title;
// then per cell u32 fg, u32 bg, u8 attributes (1 bold, 2 faint, 4 italic, 8 underline, 16 inverse,
// 32 invisible, 64 wide), u8 length and the UTF-8 text.
JNIEXPORT jbyteArray JNICALL Java_scouterkit_ssh_Native_snapshot(JNIEnv *env, jclass) {
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  if (!s) return nullptr;
  std::string out;
  Put<uint16_t>(out, s->cols);
  Put<uint16_t>(out, s->rows);
  Put<uint16_t>(out, s->cursor_row);
  Put<uint16_t>(out, s->cursor_col);
  Put<uint8_t>(out, (s->cursor_visible ? 1 : 0) | (s->application_cursor_keys ? 2 : 0));
  std::string title = s->title.substr(0, 255);
  Put<uint16_t>(out, title.size());
  out += title;
  for (const ssh::Cell &c : s->cells) {
    Put<uint32_t>(out, c.fg);
    Put<uint32_t>(out, c.bg);
    Put<uint8_t>(out, c.bold | c.faint << 1 | c.italic << 2 | c.underline << 3 | c.inverse << 4 | c.invisible << 5 |
                          c.wide << 6);
    std::string text = c.text.substr(0, 255);
    Put<uint8_t>(out, text.size());
    out += text;
  }
  jbyteArray a = env->NewByteArray(jsize(out.size()));
  env->SetByteArrayRegion(a, 0, jsize(out.size()), reinterpret_cast<const jbyte *>(out.data()));
  return a;
}

} // extern "C"
