#include "fx.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "scouter", __VA_ARGS__)
#else
#include <cstdio>
#define LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace g1 = scouter_kit::g1;
using g1::ArenaWord;

namespace ssh {

namespace {

// The block on each arm (the same address on both): the stored code with its table and pool, the parameters
// and working words, the step list, the label and the scratch bitmap.
constexpr uint32_t kCodeBytes = 1536;
constexpr int kPoolWords = 40;
enum { // parameter and working words, from the block's params
  pT0, pDt, pList, pRx, pRy, pSrc, pStride, pMode, pLabel, pLStride, pLw, pLh,
  vT, vI, vS, vW, vH, vF0, vT1 = vF0 + 8, vT2, vT3, vT4, kWords
};
constexpr uint32_t kParamBytes = 4 * 32;
static_assert(kWords <= 32);
constexpr int kJumpBudget = 900; // of the VM's 1000 per activation, for a plan that runs late (no sleep resets it)

uint32_t Next(uint32_t *s) { // xorshift32: the same plan on both eyes
  uint32_t x = *s ? *s : 0x9E3779B9u;
  x ^= x << 13;
  x ^= x >> 17;
  x ^= x << 5;
  return *s = x;
}
int Rand(uint32_t *s, int n) { return n <= 0 ? 0 : int(Next(s) % uint32_t(n)); }
double EaseOut(double t) { return 1 - std::pow(1 - std::clamp(t, 0.0, 1.0), 3); }
int Even(double v) { return int(std::lround(v / 2)) * 2; }

// A plan being written: steps in region coordinates, every piece sized to fit the scratch.
struct Plan {
  std::vector<Fx::Step> s;
  int bpp = 4; // the source's: 1 bpp copies go through the scratch

