// The phone's screen on the emulated arms: the picture's maths (band, fit, tone), then a captured frame on
// both eyes at each eye's offset, an unchanged frame not sent again, the panels off in the case, and a clean
// display after Stop.
#include <algorithm>
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

// A white screen with black 3 x 3 blocks at random (a frame that compresses badly).
static std::vector<uint8_t> Noise(int w, int h, int stride, unsigned seed) {
  std::vector<uint8_t> p(size_t(stride) * h, 255);
  for (int by = 0; by < h; by += 3)
    for (int bx = 0; bx < w; bx += 3) {
      seed = seed * 1103515245u + 12345u;
      if (!((seed >> 16) & 1)) continue;
      for (int y = by; y < std::min(h, by + 3); y++)
        for (int x = bx; x < std::min(w, bx + 3); x++) {
          uint8_t *px = &p[size_t(y) * stride + x * 4];
          px[0] = px[1] = px[2] = 0;
        }
    }
  return p;
}

// A screen of one grey.
static std::vector<uint8_t> Grey(int w, int h, int stride, uint8_t v) {
  std::vector<uint8_t> p(size_t(stride) * h, 255);
  for (int y = 0; y < h; y++)
    for (int x = 0; x < w; x++) {
      uint8_t *px = &p[size_t(y) * stride + x * 4];
      px[0] = px[1] = px[2] = v;
    }
  return p;
}

