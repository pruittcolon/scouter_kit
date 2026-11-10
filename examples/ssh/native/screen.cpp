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
constexpr uint32_t kCode = 2048;       // the VIDEO path's stored code (table, pool, the decode and the cursor)
constexpr uint32_t kPtrWords = 16;     // the cursor's words: X, Y, On (the host's), then its own
constexpr uint32_t kRecordMax = 65536; // the largest record sent; a larger change waits for a smaller one
constexpr uint32_t kHeader = 12;       // due time, frame number, record size
constexpr int kQueued = 4;             // commands not yet sent on an arm's link: more, and a frame is skipped

std::mutex g_spare_mutex;
std::function<void()> g_spare; // SetSpare

// Arena for the mirror: what others keep for later first (the terminal's fonts), then every font and picture.
void Spare() {
  std::function<void()> release;
  {
    std::lock_guard<std::mutex> lock(g_spare_mutex);
    release = g_spare;
  }
  if (release) release();
  sk::UnloadAll();
}

void Put32(std::vector<uint8_t> &v, size_t at, uint32_t x) {
  v[at] = uint8_t(x);
  v[at + 1] = uint8_t(x >> 8);
  v[at + 2] = uint8_t(x >> 16);
  v[at + 3] = uint8_t(x >> 24);
}
} // namespace

void SetSpare(std::function<void()> release) {
  std::lock_guard<std::mutex> lock(g_spare_mutex);
  g_spare = std::move(release);
}

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

std::vector<uint8_t> ArrowMasks() {
  const float poly[][2] = {{1, 1}, {1, 16}, {4.6f, 12.8f}, {7.2f, 18.4f}, {9.6f, 17.3f}, {7.1f, 11.9f}, {12, 11.9f}};
  const int np = int(std::size(poly));
  auto inside = [&](float x, float y) {
    bool in = false;
    for (int a = 0, b = np - 1; a < np; b = a++) {
      if ((poly[a][1] > y) != (poly[b][1] > y) &&
          x < (poly[b][0] - poly[a][0]) * (y - poly[a][1]) / (poly[b][1] - poly[a][1]) + poly[a][0])
        in = !in;
    }
    return in;
  };
  bool body[kArrowH][kArrowW] = {};
  for (int y = 0; y < kArrowH; y++)
    for (int x = 0; x < kArrowW; x++) body[y][x] = inside(x + 0.5f, y + 0.5f);
  std::vector<uint8_t> out(size_t(2 * 2 * kArrowH), 0);
  for (int y = 0; y < kArrowH; y++)
    for (int x = 0; x < kArrowW; x++) {
      bool edge = false;
      for (int dy = -1; dy <= 1; dy++)
        for (int dx = -1; dx <= 1; dx++) {
          int yy = y + dy, xx = x + dx;
          if (yy >= 0 && yy < kArrowH && xx >= 0 && xx < kArrowW && body[yy][xx]) edge = true;
        }
      if (body[y][x]) out[size_t(y * 2 + x / 8)] |= uint8_t(0x80 >> (x % 8));
      if (edge) out[size_t(2 * kArrowH + y * 2 + x / 8)] |= uint8_t(0x80 >> (x % 8));
    }
  return out;
}

namespace {
// Base's geometry for a w x h screen: the rows cut from its top, the cut screen's size, the turn, the shown part
// (in the turned screen's pixels) and where it goes on the picture (as Fit places it).
struct Geo {
  int w = 0, h = 0, top = 0, rot = 0;
  Rect r, out;
};
Geo Geometry(int w, int h, const ScreenOptions &o, int pw, int ph) {
  Geo g;
  g.w = w;
  g.h = h;
  int top = h * std::clamp(o.crop_top, 0, 400) / 1000, bottom = h * std::clamp(o.crop_bottom, 0, 400) / 1000;
  if (top + bottom > 0 && h - top - bottom >= 16) {
    g.top = top;
    g.h = h - top - bottom;
  }
  g.rot = ((o.rotate % 360) + 360) % 360;
  if (g.rot != 90 && g.rot != 180 && g.rot != 270) g.rot = 0;
  int sw = g.rot % 180 ? g.h : g.w, sh = g.rot % 180 ? g.w : g.h;
  Rect d = Place(pw, ph, o);
  g.r = Source(sw, sh, d.w, d.h, o);
  g.out = d;
  if (o.fit == ScreenOptions::kFit && g.r.w > 0 && g.r.h > 0) {
    double k = std::min(double(d.w) / g.r.w, double(d.h) / g.r.h);
    g.out.w = std::clamp(int(g.r.w * k), 1, d.w);
    g.out.h = std::clamp(int(g.r.h * k), 1, d.h);
    g.out.x = d.x + (d.w - g.out.w) / 2;
    g.out.y = d.y + (d.h - g.out.h) / 2;
  }
  return g;
}
} // namespace

