// The app's JNI surface (Native.kt).
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <thread>
#include <clocale>
#include <cstdint>
#include <cstring>
#include <map>
#include <memory>
#include <mutex>
#include <string>

#include <android/log.h>

#include "android_transport.hpp"
#include "fx.hpp"
#include "glasses.hpp"
#include "keys.hpp"
#include "mosh_client.hpp"
#include "screen.hpp"
#include "terminal.hpp"
#include "tunes.hpp"

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
std::mutex g_mutex; // guards g_moshes and g_mosh
// Each terminal's mosh session, by the app's id (a saved server's): they all run side by side, so one that
// cannot connect holds up no other, and a switch between them is at once. One is shown (g_mosh).
std::map<std::string, std::unique_ptr<ssh::MoshClient>> g_moshes;
ssh::MoshClient *g_mosh = nullptr;                  // the session shown (in g_moshes), or none
std::atomic<ssh::MoshClient *> g_mosh_live{nullptr}; // g_mosh, for the sessions' own threads
std::mutex g_term_mutex;                          // guards g_term and g_force
std::shared_ptr<ssh::GlassesTerminal> g_term;
bool g_force = false;
// The phone's screen on the glasses instead of the terminal (screenStart), while wanted.
// Tunes (Native.tunes): the synthwave scene instead of everything else while wanted. Guarded by g_screen_mutex.
std::shared_ptr<ssh::TunesScene> g_tunes;
bool g_tunes_wanted = false;
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
// A card over everything on the terminal (the help, the quick settings): lines with their marks (1 bold, 2 faint,
// 3 highlighted), the whole terminal or a strip at its bottom; guarded by g_band_mutex.
std::vector<std::string> g_overlay;
std::vector<int> g_overlay_marks;
bool g_overlay_full = false;
// The look-up header (lookUpHeader): lines above the terminal and a rule under them; the app gives the terminal
// as many rows fewer. Guarded by g_band_mutex.
std::vector<std::string> g_header;
int g_fade = 400; // the look-up fade, ms (terminalFade)
int g_dash_h = 0;  // the dashboard's height above the terminal (terminalDash)
// A card the terminal shows in place of the session's screen (card): what is chosen and why it cannot show
// yet. Guarded by g_band_mutex; null: none.
std::shared_ptr<const ssh::Snapshot> g_card;
// The pointer (pointer): a cell of the SSH session's screen shown inverted; -1: none. Guarded by g_band_mutex.
int g_pointer_row = -1, g_pointer_col = -1;
// The arrow (pointerAt): where, and whether it shows; guarded by g_term_mutex.
int g_ptr_x = 0, g_ptr_y = 0;
bool g_ptr_on = false;
std::vector<uint8_t> g_dash; // its last picture (dash), for a terminal that starts again; guarded by g_term_mutex
int g_top = 0, g_bottom = 0; // pixels left empty above and below the terminal (terminalMargins)
bool g_panels_off = false;
// Transitions (Native.transition): the arms' code for them, and the one asked for until the picture it is for
// comes (a terminal's next screen, or a pipeline that starts). g_fx_mutex is a leaf: nothing is locked under it.
struct FxPending {
  int style = 0;
  bool dash = false;       // the terminal's dashboard comes with it
  bool to_terminal = true; // the terminal's path shows next (else a screen)
  int64_t until = 0;
};
std::mutex g_fx_mutex;
std::shared_ptr<ssh::Fx> g_fx;
// The terminal's fonts, kept on the arms while it does not run (a screen, the display off), so that it starts
// again without uploading them. g_fonts_mutex is a leaf.
std::mutex g_fonts_mutex;
ssh::GlassesTerminal::Fonts g_fonts;

void DropFonts() {
  ssh::GlassesTerminal::Fonts f;
  {
    std::lock_guard<std::mutex> lock(g_fonts_mutex);
    std::swap(f, g_fonts);
  }
  if (f.font) scouter_kit::Unload(*f.font);
  if (f.batt) scouter_kit::Unload(*f.batt);
}
FxPending g_fx_pending;
bool g_fx_enabled = true;

std::shared_ptr<ssh::Fx> FxNow() {
  std::lock_guard<std::mutex> lock(g_fx_mutex);
  return g_fx_enabled ? g_fx : nullptr;
}

// The transition asked for, when it is for this path and has not lapsed: taken (once).
FxPending TakeFx(bool terminal) {
  std::lock_guard<std::mutex> lock(g_fx_mutex);
  FxPending p = g_fx_pending;
  if (!p.style || !g_fx_enabled || p.to_terminal != terminal || scouter_kit::g1::NowUs() > p.until) return {};
  g_fx_pending = {};
  return p;
}

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

