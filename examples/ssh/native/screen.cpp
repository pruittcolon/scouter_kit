#include "screen.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "scouter", __VA_ARGS__)
#else
#include <cstdio>
#define LOG(...) (std::fprintf(stderr, __VA_ARGS__), std::fputc('\n', stderr))
#endif

namespace g1 = scouter_kit::g1;
namespace sk = scouter_kit;

namespace ssh {

namespace {
constexpr uint32_t kCode = 512;        // the VIDEO path's stored code (table, pool, a few instructions)
constexpr uint32_t kRecordMax = 65536; // the largest record sent; a larger change waits for a smaller one
constexpr uint32_t kHeader = 12;       // due time, frame number, record size
constexpr int kQueued = 4;             // commands not yet sent on an arm's link: more, and a frame is skipped

void Put32(std::vector<uint8_t> &v, size_t at, uint32_t x) {
  v[at] = uint8_t(x);
  v[at + 1] = uint8_t(x >> 8);
  v[at + 2] = uint8_t(x >> 16);
  v[at + 3] = uint8_t(x >> 24);
}
} // namespace

void Luma(const uint8_t *rgba, int w, int h, int stride, std::vector<uint8_t> *out) {
  out->resize(size_t(w) * h);
  uint8_t *o = out->data();
  for (int y = 0; y < h; y++) {
    const uint8_t *p = rgba + size_t(y) * stride;
    for (int x = 0; x < w; x++, p += 4) *o++ = uint8_t((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8);
  }
}

void Rotate(const std::vector<uint8_t> &in, int w, int h, int degrees, std::vector<uint8_t> *out, int *ow, int *oh) {
  int d = ((degrees % 360) + 360) % 360;
  int W = (d == 90 || d == 270) ? h : w, H = (d == 90 || d == 270) ? w : h;
  *ow = W;
  *oh = H;
  out->resize(size_t(w) * h);
  uint8_t *o = out->data();
  for (int y = 0; y < H; y++) {
    uint8_t *row = o + size_t(y) * W;
    switch (d) {
    case 90:
      for (int x = 0; x < W; x++) row[x] = in[size_t(h - 1 - x) * w + y];
      break;
    case 180:
      for (int x = 0; x < W; x++) row[x] = in[size_t(h - 1 - y) * w + (w - 1 - x)];
      break;
    case 270:
      for (int x = 0; x < W; x++) row[x] = in[size_t(x) * w + (w - 1 - y)];
      break;
    default:
      std::copy_n(in.data() + size_t(y) * w, w, row);
    }
  }
}

Rect Place(int pw, int ph, const ScreenOptions &o) {
  int w = std::min(pw, std::max(8, std::clamp(o.out_w, 1, 100) * pw / 100));
  int h = std::min(ph, std::max(8, std::clamp(o.out_h, 1, 100) * ph / 100));
  int x = std::clamp(std::clamp(o.out_x, 0, 100) * pw / 100, 0, pw - w);
  int y = std::clamp(std::clamp(o.out_y, 0, 100) * ph / 100, 0, ph - h);
  return {x, y, w, h};
}

Rect Source(int sw, int sh, int dw, int dh, const ScreenOptions &o) {
  int rw = sw, rh = sh;
  if (o.fit == ScreenOptions::kFill && dw > 0 && dh > 0) {
    if (int64_t(sw) * dh > int64_t(sh) * dw) rw = std::max(1, int(int64_t(sh) * dw / dh));
    else rh = std::max(1, int(int64_t(sw) * dh / dw));
  }
  if (o.zoom > 100) {
    rw = std::max(1, int(int64_t(rw) * 100 / o.zoom));
    rh = std::max(1, int(int64_t(rh) * 100 / o.zoom));
  }
  int px = std::clamp(o.pan_x, 0, 100), py = std::clamp(o.pan_y, 0, 100);
  return {int(int64_t(sw - rw) * px / 100), int(int64_t(sh - rh) * py / 100), rw, rh};
}

Rect Fit(std::vector<uint8_t> *dst, int pw, int ph, const std::vector<uint8_t> &src, int sw, Rect r, Rect d, bool keep) {
  dst->assign(size_t(pw) * ph, 0);
  if (r.w <= 0 || r.h <= 0 || d.w <= 0 || d.h <= 0) return {};
  int ow = d.w, oh = d.h, ox = d.x, oy = d.y;
  if (keep) {
    double k = std::min(double(d.w) / r.w, double(d.h) / r.h);
    ow = std::clamp(int(r.w * k), 1, d.w);
    oh = std::clamp(int(r.h * k), 1, d.h);
    ox = d.x + (d.w - ow) / 2;
    oy = d.y + (d.h - oh) / 2;
  }
  // The source columns under each picture column, worked out once.
  std::vector<int> x0s(size_t(ow), 0), x1s(size_t(ow), 0);
  for (int x = 0; x < ow; x++) {
    x0s[size_t(x)] = r.x + int(int64_t(x) * r.w / ow);
    x1s[size_t(x)] = std::max(r.x + int(int64_t(x + 1) * r.w / ow), x0s[size_t(x)] + 1);
  }
  for (int y = 0; y < oh; y++) {
    int y0 = r.y + int(int64_t(y) * r.h / oh);
    int y1 = std::max(r.y + int(int64_t(y + 1) * r.h / oh), y0 + 1);
    uint8_t *out = dst->data() + size_t(y + oy) * pw + ox;
    for (int x = 0; x < ow; x++) {
      int x0 = x0s[size_t(x)], x1 = x1s[size_t(x)];
      int sum = 0;
      for (int yy = y0; yy < y1; yy++) {
        const uint8_t *row = src.data() + size_t(yy) * sw;
        for (int xx = x0; xx < x1; xx++) sum += row[xx];
      }
      out[x] = uint8_t(sum / ((y1 - y0) * (x1 - x0)));
    }
  }
  return {ox, oy, ow, oh};
}

void Stretch(std::vector<uint8_t> *p, int pw, Rect c, int amount) {
  if (amount <= 0 || c.w <= 0 || c.h <= 0) return;
  size_t hist[256] = {};
  for (int y = c.y; y < c.y + c.h; y++) {
    const uint8_t *row = p->data() + size_t(y) * pw + c.x;
    for (int x = 0; x < c.w; x++) hist[row[x]]++;
  }
  size_t n = size_t(c.w) * c.h, seen = 0;
  int lo = 0, hi = 255;
  for (int v = 0; v < 256; v++) {
    seen += hist[v];
    if (seen > n / 50) {
      lo = v;
      break;
    }
  }
  seen = 0;
  for (int v = 0; v < 256; v++) {
    seen += hist[v];
    if (seen >= n - n / 50) {
      hi = v;
      break;
    }
  }
  if (hi - lo < 16) return; // a flat picture: nothing to stretch
  int a = std::min(amount, 100);
  uint8_t lut[256];
  for (int v = 0; v < 256; v++) {
    int s = std::clamp((v - lo) * 255 / (hi - lo), 0, 255);
    lut[v] = uint8_t(v + (s - v) * a / 100);
  }
  for (int y = c.y; y < c.y + c.h; y++) {
    uint8_t *row = p->data() + size_t(y) * pw + c.x;
    for (int x = 0; x < c.w; x++) row[x] = lut[row[x]];
  }
}

void Sharpen(std::vector<uint8_t> *p, int pw, Rect c) {
  if (c.w < 3 || c.h < 3) return;
  std::vector<uint8_t> src(*p);
  for (int y = c.y + 1; y < c.y + c.h - 1; y++) {
    const uint8_t *up = &src[size_t(y - 1) * pw], *mid = &src[size_t(y) * pw], *down = &src[size_t(y + 1) * pw];
    uint8_t *out = p->data() + size_t(y) * pw;
    for (int x = c.x + 1; x < c.x + c.w - 1; x++) {
      int sum = up[x - 1] + up[x] + up[x + 1] + mid[x - 1] + mid[x] + mid[x + 1] + down[x - 1] + down[x] + down[x + 1];
      out[x] = uint8_t(std::clamp(2 * mid[x] - sum / 9, 0, 255));
    }
  }
}

void Tone(std::vector<uint8_t> *p, int pw, Rect c, int invert, int gamma, int threshold, int levels, bool dither,
          int cell) {
  int a = std::clamp(invert, 0, 100);
  double g = std::clamp(gamma, 20, 500) / 100.0;
  int n = (levels == 4 || levels == 8 ? levels : 16) - 1; // steps between black and full
  cell = std::max(1, cell);
  uint8_t lut[256];
  for (int v = 0; v < 256; v++) {
    int f = v + (255 - 2 * v) * a / 100; // a blend toward the inverse
    int q = g == 1.0 ? f : int(std::pow(f / 255.0, g) * 255.0 + 0.5);
    int k = (q * n + 127) / 255;
    lut[v] = levels == 2 ? (q >= threshold ? 255 : 0) : uint8_t((k * 15 + n / 2) / n * 17);
  }
  if (levels == 2 && dither) {
    // The cut-off moves with a 4 x 4 Bayer matrix (fixed in place, so a still picture stays still): greys come
    // out as patterns. The levels before the cut-off are the toned ones (lut without the cut).
    static const int kBayer[4][4] = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
    uint8_t tone[256];
    for (int v = 0; v < 256; v++) {
      int f = v + (255 - 2 * v) * a / 100;
      tone[v] = uint8_t(g == 1.0 ? f : int(std::pow(f / 255.0, g) * 255.0 + 0.5));
    }
    int shift = threshold - 128; // the cut-off still biases light or dark
    for (int y = c.y; y < c.y + c.h; y++) {
      uint8_t *row = p->data() + size_t(y) * pw + c.x;
      for (int x = 0; x < c.w; x++) {
        int cut = kBayer[((y - c.y) / cell) & 3][(x / cell) & 3] * 16 + 8 + shift;
        row[x] = tone[row[x]] >= cut ? 255 : 0;
      }
    }
    return;
  }
  for (int y = c.y; y < c.y + c.h; y++) {
    uint8_t *row = p->data() + size_t(y) * pw + c.x;
    for (int x = 0; x < c.w; x++) row[x] = lut[row[x]];
  }
}

void Pixelate(std::vector<uint8_t> *p, int pw, Rect c, int n) {
  if (n <= 1) return;
  uint8_t *d = p->data();
  for (int by = c.y; by < c.y + c.h; by += n) {
    int bh = std::min(n, c.y + c.h - by);
    for (int bx = c.x; bx < c.x + c.w; bx += n) {
      int bw = std::min(n, c.x + c.w - bx), sum = 0;
      for (int y = by; y < by + bh; y++)
        for (int x = bx; x < bx + bw; x++) sum += d[size_t(y) * pw + x];
      uint8_t v = uint8_t(sum / (bw * bh));
      for (int y = by; y < by + bh; y++) std::memset(d + size_t(y) * pw + bx, v, size_t(bw));
    }
  }
}

// The pointer (o.cursor_*) on a w x h luma picture: an arrow about 13 of the glasses' pixels tall, its tip at
// the spot, white with a black rim (one of them lit, whatever the picture's inversion).
void Cursor(std::vector<uint8_t> *luma, int w, int h, const ScreenOptions &o, int pw, int ph) {
  if (o.cursor_x < 0 || o.cursor_y < 0 || w <= 0 || h <= 0 || pw <= 0 || ph <= 0) return;
  float per = std::max(float(w) / float(pw), float(h) / float(ph)) * 100.0f / float(std::max(100, o.zoom));
  float size = 13.0f * std::max(per, 0.25f); // the arrow's height, phone pixels
  float rim = std::max(1.0f, 1.3f * per);
  int tx = int(int64_t(std::clamp(o.cursor_x, 0, 10000)) * (w - 1) / 10000);
  int ty = int(int64_t(std::clamp(o.cursor_y, 0, 10000)) * (h - 1) / 10000);
  // The arrow: the tip, straight down its left side, back up to a point right of it (units of size).
  int x0 = std::max(0, int(tx - rim - 1)), x1 = std::min(w - 1, int(tx + 0.72f * size + rim + 1));
  int y0 = std::max(0, int(ty - rim - 1)), y1 = std::min(h - 1, int(ty + size + rim + 1));
  const float k = 0.7071f;
  for (int y = y0; y <= y1; y++) {
    for (int x = x0; x <= x1; x++) {
      float u = float(x - tx), v = float(y - ty);
      float d1 = u;                                         // its left side
      float d2 = (v - u) * k;                               // the side from the tip to the right point
      float d3 = -(0.3f * u + 0.7f * (v - size)) / 0.7616f; // the side from the bottom to the right point
      float d = std::min({d1, d2, d3});
      if (d < -rim) continue;
      (*luma)[size_t(y) * size_t(w) + size_t(x)] = d >= rim ? 255 : 0;
    }
  }
}

Rect Base(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o, int pw, int ph,
          std::vector<uint8_t> *luma, std::vector<uint8_t> *rot, std::vector<uint8_t> *out) {
  Luma(rgba, w, h, stride, luma);
  Cursor(luma, w, h, o, pw, ph);
  int sw = w, sh = h;
  const std::vector<uint8_t> *src = luma;
  if (o.rotate % 360 != 0) {
    Rotate(*luma, w, h, o.rotate, rot, &sw, &sh);
    src = rot;
  }
  Rect d = Place(pw, ph, o);
  Rect c = Fit(out, pw, ph, *src, sw, Source(sw, sh, d.w, d.h, o), d, o.fit == ScreenOptions::kFit);
  Stretch(out, pw, c, o.contrast);
  if (o.sharpen) Sharpen(out, pw, c);
  return c;
}

void Finish(const std::vector<uint8_t> &base, int pw, Rect c, const ScreenOptions &o, int n, std::vector<uint8_t> *out) {
  *out = base;
  n = std::clamp(n, 1, 4);
  Pixelate(out, pw, c, n);
  Tone(out, pw, c, o.invert, o.gamma, o.threshold, o.levels, o.dither, n);
}

void Picture(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o, int pw, int ph,
             std::vector<uint8_t> *out) {
  std::vector<uint8_t> luma, rot, base;
  Rect c = Base(rgba, w, h, stride, o, pw, ph, &luma, &rot, &base);
  Finish(base, pw, c, o, std::max(1, o.detail), out);
}

void PictureSize(int dx, int dy, int *pw, int *ph) {
  *pw = std::max(2, (640 - std::min(std::abs(dx), 600)) & ~1);
  *ph = std::max(2, 480 - std::min(std::abs(dy), 400));
}

ScreenMirror::~ScreenMirror() { Stop(); }

void ScreenMirror::Panels(bool on) {
  for (g1::Arm *a : {&g1::left, &g1::right})
    if (a->GetState().connected) a->SetPanel(on);
}

bool ScreenMirror::Start(int dx, int dy, std::string *error) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (started_) return true;
  int dw = sk::gray::display.width, dh = sk::gray::display.height;
  if (dw <= 0 || dh <= 0) {
    if (error) *error = "the glasses are not connected";
    return false;
  }
  dx_ = dx;
  dy_ = dy;
  int adx = std::abs(dx);
  w_ = std::min(640 - adx, dw) & ~1; // even: 4-bpp bytes
  h_ = dh;
  x_ = std::clamp(adx, 0, dw - w_);
  y_ = 0;
  // The library's picture of the display goes black, then the panels power-cycle: that clears what the
  // terminal's program drew there, which the library never saw.
  sk::gray::Clear();
  sk::Flush();
  Panels(false);
  Panels(true);
  levels_ = 0;
  prev_.clear();
  last_base_.clear();
  hidden_ = false;
  paused_ = false;
  props_us_ = 0;
  error_.clear();
  seen_ = 0;
  stats_ = Stats{};
  window_us_ = 0;
  window_bytes_ = 0;
  started_ = true;
  LOG("screen: picture %d x %d at (%d, %d)", w_, h_, x_, y_);
  return true;
}

// Frees the arena the path used; the panels are cleared (VIDEO draws past the gray display's picture).
void ScreenMirror::DropPath() {
  if (levels_ == 16 && pic_) {
    sk::gray::Unload(*pic_);
    pic_ = nullptr;
    sk::gray::Clear();
    sk::Flush();
  }
  if (levels_ == 2) {
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    video_ = Video{};
    Panels(false);
    Panels(true);
  }
  levels_ = 0;
  prev_.clear();
}

bool ScreenMirror::Path(int levels) {
  int want = levels == 2 ? 2 : 16;
  if (want == levels_) return true;
  DropPath();
  if (want == 16) {
    pic_ = &sk::gray::CreateBitmap(w_, h_);
    if (!pic_->contents.data) {
      // Fonts or pictures nobody uses now (the terminal has stopped): once more without them.
      sk::UnloadAll();
      pic_ = &sk::gray::CreateBitmap(w_, h_);
    }
    if (!pic_->contents.data) {
      pic_ = nullptr;
      error_ = "no room on the arms for a " + std::to_string(w_) + " x " + std::to_string(h_) + " picture";
      LOG("screen: %s (free %d of %d bytes)", error_.c_str(), sk::free_memory, sk::total_memory);
      return false;
    }
  } else if (!StartVideo()) {
    LOG("screen: %s", error_.c_str());
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    video_ = Video{};
    return false;
  }
  levels_ = want;
  error_.clear();
  LOG("screen: %s path, %d x %d at (%d, %d)", want == 2 ? "VIDEO" : "gray", w_, h_, x_, y_);
  return true;
}

// The VIDEO path (black and white): per arm, stored code that sleeps until the frame's due time and draws the
// record's changed rows at the eye's place; the host writes the due time, the frame number, the size and the
// record, then CALLs it (VM.md §3.7). Both arms get the same due time: the eyes change together.
bool ScreenMirror::StartVideo() {
  Video &v = video_;
  v.stride = uint32_t(((w_ + 7) / 8 + 3) & ~3);
  uint32_t size = kCode + v.stride * uint32_t(h_) + g1::kVideoState + 8 + kHeader + kRecordMax;
  v.base = g1::Alloc(size);
  if (v.base < 0) {
    sk::UnloadAll();
    v.base = g1::Alloc(size);
  }
  if (v.base < 0) {
    error_ = "no room on the arms for the fast (black and white) path";
    return false;
  }
  v.size = size;
  v.code = uint32_t(v.base);
  v.bitmap = v.code + kCode;
  v.state = (v.bitmap + v.stride * uint32_t(h_) + 3) & ~3u;
  v.buf = (v.state + g1::kVideoState + 3) & ~3u;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    int px = i == 0 ? x_ - std::max(0, -dx_) : x_ - std::max(0, dx_);
    int py = i == 0 ? y_ + std::max(0, -dy_) : y_ + std::max(0, dy_);
    g1::Program p(v.code, 16);
    g1::Address at = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.Video(p.Const(v.bitmap), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(uint32_t(px)),
            p.Const(uint32_t(py)), p.Const(15), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2), p.Const(v.state));
    p.Ret();
    std::string why;
    if (!p.Valid(&why)) {
      error_ = "the VIDEO code: " + why;
      return false;
    }
    arm.WriteArena(v.bitmap, std::vector<uint8_t>(size_t(v.stride) * h_, 0));
    if (!arm.Load(p)) {
      error_ = "the VIDEO code did not load (a firmware before VIDEO?)";
      return false;
    }
    v.call[size_t(i)] = at;
    v.epochs[size_t(i)] = arm.GetState().epoch_changes;
  }
  v.enc = std::make_unique<g1::VideoEncoder>(w_, h_);
  v.pix.assign(size_t(w_) * h_, 0);
  v.k = 0;
  v.redraw = true;
  return true;
}