bool Locate(int cx, int cy, int w, int h, const ScreenOptions &o, int pw, int ph, int *px, int *py) {
  if (w <= 1 || h <= 1 || pw <= 0 || ph <= 0) return false;
  Geo g = Geometry(w, h, o, pw, ph);
  if (g.r.w <= 0 || g.r.h <= 0) return false;
  // As Cursor places the tip: whole pixels of the screen, then the cut rows.
  double x = double(int64_t(std::clamp(cx, 0, 10000)) * (w - 1) / 10000);
  double y = double(int64_t(std::clamp(cy, 0, 10000)) * (h - 1) / 10000) - g.top;
  double rx = x, ry = y;
  switch (g.rot) {
  case 90: rx = g.h - 1 - y, ry = x; break;
  case 180: rx = g.w - 1 - x, ry = g.h - 1 - y; break;
  case 270: rx = y, ry = g.w - 1 - x; break;
  }
  double fx = (rx + 0.5 - g.r.x) * g.out.w / g.r.w, fy = (ry + 0.5 - g.r.y) * g.out.h / g.r.h;
  *px = g.out.x + int(std::floor(fx));
  *py = g.out.y + int(std::floor(fy));
  return fx >= 0 && fx < g.out.w && fy >= 0 && fy < g.out.h;
}

Rect Shows(int w, int h, const ScreenOptions &o, int pw, int ph, int *ow, int *oh) {
  *ow = *oh = 0;
  if (w <= 1 || h <= 1 || pw <= 0 || ph <= 0) return {};
  Geo g = Geometry(w, h, o, pw, ph);
  if (g.r.w <= 0 || g.r.h <= 0) return {};
  *ow = g.out.w;
  *oh = g.out.h;
  // The shown part's corners back on the (uncut, unturned) screen.
  auto back = [&](double rx, double ry, double *x, double *y) {
    switch (g.rot) {
    case 90: *x = ry, *y = g.h - 1 - rx; break;
    case 180: *x = g.w - 1 - rx, *y = g.h - 1 - ry; break;
    case 270: *x = g.w - 1 - ry, *y = rx; break;
    default: *x = rx, *y = ry;
    }
    *y += g.top;
  };
  double ax, ay, bx, by;
  back(g.r.x, g.r.y, &ax, &ay);
  back(g.r.x + g.r.w - 1, g.r.y + g.r.h - 1, &bx, &by);
  auto fx = [&](double v) { return std::clamp(int(std::lround(v * 10000 / (w - 1))), 0, 10000); };
  auto fy = [&](double v) { return std::clamp(int(std::lround(v * 10000 / (h - 1))), 0, 10000); };
  int x0 = fx(std::min(ax, bx)), x1 = fx(std::max(ax, bx)), y0 = fy(std::min(ay, by)), y1 = fy(std::max(ay, by));
  return {x0, y0, x1 - x0, y1 - y0};
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
  // The bars cut away (after the cursor, which is placed on the whole screen).
  int top = h * std::clamp(o.crop_top, 0, 400) / 1000, bottom = h * std::clamp(o.crop_bottom, 0, 400) / 1000;
  if (top + bottom > 0 && h - top - bottom >= 16) {
    luma->erase(luma->begin() + ptrdiff_t(size_t(h - bottom) * size_t(w)), luma->end());
    luma->erase(luma->begin(), luma->begin() + ptrdiff_t(size_t(top) * size_t(w)));
    h -= top + bottom;
  }
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
  if (o.levels == 2 && o.clean && !o.dither && n == 1) Despeckle(out, pw, c);
}