// A row of text: bold or faint, padded with blanks.
void PutRow(ssh::Snapshot *out, int r, const std::string &text, bool bold, bool faint) {
  std::vector<std::string> chars = Chars(text);
  for (int c = 0; c < out->cols; c++) {
    ssh::Cell cell;
    if (size_t(c) < chars.size()) cell.text = chars[size_t(c)];
    cell.bold = bold;
    cell.faint = faint;
    out->cells[size_t(r) * out->cols + c] = cell;
  }
}

// The screen the glasses get. With the look-up header: its lines (the first bold) and a rule above the
// terminal, the voice band in the header's place while voice is in use. Without it, the voice band (bold
// lines, then a rule) over the terminal's top rows, hiding them (and the cursor there) while it shows.
std::shared_ptr<const ssh::Snapshot> WithBandOnly(std::shared_ptr<const ssh::Snapshot> s) {
  std::vector<std::string> band, header;
  int prow, pcol;
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    band = g_band;
    header = g_header;
    prow = g_pointer_row;
    pcol = g_pointer_col;
    if (g_card) s = g_card;
  }
  if (!s || s->cols < 8) return s;
  // The pointer: its cell inverted (a block), on a copy.
  if (prow >= 0 && pcol >= 0 && prow < s->rows && pcol < s->cols) {
    auto p = std::make_shared<ssh::Snapshot>(*s);
    ssh::Cell &cell = p->cells[size_t(prow) * p->cols + pcol];
    cell.inverse = !cell.inverse;
    cell.bold = true;
    s = p;
  }
  if (!header.empty()) {
    int h = int(header.size()) + 1;
    auto out = std::make_shared<ssh::Snapshot>(*s);
    out->rows = s->rows + h;
    out->cells.assign(size_t(out->rows) * out->cols, ssh::Cell());
    std::copy(s->cells.begin(), s->cells.end(), out->cells.begin() + ptrdiff_t(h) * out->cols);
    out->cursor_row = s->cursor_row + h;
    bool voice = !band.empty();
    for (int r = 0; r < h - 1; r++) {
      // The band's newest lines fill the header's rows from the top.
      size_t i = voice ? band.size() - std::min(band.size(), size_t(h - 1)) + size_t(r) : size_t(r);
      std::string line = voice ? (i < band.size() ? band[i] : "") : header[size_t(r)];
      PutRow(out.get(), r, line, voice || r == 0, false);
    }
    std::string rule;
    for (int c = 0; c < out->cols; c++) rule += "\u2500";
    PutRow(out.get(), h - 1, rule, false, true);
    return out;
  }
  if (band.empty() || s->rows < 4) return s;
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

// The screen the glasses get: WithBandOnly's, and the overlay (Native.overlay) over all of it.
std::shared_ptr<const ssh::Snapshot> WithBand(std::shared_ptr<const ssh::Snapshot> s) {
  s = WithBandOnly(std::move(s));
  std::vector<std::string> lines;
  std::vector<int> marks;
  bool full;
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    lines = g_overlay;
    marks = g_overlay_marks;
    full = g_overlay_full;
  }
  if (lines.empty() || !s || s->cols < 8 || s->rows < 3) return s;
  auto out = std::make_shared<ssh::Snapshot>(*s);
  int n = std::min(int(lines.size()), out->rows - (full ? 0 : 1));
  int top = 0;
  if (full) {
    out->cells.assign(size_t(out->rows) * out->cols, ssh::Cell());
  } else {
    top = out->rows - n - 1;
    std::string rule;
    for (int c = 0; c < out->cols; c++) rule += "\u2500";
    PutRow(out.get(), top++, rule, false, true);
  }
  for (int i = 0; i < n; i++) {
    int mark = i < int(marks.size()) ? marks[size_t(i)] : 0;
    PutRow(out.get(), top + i, lines[size_t(i)], mark == 1 || mark == 3, mark == 2);
    if (mark == 3)
      for (int c = 0; c < out->cols; c++) out->cells[size_t(top + i) * out->cols + c].inverse = true;
  }
  out->cursor_visible = false;
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
  config.fade_ms = g_fade;
  config.dash_h = g_dash_h;
  config.on_touch = [](int arm, uint32_t gesture) { Gesture(arm, gesture); };
  config.fx = FxNow();
  ssh::GlassesTerminal::Fonts fonts;
  {
    std::lock_guard<std::mutex> lock(g_fonts_mutex);
    std::swap(fonts, g_fonts);
  }
  config.fonts = &fonts;
  if (FxPending fx = TakeFx(true); fx.style) term->Reveal(fx.style, true);
  bool started = term->Start(config, g_font, Changed, &error);
  if (fonts.font) scouter_kit::Unload(*fonts.font); // not taken
  if (fonts.batt) scouter_kit::Unload(*fonts.batt);
  if (!started) return error;
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  term->Force(g_force);
  if (!g_dash.empty()) term->SetDash(g_dash);
  if (auto w = WithBand(s)) term->Show(w);
  if (g_ptr_on) term->Pointer(g_ptr_x, g_ptr_y, true);
  g_term = term;
  return {};
}

