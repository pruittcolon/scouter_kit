#include "screen.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>

namespace g1 = scouter_kit::g1;
namespace sk = scouter_kit;

namespace ssh {

void Luma(const uint8_t *rgba, int w, int h, int stride, std::vector<uint8_t> *out) {
  out->resize(size_t(w) * h);
  uint8_t *o = out->data();
  for (int y = 0; y < h; y++) {
    const uint8_t *p = rgba + size_t(y) * stride;
    for (int x = 0; x < w; x++, p += 4) *o++ = uint8_t((p[0] * 77 + p[1] * 150 + p[2] * 29) >> 8);
  }
}

Rect Source(int sw, int sh, int pw, int ph, const ScreenOptions &o) {
  if (!o.band || pw <= 0 || ph <= 0) return {0, 0, sw, sh};
  // The full width, as tall as the picture's shape allows; a screen wider than that shape (landscape) gives
  // its full height and the middle of its width.
  int bh = int(int64_t(sw) * ph / pw);
  if (bh <= sh) {
    int top = int(int64_t(sh - bh) * std::clamp(o.band_pos, 0, 100) / 100);
    return {0, top, sw, std::max(bh, 1)};
  }
  int bw = std::max(int(int64_t(sh) * pw / ph), 1);
  return {(sw - bw) / 2, 0, bw, sh};
}

void Fit(std::vector<uint8_t> *dst, int pw, int ph, const std::vector<uint8_t> &src, int sw, Rect r, bool keep,
         uint8_t bg) {
  dst->assign(size_t(pw) * ph, bg);
  if (r.w <= 0 || r.h <= 0) return;
  int ow = pw, oh = ph, ox = 0, oy = 0;
  if (keep) {
    double k = std::min(double(pw) / r.w, double(ph) / r.h);
    ow = std::clamp(int(r.w * k), 1, pw);
    oh = std::clamp(int(r.h * k), 1, ph);
    ox = (pw - ow) / 2;
    oy = (ph - oh) / 2;
  }
  for (int y = 0; y < oh; y++) {
    int y0 = r.y + int(int64_t(y) * r.h / oh);
    int y1 = std::max(r.y + int(int64_t(y + 1) * r.h / oh), y0 + 1);
    uint8_t *out = dst->data() + size_t(y + oy) * pw + ox;
    for (int x = 0; x < ow; x++) {
      int x0 = r.x + int(int64_t(x) * r.w / ow);
      int x1 = std::max(r.x + int(int64_t(x + 1) * r.w / ow), x0 + 1);
      int sum = 0, n = 0;
      for (int yy = y0; yy < y1; yy++) {
        const uint8_t *row = src.data() + size_t(yy) * sw;
        for (int xx = x0; xx < x1; xx++) sum += row[xx];
        n += x1 - x0;
      }
      out[x] = uint8_t(sum / n);
    }
  }
}

void Tone(std::vector<uint8_t> *p, bool invert, int levels) {
  uint8_t lut[256];
  for (int v = 0; v < 256; v++) {
    int f = invert ? 255 - v : v;
    lut[v] = levels == 2 ? (f >= 128 ? 255 : 0) : uint8_t((f * 15 + 127) / 255 * 17);
  }
  for (uint8_t &v : *p) v = lut[v];
}

void ScreenMirror::Panels(bool on) {
  for (g1::Arm *a : {&g1::left, &g1::right})
    if (a->GetState().connected) a->SetPanel(on);
}

bool ScreenMirror::Start(int dx, std::string *error) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (started_) return true;
  int dw = sk::gray::display.width, dh = sk::gray::display.height;
  if (dw <= 0 || dh <= 0) {
    if (error) *error = "the glasses are not connected";
    return false;
  }
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
  pic_ = &sk::gray::CreateBitmap(w_, h_);
  if (!pic_->contents.data) {
    pic_ = nullptr;
    if (error) *error = "no room on the arms for a " + std::to_string(w_) + " x " + std::to_string(h_) + " picture";
    return false;
  }
  prev_.clear();
  hidden_ = false;
  props_us_ = 0;
  started_ = true;
  return true;
}

void ScreenMirror::Stop() {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_) return;
  started_ = false;
  if (pic_) sk::gray::Unload(*pic_);
  pic_ = nullptr;
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
  return hidden_ ? why_ : std::string();
}

bool ScreenMirror::Hidden(const ScreenOptions &o) {
  int64_t now = g1::NowUs();
  if (props_us_ && now - props_us_ < 1000000) return props_hidden_;
  props_us_ = now;
  bool in_case = false, known = false, folded = true;
  for (g1::Arm *a : {&g1::left, &g1::right}) {
    g1::Arm::State s = a->GetState();
    if (!s.connected) continue;
    if (s.in_case) in_case = true;
    const uint8_t ids[2] = {g1::PROP_IN_CASE, g1::PROP_WORN};
    uint32_t v[2] = {g1::UNKNOWN, g1::UNKNOWN};
    if (!a->Props(ids, v)) continue;
    if (v[0] == 1) in_case = true;
    if (v[1] != g1::UNKNOWN) {
      known = true;
      folded = folded && v[1] == 0;
    }
  }
  // In the case a lit panel disturbs charging; folded nobody looks.
  bool by_case = o.hide_case && in_case, by_fold = o.hide_folded && known && folded;
  why_ = by_case ? "in the case" : by_fold ? "folded" : "";
  props_hidden_ = by_case || by_fold;
  return props_hidden_;
}

int ScreenMirror::Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &o) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!started_ || !pic_) return kStopped;
  if (w <= 0 || h <= 0 || stride < w * 4) return kSame;
  bool hide = Hidden(o);
  if (hide != hidden_) {
    hidden_ = hide;
    if (hide) {
      sk::gray::Clear();
      sk::Flush();
      Panels(false);
    } else {
      Panels(true);
      prev_.clear();
    }
  }
  if (hide) return kHidden;
  Luma(rgba, w, h, stride, &luma_);
  Fit(&frame_, w_, h_, luma_, w, Source(w, h, w_, h_, o), !o.band, o.invert ? 255 : 0);
  Tone(&frame_, o.invert, o.levels);
  if (frame_ == prev_) return kSame;
  sk::gray::Write(*pic_, sk::gray::ConstBitmapView{frame_.data(), w_, h_, w_}, 0, 0, sk::gray::DITHER_NONE);
  prev_ = frame_;
  sk::gray::Clear();
  sk::gray::Draw(*pic_, x_, y_);
  sk::Flush();
  frames_++;
  return kShown;
}

} // namespace ssh