void Despeckle(std::vector<uint8_t> *p, int pw, Rect c) {
  if (c.w < 3 || c.h < 3) return;
  std::vector<uint8_t> in(*p);
  auto at = [&](int x, int y) {
    return x >= c.x && x < c.x + c.w && y >= c.y && y < c.y + c.h && in[size_t(y) * size_t(pw) + size_t(x)] != 0;
  };
  for (int y = c.y; y < c.y + c.h; y++)
    for (int x = c.x; x < c.x + c.w; x++) {
      uint8_t &v = (*p)[size_t(y) * size_t(pw) + size_t(x)];
      if (v) {
        bool alone = true;
        for (int dy = -1; dy <= 1 && alone; dy++)
          for (int dx = -1; dx <= 1; dx++)
            if ((dx || dy) && at(x + dx, y + dy)) {
              alone = false;
              break;
            }
        if (alone) v = 0;
      } else if (at(x - 1, y) && at(x + 1, y) && at(x, y - 1) && at(x, y + 1)) {
        v = 255;
      }
    }
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

// frame_ at detail n. Black and white, steady: a pixel keeps what it showed unless it is clearly past the threshold
// (16 levels either way), against the last frame sent at the same detail.
void ScreenMirror::Make(const ScreenOptions &o, Rect c, int n) {
  Finish(base_, w_, c, o, n, &frame_);
  if (o.levels != 2 || !o.steady || o.dither || video_.redraw || prev_.size() != frame_.size() || prev_detail_ != n) return;
  ScreenOptions lo = o, hi = o;
  lo.threshold = std::max(1, o.threshold - 16);
  hi.threshold = std::min(254, o.threshold + 16);
  Finish(base_, w_, c, lo, n, &lo_); // lights from here: stays lit
  Finish(base_, w_, c, hi, n, &hi_); // and from here: comes on
  for (size_t i = 0; i < frame_.size(); i++) frame_[i] = prev_[i] ? lo_[i] : hi_[i];
}

void ScreenMirror::Panels(bool on) {
  for (g1::Arm *a : {&g1::left, &g1::right})
    if (a->GetState().connected) a->SetPanel(on);
}

bool ScreenMirror::Start(int dx, int dy, std::string *error, bool keep_panels) {
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
  int64_t t0 = g1::NowUs();
  sk::gray::Clear();
  sk::Flush();
  if (keep_panels) {
    BlackSides();
  } else {
    Panels(false);
    Panels(true);
  }
  start_us_ = g1::NowUs();
  first_logged_ = false;
  LOG("screen: panels cleared in %lld ms", (long long)((start_us_ - t0) / 1000));
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
  ptr_redo_us_ = 0;
  ptr_dirty_ = true;
  started_ = true;
  LOG("screen: picture %d x %d at (%d, %d)", w_, h_, x_, y_);
  return true;
}

// The columns outside the picture's place (one eye sees them: the terminal's battery band may be there) go black:
// a zeroed 4-bpp block drawn down them a band at a time.
void ScreenMirror::BlackSides() {
  Fx::Region r = Region();
  int widest = 0;
  for (int i = 0; i < 2; i++) widest = std::max({widest, r.at[size_t(i)][0], 640 - r.at[size_t(i)][0] - w_});
  if (widest <= 0) return;
  uint32_t stride = uint32_t((widest + 1) / 2);
  int rows = std::clamp(int(6144 / stride), 1, 480);
  int64_t at = g1::Alloc(stride * uint32_t(rows));
  if (at < 0) {
    Panels(false);
    Panels(true);
    return;
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (!st.present || !st.connected) continue;
    arm.WriteArena(g1::Address(at), std::vector<uint8_t>(size_t(stride) * size_t(rows), 0));
    int x = r.at[size_t(i)][0];
    for (int y = 0; y < 480; y += rows) {
      uint32_t h = uint32_t(std::min(rows, 480 - y));
      if (x > 0) arm.Draw(g1::Address(at), stride, 0, 0, 0, y, uint32_t(x), h);
      if (x + w_ < 640) arm.Draw(g1::Address(at), stride, 0, 0, x + w_, y, uint32_t(640 - x - w_), h);
    }
  }
  g1::Free(g1::Address(at)); // the DRAWs go before anything written there later
}

// Frees the arena the path used; the panels are cleared (VIDEO draws past the gray display's picture), unless a
// transition covers the picture (keep_panels).
void ScreenMirror::DropPath(bool keep_panels) {
  if (levels_ == 16 && pic_) {
    sk::gray::Unload(*pic_);
    pic_ = nullptr;
    sk::gray::Clear();
    sk::Flush();
  }
  if (levels_ == 2) {
    if (video_.base >= 0) g1::Free(g1::Address(video_.base));
    video_ = Video{};
    if (!keep_panels) {
      Panels(false);
      Panels(true);
    }
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
      Spare();
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
  uint32_t size = kCode + v.stride * uint32_t(h_) + g1::kVideoState + 8 + kHeader + kRecordMax + 4 +
                  4 * kPtrWords + 2 * 2 * kArrowH + kArrowW / 2 * kArrowH;
  v.base = g1::Alloc(size);
  if (v.base < 0) {
    Spare();
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
  v.ptr_state = (v.buf + kHeader + kRecordMax + 3) & ~3u;
  v.ptr_masks = v.ptr_state + 4 * kPtrWords;
  v.ptr_scr = v.ptr_masks + 2 * 2 * kArrowH;
  v.ptr_shown = -1;
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    int px = i == 0 ? x_ - std::max(0, -dx_) : x_ - std::max(0, dx_);
    int py = i == 0 ? y_ + std::max(0, -dy_) : y_ + std::max(0, dy_);
    g1::Program p(v.code, 48);
    auto C = [&](int32_t x) { return p.Const(uint32_t(x)); };
    auto W = [&](int k) { return g1::ArenaWord(v.ptr_state / 4 + uint32_t(k)); };
    // The cursor's words: where the host wants it (X, Y: the arrow's box on the picture; On), where it is drawn
    // (DX, DY; Drawn), the box being drawn (HX, HY; Arrow: with the arrow, else the picture alone), working words.
    auto X = W(0), Y = W(1), On = W(2), DX = W(3), DY = W(4), Drawn = W(5), HX = W(6), HY = W(7), Arrow = W(8),
         x0 = W(9), x1 = W(10), y0 = W(11), y1 = W(12), t = W(13), t2 = W(14);
    auto over = p.NewLabel();
    g1::Address at = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.Video(p.Const(v.bitmap), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(uint32_t(px)),
            p.Const(uint32_t(py)), p.Const(15), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2), p.Const(v.state));
    p.Call(over); // the frame's changed rows went over the arrow
    p.Ret();
    // The same with the picture off the panel: only the bitmap changes (a transition reveals it).
    g1::Address hidden = p.Here();
    p.SleepUntil(g1::ArenaWord(v.buf / 4));
    p.Video(p.Const(v.bitmap), p.Const(v.stride), p.Const(uint32_t(w_)), p.Const(uint32_t(h_)), p.Const(640),
            p.Const(uint32_t(py)), p.Const(15), p.Const(v.buf + kHeader), g1::ArenaWord(v.buf / 4 + 2), p.Const(v.state));
    p.Ret();
    // box: the picture under the box (HX, HY) from the bitmap into the scratch, the arrow over it (Arrow), and the
    // part inside the picture to the panel: one small DRAW, so the cursor moves at once and costs a few bytes.
    auto box = p.NewLabel(), l1 = p.NewLabel(), l2 = p.NewLabel(), l3 = p.NewLabel(), l4 = p.NewLabel(),
         plain = p.NewLabel(), bret = p.NewLabel();
    p.Bind(box);
    p.Fill(p.Const(v.ptr_scr), C(kArrowW / 2), C(0), C(0), C(kArrowW), C(kArrowH), C(0));
    p.Mov(x0, HX);
    p.BgeS(x0, C(0), l1);
    p.Mov(x0, C(0));
    p.Bind(l1);
    p.Add(x1, HX, C(kArrowW));
    p.BltS(x1, C(w_), l2);
    p.Mov(x1, C(w_));
    p.Bind(l2);
    p.Mov(y0, HY);
    p.BgeS(y0, C(0), l3);
    p.Mov(y0, C(0));
    p.Bind(l3);
    p.Add(y1, HY, C(kArrowH));
    p.BltS(y1, C(h_), l4);
    p.Mov(y1, C(h_));
    p.Bind(l4);
    p.BgeS(x0, x1, bret);
    p.BgeS(y0, y1, bret);
    p.Sub(x1, x1, x0); // the columns
    p.Sub(y1, y1, y0); // the rows
    p.Sub(t, x0, HX);  // in the scratch
    p.Sub(t2, y0, HY);
    p.Blit(p.Const(v.bitmap), p.Const(v.stride), x0, y0, p.Const(v.ptr_scr), C(kArrowW / 2), t, t2, x1, y1,
           C(4 | g1::MODE_COPY), C(15));
    p.Beq(Arrow, C(0), plain);
    p.Blit(p.Const(v.ptr_masks + 2 * kArrowH), C(2), C(0), C(0), p.Const(v.ptr_scr), C(kArrowW / 2), C(0), C(0),
           C(kArrowW), C(kArrowH), C(4 | g1::MODE_ERASE), C(15));
    p.Blit(p.Const(v.ptr_masks), C(2), C(0), C(0), p.Const(v.ptr_scr), C(kArrowW / 2), C(0), C(0), C(kArrowW),
           C(kArrowH), C(4 | g1::MODE_MASK), C(15));
    p.Bind(plain);
    p.Add(x0, x0, C(px));
    p.Add(y0, y0, C(py));
    p.Draw(p.Const(v.ptr_scr), C(kArrowW / 2), t, t2, x0, y0, x1, y1);
    p.Bind(bret);
    p.Ret();
    // move (the host's call after writing X, Y, On): the picture back where the arrow was, the arrow where asked.
    auto fresh = p.NewLabel(), mret = p.NewLabel(), oret = p.NewLabel();
    g1::Address move = p.Here();
    p.Beq(Drawn, C(0), fresh);
    p.Mov(HX, DX);
    p.Mov(HY, DY);
    p.Mov(Arrow, C(0));
    p.Call(box);
    p.Bind(fresh);
    p.Mov(Drawn, C(0));
    p.Beq(On, C(0), mret);
    p.Mov(DX, X);
    p.Mov(DY, Y);
    p.Mov(HX, X);
    p.Mov(HY, Y);
    p.Mov(Arrow, C(1));
    p.Call(box);
    p.Mov(Drawn, C(1));
    p.Bind(mret);
    p.Ret();
    // over: the arrow again where it is drawn.
    p.Bind(over);
    p.Beq(Drawn, C(0), oret);
    p.Mov(HX, DX);
    p.Mov(HY, DY);
    p.Mov(Arrow, C(1));
    p.Call(box);
    p.Bind(oret);
    p.Ret();
    p.Changes(v.ptr_state + 12, 4 * (kPtrWords - 3));
    p.Changes(v.ptr_scr, kArrowW / 2 * kArrowH);
    std::string why;
    if (!p.Valid(&why)) {
      error_ = "the VIDEO code: " + why;
      return false;
    }
    if (p.Here() > v.code + kCode) {
      error_ = "the VIDEO code is larger than its room";
      return false;
    }
    arm.WriteArena(v.bitmap, std::vector<uint8_t>(size_t(v.stride) * h_, 0));
    std::vector<uint8_t> ptr(4 * kPtrWords, 0);
    std::vector<uint8_t> masks = ArrowMasks();
    ptr.insert(ptr.end(), masks.begin(), masks.end());
    arm.WriteArena(v.ptr_state, ptr);
    if (!arm.Load(p)) {
      error_ = "the VIDEO code did not load (a firmware before VIDEO?)";
      return false;
    }
    v.call[size_t(i)] = at;
    v.hidden[size_t(i)] = hidden;
    v.ptr_call[size_t(i)] = move;
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
  // A transition asked for: this one decoded off the panel, then revealed from the bitmap.
  bool reveal = reveal_style_ && fx_ && g1::NowUs() < reveal_until_;
  std::vector<uint8_t> msg(kHeader + rec.size());
  Put32(msg, 4, v.k);
  Put32(msg, 8, uint32_t(rec.size()));
  std::memcpy(msg.data() + kHeader, rec.data(), rec.size());
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present) continue;
    Put32(msg, 0, arm.DeviceTime(lead));
    arm.WriteArena(v.buf, msg);
    arm.Call(reveal ? v.hidden[size_t(i)] : v.call[size_t(i)]);
  }
  if (reveal) {
    // The decode takes up to about 40 ms for a whole picture (VM.md §3.7).
    fx_->In(Fx::Style(reveal_style_), Region(), Fx::Source{v.bitmap, v.stride, 1}, g1::NowUs() + lead + 40000);
    LOG("screen: revealed (%d)", reveal_style_);
    // The transition draws the picture without the arrow: it comes back once that is done.
    ptr_redo_us_ = std::max<int64_t>(fx_->busy_until(), g1::NowUs() + lead + 40000) + 20000;
  }
  reveal_style_ = 0;
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

void ScreenMirror::Reveal(int style, std::shared_ptr<Fx> fx) {
  std::lock_guard<std::mutex> lock(mutex_);
  wiped_until_ = 0;
  reveal_style_ = style;
  fx_ = std::move(fx);
  reveal_until_ = g1::NowUs() + 4000000;
}

void ScreenMirror::Wiped() {
  std::lock_guard<std::mutex> lock(mutex_);
  // No frame over the transition until its reveal (or another path takes over); 1.5 s at most.
  wiped_until_ = g1::NowUs() + 1500000;
  prev_.clear();
  last_base_.clear();
  video_.redraw = true;
  if (levels_ == 16 && pic_) { // the library's picture of the display: black, so the next frame goes whole
    sk::gray::Clear();
    sk::Flush();
  }
}

Fx::Region ScreenMirror::FxRegion() {
  std::lock_guard<std::mutex> lock(mutex_);
  return Region();
}

void ScreenMirror::Pointer(int x, int y, bool on) {
  std::lock_guard<std::mutex> lock(mutex_);
  x = std::clamp(x, 0, 10000);
  y = std::clamp(y, 0, 10000);
  if (x == ptr_cx_ && y == ptr_cy_ && on == ptr_on_ && !ptr_dirty_ && !ptr_redo_us_) return;
  ptr_cx_ = x;
  ptr_cy_ = y;
  ptr_on_ = on;
  ptr_dirty_ = true;
  SendPointer(false);
}

void ScreenMirror::SetOverlay(std::vector<uint8_t> pixels, int w, int h, int x, int y) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (w <= 0 || h <= 0 || pixels.size() < size_t(w) * size_t(h)) pixels.clear(), w = h = 0;
  over_ = std::move(pixels);
  over_w_ = w;
  over_h_ = h;
  over_x_ = x;
  over_y_ = y;
}