// keep_fonts: its fonts stay on the arms for its next start (g_fonts).
void StopTerminal(bool keep = false, bool keep_fonts = false) {
  std::shared_ptr<ssh::GlassesTerminal> term;
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    term.swap(g_term);
  }
  if (!term) return;
  ssh::GlassesTerminal::Fonts fonts;
  term->Stop(keep, keep_fonts ? &fonts : nullptr);
  if (!fonts.font) return;
  DropFonts();
  std::lock_guard<std::mutex> lock(g_fonts_mutex);
  g_fonts = fonts;
}

bool ScreenWanted() {
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  return g_screen_wanted;
}

void Panels(bool on) {
  for (scouter_kit::g1::Arm *a : {&scouter_kit::g1::left, &scouter_kit::g1::right})
    if (a->GetState().connected) a->SetPanel(on);
}

// The panels off once the transition playing now has ended (the display's switch-off: its CRT close is seen);
// a later switch-on (g_off_token) cancels it.
uint64_t g_off_token = 0;
void PanelsOffAfterFx() {
  auto fx = FxNow();
  int64_t until = fx ? fx->busy_until() : 0, now = scouter_kit::g1::NowUs();
  if (until <= now) return Panels(false);
  uint64_t token = ++g_off_token;
  std::thread([token, wait = until - now + 30000] {
    std::this_thread::sleep_for(std::chrono::microseconds(wait));
    std::lock_guard<std::mutex> lock(g_glasses_mutex);
    if (token == g_off_token && g_panels_off && g_glasses) Panels(false);
  }).detach();
}

// The cursor on the phone's screen (screenPointer): kept for the next mirror; guarded by g_screen_mutex.
int g_sptr_x = 5000, g_sptr_y = 5000;
bool g_sptr_on = false;

// A transition plays now (its out blacks the picture's place out): the next path need not clear the panels.
bool FxCovers() {
  auto fx = FxNow();
  return fx && fx->busy_until() > scouter_kit::g1::NowUs();
}

// The mirror's object on the arms (g_glasses_mutex held, nothing else drawing).
std::string StartScreen() {
  auto screen = std::make_shared<ssh::ScreenMirror>();
  std::string error;
  bool keep = FxCovers();
  if (!screen->Start(g_config.dx, g_config.dy, &error, keep)) {
    LOG("screen: %s", error.c_str());
    return error;
  }
  LOG("screen: on the glasses");
  // A transition: the first picture revealed (and its label again if Start cleared the panels).
  if (FxPending p = TakeFx(false); p.style)
    if (auto fx = FxNow()) {
      screen->Reveal(p.style, fx);
      if (!keep) fx->Label(screen->FxRegion());
    }
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  g_screen = screen;
  screen->Pointer(g_sptr_x, g_sptr_y, g_sptr_on);
  return {};
}

// Returns whether a mirror ran; waits for a frame in progress.
bool StopScreen() {
  std::shared_ptr<ssh::ScreenMirror> screen;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    screen.swap(g_screen);
  }
  if (screen) screen->Stop(FxCovers());
  return screen != nullptr;
}

enum class Show { kNone, kTerminal, kScreen, kTunes };

Show Current() {
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    if (g_term) return Show::kTerminal;
  }
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  return g_tunes ? Show::kTunes : g_screen ? Show::kScreen : Show::kNone;
}

bool TunesWanted() {
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  return g_tunes_wanted;
}

std::string StartTunes() {
  auto t = std::make_shared<ssh::TunesScene>();
  std::string error;
  if (!t->Start(g_config.dx, g_config.dy, g_ignore_worn, &error)) {
    LOG("tunes: %s", error.c_str());
    return error;
  }
  std::lock_guard<std::mutex> lock(g_screen_mutex);
  g_tunes = t;
  return {};
}

void StopTunes() {
  std::shared_ptr<ssh::TunesScene> t;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    t.swap(g_tunes);
  }
  if (t) t->Stop();
}

