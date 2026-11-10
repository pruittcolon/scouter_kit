#include "tunes.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "base/scouter_kit.hpp"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "scouter", __VA_ARGS__)
#else
#include <cstdio>
#define LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace sk = scouter_kit;
namespace g1 = scouter_kit::g1;
using g1::ArenaWord;
using g1::R;

namespace ssh {

namespace {

constexpr uint32_t kCode = 6144;
constexpr int kLines = 8;          // the floor's moving lines
constexpr int kStripe = 12;        // the sun's stripes: one each this many rows
constexpr int kFrameUs = 33333;
constexpr uint32_t kNone = 0xFFFFFFFF;
// State words: the host's pulse and speed, then the arms' own.
enum { sPulse, sSpeed, sBase, sOp, sNext, sPhase, sFrame, sZ, sY = sZ + kLines, kStateWords = sY + kLines };

uint32_t Next(uint32_t *s) {
  uint32_t x = *s ? *s : 0x2545F491u;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  return *s = x;
}

} // namespace

TunesScene::Scene TunesScene::Make(int w, int h, uint32_t seed) {
  Scene s;
  s.w = w & ~1;
  s.h = h;
  w = s.w;
  s.horizon = h * 56 / 100;
  s.sun_r = std::min(h * 24 / 100, w * 20 / 100);
  s.sun_x = w / 2;
  int cy = s.horizon - s.sun_r / 4;
  auto fill = [&](int x, int y, int ww, int hh, int level) {
    if (ww > 0 && hh > 0) s.ops.push_back({kFill, uint16_t(x), uint16_t(y), uint16_t(ww), uint16_t(hh), uint16_t(level)});
  };
  auto line = [&](int x0, int y0, int x1, int y1, int level) {
    s.ops.push_back({kLine, uint16_t(int16_t(x0)), uint16_t(int16_t(y0)), uint16_t(int16_t(x1)), uint16_t(int16_t(y1)),
                     uint16_t(level)});
  };
  // Stars, away from the sun.
  for (int i = 0; i < 46; i++) {
    int x = int(Next(&seed) % uint32_t(w)), y = int(Next(&seed) % uint32_t(std::max(1, s.horizon - 8)));
    if (std::abs(x - s.sun_x) < s.sun_r + 8 && y > cy - s.sun_r - 8) continue;
    fill(x, y, 1 + int(Next(&seed) % 4 == 0), 1, 5 + int(Next(&seed) % 8));
  }
  // The sun: a disc cut by the horizon, brightest at its top.
  int top = std::max(0, cy - s.sun_r);
  for (int y = top; y < s.horizon; y++) {
    int dy = y - cy;
    int half = int(std::sqrt(std::max(0.0, double(s.sun_r) * s.sun_r - double(dy) * dy)));
    int level = 15 - 7 * (y - top) / std::max(1, s.horizon - top);
    fill(s.sun_x - half, y, 2 * half, 1, level);
  }
  s.stripe_top = std::max(top, cy - s.sun_r * 2 / 5); // the lower part, striped
  for (int y = s.stripe_top; y < s.horizon; y++) {
    int dy = y - cy;
    s.half.push_back(uint16_t(std::sqrt(std::max(0.0, double(s.sun_r) * s.sun_r - double(dy) * dy))));
  }
  // Mountains at both sides, a nearer dim range and a farther brighter edge.
  for (int side = 0; side < 2; side++) {
    int x = side ? w - 1 : 0, end = side ? s.sun_x + s.sun_r + 10 : s.sun_x - s.sun_r - 10;
    int y = s.horizon;
    int step = side ? -1 : 1;
    while (side ? x > end : x < end) {
      int nx = x + step * (14 + int(Next(&seed) % 26));
      if (side ? nx < end : nx > end) nx = end;
      int peak = s.horizon - 8 - int(Next(&seed) % uint32_t(std::max(8, h / 9)));
      int mx = (x + nx) / 2;
      line(x, y, mx, peak, 10);
      line(mx, peak, nx, s.horizon - int(Next(&seed) % 6), 10);
      x = nx;
      y = s.horizon - int(Next(&seed) % 6);
    }
  }
  // The floor: lines from the horizon's middle out to the bottom, then the horizon.
  for (int k = -12; k <= 12; k++) line(s.sun_x + k * 2, s.horizon, s.sun_x + k * w / 9, h - 1, k == 0 ? 7 : 5);
  fill(0, s.horizon, w, 1, 15);
  return s;
}

TunesScene::~TunesScene() { Stop(); }

bool TunesScene::Start(int dx, int dy, bool ignore_worn, std::string *error) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (started_) return true;
  int dw = sk::gray::display.width, dh = sk::gray::display.height;
  if (dw <= 0 || dh <= 0) {
    if (error) *error = "the glasses are not connected";
    return false;
  }
  int adx = std::abs(dx);
  int w = std::min(640 - adx, dw) & ~1, h = dh;
  int x = std::clamp(adx, 0, dw - w);
  Scene scene = Make(w, h, 0x5EED1234u);
  uint32_t stride = uint32_t(w / 2);
  uint32_t ops_bytes = uint32_t(scene.ops.size()) * 12, half_bytes = uint32_t((scene.half.size() * 2 + 3) & ~size_t(3));
  uint32_t size = kCode + 4 * kStateWords + ops_bytes + half_bytes + stride * uint32_t(h) + 2 * stride + 8;
  base_ = g1::Alloc(size);
  if (base_ < 0) {
    sk::UnloadAll();
    base_ = g1::Alloc(size);
  }
  if (base_ < 0) {
    if (error) *error = "no room on the arms for the scene";
    return false;
  }
  code_ = uint32_t(base_);
  state_ = code_ + kCode;
  uint32_t ops = state_ + 4 * kStateWords, half = ops + ops_bytes, bg = half + half_bytes;
  uint32_t row = (bg + stride * uint32_t(h) + 3) & ~3u, zero = row + stride;
  // What the terminal drew goes: the library's picture black, the panels power-cycled.
  sk::gray::Clear();
  sk::Flush();
  std::vector<uint8_t> state(4 * kStateWords, 0);
  auto put = [&](int word, uint32_t v) { std::memcpy(&state[size_t(word) * 4], &v, 4); };
  for (int i = 0; i < kLines; i++) {
    put(sZ + i, uint32_t(256 * (1 + i)));
    put(sY + i, kNone);
  }
  std::vector<uint8_t> opb(ops_bytes), halfb(half_bytes, 0);
  for (size_t i = 0; i < scene.ops.size(); i++) std::memcpy(&opb[i * 12], scene.ops[i].data(), 12);
  std::memcpy(halfb.data(), scene.half.data(), scene.half.size() * 2);
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    present_[size_t(i)] = arm.GetState().present;
    if (!present_[size_t(i)]) continue;
    int px = i == 0 ? x - std::max(0, -dx) : x - std::max(0, dx);
    int py = i == 0 ? std::max(0, -dy) : std::max(0, dy);
    g1::Program p(code_, 96);
    auto c = [&](uint32_t v) { return p.Const(v); };
    auto n = [&](int v) { return p.Const(uint32_t(int32_t(v))); };
    auto s = [&](int word) { return ArenaWord(state_ / 4 + uint32_t(word)); };
    ArenaWord BG = c(bg), STRIDE = c(stride), W = n(w), H = n(h);
    auto redraw = p.NewLabel();
    if (!ignore_worn) p.PanelPolicy(redraw);
    // START: the base brightness, the background black, then the drawing list in chunks (USER0).
    auto start = p.NewLabel(), build = p.NewLabel(), bloop = p.NewLabel(), bline = p.NewLabel(), bnext = p.NewLabel(),
         built = p.NewLabel(), frame = p.NewLabel(), done = p.NewLabel();
    p.Bind(start);
    p.SysRead(s(sBase), c(g1::PROP_BRIGHTNESS));
    p.Fill(BG, STRIDE, c(0), c(0), W, H, c(0));
    p.Mov(s(sOp), c(0));
    p.Raise(c(g1::TRIGGER_USER0));
    p.End();
    p.Bind(build);
    p.Mov(R(0), c(0));
    p.Bind(bloop);
    p.BgeU(s(sOp), n(int(scene.ops.size())), built);
    p.Mul(R(7), s(sOp), c(12));
    p.Add(R(7), R(7), c(ops));
    for (int k = 0; k < 6; k++) p.Load(16, R(1 + k), R(7), c(uint32_t(k)));
    // LINE's coordinates are signed 16-bit.
    for (int k = 2; k <= 5; k++) {
      p.Shl(R(k), R(k), c(16));
      p.Sar(R(k), R(k), c(16));
    }
    p.Bne(R(1), c(kFill), bline);
    p.Fill(BG, STRIDE, R(2), R(3), R(4), R(5), R(6));
    p.Jmp(bnext);
    p.Bind(bline);
    p.Line(BG, STRIDE, R(2), R(3), R(4), R(5), R(6));
    p.Bind(bnext);
    p.Add(s(sOp), s(sOp), c(1));
    p.Add(R(0), R(0), c(1));
    p.BltU(R(0), c(60), bloop);
    p.Raise(c(g1::TRIGGER_USER0));
    p.End();
    p.Bind(built);
    p.Draw(BG, STRIDE, c(0), c(0), n(px), n(py), W, H);
    p.SysRead(R(1), c(g1::PROP_TIME_US));
    p.Add(s(sNext), R(1), n(kFrameUs));
    p.SysWrite(c(g1::PROP_TIMER), s(sNext));
    p.End();