// mutex_ held.
void ScreenMirror::Compose() {
  if (over_.empty() || frame_.size() != size_t(w_) * size_t(h_)) return;
  int x0 = std::max(0, over_x_), x1 = std::min(w_, over_x_ + over_w_);
  int y0 = std::max(0, over_y_), y1 = std::min(h_, over_y_ + over_h_);
  for (int y = y0; y < y1; y++)
    for (int x = x0; x < x1; x++)
      frame_[size_t(y) * size_t(w_) + size_t(x)] = over_[size_t(y - over_y_) * size_t(over_w_) + size_t(x - over_x_)];
}

bool ScreenMirror::PointerPending() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (ptr_dirty_ || (ptr_redo_us_ && g1::NowUs() >= ptr_redo_us_)) SendPointer(false);
  return started_ && (ptr_dirty_ || ptr_redo_us_);
}

bool ScreenMirror::Shows(int out[6]) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || src_w_ <= 1 || src_h_ <= 1) return false;
  int ow = 0, oh = 0;
  Rect r = ssh::Shows(src_w_, src_h_, src_o_, w_, h_, &ow, &oh);
  if (ow <= 0 || oh <= 0) return false;
  out[0] = r.x;
  out[1] = r.y;
  out[2] = r.x + r.w;
  out[3] = r.y + r.h;
  out[4] = ow;
  out[5] = oh;
  return true;
}