  void Add(std::initializer_list<int> v) {
    Fx::Step step;
    int i = 0;
    for (int x : v) step.v[size_t(i++)] = uint16_t(std::clamp(x, 0, 65535));
    s.push_back(step);
  }
  static int Rows(int w) { return std::max(1, Fx::kScratchBytes / std::max(1, (w + 1) / 2)); }
  void Frame() { Add({Fx::kFrame}); }
  void End() { Add({Fx::kEnd}); }
  // The new picture's (x, y, w, h) at (px, py).
  void Copy(int x, int y, int w, int h, int px, int py) {
    if (w <= 0 || h <= 0) return;
    for (int r = 0, n = bpp == 1 ? Rows(w) : h; r < h; r += n)
      Add({Fx::kCopy, x, y + r, w, std::min(n, h - r), px, py + r});
  }
  // Black over (x, y, w, h): one black scratch shown as often as it takes.
  void Erase(int x, int y, int w, int h) {
    if (w <= 0 || h <= 0) return;
    int n = std::min(h, Rows(w));
    Add({Fx::kBlank, w, n});
    for (int r = 0; r < h; r += n) {
      if (h - r < n) Add({Fx::kBlank, w, h - r});
      Add({Fx::kShow, x, y + r});
    }
  }
  // Lines at levels (levels[i] for line i) in the scratch: axis 0 columns from x, len rows high; 1 rows from y.
  void Grad(int x, int y, int len, const std::vector<int> &levels, int axis) {
    for (size_t i = 0; i < levels.size(); i += 4) {
      uint32_t packed = 0;
      int n = int(std::min<size_t>(4, levels.size() - i));
      for (int k = 0; k < n; k++) packed |= uint32_t(std::clamp(levels[i + size_t(k)], 0, 15)) << (4 * k);
      int at = int(i);
      Add({Fx::kGrad, axis ? x : x + at, axis ? y + at : y, len, int(packed & 0xFF), int(packed >> 8), n | axis << 3});
    }
  }
  // A strip of rows y .. y + h: content (the new picture, or black) at columns [c0, c1), a beam of lines with
  // `beam` levels beside it (the first level next to the content) at [b0, b1), where b1 == c0 (beam on the left)
  // or b0 == c1 (on the right); one scratch, one DRAW per piece of rows.
  void Strip(bool content, int y, int h, int c0, int c1, int b0, int b1, const std::vector<int> &beam) {
    int x0 = std::min(c0 < c1 ? c0 : b0, b0 < b1 ? b0 : c0), x1 = std::max(c0 < c1 ? c1 : b1, b0 < b1 ? b1 : c1);
    int tw = x1 - x0;
    if (tw <= 0 || h <= 0) return;
    bool left = b1 <= c0 && b0 < b1;
    std::vector<int> levels;
    for (int x = b0; x < b1; x++) {
      int d = left ? c0 - 1 - x : x - c1; // distance from the content
      levels.push_back(d >= 0 && d < int(beam.size()) ? beam[size_t(d)] : 0);
    }
    for (int r = 0, n = Rows(tw); r < h; r += n) {
      int rows = std::min(n, h - r);
      if (content && c1 > c0) Add({Fx::kLoad, c0, y + r, c1 - c0, rows, c0 - x0, tw});
      else Add({Fx::kBlank, tw, rows});
      if (b1 > b0) Grad(b0 - x0, 0, rows, levels, 0);
      Add({Fx::kShow, x0, y + r});
    }
  }
  // Rows of a beam (levels[0] nearest the content), black-backed: rows [y0, y0 + n) of width w at x.
  void RowBeam(int x, int y0, int w, int n, const std::vector<int> &levels_top_down) {
    if (n <= 0 || w <= 0) return;
    Add({Fx::kBlank, w, n});
    Grad(0, 0, w, levels_top_down, 1);
    Add({Fx::kShow, x, y0});
  }
  // A line of one level: w x h at (x, y).
  void Bar(int x, int y, int w, int h, int level) {
    if (w <= 0 || h <= 0) return;
    Add({Fx::kBlank, w, h});
    Add({Fx::kFill, 0, 0, w, h, level});
    Add({Fx::kShow, x, y});
  }
};

const std::vector<int> kEdge = {15, 15, 7, 3};   // a neon edge, from the content outward
const std::vector<int> kScan = {15, 10, 5, 2};   // a scan line and its glow

// The bands of the data stream: rows split in about 90-pixel bands, each starting a little later than the
// one before it would (lag up to `spread` of the run).
struct Bands {
  std::vector<int> y, h;
  std::vector<double> lag;
};
Bands MakeBands(int height, double spread, uint32_t *seed) {
  Bands b;
  int n = std::clamp(height / 90, 3, 6);
  for (int i = 0; i < n; i++) {
    int y0 = height * i / n, y1 = height * (i + 1) / n;
    b.y.push_back(y0);
    b.h.push_back(y1 - y0);
    b.lag.push_back(spread * Rand(seed, 1000) / 1000.0);
  }
  return b;
}

// The data stream: content (in) or black (out) enters from the right (or the left) band by band behind a neon
// edge; in, shards of the new picture fly ahead of the edge. Every column of every band is covered by the end.
void Stream(Plan &p, bool in, bool from_right, int w, int h, int frames, double spread, bool shards, uint32_t *seed) {
  Bands b = MakeBands(h, spread, seed);
  std::vector<int> done(b.y.size(), 0);
  int bw = int(kEdge.size());
  for (int k = 1; k <= frames; k++) {
    double t = double(k) / frames;
    for (size_t i = 0; i < b.y.size(); i++) {
      double q = (t - b.lag[i]) / (1 - spread);
      int r = k == frames ? w : std::min(w, std::max(done[i], Even(EaseOut(q) * w)));
      if (r == done[i]) continue;
      if (from_right) {
        int c0 = w - r, c1 = w - done[i];
        p.Strip(in, b.y[i], b.h[i], c0, c1, std::max(0, c0 - bw), c0, kEdge);
      } else {
        int c0 = done[i], c1 = r;
        p.Strip(in, b.y[i], b.h[i], c0, c1, c1, std::min(w, c1 + bw), kEdge);
      }
      done[i] = r;
    }
    if (shards && k < frames - 1) {
      for (int n = 0; n < 2; n++) {
        size_t i = size_t(Rand(seed, int(b.y.size())));
        int ahead0 = from_right ? 0 : done[i] + bw, ahead1 = from_right ? w - done[i] - bw : w;
        int len = 24 + 2 * Rand(seed, 24);
        if (done[i] == 0 || ahead1 - ahead0 < len + 8) continue;
        int sh = 2 + Rand(seed, 4);
        int sy = b.y[i] + Rand(seed, std::max(1, b.h[i] - sh));
        int dst = ahead0 + 2 * Rand(seed, (ahead1 - ahead0 - len) / 2);
        int shift = (16 + 2 * Rand(seed, 16)) * (from_right ? 1 : -1);
        int src = std::clamp(dst + shift, 0, w - len) & ~1;
        p.Copy(src, sy, len, sh, dst, sy);
      }
    }
    if (k < frames) p.Frame();
  }
}

// The raster: content (in) or black (out) from the top (or the bottom) behind a scan line; in, interference
// lines ahead of it.
void Raster(Plan &p, bool in, bool from_top, int w, int h, int frames, bool noise, uint32_t *seed) {
  int done = 0, bh = int(kScan.size());
  for (int k = 1; k <= frames; k++) {
    int r = k == frames ? h : std::max(done, Even(EaseOut(double(k) / frames) * h));
    if (r > done) {
      int y0 = from_top ? done : h - r, n = r - done;
      if (in) p.Copy(0, y0, w, n, 0, y0);
      else p.Erase(0, y0, w, n);
      // The scan line just ahead.
      if (from_top) {
        int rows = std::min(bh, h - r);
        p.RowBeam(0, r, w, rows, std::vector<int>(kScan.begin(), kScan.begin() + rows));
      } else {
        int rows = std::min(bh, h - r);
        std::vector<int> lv(kScan.rbegin() + (bh - rows), kScan.rend());
        p.RowBeam(0, h - r - rows, w, rows, lv);
      }
      done = r;
    }
    if (noise && k < frames - 1) {
      int ahead0 = from_top ? done + bh + 4 : 0, ahead1 = from_top ? h : h - done - bh - 4;
      for (int n = 0; n < 1 + (k % 2); n++) {
        if (ahead1 - ahead0 < 8) break;
        int len = w / 6 + Rand(seed, w / 2);
        int x = Rand(seed, std::max(1, w - len));
        int y = ahead0 + Rand(seed, ahead1 - ahead0);
        p.Bar(x, y, len, 1, 3 + Rand(seed, 5));
      }
    }
    if (k < frames) p.Frame();
  }
}

// Decrypt: tiles in a random order; in, each shows first with a bright top line and a dim bottom one, then
// clean; out, each goes black with a bright top line first.
void Tiles(Plan &p, bool in, int w, int h, int frames, uint32_t *seed) {
  int gx = std::clamp(w / 150, 3, 4), gy = std::clamp(h / 130, 3, 4);
  struct Tile {
    int x, y, w, h;
  };
  std::vector<Tile> tiles;
  for (int j = 0; j < gy; j++)
    for (int i = 0; i < gx; i++) {
      int x0 = (w * i / gx) & ~1, x1 = i == gx - 1 ? w : (w * (i + 1) / gx) & ~1;
      int y0 = h * j / gy, y1 = h * (j + 1) / gy;
      tiles.push_back({x0, y0, x1 - x0, y1 - y0});
    }
  for (size_t i = tiles.size(); i > 1; i--) std::swap(tiles[i - 1], tiles[size_t(Rand(seed, int(i)))]);
  int n = int(tiles.size());
  auto first = [&](int i) { return 1 + i * (frames - 2) / n; };
  for (int k = 1; k <= frames; k++) {
    for (int i = 0; i < n; i++) {
      const Tile &t = tiles[size_t(i)];
      if (first(i) == k) { // the flash
        for (int r = 0, rows = Plan::Rows(t.w); r < t.h; r += rows) {
          int m = std::min(rows, t.h - r);
          if (in) p.Add({Fx::kLoad, t.x, t.y + r, t.w, m, 0, t.w});
          else p.Add({Fx::kBlank, t.w, m});
          if (r == 0) p.Grad(0, 0, t.w, {15}, 1);
          if (r + m == t.h && m >= 2) p.Grad(0, m - 1, t.w, {in ? 6 : 4}, 1);
          p.Add({Fx::kShow, t.x, t.y + r});
        }
      } else if (first(i) + 1 == k) { // clean
        if (in) p.Copy(t.x, t.y, t.w, t.h, t.x, t.y);
        else p.Erase(t.x, t.y, t.w, t.h);
      }
    }
    if (k < frames) p.Frame();
  }
}

// A CRT switching on: a line at the middle, then the picture opens from it.
void PowerOn(Plan &p, int w, int h, int frames) {
  int c = h / 2;
  p.Bar((w - w / 3) / 2 & ~1, std::max(0, c - 1), w / 3, 2, 15);
  p.Frame();
  p.Bar(0, std::max(0, c - 1), w, 2, 15);
  p.Frame();
  int top = c, bottom = c; // the picture shows in [top, bottom)
  int steps = std::max(1, frames - 2);
  for (int k = 1; k <= steps; k++) {
    double e = EaseOut(double(k) / steps);
    int t = k == steps ? 0 : std::min(top, c - int(e * c)), b = k == steps ? h : std::max(bottom, c + int(e * (h - c)));
    // The line at the middle (2 rows) is covered by the first opening.
    if (k == 1) {
      t = std::min(t, std::max(0, c - 1));
      b = std::max(b, std::min(h, c + 1));
    }
    p.Copy(0, t, w, top - t, 0, t);
    p.Copy(0, bottom, w, b - bottom, 0, bottom);
    top = t;
    bottom = b;
    if (k < steps) {
      int up = std::min(2, top), down = std::min(2, h - bottom);
      if (up > 0) p.RowBeam(0, top - up, w, up, up == 2 ? std::vector<int>{6, 15} : std::vector<int>{15});
      if (down > 0) p.RowBeam(0, bottom, w, down, down == 2 ? std::vector<int>{15, 6} : std::vector<int>{15});
      p.Frame();
    }
  }
}

// A CRT switching off: the picture closes to a bright line at the middle, the line to a dot, then black.
void PowerOff(Plan &p, int w, int h) {
  int c = h / 2, top = 0, bottom = h;
  const int close = 5;
  for (int k = 1; k <= close; k++) {
    double e = EaseOut(double(k) / close);
    int t = k == close ? c - 1 : std::max(top, int(e * (c - 1))), b = k == close ? c + 1 : std::min(bottom, h - int(e * (h - c - 1)));
    p.Erase(0, top, w, t - top);
    p.Erase(0, b, w, bottom - b);
    top = t;
    bottom = b;
    int inner = bottom - top;
    if (k < close && inner > 4) {
      p.RowBeam(0, top, w, 2, {15, 8});
      p.RowBeam(0, bottom - 2, w, 2, {8, 15});
    } else {
      p.Bar(0, top, w, inner, 15);
    }
    p.Frame();
  }
  int y = top, lh = bottom - top, line = w;
  for (int k = 1; k <= 3; k++) {
    int next = k == 3 ? 6 : w / (k == 1 ? 3 : 10);
    next = std::max(2, next & ~1);
    int x0 = (w - line) / 2, x1 = (w - next) / 2;
    p.Erase(x0, y, x1 - x0, lh);
    p.Erase(x1 + next, y, (x0 + line) - (x1 + next), lh);
    line = next;
    p.Frame();
  }
  p.Erase((w - line) / 2, y, line, lh);
}

} // namespace

int Fx::Jumps(const std::vector<Step> &steps) {
  int n = 0;
  for (const Step &s : steps) n += s.v[0] == kGrad ? 2 + 3 * (s.v[6] & 7) : s.v[0] == kCopy ? 3 : 2;
  return n;
}

// Each step in 8 bytes (little-endian u64): the opcode in bits 0-3, operands of 10 bits from bit 4.
std::vector<uint8_t> Fx::Pack(const std::vector<Step> &steps) {
  std::vector<uint8_t> out(steps.size() * 8);
  for (size_t i = 0; i < steps.size(); i++) {
    uint64_t v = steps[i].v[0] & 15u;
    for (int k = 1; k <= 6; k++) v |= uint64_t(steps[i].v[size_t(k)] & 1023u) << (4 + 10 * (k - 1));
    for (int b = 0; b < 8; b++) out[i * 8 + size_t(b)] = uint8_t(v >> (8 * b));
  }
  return out;
}

int Fx::Frames(const std::vector<Step> &steps) {
  int n = 1;
  for (const Step &s : steps) n += s.v[0] == kFrame;
  return n;
}

std::vector<Fx::Step> Fx::PlanOut(Style style, int w, int h, int lw, int lh, uint32_t seed) {
  Plan p;
  w &= ~1;
  switch (style) {
  case kLeft:
  case kRight:
    Stream(p, false, style == kRight, w, h, 6, 0.25, false, &seed);
    break;
  case kTabLeft:
  case kTabRight:
    Stream(p, false, style == kTabRight, w, h, 5, 0.2, false, &seed);
    break;
  case kUp:
  case kDown:
    Raster(p, false, style == kUp, w, h, 6, false, &seed);
    break;
  case kJump:
    Tiles(p, false, w, h, 6, &seed);
    break;
  case kOff:
    PowerOff(p, w, h);
    break;
  default:
    p.Erase(0, 0, w, h);
    break;
  }
  if (lw > 0 && lh > 0 && lw <= w && lh <= h && style != kOff) p.Add({kLabel, ((w - lw) / 2) & ~1, (h - lh) / 2});
  p.End();
  return std::move(p.s);
}

std::vector<Fx::Step> Fx::PlanIn(Style style, int w, int h, uint32_t seed, int bpp) {
  Plan p;
  p.bpp = bpp;
  w &= ~1;
  switch (style) {
  case kLeft:
  case kRight:
    Stream(p, true, style == kRight, w, h, 10, 0.28, true, &seed);
    break;
  case kTabLeft:
  case kTabRight:
    Stream(p, true, style == kTabRight, w, h, 8, 0.2, false, &seed);
    break;
  case kUp:
  case kDown:
    Raster(p, true, style == kUp, w, h, 11, true, &seed);
    break;
  case kJump:
    Tiles(p, true, w, h, 10, &seed);
    break;
  case kOn:
    PowerOn(p, w, h, 10);
    break;
  default:
    p.Copy(0, 0, w, h, 0, 0);
    break;
  }
  p.End();
  return std::move(p.s);
}

Fx::~Fx() { Stop(); }

bool Fx::Start(std::string *error) {
  std::lock_guard<std::mutex> lock(mutex_);
  return StartLocked(error);
}

// mutex_ held.
bool Fx::StartLocked(std::string *error) {
  if (started_) return true;
  uint32_t size = kCodeBytes + kParamBytes + uint32_t(kListSteps) * 8 + uint32_t(kLabelBytes) + uint32_t(kScratchBytes);
  base_ = g1::Alloc(size);
  if (base_ < 0) {
    if (error) *error = "no room on the arms for the transitions";
    return false;
  }
  code_ = uint32_t(base_);
  params_ = code_ + kCodeBytes;
  list_ = params_ + kParamBytes;
  label_ = list_ + uint32_t(kListSteps) * 8;
  scratch_ = label_ + uint32_t(kLabelBytes);

  // The stored code: runs the step list (VM.md §5.4: called by a host packet; each frame sleeps until its due
  // time). Words: vF0 ... the step's operands, vT1 ... scratch values.
  auto w = [&](int i) { return ArenaWord(params_ / 4 + uint32_t(i)); };
  g1::Program p(code_, kPoolWords);
  auto c = [&](uint32_t v) { return p.Const(v); };
  ArenaWord scr = c(scratch_);
  ArenaWord f[7];
  for (int i = 0; i < 7; i++) f[i] = w(vF0 + i);
  ArenaWord lo = w(vF0 + 7), t1 = w(vT1), t2 = w(vT2), t3 = w(vT3), t4 = w(vT4);
  auto top = p.NewLabel(), done = p.NewLabel(), frame = p.NewLabel(), copy = p.NewLabel(), copy1 = p.NewLabel(),
       load = p.NewLabel(), blank = p.NewLabel(), fill = p.NewLabel(), show = p.NewLabel(), label = p.NewLabel(),
       grad = p.NewLabel(), gloop = p.NewLabel(), grow = p.NewLabel(), gnext = p.NewLabel();
  entry_ = p.Here();
  // The panel off or in standby (hidden, folded, in the case): nothing; a DRAW would switch it on.
  p.SysRead(t1, c(g1::PROP_PANEL));
  p.Bne(t1, c(1), done);
  p.Mov(w(vI), w(pList));
  p.Mov(w(vT), w(pT0));
  p.SleepUntil(w(vT));
  p.Bind(top);
  // The step: f[0] the opcode, f[1] ... f[6] its operands (Pack).
  p.Load(32, lo, w(vI), c(0));
  p.Load(32, t1, w(vI), c(1));
  p.Add(w(vI), w(vI), c(8));
  p.And(f[0], lo, c(15));
  p.Shr(f[1], lo, c(4));
  p.And(f[1], f[1], c(1023));
  p.Shr(f[2], lo, c(14));
  p.And(f[2], f[2], c(1023));
  p.Shr(f[3], lo, c(24));
  p.And(t2, t1, c(3));
  p.Shl(t2, t2, c(8));
  p.Or(f[3], f[3], t2);
  p.Shr(f[4], t1, c(2));
  p.And(f[4], f[4], c(1023));
  p.Shr(f[5], t1, c(12));
  p.And(f[5], f[5], c(1023));
  p.Shr(f[6], t1, c(22));
  p.Beq(f[0], c(kCopy), copy);
  p.Beq(f[0], c(kShow), show);
  p.Beq(f[0], c(kLoad), load);
  p.Beq(f[0], c(kGrad), grad);
  p.Beq(f[0], c(kFrame), frame);
  p.Beq(f[0], c(kBlank), blank);
  p.Beq(f[0], c(kFill), fill);
  p.Beq(f[0], c(kLabel), label);
  p.Bind(done); // kEnd, or anything else
  p.Ret();
  p.Bind(frame);
  p.Add(w(vT), w(vT), w(pDt));
  p.SleepUntil(w(vT));
  p.Jmp(top);
  p.Bind(copy); // x, y, w, h, px, py
  p.Add(t1, w(pRx), f[5]);
  p.Add(t2, w(pRy), f[6]);
  p.Bne(w(pMode), c(0), copy1);
  p.Draw(w(pSrc), w(pStride), f[1], f[2], t1, t2, f[3], f[4]);
  p.Jmp(top);
  p.Bind(copy1); // 1 bpp: through the scratch
  p.Add(w(vS), f[3], c(1));
  p.Shr(w(vS), w(vS), c(1));
  p.Blit(w(pSrc), w(pStride), f[1], f[2], scr, w(vS), c(0), c(0), f[3], f[4], w(pMode), c(15));
  p.Draw(scr, w(vS), c(0), c(0), t1, t2, f[3], f[4]);
  p.Jmp(top);
  p.Bind(load); // x, y, w, h, dx, total
  p.Add(w(vS), f[6], c(1));
  p.Shr(w(vS), w(vS), c(1));
  p.Mov(w(vW), f[6]);
  p.Mov(w(vH), f[4]);
  p.Blit(w(pSrc), w(pStride), f[1], f[2], scr, w(vS), f[5], c(0), f[3], f[4], w(pMode), c(15));
  p.Jmp(top);
  p.Bind(blank); // w, h
  p.Add(w(vS), f[1], c(1));
  p.Shr(w(vS), w(vS), c(1));
  p.Mov(w(vW), f[1]);
  p.Mov(w(vH), f[2]);
  p.Fill(scr, w(vS), c(0), c(0), f[1], f[2], c(0));
  p.Jmp(top);
  p.Bind(fill); // x, y, w, h, level
  p.Fill(scr, w(vS), f[1], f[2], f[3], f[4], f[5]);
  p.Jmp(top);
  p.Bind(show); // px, py
  p.Add(t1, w(pRx), f[1]);
  p.Add(t2, w(pRy), f[2]);
  p.Draw(scr, w(vS), c(0), c(0), t1, t2, w(vW), w(vH));
  p.Jmp(top);
  p.Bind(label); // px, py
  p.Add(t1, w(pRx), f[1]);
  p.Add(t2, w(pRy), f[2]);
  p.Draw(w(pLabel), w(pLStride), c(0), c(0), t1, t2, w(pLw), w(pLh));
  p.Jmp(top);
  p.Bind(grad); // x, y, len, levels low, levels high, n | axis << 3
  p.Shl(t3, f[5], c(8));
  p.Or(t3, t3, f[4]);
  p.And(t2, f[6], c(7)); // n
  p.Shr(f[6], f[6], c(3)); // axis
  p.Mov(t4, c(0));
  p.Bind(gloop);
  p.BgeU(t4, t2, top);
  p.And(t1, t3, c(15));
  p.Shr(t3, t3, c(4));
  p.Bne(f[6], c(0), grow);
  p.Add(f[4], f[1], t4);
  p.Fill(scr, w(vS), f[4], f[2], c(1), f[3], t1);
  p.Jmp(gnext);
  p.Bind(grow);
  p.Add(f[4], f[2], t4);
  p.Fill(scr, w(vS), f[1], f[4], f[3], c(1), t1);
  p.Bind(gnext);
  p.Add(t4, t4, c(1));
  p.Jmp(gloop);
  p.Changes(params_ + 4 * vT, 4 * (kWords - vT));
  p.Changes(scratch_, uint32_t(kScratchBytes));
  std::string why;
  if (!p.Valid(&why) || p.Here() > params_) {
    if (error) *error = "the transitions' code: " + (why.empty() ? std::string("too long") : why);
    g1::Free(code_);
    base_ = -1;
    return false;
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    epochs_[size_t(i)] = arm.GetState().epoch_changes;
    if (!arm.GetState().present) continue;
    arm.WriteArena(params_, std::vector<uint8_t>(kParamBytes, 0));
    if (!arm.Load(p)) {
      if (error) *error = "the transitions' code did not load";
      g1::Free(code_);
      base_ = -1;
      return false;
    }
  }
  started_ = true;
  blank_ = false;
  LOG("fx: %u bytes at %u, code %u bytes", size, code_, p.Here() - code_);
  return true;
}

void Fx::Stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  started_ = false;
  if (base_ >= 0) g1::Free(code_);
  base_ = -1;
}