// Whether a record may go now: both arms connected with little queued; a rebooted arm (its bitmap and decoder
// state lost) starts the path again.
int ScreenMirror::VideoReady() {
  Video &v = video_;
  int64_t lead = 30000;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present) continue;
    if (!st.connected || st.queued > kQueued) {
      stats_.busy++;
      return kBusy;
    }
    if (st.epoch_changes != v.epochs[size_t(i)]) {
      LOG("screen: arm %d rebooted, the VIDEO path starts again", i);
      DropPath();
      return Path(2) ? kBusy : kSame;
    }
    lead = std::max<int64_t>(lead, st.interval_us + st.clock_error_us + 10000);
  }
  v.lead = lead;
  return kShown;
}

// frame_ as a VIDEO record to both arms, drawn at the same due time; false (nothing sent, the encoder as it was)
// when the record would be larger than max_bytes.
bool ScreenMirror::SendVideo(size_t max_bytes) {
  Video &v = video_;
  for (size_t i = 0; i < v.pix.size(); i++) v.pix[i] = frame_[i] >= 128;
  std::vector<uint8_t> rec = v.enc->Encode(v.pix, v.redraw, std::min<size_t>(max_bytes, kRecordMax));
  if (rec.empty()) return false;
  // The record's time on the link at about 40 KB/s (2M PHY, short intervals); an arm that gets it later draws it
  // as soon as it is in, so a short lead only costs the eyes' sync on large changes, a long one the frame rate.
  int64_t lead = std::min<int64_t>(v.lead + int64_t(rec.size()) * 1000000 / 40000, 1000000);
  std::vector<uint8_t> msg(kHeader + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  std::memcpy(msg.data() + kHeader, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    Put32(msg, 0, arm.DeviceTime(lead));
    arm.WriteArena(v.buf, msg);
    arm.Call(v.call[size_t(i)]);
  }
  v.redraw = false;
  v.k++;
  v.sent = int(msg.size());
  return true;
}

void ScreenMirror::Count(int bytes, int detail) {
  frames_++;
  stats_.bytes = bytes;
  stats_.detail = detail;
  int64_t now = g1::NowUs();
  if (!window_us_) window_us_ = now;
  window_bytes_ += bytes;
  if (now - window_us_ >= 2000000) {
    stats_.rate = int(int64_t(window_bytes_) * 1000000 / (now - window_us_));
    window_us_ = now;
    window_bytes_ = 0;
  }
}

ScreenMirror::Stats ScreenMirror::stats() {
  std::lock_guard<std::mutex> lock(mutex_);
  Stats s = stats_;
  s.frames = frames_;
  if (window_us_ && g1::NowUs() - window_us_ > 4000000) s.rate = 0; // nothing sent lately
  s.w = w_;
  s.h = h_;
  s.x = x_;
  s.y = y_;
  s.path = started_ ? levels_ : 0;
  s.hidden = hidden_ ? 1 : 0;
  return s;
}

bool ScreenMirror::Preview(std::vector<uint8_t> *out, int *w, int *h) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || hidden_ || prev_.size() != size_t(w_) * h_ || prev_.empty()) return false;
  *out = prev_;
  *w = w_;
  *h = h_;
  return true;
}