// What the glasses show, from the wishes (g_glasses_mutex held): nothing while the display is off, else the
// phone's screen while wanted, else the terminal. A mirror that cannot start gives the terminal (and the
// error). The input pump runs while the terminal does not.
std::string Apply() {
  if (!g_glasses) return {};
  Show want = g_blank ? Show::kNone : TunesWanted() ? Show::kTunes : ScreenWanted() ? Show::kScreen : Show::kTerminal;
  Show now = Current();
  std::string error;
  int64_t t0 = scouter_kit::g1::NowUs();
  if (now != want) {
    if (now == Show::kTerminal) StopTerminal(false, true);
    if (now == Show::kScreen) StopScreen();
    if (now == Show::kTunes) StopTunes();
    if (want == Show::kNone) {
      scouter_kit::gray::Clear();
      scouter_kit::Flush();
      PanelsOffAfterFx();
      g_panels_off = true;
    } else {
      g_pump.Stop();
      ++g_off_token;
      if (g_panels_off) Panels(true);
      g_panels_off = false;
      if (want == Show::kTunes) {
        error = StartTunes();
        if (!error.empty()) {
          std::lock_guard<std::mutex> lock(g_screen_mutex);
          g_tunes_wanted = false;
          want = ScreenWanted() ? Show::kScreen : Show::kTerminal;
        }
      }
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
    PanelsOffAfterFx();
    g_panels_off = true;
  }
  if (Current() == Show::kTerminal) g_pump.Stop();
  else g_pump.Start();
  if (now != want)
    LOG("switch: %d -> %d took %lld ms on the phone", int(now), int(Current()),
        (long long)((scouter_kit::g1::NowUs() - t0) / 1000));
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
  return env->NewStringUTF("scouter_ssh 0.19, mosh 1.4.0");
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
  // A screen short of arena takes back the terminal's kept fonts first.
  ssh::SetSpare(DropFonts);
  // The transitions' code on the arms (without it every switch is at once).
  auto fx = std::make_shared<ssh::Fx>();
  std::string fx_error;
  if (fx->Start(&fx_error)) {
    std::lock_guard<std::mutex> l(g_fx_mutex);
    g_fx = fx;
  } else {
    LOG("fx: %s", fx_error.c_str());
  }
  error = Apply();
  return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_glassesStop(JNIEnv *, jclass, jboolean keep) {
  g_pump.Stop();
  StopScreen();
  StopTunes();
  StopTerminal(keep);
  DropFonts();
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  std::shared_ptr<ssh::Fx> fx;
  {
    std::lock_guard<std::mutex> l(g_fx_mutex);
    fx.swap(g_fx);
    g_fx_pending = {};
  }
  if (fx) fx->Stop();
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

JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_moshStart(JNIEnv *env, jclass, jstring jid, jstring ip,
                                                                jint port, jstring key, jint cols, jint rows) {
  std::string id = Text(env, jid);
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_moshes.count(id)) return env->NewStringUTF("already started");
  // On its own thread: shown on the glasses while it is the one shown, else only kept up to date.
  auto self = std::make_shared<std::atomic<ssh::MoshClient *>>(nullptr);
  auto mosh = std::make_unique<ssh::MoshClient>([self] {
    ssh::MoshClient *m = self->load();
    if (m && m == g_mosh_live.load()) {
      std::lock_guard<std::mutex> lock(g_term_mutex);
      if (g_term)
        if (auto w = WithBand(m->Latest())) g_term->Show(w);
    }
    Changed();
  });
  self->store(mosh.get());
  std::string error;
  if (!mosh->Start(Text(env, ip), port, Text(env, key), cols, rows, &error)) {
    self->store(nullptr);
    return env->NewStringUTF(error.c_str());
  }
  g_moshes[id] = std::move(mosh);
  return nullptr;
}

// The session shown on the glasses (and typed into): that id's; false (none shown) when it has none.
JNIEXPORT jboolean JNICALL Java_scouterkit_ssh_Native_moshShow(JNIEnv *env, jclass, jstring jid) {
  std::string id = Text(env, jid);
  std::shared_ptr<const ssh::Snapshot> s;
  bool found;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_moshes.find(id);
    g_mosh = it == g_moshes.end() ? nullptr : it->second.get();
    g_mosh_live = g_mosh;
    found = g_mosh != nullptr;
    if (g_mosh) s = g_mosh->Latest();
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  if (g_term) {
    if (FxPending fx = TakeFx(true); fx.style) g_term->Reveal(fx.style, fx.dash);
    if (auto w = WithBand(s)) g_term->Show(w);
  }
  return found ? JNI_TRUE : JNI_FALSE;
}

// heard, ended, silent ms of that id's session; null: there is none.
JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_moshStatus(JNIEnv *env, jclass, jstring jid) {
  std::string id = Text(env, jid);
  jint v[3] = {0, 0, 0};
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_moshes.find(id);
    if (it == g_moshes.end()) return nullptr;
    ssh::Status st = it->second->GetStatus();
    v[0] = st.heard;
    v[1] = st.ended;
    v[2] = jint(std::min<int64_t>(st.silent_ms, INT32_MAX));
  }
  jintArray out = env->NewIntArray(3);
  env->SetIntArrayRegion(out, 0, 3, v);
  return out;
}

// Every session quiet (keyer gone, a screen shown) or going again.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_moshPause(JNIEnv *, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_mutex);
  for (auto &m : g_moshes) m.second->Pause(on);
}