bool Fx::Ready() {
  std::lock_guard<std::mutex> lock(mutex_);
  return Ensure();
}

// An arm that rebooted lost the block: the code again (mutex_ held).
bool Fx::Ensure() {
  if (!started_) return false;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present || st.epoch_changes == epochs_[size_t(i)]) continue;
    LOG("fx: arm %d rebooted, the code again", i);
    started_ = false;
    g1::Free(code_);
    base_ = -1;
    return StartLocked(nullptr);
  }
  return true;
}

int64_t Fx::Lead() {
  int64_t lead = 30000;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present) continue;
    lead = std::max<int64_t>(lead, 2 * st.interval_us + st.clock_error_us + 15000);
  }
  return lead;
}

// The steps on both arms, the first frame at the same due time (mutex_ held).
void Fx::Run(const Region &r, const std::vector<Step> &steps, const Source *src, int frame_us, int64_t from_us) {
  if (steps.size() > size_t(kListSteps) || Jumps(steps) > kJumpBudget) {
    LOG("fx: a plan of %zu steps (%d jumps) is too long", steps.size(), Jumps(steps));
    return;
  }
  std::vector<uint8_t> list = Pack(steps);
  // The steps' time on the link (about 20 KB/s), then both eyes start together.
  int64_t now = g1::NowUs();
  int64_t start = std::max(from_us, now + Lead() + int64_t(list.size()) * 50);
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present || !st.connected) continue;
    uint32_t words[12] = {arm.DeviceTime(start - now), uint32_t(frame_us), list_, uint32_t(r.at[size_t(i)][0]),
                          uint32_t(r.at[size_t(i)][1]), src ? src->address : 0, src ? src->stride : 0,
                          src && src->bpp == 1 ? 4u : 0u, label_, uint32_t(lw_ / 2), uint32_t(lw_), uint32_t(lh_)};
    std::vector<uint8_t> bytes(sizeof words);
    std::memcpy(bytes.data(), words, sizeof words);
    arm.WriteArena(list_, list);
    arm.WriteArena(params_, bytes);
    arm.Call(entry_);
  }
  busy_until_ = start + int64_t(Frames(steps)) * frame_us;
}

