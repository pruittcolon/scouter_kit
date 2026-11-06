#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "base/scouter_kit.hpp"
#include "g1/scouter_kit_g1.hpp"

namespace ssh {

// How the phone's screen goes on the glasses.
struct ScreenOptions {
  static constexpr int kFit = 0, kFill = 1, kStretch = 2;
  // kFit: the screen (or its zoomed part) keeps its shape inside the picture's place; kFill: cropped to that
  // place's shape and filling it; kStretch: distorted to fill it.
  int fit = kFit;
  int pan_x = 50, pan_y = 0; // where a crop (fill, zoom) sits on the phone's screen: 0 left/top ... 100 right/bottom
  int zoom = 100;            // percent: above 100 a smaller part of the screen shows larger
  int rotate = 0;            // the phone's picture turned clockwise first: 0, 90, 180 or 270 degrees
  // The picture's place in the glasses' area, in percent of it: left, top, width, height.
  int out_x = 0, out_y = 0, out_w = 100, out_h = 100;
  int invert = 100;         // percent toward the inverse: 0 as on the phone, 100 light backgrounds dark (black is
                            // see-through on the glasses)
  int gamma = 100;          // the midtones: 100 as is, above darker, below brighter (x100)
  int threshold = 128;      // black and white: brighter than this lights
  bool steady = true;       // 16 greys: a pixel changed by one level only keeps its old level (less flicker and
                            // fewer pixels to send)
  int levels = 16;          // 16 greys, or 2 (black and white: sent as VIDEO records, much faster)
  int contrast = 0;         // 0 ... 100: how far the levels stretch toward the picture's darkest and brightest
  bool sharpen = false;     // an unsharp mask after scaling: crisper text
  bool hide_case = true;    // panels off while an arm is in the case (a lit panel disturbs charging)
  bool hide_folded = false; // panels off while both arms say "not worn" (WORN is a guess from the arms' radio)
};

// RGBA_8888 rows (stride bytes apart) to 0..255 luma, w x h bytes.
void Luma(const uint8_t *rgba, int w, int h, int stride, std::vector<uint8_t> *out);

struct Rect {
  int x = 0, y = 0, w = 0, h = 0;
};

// Turns a w x h image clockwise by 0, 90, 180 or 270 degrees into out, of *ow x *oh.
void Rotate(const std::vector<uint8_t> &in, int w, int h, int degrees, std::vector<uint8_t> *out, int *ow, int *oh);

// The picture's place (out_*) in a pw x ph area.
Rect Place(int pw, int ph, const ScreenOptions &);

// The part of an sw x sh screen that shows in a dw x dh place: all of it, or (kFill) a crop of the place's
// shape; zoom narrows it, pan places it.
Rect Source(int sw, int sh, int dw, int dh, const ScreenOptions &);

// Scales the src rectangle r of a (stride sw) luma image into the place d of dst (pw x ph, black around it),
// averaging the source pixels under each picture pixel. keep: the shape is kept, centred in d. Returns where
// the picture went (the content: what Stretch, Sharpen and Tone change).
Rect Fit(std::vector<uint8_t> *dst, int pw, int ph, const std::vector<uint8_t> &src, int sw, Rect r, Rect d, bool keep);

// Stretches the levels inside c (of a pw-wide image) toward its 2nd and 98th percentiles; amount 0 ... 100.
void Stretch(std::vector<uint8_t> *p, int pw, Rect c, int amount);

// An unsharp mask (3 x 3 box, strength 1) inside c: edges, such as text, come out crisper.
void Sharpen(std::vector<uint8_t> *p, int pw, Rect c);

// Inside c: toward the inverse by `invert` percent, the midtones by gamma (x100), then the glasses' levels:
// multiples of 17, which the library writes as they are (no dithering noise to send); 2 levels: 255 from
// threshold up, else 0. Outside c stays black (see-through).
void Tone(std::vector<uint8_t> *p, int pw, Rect c, int invert, int gamma, int threshold, int levels);

// The phone's screen on both eyes, in the columns both eyes see (the display's x from dx), all rows. 16 greys
// go through the library's gray display (a bitmap, changed pixels uploaded); 2 levels as VIDEO records (VM.md
// §3.7) into a 1-bpp bitmap on each arm, which both draw at the same due time: far less to send. Nothing else
// may draw meanwhile (the terminal is stopped first).
class ScreenMirror {
public:
  ~ScreenMirror();
  // After g1::Init. Clears both panels.
  bool Start(int dx, int dy, std::string *error);
  // Clears the display; the arms keep nothing.
  void Stop();
  // One captured frame (RGBA_8888). 16 greys: blocks while the previous frame is not on the display yet;
  // 2 levels: skipped (kBusy) while the link still carries earlier ones.
  int Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &);
  int frames();
  // Why the panels are off, or empty: "in the case" or "folded"; or the last error.
  std::string why();

  static constexpr int kShown = 0, kSame = 1, kHidden = 2, kStopped = 3, kBusy = 4;

private:
  bool Hidden(const ScreenOptions &); // reads the arms' sensors at most once a second (mutex_ held)
  void Panels(bool on);
  bool Path(int levels);              // the drawing path for these levels (mutex_ held)
  void DropPath();
  bool StartVideo();
  int SendVideo();

  std::mutex mutex_;
  bool started_ = false;
  int dx_ = 0, dy_ = 0;
  int x_ = 0, y_ = 0, w_ = 0, h_ = 0;
  int levels_ = 0; // the path in use: 16 gray, 2 video, 0 none
  scouter_kit::gray::Bitmap *pic_ = nullptr;
  std::vector<uint8_t> luma_, rot_, frame_, prev_;
  bool hidden_ = false;
  int64_t props_us_ = 0;
  bool props_hidden_ = false;
  std::string hide_why_, error_;
  int frames_ = 0;
  // The VIDEO path: its arena block (code, 1-bpp bitmap, decoder state, the record's buffer), each arm's
  // entry point, the encoder, the frame number.
  struct Video {
    int64_t base = -1;
    uint32_t code = 0, bitmap = 0, state = 0, buf = 0, stride = 0, size = 0;
    std::array<uint32_t, 2> call{}, epochs{};
    std::unique_ptr<scouter_kit::g1::VideoEncoder> enc;
    std::vector<uint8_t> pix;
    uint32_t k = 0;
    bool redraw = true; // the panels lost their image: every row again
  } video_;
};

} // namespace ssh