// That id's session ends (its mosh-server goes by itself after a while).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_moshStop(JNIEnv *env, jclass, jstring jid) {
  std::string id = Text(env, jid);
  std::unique_ptr<ssh::MoshClient> mosh;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    auto it = g_moshes.find(id);
    if (it == g_moshes.end()) return;
    mosh = std::move(it->second);
    g_moshes.erase(it);
    if (g_mosh == mosh.get()) {
      g_mosh = nullptr;
      g_mosh_live = nullptr;
    }
  }
  mosh->Stop();
}

// Every session's size (they share the glasses).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_resize(JNIEnv *, jclass, jint cols, jint rows) {
  std::lock_guard<std::mutex> lock(g_mutex);
  for (auto &m : g_moshes) m.second->Resize(cols, rows);
}

// A card in place of the shown session's screen: its lines centred (the first bold, the last faint, when more
// than one), on a terminal of cols x rows; none (no lines): the session's screen again.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_card(JNIEnv *env, jclass, jobjectArray lines, jint cols, jint rows) {
  std::vector<std::string> l;
  jsize n = lines ? env->GetArrayLength(lines) : 0;
  for (jsize i = 0; i < n; i++) {
    auto js = static_cast<jstring>(env->GetObjectArrayElement(lines, i));
    l.push_back(js ? Text(env, js) : std::string());
    if (js) env->DeleteLocalRef(js);
  }
  std::shared_ptr<ssh::Snapshot> card;
  if (!l.empty() && cols >= 8 && rows > 0) {
    card = std::make_shared<ssh::Snapshot>();
    card->cols = cols;
    card->rows = rows;
    card->cells.assign(size_t(cols) * size_t(rows), ssh::Cell());
    card->cursor_visible = false;
    int top = std::max(0, (rows - int(l.size())) / 2);
    for (size_t i = 0; i < l.size() && top + int(i) < rows; i++) {
      std::vector<std::string> chars = Chars(l[i]);
      int w = std::min<int>(int(chars.size()), cols);
      int left = (cols - w) / 2;
      for (int c = 0; c < w; c++) {
        ssh::Cell &cell = card->cells[size_t(top + int(i)) * size_t(cols) + size_t(left + c)];
        cell.text = chars[size_t(c)];
        cell.bold = i == 0;
        cell.faint = l.size() > 1 && i == l.size() - 1;
      }
    }
  }
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    g_card = card;
  }
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  if (g_term) {
    if (FxPending fx = TakeFx(true); fx.style) g_term->Reveal(fx.style, fx.dash);
    if (auto w = WithBand(s)) g_term->Show(w);
  }
}

// A key from keyer (R17): encoded with the screen's cursor-key mode, then to mosh.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_keyEvent(JNIEnv *, jclass, jint kind, jint mods, jint value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_mosh) return;
  auto s = g_mosh->Latest();
  std::string bytes = ssh::EncodeKey(kind, uint8_t(mods), uint32_t(value), s && s->application_cursor_keys);
  if (!bytes.empty()) g_mosh->Keys(bytes);
}

// ------------------------------------------------ transitions

