// The phone's screen on the emulated arms: the picture's maths (band, fit, tone), then a captured frame on
// both eyes at each eye's offset, an unchanged frame not sent again, the panels off in the case, and a clean
// display after Stop.
#include <chrono>
#include <cstdio>
#include <thread>
#include <vector>

#include "../screen.hpp"

namespace g1 = scouter_kit::g1;

static int failures = 0;
#define CHECK(cond)                                                       \
  do {                                                                    \
    if (!(cond)) {                                                        \
      std::printf("%s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #cond); \
      failures++;                                                         \
    }                                                                     \
  } while (0)

static bool WaitFor(auto done, int ms = 5000) {
  auto end = std::chrono::steady_clock::now() + std::chrono::milliseconds(ms);
  while (!done()) {
    if (std::chrono::steady_clock::now() > end) return false;
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }
  return true;
}

// Lit pixels of an eye's panel inside a rectangle.
static int Lit(int arm, int x0 = 0, int y0 = 0, int x1 = 640, int y1 = 480) {
  const uint8_t *p = g1::emulator::Panel(arm);
  int n = 0;
  for (int y = y0; y < y1; y++)
    for (int x = x0; x < x1; x++) n += p[y * 640 + x] != 0;
  return n;
}

// A white RGBA screen with a black rectangle (inverted on the glasses: a lit rectangle on see-through black).
static std::vector<uint8_t> Phone(int w, int h, int rx, int ry, int rw, int rh, int stride) {
  std::vector<uint8_t> p(size_t(stride) * h, 255);
  for (int y = ry; y < ry + rh; y++)
    for (int x = rx; x < rx + rw; x++) {
      uint8_t *px = &p[size_t(y) * stride + x * 4];
      px[0] = px[1] = px[2] = 0;
    }
  return p;
}

int main() {
  // Tone: inverted, 16 levels as multiples of 17; 2 levels.
  std::vector<uint8_t> t = {0, 255, 128, 17};
  ssh::Tone(&t, true, 16);
  CHECK(t[0] == 255 && t[1] == 0 && t[2] % 17 == 0 && t[3] == 238);
  t = {0, 200, 100};
  ssh::Tone(&t, false, 2);
  CHECK(t[0] == 0 && t[1] == 255 && t[2] == 0);

  // Source: the whole screen, or a full-width band in the picture's shape at its place.
  ssh::ScreenOptions o;
  ssh::Rect r = ssh::Source(1080, 2340, 540, 471, o);
  CHECK(r.x == 0 && r.y == 0 && r.w == 1080 && r.h == 2340);
  o.band = true;
  o.band_pos = 0;
  r = ssh::Source(1080, 2340, 540, 471, o);
  CHECK(r.x == 0 && r.y == 0 && r.w == 1080 && r.h == 942);
  o.band_pos = 100;
  r = ssh::Source(1080, 2340, 540, 471, o);
  CHECK(r.y == 2340 - 942 && r.y + r.h == 2340);
  r = ssh::Source(2340, 1080, 540, 471, o); // landscape: all rows, the middle columns
  CHECK(r.h == 1080 && r.w == 1238 && r.x == (2340 - 1238) / 2);

  // Fit: averages, keeps the shape centred with the background around it.
  std::vector<uint8_t> src = {0, 255, 0, 255, 0, 255, 0, 255};
  std::vector<uint8_t> dst;
  ssh::Fit(&dst, 2, 1, src, 4, {0, 0, 4, 2}, false, 0);
  CHECK(dst.size() == 2 && dst[0] == 127 && dst[1] == 127);
  ssh::Fit(&dst, 4, 1, src, 4, {0, 0, 4, 2}, true, 9); // 2:1 into 4:1: 2 x 1 in the middle
  CHECK(dst[0] == 9 && dst[3] == 9 && dst[1] == 127);

  // The emulated arms, worn, head level.
  g1::GrayConfig gray{100, 9};
  std::string error;
  CHECK(g1::Init(&gray, &error, nullptr) == g1::ERROR_NONE);
  for (int arm = 0; arm < 2; arm++) {
    g1::emulator::SetProperty(arm, g1::PROP_WORN, 1);
    g1::emulator::SetProperty(arm, g1::PROP_IN_CASE, 0);
  }
  ssh::ScreenMirror m;
  CHECK(m.Start(100, &error));
  if (!error.empty()) std::printf("start: %s\n", error.c_str());
  CHECK(WaitFor([] { return Lit(0) == 0 && Lit(1) == 0; }));

  // A 540 x 471 "screen" (the picture's own size, padded rows) with a black square: lit on both eyes. The
  // picture sits at the display's (100, 0); the left eye shows the display as it is, the right eye 100 to the
  // left and 9 lower.
  int stride = 540 * 4 + 64;
  ssh::ScreenOptions opt;
  opt.band = true;
  auto phone = Phone(540, 471, 200, 100, 80, 80, stride);
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown);
  CHECK(WaitFor([] { return Lit(0) > 5000 && Lit(1) > 5000; }));
  int left = Lit(0, 300, 100, 380, 180), right = Lit(1, 200, 109, 280, 189);
  CHECK(left > 5500 && right > 5500);
  CHECK(Lit(0) - left < 400 && Lit(1) - right < 400); // nothing else lit
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kSame);
  CHECK(m.frames() == 1);

  // The square moves: the old place goes dark.
  phone = Phone(540, 471, 0, 0, 80, 80, stride);
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown);
  CHECK(WaitFor([] { return Lit(1, 200, 109, 280, 189) == 0 && Lit(1, 0, 9, 80, 89) > 5500; }));

  // In the case: hidden, the panels off; out again: shown.
  g1::emulator::SetProperty(0, g1::PROP_IN_CASE, 1);
  std::this_thread::sleep_for(std::chrono::milliseconds(1100));
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kHidden);
  uint32_t panel = 1;
  CHECK(WaitFor([&] { return g1::left.Prop(g1::PROP_PANEL, &panel) && panel == 0; }));
  g1::emulator::SetProperty(0, g1::PROP_IN_CASE, 0);
  std::this_thread::sleep_for(std::chrono::milliseconds(1100));
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown);
  CHECK(WaitFor([] { return Lit(1, 0, 9, 80, 89) > 5500; }));

  m.Stop();
  CHECK(WaitFor([] { return Lit(0) == 0 && Lit(1) == 0; }));
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kStopped);
  g1::Deinit();
  std::printf(failures ? "screen_test: %d failures\n" : "screen_test: ok\n", failures);
  return failures ? 1 : 0;
}