std::string ScreenMirror::Check() {
  {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!started_) return "the phone's screen is not on the glasses\n";
    if (hidden_) return "the panels are off: " + hide_why_ + "\n";
    paused_ = true; // no frames while the arms answer: the library's picture stays the one they are asked about
  }
  std::string out;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    std::string name = i == 0 ? "left eye" : "right eye";
    auto st = arm.GetState();
    if (!st.present) continue;
    if (!st.connected) {
      out += name + ": not connected\n";
      continue;
    }
    uint32_t got = 0, want = 0;
    if (!arm.PanelCrc(0, 480, &got, &want)) out += name + ": no answer (panel off?)\n";
    else out += name + (got == want ? ": shows what was sent\n" : ": differs from what was sent\n");
  }
  {
    std::lock_guard<std::mutex> lock(mutex_);
    paused_ = false;
  }
  LOG("screen: check: %s", out.c_str());
  return out;
}

void ScreenMirror::Stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  started_ = false;
  DropPath();
  sk::gray::Clear();
  sk::Flush();
  if (hidden_) Panels(true); // the terminal's program decides from here
  hidden_ = false;
}

int ScreenMirror::frames() {
  std::lock_guard<std::mutex> lock(mutex_);
  return frames_;
}

std::string ScreenMirror::why() {
  std::lock_guard<std::mutex> lock(mutex_);
  return hidden_ ? hide_why_ : error_;
}