void Fx::Out(Style style, const Region &r, const std::vector<uint8_t> &label, int lw, int lh) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!Ensure() || r.w <= 0 || r.h <= 0) return;
  bool fits = lw > 0 && lh > 0 && !(lw & 1) && lw <= r.w && lh <= r.h && label.size() >= size_t(lw / 2) * lh &&
              size_t(lw / 2) * lh <= size_t(kLabelBytes) && style != kOff;
  // Already black from an Out with nothing shown since (switching on quickly): only the label changes.
  bool again = blank_ && blank_w_ == r.w && blank_h_ == r.h && style != kOff;
  std::vector<Step> steps;
  if (again) {
    Plan p;
    if (blank_lw_) p.Erase(((r.w - blank_lw_) / 2) & ~1, (r.h - blank_lh_) / 2, blank_lw_, blank_lh_);
    steps = std::move(p.s);
  }
  lw_ = fits ? lw : 0;
  lh_ = fits ? lh : 0;
  if (fits) {
    label_pixels_.assign(label.begin(), label.begin() + ptrdiff_t(size_t(lw / 2) * lh));
    for (g1::Arm *a : {&g1::left, &g1::right})
      if (a->GetState().present && a->GetState().connected) a->WriteArena(label_, label_pixels_);
  }
  if (again) {
    if (lw_) steps.push_back(Step{{kLabel, uint16_t(((r.w - lw_) / 2) & ~1), uint16_t((r.h - lh_) / 2)}});
    steps.push_back(Step{{kEnd}});
  } else {
    steps = PlanOut(style, r.w, r.h, lw_, lh_, seed_ = seed_ * 1103515245u + 12345u);
  }
  Run(r, steps, nullptr, style == kOff ? 26000 : kOutUs, 0);
  blank_ = style != kOff;
  blank_w_ = r.w;
  blank_h_ = r.h;
  blank_lw_ = lw_;
  blank_lh_ = lh_;
}

void Fx::Label(const Region &r) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!Ensure() || !lw_ || lw_ > r.w || lh_ > r.h) return;
  std::vector<Step> steps{Step{{kLabel, uint16_t(((r.w - lw_) / 2) & ~1), uint16_t((r.h - lh_) / 2)}}, Step{{kEnd}}};
  Run(r, steps, nullptr, kOutUs, 0);
  blank_ = true;
  blank_w_ = r.w;
  blank_h_ = r.h;
  blank_lw_ = lw_;
  blank_lh_ = lh_;
}

void Fx::In(Style style, const Region &r, const Source &src, int64_t from_us) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!Ensure() || r.w <= 0 || r.h <= 0) return;
  std::vector<Step> steps = PlanIn(style, r.w, r.h, seed_ = seed_ * 1103515245u + 12345u, src.bpp);
  Run(r, steps, &src, kInUs, std::max(from_us, busy_until_));
  blank_ = false;
}

int64_t Fx::busy_until() {
  std::lock_guard<std::mutex> lock(mutex_);
  return busy_until_;
}

} // namespace ssh