    // TIMER: a frame. The brightness breathes with the pulse; each floor line moves nearer (faster with the speed),
    // its old row put back from the background and its new row drawn over it, brighter the nearer it is; every other
    // frame the sun's stripes slide down a row.
    auto lvl = p.NewLabel();
    auto lines = p.NewLabel(), zok = p.NewLabel(), yok = p.NewLabel(), noold = p.NewLabel(), lnext = p.NewLabel(),
         sun = p.NewLabel(), phok = p.NewLabel(), srow = p.NewLabel(), sgap = p.NewLabel(), snext = p.NewLabel(),
         rearm = p.NewLabel(), late = p.NewLabel(), set = p.NewLabel();
    const int K = (h - 1 - scene.horizon) * 256;
    const int rows = int(scene.half.size());
    p.Bind(frame);
    p.Mul(R(1), s(sPulse), c(35));
    p.DivU(R(1), R(1), c(100));
    p.Add(R(1), R(1), c(65));
    p.Mul(R(1), R(1), s(sBase));
    p.DivU(R(1), R(1), c(100));
    p.SysWrite(c(g1::PROP_BRIGHTNESS), R(1));
    p.DivU(R(2), s(sSpeed), c(5));
    p.Add(R(2), R(2), c(5));
    p.Mov(R(0), c(0));
    p.Bind(lines);
    p.BgeU(R(0), n(kLines), sun);
    p.Load(32, R(3), c(state_ + 4 * sZ), R(0));
    p.Sub(R(3), R(3), R(2));
    p.BgeS(R(3), c(256), zok);
    p.Add(R(3), R(3), c(256 * (kLines - 1)));
    p.Bind(zok);
    p.Store(32, c(state_ + 4 * sZ), R(0), R(3));
    p.DivU(R(4), n(K), R(3));
    p.Add(R(4), R(4), n(scene.horizon));
    p.BltU(R(4), H, yok);
    p.Mov(R(4), n(h - 1));
    p.Bind(yok);
    p.Load(32, R(5), c(state_ + 4 * sY), R(0));
    p.Beq(R(4), R(5), lnext);
    p.Beq(R(5), c(kNone), noold);
    p.Add(R(6), R(5), n(py));
    p.Draw(BG, STRIDE, c(0), R(5), n(px), R(6), W, c(1));
    p.Bind(noold);
    p.Mul(R(7), R(4), STRIDE);
    p.Add(R(7), R(7), BG);
    p.Copy(c(row), R(7), STRIDE);
    p.Mov(R(8), R(3));
    p.BltU(R(8), c(256 * kLines), lvl);
    p.Mov(R(8), c(256 * kLines));
    p.Bind(lvl);
    p.Sub(R(8), c(256 * kLines), R(8));
    p.Mul(R(8), R(8), c(11));
    p.DivU(R(8), R(8), c(256 * (kLines - 1)));
    p.Add(R(8), R(8), c(4));
    p.Fill(c(row), STRIDE, c(0), c(0), W, c(1), R(8));
    p.Add(R(6), R(4), n(py));
    p.Draw(c(row), STRIDE, c(0), c(0), n(px), R(6), W, c(1));
    p.Store(32, c(state_ + 4 * sY), R(0), R(4));
    p.Bind(lnext);
    p.Add(R(0), R(0), c(1));
    p.Jmp(lines);
    // The sun's lower part: rows in stripes that slide down; a gap grows toward the horizon.
    p.Bind(sun);
    p.Add(s(sFrame), s(sFrame), c(1));
    p.And(R(1), s(sFrame), c(1));
    p.Bne(R(1), c(0), rearm);
    p.Add(s(sPhase), s(sPhase), c(kStripe - 1)); // down: the pattern's phase goes back a row
    p.BltU(s(sPhase), c(kStripe), phok);
    p.Sub(s(sPhase), s(sPhase), c(kStripe));
    p.Bind(phok);
    p.Mov(R(0), c(0));
    p.Bind(srow);
    p.BgeU(R(0), n(rows), rearm);
    p.Add(R(9), R(0), n(scene.stripe_top));
    p.Load(16, R(10), c(half), R(0));
    p.Beq(R(10), c(0), snext);
    p.Sub(R(11), n(scene.sun_x), R(10));
    p.Add(R(12), R(10), R(10));
    p.Mul(R(13), R(0), c(5));
    p.DivU(R(13), R(13), n(std::max(1, rows)));
    p.Add(R(13), R(13), c(1));
    p.Add(R(14), R(9), s(sPhase));
    p.ModU(R(14), R(14), c(kStripe));
    p.Add(R(6), R(9), n(py));
    p.Add(R(7), R(11), n(px));
    p.BltU(R(14), R(13), sgap);
    p.Draw(BG, STRIDE, R(11), R(9), R(7), R(6), R(12), c(1));
    p.Jmp(snext);
    p.Bind(sgap);
    p.Draw(c(zero), STRIDE, R(11), c(0), R(7), R(6), R(12), c(1));
    p.Bind(snext);
    p.Add(R(0), R(0), c(1));
    p.Jmp(srow);
    // The next frame on the grid of 33 ms (from now when late).
    p.Bind(rearm);
    p.SysRead(R(1), c(g1::PROP_TIME_US));
    p.Add(s(sNext), s(sNext), n(kFrameUs));
    p.Sub(R(2), s(sNext), R(1));
    p.BltS(R(2), c(0), late);
    p.Jmp(set);
    p.Bind(late);
    p.Add(s(sNext), R(1), n(kFrameUs));
    p.Bind(set);
    p.SysWrite(c(g1::PROP_TIMER), s(sNext));
    p.Bind(done);
    p.End();
    // The panel policy's hold ended: the background again (the lines and stripes follow by themselves).
    p.Bind(redraw);
    p.Draw(BG, STRIDE, c(0), c(0), n(px), n(py), W, H);
    p.End();
    p.Changes(state_ + 4 * sBase, 4 * (kStateWords - sBase));
    p.Changes(bg, row + 2 * stride - bg);
    p.On(g1::TRIGGER_START, start);
    p.On(g1::TRIGGER_USER0, build);
    p.On(g1::TRIGGER_TIMER, frame);
    std::string why;
    if (!p.Valid(&why) || p.Here() > state_) {
      if (error) *error = "the scene's code: " + (why.empty() ? std::string("too long") : why);
      return false;
    }
    arm.WriteArena(state_, state);
    arm.WriteArena(ops, opb);
    arm.WriteArena(half, halfb);
    arm.WriteArena(zero, std::vector<uint8_t>(stride, 0));
    arm.SetPanel(false);
    arm.SetPanel(true);
    if (!arm.Load(p) || !arm.Start(code_)) {
      if (error) *error = "the scene did not start";
      return false;
    }
  }
  started_ = true;
  pulse_ = speed_ = -1;
  LOG("tunes: %d x %d, %zu drawing steps", w, h, scene.ops.size());
  return true;
}

void TunesScene::Stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  started_ = false;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!present_[size_t(i)]) continue;
    arm.Stop();
    arm.SetPanel(false);
    arm.SetPanel(true);
  }
  if (base_ >= 0) g1::Free(code_);
  base_ = -1;
}

void TunesScene::Beat(int pulse, int speed) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  pulse = std::clamp(pulse, 0, 100);
  speed = std::clamp(speed, 0, 100);
  if (pulse == pulse_ && speed == speed_) return;
  pulse_ = pulse;
  speed_ = speed;
  uint32_t v[2] = {uint32_t(pulse), uint32_t(speed)};
  std::vector<uint8_t> b(8);
  std::memcpy(b.data(), v, 8);
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (present_[size_t(i)] && arm.GetState().queued < 4) arm.WriteArena(state_, b);
  }
}

} // namespace ssh