bool ScreenMirror::Hidden(const ScreenOptions &o) {
  int64_t now = g1::NowUs();
  if (props_us_ && now - props_us_ < 1000000) return props_hidden_;
  props_us_ = now;
  bool in_case = false, known = false, folded = true;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    g1::Arm::State s = a->GetState();
    if (!s.connected) continue;
    const uint8_t ids[2] = {g1::PROP_IN_CASE, g1::PROP_WORN};
    uint32_t v[2] = {g1::UNKNOWN, g1::UNKNOWN};
    bool got = a->Props(ids, v);
    // The arm's IN_CASE now; State::in_case is only what its session's first message said (the glasses
    // connected in the case and taken out since: still true), so it counts only without the property.
    if (got && v[0] != g1::UNKNOWN) {
      if (v[0] == 1) in_case = true;
    } else if (s.in_case) {
      in_case = true;
    }
    if (!got) continue;
    if (v[1] != g1::UNKNOWN) {
      known = true;
      folded = folded && v[1] == 0;
    }
  }
  bool by_case = o.hide_case && in_case, by_fold = o.hide_folded && known && folded;
  hide_why_ = by_case ? "in the case" : by_fold ? "folded" : "";
  props_hidden_ = by_case || by_fold;
  return props_hidden_;
}

int ScreenMirror::Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return kStopped;
  if (paused_) return kBusy;
  if (w <= 0 || h <= 0 || stride < w * 4) return kSame;
  if (seen_++ == 0) LOG("screen: first frame, %d x %d", w, h);
  bool hide = Hidden(o);
  if (hide != hidden_) {
    hidden_ = hide;
    if (hide) {
      LOG("screen: panels off (%s)", hide_why_.c_str());
      sk::gray::Clear();
      sk::Flush();
      Panels(false);
    } else {
      LOG("screen: panels on again");
      Panels(true);
      prev_.clear();
      video_.redraw = true; // the power-on cleared the panels: every row again
    }
  }
  if (hide) return kHidden;
  if (!Path(o.levels)) return kSame;
  Rect c = Base(rgba, w, h, stride, o, w_, h_, &luma_, &rot_, &base_);
  bool still = base_ == last_base_; // the same screen as last time: nothing moved
  if (!still) last_base_ = base_;

  if (levels_ == 2) {
    int ready = VideoReady();
    if (ready != kShown) return ready;
    bool redraw = video_.redraw;
    if (o.detail >= 1) {
      int n = std::clamp(o.detail, 1, 4);
      Finish(base_, w_, c, o, n, &frame_);
      if (frame_ == prev_ && !redraw) return kSame;
      if (!SendVideo(kRecordMax)) return kBusy; // too large for one record: the next, smaller change goes
      prev_ = frame_;
      Count(video_.sent, n);
      return kShown;
    }
    // Auto: every pixel when that fits the budget, else coarser; nothing moved: every pixel whatever the size.
    size_t budget = still ? kRecordMax : size_t(std::clamp(o.budget, 1, 64)) * 1024;
    for (int n = 1; n <= 4; n++) {
      Finish(base_, w_, c, o, n, &frame_);
      if (frame_ == prev_ && !redraw) return n == 1 ? kSame : kLater;
      if (SendVideo(n == 4 ? kRecordMax : budget)) {
        prev_ = frame_;
        Count(video_.sent, n);
        return n == 1 ? kShown : kLater;
      }
    }
    return kBusy;
  }

  // Greys: the library uploads the changed pixels (4 bits each).
  int n = std::clamp(o.detail, 1, 4);
  Finish(base_, w_, c, o, n, &frame_);
  if (o.steady && o.levels != 4 && o.levels != 8 && prev_.size() == frame_.size())
    for (size_t i = 0; i < frame_.size(); i++)
      if (std::abs(int(frame_[i]) - int(prev_[i])) <= 17) frame_[i] = prev_[i];
  if (frame_ == prev_) return kSame;
  int changed = 0;
  if (prev_.size() == frame_.size()) {
    for (size_t i = 0; i < frame_.size(); i++) changed += frame_[i] != prev_[i];
  } else {
    changed = int(frame_.size());
  }
  sk::gray::Write(*pic_, sk::gray::ConstBitmapView{frame_.data(), w_, h_, w_}, 0, 0, sk::gray::DITHER_NONE);
  sk::gray::Clear();
  sk::gray::Draw(*pic_, x_, y_);
  sk::Flush();
  prev_ = frame_;
  Count((changed + 1) / 2, n);
  return kShown;
}

} // namespace ssh