// The cursor to both arms: their code puts the picture back where the arrow was and draws it at its new place
// (mutex_ held). Only on the black and white path with the panels on; while a link is busy it waits (dirty) for the
// next call, so a quick stroke sends its latest place, not every step. force: drawn again even where it is.
void ScreenMirror::SendPointer(bool force) {
  Video &v = video_;
  int64_t now = g1::NowUs();
  if (ptr_redo_us_ && now >= ptr_redo_us_) {
    ptr_redo_us_ = 0;
    force = true;
  }
  if (!started_ || levels_ != 2 || !v.ptr_state || hidden_ || paused_) return; // greys: in the next frame
  if (ptr_redo_us_ || v.redraw) return; // a transition draws the picture meanwhile, or the panels lost it
  int x = 0, y = 0;
  bool on = ptr_on_ && src_w_ > 1 && Locate(ptr_cx_, ptr_cy_, src_w_, src_h_, src_o_, w_, h_, &x, &y);
  x -= 1; // the arrow's tip is (1, 1) of its box
  y -= 1;
  if (!force && v.ptr_shown == int(on) && (!on || (x == v.ptr_x && y == v.ptr_y))) {
    ptr_dirty_ = false;
    return;
  }
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    auto st = arm.GetState();
    if (st.present && (!st.connected || st.queued > 3)) return; // later (still dirty)
  }
  std::vector<uint8_t> words(12);
  Put32(words, 0, uint32_t(x));
  Put32(words, 4, uint32_t(y));
  Put32(words, 8, on ? 1u : 0u);
  for (int i = 0; i < 2; i++) {
    g1::Arm &arm = i == 0 ? g1::left : g1::right;
    if (!arm.GetState().present || !v.ptr_call[size_t(i)]) continue;
    arm.WriteArena(v.ptr_state, words);
    arm.Call(v.ptr_call[size_t(i)]);
  }
  v.ptr_x = x;
  v.ptr_y = y;
  v.ptr_shown = on ? 1 : 0;
  ptr_dirty_ = false;
}

