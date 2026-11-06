#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "base/scouter_kit.hpp"
#include "g1/scouter_kit_g1.hpp"

namespace ssh {

// How the phone's screen goes on the glasses.
struct ScreenOptions {
  // false: the whole screen, its shape kept (a portrait phone is a narrow column); true: a band as wide as the
  // screen and as tall as the glasses' shape allows, filling the picture.
  bool band = false;
  int band_pos = 0;     // the band's place: 0 the top of the screen ... 100 the bottom
  bool invert = true;   // light backgrounds go dark: black is see-through on the glasses
  int levels = 16;      // 16 greys, or 2 (sharper, lighter on the link)
  bool hide_case = true;    // panels off while an arm is in the case (a lit panel disturbs charging)
  bool hide_folded = false; // panels off while both arms say "not worn" (WORN is a guess from the arms' radio)
};

// RGBA_8888 rows (stride bytes apart) to 0..255 luma, w x h bytes.
void Luma(const uint8_t *rgba, int w, int h, int stride, std::vector<uint8_t> *out);

// The source rectangle (x, y, w, h) of an sw x sh screen that the options show in a pw x ph picture.
struct Rect {
  int x = 0, y = 0, w = 0, h = 0;
};
Rect Source(int sw, int sh, int pw, int ph, const ScreenOptions &);

// Scales the src rectangle of a (stride sw) luma image into dst (pw x ph), averaging the source pixels under
// each picture pixel. keep: the shape is kept, centred, with bg around it.
void Fit(std::vector<uint8_t> *dst, int pw, int ph, const std::vector<uint8_t> &src, int sw, Rect r, bool keep,
         uint8_t bg);

// Inverts (if asked) and quantizes to the glasses' levels: multiples of 17, which the library writes as they
// are (no dithering noise to send).
void Tone(std::vector<uint8_t> *p, bool invert, int levels);

// The phone's screen on both eyes, in the columns both eyes see (the display's x from dx), all rows. Uses the
// library's gray display: nothing else may draw meanwhile (the terminal is stopped first).
class ScreenMirror {
public:
  // After g1::Init. Clears both panels.
  bool Start(int dx, std::string *error);
  // Clears the display; the arms keep nothing.
  void Stop();
  // One captured frame (RGBA_8888); blocks while the previous one is not on the display yet.
  // Returns kShown, kSame (nothing changed), kHidden (in the case or folded: the panels are off) or kStopped.
  int Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &);
  int frames();
  // Why the panels are off, or empty: "in the case" or "folded".
  std::string why();

  static constexpr int kShown = 0, kSame = 1, kHidden = 2, kStopped = 3;

private:
  bool Hidden(const ScreenOptions &); // reads the arms' sensors at most once a second (mutex_ held)
  void Panels(bool on);

  std::mutex mutex_;
  bool started_ = false;
  int x_ = 0, y_ = 0, w_ = 0, h_ = 0;
  scouter_kit::gray::Bitmap *pic_ = nullptr;
  std::vector<uint8_t> luma_, frame_, prev_;
  bool hidden_ = false;
  int64_t props_us_ = 0;
  bool props_hidden_ = false;
  std::string why_;
  int frames_ = 0;
};

} // namespace ssh