int main() {
  // Despeckle: a lone lit pixel goes, a pinhole in a lit area fills, the area's edge stays.
  {
    int w = 12, h = 8;
    std::vector<uint8_t> p(size_t(w) * h, 0);
    p[size_t(2 * w + 2)] = 255;
    for (int y = 3; y < 7; y++)
      for (int x = 6; x < 11; x++) p[size_t(y * w + x)] = 255;
    p[size_t(4 * w + 8)] = 0;
    ssh::Despeckle(&p, w, ssh::Rect{0, 0, w, h});
    CHECK(p[size_t(2 * w + 2)] == 0);
    CHECK(p[size_t(4 * w + 8)] == 255);
    CHECK(p[size_t(3 * w + 6)] == 255 && p[size_t(6 * w + 10)] == 255);
  }
  // The bars cut: a black status bar (lit once inverted) shows, and with the top 6 % cut it is gone.
  {
    int sw = 100, sh = 200, st = sw * 4;
    auto bar = Phone(sw, sh, 0, 0, sw, 10, st);
    ssh::ScreenOptions po;
    po.levels = 2;
    po.fit = ssh::ScreenOptions::kStretch;
    std::vector<uint8_t> with, without;
    ssh::Picture(bar.data(), sw, sh, st, po, 100, 200, &with);
    po.crop_top = 60;
    ssh::Picture(bar.data(), sw, sh, st, po, 100, 200, &without);
    int top = 0, rest = 0;
    for (int i = 0; i < 100 * 5; i++) top += with[size_t(i)] != 0;
    for (uint8_t v : without) rest += v != 0;
    CHECK(top == 500 && rest == 0);
  }
  // Tone: inverted, 16 levels as multiples of 17; 2 levels.
  std::vector<uint8_t> t = {0, 255, 128, 17};
  ssh::Tone(&t, 4, {0, 0, 4, 1}, 100, 100, 128, 16);
  CHECK(t[0] == 255 && t[1] == 0 && t[2] % 17 == 0 && t[3] == 238);
  t = {0, 200, 100};
  ssh::Tone(&t, 3, {0, 0, 3, 1}, 0, 100, 128, 2);
  CHECK(t[0] == 0 && t[1] == 255 && t[2] == 0);
  t = {0, 255};
  ssh::Tone(&t, 2, {0, 0, 2, 1}, 70, 100, 128, 16); // 70 % inverted: dark goes light grey, light goes dark grey
  CHECK(t[0] == 170 && t[1] == 85);
  t = {128, 128, 128, 128}; // a mid grey dithered: some pixels lit, some not
  ssh::Tone(&t, 4, {0, 0, 4, 1}, 0, 100, 128, 2, true);
  CHECK((t[0] == 255) + (t[1] == 255) + (t[2] == 255) + (t[3] == 255) == 2);
  t = {9, 9, 9};
  ssh::Tone(&t, 3, {1, 0, 1, 1}, 100, 100, 128, 16); // outside the content: untouched
  CHECK(t[0] == 9 && t[1] == 238 && t[2] == 9);
  t = {0, 100, 200, 255}; // 4 greys: 0, 85, 170, 255
  ssh::Tone(&t, 4, {0, 0, 4, 1}, 0, 100, 128, 4);
  CHECK(t[0] == 0 && t[1] == 85 && t[2] == 170 && t[3] == 255);

  // Pixelate: 2 x 2 blocks of their average; the dither keeps a block whole.
  std::vector<uint8_t> px = {0, 100, 0, 100, 0, 100, 0, 100};
  ssh::Pixelate(&px, 4, {0, 0, 4, 2}, 2);
  CHECK(px == std::vector<uint8_t>(8, 50));
  px.assign(16, 128);
  ssh::Tone(&px, 4, {0, 0, 4, 4}, 0, 100, 128, 2, true, 2);
  CHECK(px[0] == px[1] && px[0] == px[4] && px[0] == px[5]);
  int pw = 0, ph = 0;
  ssh::PictureSize(100, 9, &pw, &ph);
  CHECK(pw == 540 && ph == 471);

  // Source: the whole screen, or a full-width band in the picture's shape at its place.
  ssh::ScreenOptions o;
  ssh::Rect r = ssh::Source(1080, 2340, 540, 471, o);
  CHECK(r.x == 0 && r.y == 0 && r.w == 1080 && r.h == 2340);
  o.fit = ssh::ScreenOptions::kFill;
  o.pan_y = 0;
  r = ssh::Source(1080, 2340, 540, 471, o);
  CHECK(r.x == 0 && r.y == 0 && r.w == 1080 && r.h == 942);
  o.pan_y = 100;
  r = ssh::Source(1080, 2340, 540, 471, o);
  CHECK(r.y == 2340 - 942 && r.y + r.h == 2340);
  r = ssh::Source(2340, 1080, 540, 471, o); // landscape: all rows, the middle columns
  CHECK(r.h == 1080 && r.w == 1238 && r.x == (2340 - 1238) / 2);

  // Fit: averages, keeps the shape centred with the background around it.
  std::vector<uint8_t> src = {0, 255, 0, 255, 0, 255, 0, 255};
  std::vector<uint8_t> dst;
  ssh::Fit(&dst, 2, 1, src, 4, {0, 0, 4, 2}, {0, 0, 2, 1}, false);
  CHECK(dst.size() == 2 && dst[0] == 127 && dst[1] == 127);
  ssh::Rect drawn = ssh::Fit(&dst, 4, 1, src, 4, {0, 0, 4, 2}, {0, 0, 4, 1}, true); // 2:1 into 4:1: 2 x 1 in the middle
  CHECK(dst[0] == 0 && dst[3] == 0 && dst[1] == 127);
  CHECK(drawn.x == 1 && drawn.y == 0 && drawn.w == 2 && drawn.h == 1);
  // Place: the picture's place in percent of the area; Stretch mode fills it (distorted).
  ssh::ScreenOptions placed;
  placed.out_x = 50;
  placed.out_y = 0;
  placed.out_w = 50;
  placed.out_h = 100;
  ssh::Rect pl = ssh::Place(540, 471, placed);
  CHECK(pl.x == 270 && pl.y == 0 && pl.w == 270 && pl.h == 471);
  ssh::Fit(&dst, 4, 1, src, 4, {0, 0, 4, 2}, {2, 0, 2, 1}, false);
  CHECK(dst[0] == 0 && dst[1] == 0 && dst[2] == 127 && dst[3] == 127);

  // Rotate: a 3 x 2 image turned clockwise, upside down and counter-clockwise.
  std::vector<uint8_t> six = {1, 2, 3, 4, 5, 6}, turned;
  int tw = 0, th = 0;
  ssh::Rotate(six, 3, 2, 90, &turned, &tw, &th);
  CHECK(tw == 2 && th == 3 && turned == std::vector<uint8_t>({4, 1, 5, 2, 6, 3}));
  ssh::Rotate(six, 3, 2, 180, &turned, &tw, &th);
  CHECK(tw == 3 && th == 2 && turned == std::vector<uint8_t>({6, 5, 4, 3, 2, 1}));
  ssh::Rotate(six, 3, 2, 270, &turned, &tw, &th);
  CHECK(tw == 2 && th == 3 && turned == std::vector<uint8_t>({3, 6, 2, 5, 1, 4}));

  // Zoom: the middle half; Stretch: a dull 100 ... 199 ramp spans nearly all levels; Sharpen: an edge grows.
  ssh::ScreenOptions zoomed;
  zoomed.zoom = 200;
  zoomed.pan_x = 50;
  zoomed.pan_y = 50;
  r = ssh::Source(100, 50, 100, 50, zoomed);
  CHECK(r.x == 25 && r.y == 12 && r.w == 50 && r.h == 25);
  std::vector<uint8_t> ramp(100);
  for (int i = 0; i < 100; i++) ramp[i] = uint8_t(100 + i);
  ssh::Stretch(&ramp, 100, {0, 0, 100, 1}, 100);
  CHECK(ramp[0] < 10 && ramp[99] > 245);
  std::vector<uint8_t> edge = {100, 100, 200, 200, 100, 100, 200, 200, 100, 100, 200, 200};
  ssh::Sharpen(&edge, 4, {0, 0, 4, 3});
  CHECK(edge[5] < 100 && edge[6] > 200);

  // The emulated arms, worn, head level.
  g1::GrayConfig gray{100, 9};
  std::string error;
  CHECK(g1::Init(&gray, &error, nullptr) == g1::ERROR_NONE);
  for (int arm = 0; arm < 2; arm++) {
    g1::emulator::SetProperty(arm, g1::PROP_WORN, 1);
    g1::emulator::SetProperty(arm, g1::PROP_IN_CASE, 0);
  }
  ssh::ScreenMirror m;
  CHECK(m.Start(100, 9, &error));
  if (!error.empty()) std::printf("start: %s\n", error.c_str());
  CHECK(WaitFor([] { return Lit(0) == 0 && Lit(1) == 0; }));

  // A 540 x 471 "screen" (the picture's own size, padded rows) with a black square: lit on both eyes. The
  // picture sits at the display's (100, 0); the left eye shows the display as it is, the right eye 100 to the
  // left and 9 lower.
  int stride = 540 * 4 + 64;
  ssh::ScreenOptions opt;
  opt.fit = ssh::ScreenOptions::kFill;
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

  // Black and white: the VIDEO path (records into each arm's 1-bpp bitmap) draws the square on both eyes.
  opt.levels = 2;
  phone = Phone(540, 471, 300, 200, 80, 80, stride);
  CHECK(WaitFor([&] { return m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown; }, 10000));
  CHECK(WaitFor([] { return Lit(0, 400, 200, 480, 280) > 5500 && Lit(1, 300, 209, 380, 289) > 5500; }));
  CHECK(Lit(1, 0, 9, 80, 89) == 0); // the gray picture is gone
  phone = Phone(540, 471, 0, 0, 80, 80, stride);
  CHECK(WaitFor([&] { return m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown; }, 10000));
  CHECK(WaitFor([] { return Lit(1, 300, 209, 380, 289) == 0 && Lit(1, 0, 9, 80, 89) > 5500; }));

  // A transition: the old picture goes (out), the next one is decoded off the panel and revealed from the
  // bitmap: the square at its new place on both eyes, nothing else lit; the frame after it draws at once.
  auto fx = std::make_shared<ssh::Fx>();
  CHECK(fx->Start(&error));
  fx->Out(ssh::Fx::kRight, m.FxRegion());
  m.Reveal(ssh::Fx::kRight, fx);
  phone = Phone(540, 471, 300, 200, 80, 80, stride);
  CHECK(WaitFor([&] { return m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown; }, 10000));
  int64_t until = fx->busy_until();
  CHECK(until > g1::NowUs());
  CHECK(WaitFor([&] { return g1::NowUs() > until + 150000; }));
  CHECK(Lit(0, 400, 200, 480, 280) > 5500 && Lit(1, 300, 209, 380, 289) > 5500);
  CHECK(Lit(1, 0, 9, 80, 89) == 0);
  CHECK(Lit(0) - Lit(0, 400, 200, 480, 280) < 400 && Lit(1) - Lit(1, 300, 209, 380, 289) < 400);
  phone = Phone(540, 471, 0, 0, 80, 80, stride);
  CHECK(WaitFor([&] { return m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown; }, 10000));
  CHECK(WaitFor([] { return Lit(1, 300, 209, 380, 289) == 0 && Lit(1, 0, 9, 80, 89) > 5500; }));
  CHECK(fx->busy_until() == until); // no reveal for it

  // Steady black and white: a grey just past the threshold either way changes nothing (no frame sent); one
  // clearly past it does.
  auto grey = [&](uint8_t v) { return Grey(540, 471, stride, v); };
  auto send = [&](const std::vector<uint8_t> &p) {
    int r = ssh::ScreenMirror::kBusy;
    WaitFor([&] { r = m.Frame(p.data(), 540, 471, stride, opt); return r != ssh::ScreenMirror::kBusy; }, 10000);
    return r;
  };
  int first = send(grey(100));
  if (first != ssh::ScreenMirror::kShown) std::printf("steady: first grey gave %d\n", first);
  CHECK(first == ssh::ScreenMirror::kShown); // lit (inverted 155: clearly past 128)
  CHECK(send(grey(134)) == ssh::ScreenMirror::kSame);  // 121: under 128, within the margin
  CHECK(send(grey(170)) == ssh::ScreenMirror::kShown); // 85: clearly dark
  CHECK(send(grey(122)) == ssh::ScreenMirror::kSame);  // 133: over 128, within the margin

  // Auto detail: noise does not fit a 1 KB budget at every pixel, so it goes coarser (kLater, sent at a coarser
  // detail); the same frame again (nothing moved) goes at every pixel.
  opt.detail = 0;
  opt.budget = 1;
  auto noise = Noise(540, 471, stride, 7);
  int res = ssh::ScreenMirror::kBusy;
  CHECK(WaitFor([&] { res = m.Frame(noise.data(), 540, 471, stride, opt); return res != ssh::ScreenMirror::kBusy; }, 10000));
  CHECK(res == ssh::ScreenMirror::kLater);
  CHECK(m.stats().detail > 1 && m.stats().bytes > 0);
  CHECK(WaitFor([&] { res = m.Frame(noise.data(), 540, 471, stride, opt); return res != ssh::ScreenMirror::kBusy; }, 10000));
  CHECK(res == ssh::ScreenMirror::kShown && m.stats().detail == 1);
  // The preview is that picture, as a render without the glasses makes it too.
  std::vector<uint8_t> shown, local;
  CHECK(m.Preview(&shown, &pw, &ph) && pw == 540 && ph == 471);
  ssh::ScreenOptions every = opt;
  every.detail = 1;
  ssh::Picture(noise.data(), 540, 471, stride, every, 540, 471, &local);
  CHECK(shown == local);
  std::string check = m.Check();
  std::printf("check:\n%s", check.c_str());
  CHECK(!check.empty());
  opt.detail = 1;

  // In the case: hidden, the panels off; out again: shown.
  g1::emulator::SetProperty(0, g1::PROP_IN_CASE, 1);
  std::this_thread::sleep_for(std::chrono::milliseconds(1100));
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kHidden);
  uint32_t panel = 1;
  CHECK(WaitFor([&] { return g1::left.Prop(g1::PROP_PANEL, &panel) && panel == 0; }));
  g1::emulator::SetProperty(0, g1::PROP_IN_CASE, 0);
  std::this_thread::sleep_for(std::chrono::milliseconds(1100));
  CHECK(WaitFor([&] { return m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown; }, 10000));
  CHECK(WaitFor([] { return Lit(1, 0, 9, 80, 89) > 5500; }));

  // An arm whose session began in the case (the glasses connected there) and was taken out since: shown, by
  // the arm's IN_CASE now rather than its session's first message.
  g1::emulator::SetProperty(0, g1::PROP_IN_CASE, 1);
  g1::emulator::Disconnect(0, 300);
  CHECK(WaitFor([] { auto s = g1::left.GetState(); return s.connected && s.in_case; }, 15000));
  g1::emulator::SetProperty(0, g1::PROP_IN_CASE, 0);
  std::this_thread::sleep_for(std::chrono::milliseconds(1100));
  phone = Phone(540, 471, 200, 200, 80, 80, stride);
  CHECK(WaitFor([&] { return m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kShown; }, 15000));

  // The pointer: an arrow at its spot on a black screen (its rim or its fill lit, whatever the inversion), nothing
  // without it; it moves with its spot.
  {
    std::vector<uint8_t> dark(size_t(540) * 4 * 471, 0);
    for (size_t i = 3; i < dark.size(); i += 4) dark[i] = 255;
    ssh::ScreenOptions po;
    po.invert = 0;
    po.levels = 16;
    po.detail = 1;
    std::vector<uint8_t> none, at, moved;
    ssh::Picture(dark.data(), 540, 471, 540 * 4, po, 270, 236, &none);
    po.cursor_x = 5000;
    po.cursor_y = 5000;
    ssh::Picture(dark.data(), 540, 471, 540 * 4, po, 270, 236, &at);
    po.cursor_x = 1000;
    ssh::Picture(dark.data(), 540, 471, 540 * 4, po, 270, 236, &moved);
    auto lit = [](const std::vector<uint8_t> &p, int x0, int x1, int y0, int y1, int pw) {
      int n = 0;
      for (int y = y0; y < y1; y++)
        for (int x = x0; x < x1; x++) n += p[size_t(y) * size_t(pw) + size_t(x)] > 128;
      return n;
    };
    CHECK(lit(none, 0, 270, 0, 236, 270) == 0);
    CHECK(lit(at, 130, 150, 112, 135, 270) > 10); // just right of and below the middle
    CHECK(lit(at, 0, 100, 0, 236, 270) == 0);
    CHECK(lit(moved, 20, 45, 112, 135, 270) > 10);
    CHECK(lit(moved, 130, 150, 112, 135, 270) == 0);
  }

  m.Stop();
  fx->Stop();
  CHECK(WaitFor([] { return Lit(0) == 0 && Lit(1) == 0; }));
  CHECK(m.Frame(phone.data(), 540, 471, stride, opt) == ssh::ScreenMirror::kStopped);
  g1::Deinit();
  std::printf(failures ? "screen_test: %d failures\n" : "screen_test: ok\n", failures);
  return failures ? 1 : 0;
}