// mutex_ held.
Fx::Region ScreenMirror::Region() const {
  Fx::Region r;
  r.w = w_;
  r.h = h_;
  r.at[0] = {x_ - std::max(0, -dx_), y_ + std::max(0, -dy_)};
  r.at[1] = {x_ - std::max(0, dx_), y_ + std::max(0, dy_)};
  return r;
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

void ScreenMirror::Stop(bool keep_panels) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  started_ = false;
  DropPath(keep_panels);
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

// Heads-up on a screen: the panels go this many degrees below the angle (after kLookHoldUs), as the terminal's.
constexpr int kLookDown = 5;
constexpr int64_t kLookHoldUs = 1000000;

bool ScreenMirror::Hidden(const ScreenOptions &o) {
  int64_t now = g1::NowUs();
  if (props_us_ && now - props_us_ < (o.look_up ? 200000 : 1000000)) return props_hidden_;
  props_us_ = now;
  bool in_case = false, known = false, folded = true, up = false, pitched = false;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    g1::Arm::State s = a->GetState();
    if (!s.connected) continue;
    const uint8_t ids[3] = {g1::PROP_IN_CASE, g1::PROP_WORN, g1::PROP_PITCH};
    uint32_t v[3] = {g1::UNKNOWN, g1::UNKNOWN, g1::UNKNOWN};
    bool got = a->Props(ids, v);
    if (got && o.look_up && v[2] != g1::UNKNOWN) { // either eye up is up
      pitched = true;
      int pitch = int32_t(v[2]);
      if (pitch >= o.angle || (head_up_ && pitch >= o.angle - kLookDown)) up = true;
    }
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
  // Heads-up: up shows at once; down hides after the hold (a glance down at the keys keeps it a moment).
  bool by_head = false;
  if (!o.look_up || !pitched || o.hold || up) {
    head_up_ = true;
    head_down_us_ = 0;
  } else if (head_up_) {
    if (!head_down_us_) head_down_us_ = now;
    if (now - head_down_us_ >= kLookHoldUs) head_up_ = false;
  }
  by_head = o.look_up && !head_up_;
  bool by_case = o.hide_case && in_case, by_fold = o.hide_folded && known && folded;
  hide_why_ = by_case ? "in the case" : by_fold ? "folded" : by_head ? "heads-up: head down" : "";
  props_hidden_ = by_case || by_fold || by_head;
  return props_hidden_;
}

bool ScreenMirror::ApplyHidden(bool hide) {
  if (hide == hidden_) return false;
  hidden_ = hide;
  if (hide) {
    LOG("screen: panels off (%s)", hide_why_.c_str());
    sk::gray::Clear();
    sk::Flush();
    Panels(false);
    return false;
  }
  LOG("screen: panels on again");
  Panels(true);
  prev_.clear();
  video_.redraw = true; // the power-on cleared the panels: every row again
  return true;
}

int ScreenMirror::Look(const ScreenOptions &o) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || paused_) return 0;
  return ApplyHidden(Hidden(o)) ? 1 : 0;
}