// A switch is coming (Fx.kt): what shows goes now (style's out, then the label: 4 bpp, lw x lh), and the next
// picture comes with the style's reveal: the terminal's next screen (to_terminal) or the next screen's first
// picture. now: the terminal's next screen whatever brings it (its tabs); otherwise the screen of the session or
// card shown next, or the path that starts next. kOn: no out (the display was off).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_transition(JNIEnv *env, jclass, jint style, jbyteArray label,
                                                             jint lw, jint lh, jboolean to_terminal, jboolean now) {
  std::vector<uint8_t> pixels;
  if (label) {
    pixels.resize(size_t(env->GetArrayLength(label)));
    env->GetByteArrayRegion(label, 0, jsize(pixels.size()), reinterpret_cast<jbyte *>(pixels.data()));
  }
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  auto fx = FxNow();
  if (!g_glasses || !fx || style <= ssh::Fx::kNone || style >= ssh::Fx::kStyles) return;
  Show cur = g_blank ? Show::kNone : Current();
  // The same path goes on: only the terminal goes (its dashboard stays), or the screen's picture; another
  // path: all of it.
  bool same = (cur == Show::kTerminal && to_terminal) || (cur == Show::kScreen && !to_terminal);
  ssh::Fx::Region r;
  {
    std::lock_guard<std::mutex> l(g_term_mutex);
    if (cur == Show::kTerminal && g_term) {
      r = g_term->FxRegion(!same);
      if (style != ssh::Fx::kOn) g_term->Wiped(!same);
    }
  }
  {
    std::lock_guard<std::mutex> l(g_screen_mutex);
    if (cur == Show::kScreen && g_screen) {
      r = g_screen->FxRegion();
      if (style != ssh::Fx::kOn) g_screen->Wiped();
    }
  }
  if (style != ssh::Fx::kOn && r.w > 0) fx->Out(ssh::Fx::Style(style), r, pixels, lw, lh);
  if (style == ssh::Fx::kOff) return; // the display goes off: nothing comes
  FxPending p{style, !same, bool(to_terminal), scouter_kit::g1::NowUs() + 4000000};
  // The tabs (the same session's next screen), or a screen after a screen (its next picture): attached now.
  if (same && (now || !to_terminal)) {
    if (to_terminal) {
      std::lock_guard<std::mutex> l(g_term_mutex);
      if (g_term) g_term->Reveal(style, false);
    } else {
      std::lock_guard<std::mutex> l(g_screen_mutex);
      if (g_screen) g_screen->Reveal(style, fx);
    }
    return;
  }
  std::lock_guard<std::mutex> l(g_fx_mutex);
  g_fx_pending = p;
}

// Tunes: the synthwave scene on the glasses instead of everything else (on), or what was there again. An error or
// null.
JNIEXPORT jstring JNICALL Java_scouterkit_ssh_Native_tunes(JNIEnv *env, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  {
    std::lock_guard<std::mutex> l(g_screen_mutex);
    g_tunes_wanted = on;
  }
  std::string error = Apply();
  return error.empty() ? nullptr : env->NewStringUTF(error.c_str());
}

// The music's pulse and speed for the scene (0 ... 100 each).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_tunesBeat(JNIEnv *, jclass, jint pulse, jint speed) {
  std::shared_ptr<ssh::TunesScene> t;
  {
    std::lock_guard<std::mutex> l(g_screen_mutex);
    t = g_tunes;
  }
  if (t) t->Beat(pulse, speed);
}

// Transitions on or off (off: every switch at once).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_fxEnabled(JNIEnv *, jclass, jboolean on) {
  std::lock_guard<std::mutex> lock(g_fx_mutex);
  g_fx_enabled = on;
  if (!on) g_fx_pending = {};
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
  StopTerminal(false, true);
  StartTerminal();
}

// Pixels left empty above and below the terminal on the glasses, at once (a running terminal starts again).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_terminalMargins(JNIEnv *, jclass, jint top, jint bottom) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_top == top && g_bottom == bottom) return;
  g_top = top;
  g_bottom = bottom;
  if (!g_glasses || Current() != Show::kTerminal) return;
  StopTerminal(false, true);
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
// A card over the terminal (lines with marks: 1 bold, 2 faint, 3 highlighted; full: the whole terminal, else a
// strip at its bottom), at once; null or empty: none.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_overlay(JNIEnv *env, jclass, jobjectArray lines, jintArray marks,
                                                          jboolean full) {
  std::vector<std::string> v;
  std::vector<int> m;
  if (lines) {
    jsize n = env->GetArrayLength(lines);
    for (jsize i = 0; i < n; i++) {
      auto js = static_cast<jstring>(env->GetObjectArrayElement(lines, i));
      v.push_back(Text(env, js));
      env->DeleteLocalRef(js);
    }
  }
  if (marks) {
    std::vector<jint> t(size_t(env->GetArrayLength(marks)));
    env->GetIntArrayRegion(marks, 0, jsize(t.size()), t.data());
    m.assign(t.begin(), t.end());
  }
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    g_overlay = std::move(v);
    g_overlay_marks = std::move(m);
    g_overlay_full = full;
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  if (g_term)
    if (auto w = WithBand(s)) g_term->Show(w);
}

