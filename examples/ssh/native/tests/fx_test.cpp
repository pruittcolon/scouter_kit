// The transitions on the emulated arms (fx.hpp): every style's out leaves its region black but for the label,
// every style's in leaves exactly the new picture (4 and 1 bpp) on both eyes and nothing outside the region
// changes; the plans fit the arms (steps, jumps, the scratch, 10-bit operands) and their time.
#include <chrono>
#include <cstdio>
#include <thread>

#include "../fx.hpp"
#include "../tunes.hpp"

namespace g1 = scouter_kit::g1;
using ssh::Fx;

static int failures = 0;
#define CHECK(cond)                                                       \
  do {                                                                    \
    if (!(cond)) {                                                        \
      std::printf("%s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #cond); \
      failures++;                                                         \
    }                                                                     \
  } while (0)

static const char *kNames[] = {"none", "left", "right", "up", "down", "jump", "on", "off", "tab-left", "tab-right"};

// Waits until the arms ran what was sent (both panels settle), at most ms.
static void Settle(Fx &fx, int ms = 3000) {
  auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
  while (g1::NowUs() < fx.busy_until() + 60000 && std::chrono::steady_clock::now() < end)
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    uint32_t crc, want;
    a->PanelCrc(0, 1, &crc, &want); // waits until the arm ran everything queued
  }
}

// The region of an eye's panel: equal to want(x, y) everywhere; outside it, every pixel `outside`.
template <typename F> static int Diff(int arm, const Fx::Region &r, F want, int outside) {
  const uint8_t *p = g1::emulator::Panel(arm);
  int bad = 0;
  int rx = r.at[size_t(arm)][0], ry = r.at[size_t(arm)][1];
  for (int y = 0; y < 480; y++)
    for (int x = 0; x < 640; x++) {
      bool in = x >= rx && x < rx + r.w && y >= ry && y < ry + r.h;
      int v = p[y * 640 + x];
      bad += in ? v != want(x - rx, y - ry) : v != outside;
    }
  return bad;
}

static void CheckPlan(const std::vector<Fx::Step> &steps, const char *what, int frame_us, int max_ms) {
  bool ok = steps.size() <= size_t(Fx::kListSteps) && Fx::Jumps(steps) <= 900 && !steps.empty() &&
            steps.back().v[0] == Fx::kEnd;
  int sw = 0, sh = 0;
  for (const Fx::Step &s : steps) {
    for (int k = 1; k <= 6; k++) ok &= s.v[size_t(k)] < 1024;
    auto fits = [](int w, int h) { return (w + 1) / 2 * h <= Fx::kScratchBytes; };
    if (s.v[0] == Fx::kLoad) ok &= fits(s.v[6], s.v[4]) && s.v[5] + s.v[3] <= s.v[6], sw = s.v[6], sh = s.v[4];
    if (s.v[0] == Fx::kBlank) ok &= fits(s.v[1], s.v[2]), sw = s.v[1], sh = s.v[2];
    if (s.v[0] == Fx::kFill || s.v[0] == Fx::kGrad) ok &= sw > 0 && s.v[1] < sw && s.v[2] < sh;
  }
  int ms = Fx::Frames(steps) * frame_us / 1000;
  if (!ok || ms > max_ms)
    std::printf("plan %s: %zu steps, %d jumps, %d frames (%d ms)\n", what, steps.size(), Fx::Jumps(steps),
                Fx::Frames(steps), ms);
  CHECK(ok);
  CHECK(ms <= max_ms);
}

int main() {
  g1::GrayConfig gray{100, 9};
  std::string error;
  CHECK(g1::Init(&gray, &error, nullptr) == g1::ERROR_NONE);
  for (int arm = 0; arm < 2; arm++) g1::emulator::SetProperty(arm, g1::PROP_WORN, 1);

  // The plans alone, for the sizes the app uses: the terminal (with and without the dashboard) and the phone's
  // picture.
  for (int s = Fx::kLeft; s < Fx::kStyles; s++)
    for (auto [w, h] : {std::pair{540, 462}, std::pair{540, 364}, std::pair{540, 480}, std::pair{640, 480}}) {
      std::string what = std::string(kNames[s]) + " " + std::to_string(w) + "x" + std::to_string(h);
      if (s != Fx::kOn)
        CheckPlan(Fx::PlanOut(Fx::Style(s), w, h, 200, 24, 7), (what + " out").c_str(), s == Fx::kOff ? 26000 : Fx::kOutUs,
                  s == Fx::kOff ? 240 : 160);
      if (s != Fx::kOff) {
        CheckPlan(Fx::PlanIn(Fx::Style(s), w, h, 7, 4), (what + " in").c_str(), Fx::kInUs, 320);
        CheckPlan(Fx::PlanIn(Fx::Style(s), w, h, 7, 1), (what + " in 1bpp").c_str(), Fx::kInUs, 320);
      }
    }

  Fx fx;
  CHECK(fx.Start(&error));
  if (!error.empty()) std::printf("fx: %s\n", error.c_str());
  std::printf("free arena after the transitions: %d of %d bytes\n", scouter_kit::free_memory, scouter_kit::total_memory);

  // The new picture on the arms: 4 bpp (a pattern of every level) and 1 bpp (stripes and dots).
  Fx::Region r;
  r.w = 540;
  r.h = 462;
  r.at = {{{100, 4}, {0, 13}}};
  auto four = [](int x, int y) { return (x / 3 + y / 5 + (x * y) % 7) % 16; };
  auto one = [](int x, int y) { return ((x / 4 + y / 3) % 3 == 0 || (x % 17 == 3)) ? 15 : 0; };
  uint32_t stride4 = uint32_t(r.w / 2), stride1 = uint32_t((r.w + 7) / 8 + 3) & ~3u;
  int64_t a4 = g1::Alloc(stride4 * uint32_t(r.h)), a1 = g1::Alloc(stride1 * uint32_t(r.h));
  CHECK(a4 >= 0 && a1 >= 0);
  std::vector<uint8_t> pic4(stride4 * size_t(r.h)), pic1(stride1 * size_t(r.h), 0);
  for (int y = 0; y < r.h; y++)
    for (int x = 0; x < r.w; x++) {
      pic4[size_t(y) * stride4 + size_t(x / 2)] |= uint8_t(four(x, y) << (x % 2 ? 0 : 4));
      if (one(x, y)) pic1[size_t(y) * stride1 + size_t(x / 8)] |= uint8_t(0x80 >> (x % 8));
    }
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    a->WriteArena(uint32_t(a4), pic4);
    a->WriteArena(uint32_t(a1), pic1);
  }
  Fx::Source s4{uint32_t(a4), stride4, 4}, s1{uint32_t(a1), stride1, 1};

  // A label: a frame with a bar in it, 4 bpp.
  int lw = 120, lh = 20;
  std::vector<uint8_t> label(size_t(lw / 2) * lh);
  auto lab = [&](int x, int y) { return x == 0 || y == 0 || x == lw - 1 || y == lh - 1 ? 15 : (y == 10 && x > 10 && x < 100) ? 9 : 0; };
  for (int y = 0; y < lh; y++)
    for (int x = 0; x < lw; x++) label[size_t(y) * (lw / 2) + size_t(x / 2)] |= uint8_t(lab(x, y) << (x % 2 ? 0 : 4));
  int lx = ((r.w - lw) / 2) & ~1, ly = (r.h - lh) / 2;
  auto with_label = [&](int x, int y) { return x >= lx && x < lx + lw && y >= ly && y < ly + lh ? lab(x - lx, y - ly) : 0; };

  // Something on the panels first (the old picture), so that every out has pixels to take away.
  for (g1::Arm *a : {&g1::left, &g1::right}) a->SetPanel(true);
  fx.In(Fx::kNone, r, s4);
  Settle(fx);
  CHECK(Diff(0, r, four, 0) == 0 && Diff(1, r, four, 0) == 0);

  for (int s = Fx::kLeft; s < Fx::kStyles; s++) {
    Fx::Style style = Fx::Style(s);
    if (style != Fx::kOn) {
      auto t0 = std::chrono::steady_clock::now();
      fx.Out(style, r, label, lw, lh);
      Settle(fx);
      int ms = int(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count());
      int bad0 = style == Fx::kOff ? Diff(0, r, [](int, int) { return 0; }, 0) : Diff(0, r, with_label, 0);
      int bad1 = style == Fx::kOff ? Diff(1, r, [](int, int) { return 0; }, 0) : Diff(1, r, with_label, 0);
      std::printf("%-9s out: %d ms, %d / %d pixels differ\n", kNames[s], ms, bad0, bad1);
      CHECK(bad0 == 0 && bad1 == 0);
    }
    if (style == Fx::kOff) {
      fx.In(Fx::kNone, r, s4); // the picture back for the next one
      Settle(fx);
      continue;
    }
    for (int bpp : {4, 1}) {
      auto t0 = std::chrono::steady_clock::now();
      fx.In(style, r, bpp == 4 ? s4 : s1);
      Settle(fx);
      int ms = int(std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count());
      int bad0 = bpp == 4 ? Diff(0, r, four, 0) : Diff(0, r, one, 0);
      int bad1 = bpp == 4 ? Diff(1, r, four, 0) : Diff(1, r, one, 0);
      std::printf("%-9s in %d bpp: %d ms, %d / %d pixels differ\n", kNames[s], bpp, ms, bad0, bad1);
      CHECK(bad0 == 0 && bad1 == 0);
      if (bpp == 4) { // out again before the 1 bpp one
        fx.Out(style == Fx::kOn ? Fx::kJump : style, r);
        Settle(fx);
      }
    }
  }
  // Two outs in a row: the second only changes the label.
  fx.Out(Fx::kRight, r, label, lw, lh);
  Settle(fx);
  fx.Out(Fx::kLeft, r, label, lw - 40, lh);
  Settle(fx);
  // Nothing lit but the new label (its pixels: the old label's bytes at the new stride).
  int lw2 = lw - 40, lx2 = ((r.w - lw2) / 2) & ~1;
  int lit = 0;
  const uint8_t *p = g1::emulator::Panel(0);
  for (int y = 0; y < 480; y++)
    for (int x = 0; x < 640; x++) {
      bool in_label = x >= 100 + lx2 && x < 100 + lx2 + lw2 && y >= 4 + ly && y < 4 + ly + lh;
      if (!in_label) lit += p[y * 640 + x] != 0;
    }
  CHECK(lit == 0);

  for (g1::Arm *a : {&g1::left, &g1::right}) {
    auto st = a->GetState();
    if (st.errors) std::printf("arm errors: %u (%s)\n", st.errors, st.last_error.c_str());
    CHECK(st.errors == 0);
  }
  fx.Stop();

  // Tunes: the scene draws itself and keeps moving (a later frame differs), the pulse sets the brightness, both
  // eyes alike; stopped, the program is gone.
  {
    g1::Free(uint32_t(a4)); // room for the scene's picture
    g1::Free(uint32_t(a1));
    ssh::TunesScene t;
    bool started = t.Start(100, 9, true, &error);
    if (!started) std::printf("tunes: %s\n", error.c_str());
    CHECK(started);
    std::this_thread::sleep_for(std::chrono::milliseconds(1500));
    auto lit = [](int arm) {
      int n = 0;
      for (int i = 0; i < 640 * 480; i++) n += g1::emulator::Panel(arm)[i] != 0;
      return n;
    };
    CHECK(lit(0) > 20000 && lit(1) > 20000);
    std::vector<uint8_t> a(g1::emulator::Panel(0), g1::emulator::Panel(0) + 640 * 480);
    t.Beat(100, 100);
    std::this_thread::sleep_for(std::chrono::milliseconds(400));
    CHECK(!std::equal(a.begin(), a.end(), g1::emulator::Panel(0)));
    g1::VmStatus vs{};
    CHECK(g1::left.Status(&vs) && vs.state == 1); // running, not halted
    if (vs.state != 1) std::printf("tunes: state %d halt %d at %u\n", vs.state, vs.halt_code, vs.next);
    uint32_t b0 = 0, b1 = 0;
    t.Beat(0, 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    g1::left.Prop(g1::PROP_BRIGHTNESS, &b0);
    t.Beat(100, 0);
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    g1::left.Prop(g1::PROP_BRIGHTNESS, &b1);
    CHECK(b1 > b0);
    t.Stop();
  }
  g1::Deinit();
  std::printf(failures ? "%d failures\n" : "all tests passed (0 failures)\n", failures);
  return failures ? 1 : 0;
}