int ScreenMirror::Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return kStopped;
  if (paused_) return kBusy;
  if (wiped_until_ && g1::NowUs() < wiped_until_) return kBusy;
  wiped_until_ = 0;
  if (w <= 0 || h <= 0 || stride < w * 4) return kSame;
  if (seen_++ == 0) LOG("screen: first frame, %d x %d", w, h);
  bool hide = Hidden(o);
  ApplyHidden(hide);
  if (hide) return kHidden;
  if (!Path(o.levels)) return kSame;
  // Where the cursor lands follows the screen's size and the options that place it.
  if (w != src_w_ || h != src_h_ || o.fit != src_o_.fit || o.pan_x != src_o_.pan_x || o.pan_y != src_o_.pan_y ||
      o.zoom != src_o_.zoom || o.rotate != src_o_.rotate || o.out_x != src_o_.out_x || o.out_y != src_o_.out_y ||
      o.out_w != src_o_.out_w || o.out_h != src_o_.out_h || o.crop_top != src_o_.crop_top ||
      o.crop_bottom != src_o_.crop_bottom)
    ptr_dirty_ = true;
  src_w_ = w;
  src_h_ = h;
  src_o_ = o;
  // The cursor: the arms draw it themselves on the black and white path; greys get it in the picture.
  ScreenOptions oc = o;
  bool drawn_in = levels_ != 2 && ptr_on_;
  oc.cursor_x = drawn_in ? ptr_cx_ : -1;
  oc.cursor_y = drawn_in ? ptr_cy_ : -1;
  Rect c = Base(rgba, w, h, stride, oc, w_, h_, &luma_, &rot_, &base_);
  bool still = base_ == last_base_; // the same screen as last time: nothing moved
  if (!still) last_base_ = base_;

  if (levels_ == 2) {
    int ready = VideoReady();
    if (ready != kShown) return ready;
    int r = FrameVideo(o, c, still);
    if ((r == kShown || r == kLater) && !first_logged_) {
      first_logged_ = true;
      LOG("screen: first picture sent %lld ms after the start (%d bytes)", (long long)((g1::NowUs() - start_us_) / 1000),
          video_.sent);
    }
    // The cursor after the picture it goes over (the first one, a redraw).
    if (r != kBusy && (ptr_dirty_ || video_.ptr_shown < 0 || (ptr_redo_us_ && g1::NowUs() >= ptr_redo_us_)))
      SendPointer(false);
    return r;
  }
  return FrameGray(o, c);
}