// A card over the screen's picture (0 ... 255 per pixel, w x h at (x, y) of the picture); null: none. It goes with
// the next frame (the capture is asked for one).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_screenOverlay(JNIEnv *env, jclass, jbyteArray pixels, jint w, jint h,
                                                                jint x, jint y) {
  std::vector<uint8_t> p;
  if (pixels && w > 0 && h > 0) {
    p.resize(size_t(env->GetArrayLength(pixels)));
    env->GetByteArrayRegion(pixels, 0, jsize(p.size()), reinterpret_cast<jbyte *>(p.data()));
  }
  std::shared_ptr<ssh::ScreenMirror> screen;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    screen = g_screen;
  }
  if (screen) screen->SetOverlay(std::move(p), w, h, x, y);
}

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
  if (g_term)
    if (auto w = WithBand(s)) g_term->Show(w);
}

// The look-up header's lines above the terminal (none: no header); the app gives mosh as many rows fewer, plus
// one for the rule. The terminal shows the change at once.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_lookUpHeader(JNIEnv *env, jclass, jobjectArray lines) {
  std::vector<std::string> h;
  jsize n = lines ? env->GetArrayLength(lines) : 0;
  for (jsize i = 0; i < n && i < 3; i++) {
    auto s = static_cast<jstring>(env->GetObjectArrayElement(lines, i));
    h.push_back(s ? Text(env, s) : std::string());
    if (s) env->DeleteLocalRef(s);
  }
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    if (h == g_header) return;
    g_header = h;
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  if (g_term)
    if (auto w = WithBand(s)) g_term->Show(w);
}

// The dashboard above the terminal, h pixels high (0: none), at once (a running terminal starts again).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_terminalDash(JNIEnv *, jclass, jint h) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_dash_h == h) return;
  g_dash_h = h;
  {
    std::lock_guard<std::mutex> l(g_term_mutex);
    g_dash.clear();
  }
  if (!g_glasses || Current() != Show::kTerminal) return;
  StopTerminal(false, true);
  StartTerminal();
}

// The dashboard's size on the running terminal: width, height (0, 0: none).
JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_dashSize(JNIEnv *env, jclass) {
  jint v[2] = {0, 0};
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    if (g_term) {
      v[0] = g_term->dash_w();
      v[1] = g_term->dash_h();
    }
  }
  jintArray a = env->NewIntArray(2);
  env->SetIntArrayRegion(a, 0, 2, v);
  return a;
}

// The dashboard's picture (4 bpp, the high nibble the left pixel, width / 2 bytes a row): to the arms (the rows
// that changed), and kept for a terminal that starts again.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_dash(JNIEnv *env, jclass, jbyteArray pixels) {
  std::vector<uint8_t> p(size_t(env->GetArrayLength(pixels)));
  env->GetByteArrayRegion(pixels, 0, jsize(p.size()), reinterpret_cast<jbyte *>(p.data()));
  std::lock_guard<std::mutex> lock(g_term_mutex);
  g_dash = p;
  if (g_term) g_term->SetDash(g_dash);
}

// The look-up fade in ms (0: none), at once (a running terminal starts again).
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_terminalFade(JNIEnv *, jclass, jint ms) {
  std::lock_guard<std::mutex> lock(g_glasses_mutex);
  if (g_fade == ms) return;
  g_fade = ms;
  if (!g_glasses || Current() != Show::kTerminal) return;
  StopTerminal(false, true);
  StartTerminal();
}

// The pointer at a cell of the SSH session's screen (row, col from 0; -1: none), shown at once.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_pointer(JNIEnv *, jclass, jint row, jint col) {
  std::shared_ptr<const ssh::Snapshot> s;
  {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_mosh) s = g_mosh->Latest();
  }
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    if (g_pointer_row == row && g_pointer_col == col) return;
    g_pointer_row = row;
    g_pointer_col = col;
  }
  std::lock_guard<std::mutex> lock(g_term_mutex);
  if (g_term)
    if (auto w = WithBand(s)) g_term->Show(w);
}

// A mouse event at a cell (row, col from 0) for the program in the SSH session, in the encoding it asked for:
// button 0 left, 1 middle, 2 right (a press and a release), 64 / 65 the wheel up / down. False (nothing sent)
// while the program has not asked for mouse reports: the bytes would be typed into it.
JNIEXPORT void JNICALL Java_scouterkit_ssh_Native_pointerAt(JNIEnv *, jclass, jint x, jint y, jboolean on) {
  std::lock_guard<std::mutex> lock(g_term_mutex);
  g_ptr_x = x;
  g_ptr_y = y;
  g_ptr_on = on;
  if (g_term) g_term->Pointer(x, y, on);
}

JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_termGeometry(JNIEnv *env, jclass) {
  jint v[5] = {0, 0, 0, 0, 0};
  {
    std::lock_guard<std::mutex> lock(g_term_mutex);
    if (g_term) {
      v[0] = g_term->dash_w() ? g_term->dash_w() : g_term->cols() * g_term->cell_w();
      v[1] = g_term->rows() * g_term->cell_h();
      v[2] = g_term->cell_w();
      v[3] = g_term->cell_h();
      v[4] = g_term->dash_h();
    }
  }
  jintArray out = env->NewIntArray(5);
  env->SetIntArrayRegion(out, 0, 5, v);
  return out;
}

JNIEXPORT jboolean JNICALL Java_scouterkit_ssh_Native_mouse(JNIEnv *, jclass, jint button, jint row, jint col) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (!g_mosh) return JNI_FALSE;
  auto s = g_mosh->Latest();
  if (!s || s->mouse == 0 || row < 0 || col < 0) return JNI_FALSE;
  int x = col + 1, y = row + 1;
  bool wheel = button >= 64;
  std::string out;
  if (s->mouse_encoding == 1006) { // SGR
    out = "\x1b[<" + std::to_string(button) + ";" + std::to_string(x) + ";" + std::to_string(y) + "M";
    if (!wheel) out += "\x1b[<" + std::to_string(button) + ";" + std::to_string(x) + ";" + std::to_string(y) + "m";
  } else if (s->mouse_encoding == 1015) { // urxvt
    out = "\x1b[" + std::to_string(32 + button) + ";" + std::to_string(x) + ";" + std::to_string(y) + "M";
    if (!wheel) out += "\x1b[" + std::to_string(32 + 3) + ";" + std::to_string(x) + ";" + std::to_string(y) + "M";
  } else { // X10 bytes (the default and UTF-8 encodings agree below 95)
    if (x > 94 || y > 94) return JNI_FALSE;
    out = std::string("\x1b[M") + char(32 + button) + char(32 + x) + char(32 + y);
    if (!wheel) out += std::string("\x1b[M") + char(32 + 3) + char(32 + x) + char(32 + y);
  }
  g_mosh->Keys(out);
  return JNI_TRUE;
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
// sharpen, hide in the case, hide folded, gamma (x100), threshold, steady, dither, detail (0 auto), budget (KB),
// the pointer's x, y (1/10000 of the screen; -1 none).
ssh::ScreenOptions Options(JNIEnv *env, jintArray options) {
  jint v[26] = {0, 50, 0, 100, 0, 0, 0, 100, 100, 100, 2, 0, 0, 1, 0, 100, 128, 1, 0, 1, 6, -1, -1, 1, 0, 0};
  jsize n = std::min<jsize>(env->GetArrayLength(options), 26);
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
  o.cursor_x = v[21] < 0 ? -1 : std::min(int(v[21]), 10000);
  o.cursor_y = v[22] < 0 ? -1 : std::min(int(v[22]), 10000);
  o.clean = v[23] != 0;
  o.crop_top = std::clamp(int(v[24]), 0, 400);
  o.crop_bottom = std::clamp(int(v[25]), 0, 400);
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

// The cursor on the phone's screen (1/10000 of its width and height; on: shown): the arms draw it over the picture
// at once (black and white). True while it still waits to go (a busy link, a transition): call again soon.
JNIEXPORT jboolean JNICALL Java_scouterkit_ssh_Native_screenPointer(JNIEnv *, jclass, jint x, jint y, jboolean on) {
  std::shared_ptr<ssh::ScreenMirror> screen;
  {
    std::lock_guard<std::mutex> lock(g_screen_mutex);
    g_sptr_x = x;
    g_sptr_y = y;
    g_sptr_on = on;
    screen = g_screen;
  }
  if (!screen) return JNI_FALSE;
  screen->Pointer(x, y, on);
  return screen->PointerPending() ? JNI_TRUE : JNI_FALSE;
}

// The part of the phone's screen on the glasses (left, top, right, bottom in 1/10000) and its size there (w, h
// pixels), or null before a frame.
JNIEXPORT jintArray JNICALL Java_scouterkit_ssh_Native_screenShows(JNIEnv *env, jclass) {
  int v[6] = {};
  auto screen = Mirror();
  if (!screen || !screen->Shows(v)) return nullptr;
  jint t[6] = {v[0], v[1], v[2], v[3], v[4], v[5]};
  jintArray a = env->NewIntArray(6);
  env->SetIntArrayRegion(a, 0, 6, t);
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
  {
    std::lock_guard<std::mutex> lock(g_band_mutex);
    if (g_card) s = g_card;
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