// A frame on the black and white path, the link ready (mutex_ held).
int ScreenMirror::FrameVideo(const ScreenOptions &o, Rect c, bool still) {
  bool redraw = video_.redraw;
  if (o.detail >= 1) {
    int n = std::clamp(o.detail, 1, 4);
    Make(o, c, n);
    Compose();
    if (frame_ == prev_ && !redraw) return kSame;
    if (!SendVideo(kRecordMax)) return kBusy; // too large for one record: the next, smaller change goes
    prev_ = frame_;
    prev_detail_ = n;
    Count(video_.sent, n);
    return kShown;
  }
  // Auto: every pixel when that fits the budget, else coarser; nothing moved: every pixel whatever the size.
  size_t budget = still ? kRecordMax : size_t(std::clamp(o.budget, 1, 64)) * 1024;
  for (int n = 1; n <= 4; n++) {
    Make(o, c, n);
    Compose();
    if (frame_ == prev_ && !redraw) return n == 1 ? kSame : kLater;
    if (SendVideo(n == 4 ? kRecordMax : budget)) {
      prev_ = frame_;
      prev_detail_ = n;
      Count(video_.sent, n);
      return n == 1 ? kShown : kLater;
    }
  }
  return kBusy;
}

// A frame of greys (mutex_ held): the library uploads the changed pixels (4 bits each); a transition's reveal is
// left out.
int ScreenMirror::FrameGray(const ScreenOptions &o, Rect c) {
  reveal_style_ = 0;
  int n = std::clamp(o.detail, 1, 4);
  Finish(base_, w_, c, o, n, &frame_);
  if (o.steady && o.levels != 4 && o.levels != 8 && prev_.size() == frame_.size())
    for (size_t i = 0; i < frame_.size(); i++)
      if (std::abs(int(frame_[i]) - int(prev_[i])) <= 17) frame_[i] = prev_[i];
  Compose();
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
