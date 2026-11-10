#pragma once

#include <array>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "base/scouter_kit.hpp"
#include "fx.hpp"
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
  bool steady = true;       // 16 greys: a pixel changed by one level only keeps its old level; black and white: a
                            // pixel changes only once it is clearly past the threshold (less flicker, less to send)
  bool dither = false;      // black and white: an ordered (4 x 4 Bayer) dither shows greys as patterns (photos)
  int levels = 16;          // 16, 8 or 4 greys (the gray display), or 2 (black and white: VIDEO records, fastest)
  // Less to send: the picture in blocks of detail x detail pixels (1 every pixel ... 4). 0, auto (black and
  // white): every pixel when the frame's record fits `budget` KB, else coarser until it does; the same screen
  // again (nothing moved) goes at every pixel whatever its size.
  int detail = 1;
  int budget = 6;
  int contrast = 0;         // 0 ... 100: how far the levels stretch toward the picture's darkest and brightest
  bool sharpen = false;     // an unsharp mask after scaling: crisper text
  bool hide_case = true;    // panels off while an arm is in the case (a lit panel disturbs charging)
  bool hide_folded = false; // panels off while both arms say "not worn" (WORN is a guess from the arms' radio)
  // A pointer on the phone's screen (the glasses' cursor for it), where it sits in 1/10000 of the screen's width
  // and height; -1: none. Drawn on the phone's picture first, so it follows rotate, zoom and pan.
  int cursor_x = -1, cursor_y = -1;
  bool clean = true;       // black and white at every pixel: lone specks go, pinholes fill (cleaner, less to send)
  // Rows cut from the screen's top and bottom first (status and navigation bars), in 1/1000 of its height.
  int crop_top = 0, crop_bottom = 0;
  // Heads-up: the panels on only while the head is up, as the terminal's looking-up rule (up at `angle` degrees,
  // down again kLookDown lower after a hold); `hold` keeps them on meanwhile (a panel open over the picture).
  bool look_up = false;
  int angle = 20;
  bool hold = false;
};

// What the mirror may take back when the arms' arena runs short (the terminal's kept fonts): called, with no lock
// of the mirror's held, before it unloads every font and picture as a last resort.
void SetSpare(std::function<void()> release);

// The pointer (o.cursor_*) drawn on a w x h luma picture that becomes a pw x ph one on the glasses.
void Cursor(std::vector<uint8_t> *luma, int w, int h, const ScreenOptions &o, int pw, int ph);

// The cursor's arrow for the arms (16 x 20): its body, then its edge (the body grown by a pixel), each 1 bpp,
// 2 bytes a row; its tip at (1, 1).
std::vector<uint8_t> ArrowMasks();
constexpr int kArrowW = 16, kArrowH = 20;

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

// Where the point (cx, cy) of a w x h screen (1/10000 of its width and height) lands on the pw x ph picture
// (Base's geometry: the bars cut, turned, placed, scaled); false when it is outside what shows.
bool Locate(int cx, int cy, int w, int h, const ScreenOptions &, int pw, int ph, int *px, int *py);

// The part of a w x h screen that shows on the pw x ph picture, in 1/10000 of the screen (x, y, w, h: left, top,
// width, height), and the size it takes there (*ow x *oh pixels).
Rect Shows(int w, int h, const ScreenOptions &, int pw, int ph, int *ow, int *oh);

// Scales the src rectangle r of a (stride sw) luma image into the place d of dst (pw x ph, black around it),
// averaging the source pixels under each picture pixel. keep: the shape is kept, centred in d. Returns where
// the picture went (the content: what Stretch, Sharpen and Tone change).
Rect Fit(std::vector<uint8_t> *dst, int pw, int ph, const std::vector<uint8_t> &src, int sw, Rect r, Rect d, bool keep);

// Stretches the levels inside c (of a pw-wide image) toward its 2nd and 98th percentiles; amount 0 ... 100.
void Stretch(std::vector<uint8_t> *p, int pw, Rect c, int amount);

// An unsharp mask (3 x 3 box, strength 1) inside c: edges, such as text, come out crisper.
void Sharpen(std::vector<uint8_t> *p, int pw, Rect c);

// Inside c: toward the inverse by `invert` percent, the midtones by gamma (x100), then the glasses' levels:
// multiples of 17 (16 levels; 8 and 4 use every second or fifth), which the library writes as they are (no
// dithering noise to send); 2 levels: 255 from threshold up, else 0. Outside c stays black (see-through).
// cell: the dither's matrix steps every cell pixels (blocks of Pixelate stay whole).
void Tone(std::vector<uint8_t> *p, int pw, Rect c, int invert, int gamma, int threshold, int levels,
          bool dither = false, int cell = 1);

// Inside c: each n x n block (from c's corner; the last ones may be smaller) its pixels' average.
void Pixelate(std::vector<uint8_t> *p, int pw, Rect c, int n);

// Black and white (0 / 255) inside c: a lit pixel with no lit neighbour goes dark, a dark one with its four
// neighbours lit lights (outside c counts as dark).
void Despeckle(std::vector<uint8_t> *p, int pw, Rect c);

// The picture before its tones: the phone's screen (RGBA_8888) turned, placed and scaled into pw x ph, its
// contrast stretched and sharpened (luma, rot: scratch). Returns the content's rectangle.
Rect Base(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &, int pw, int ph,
          std::vector<uint8_t> *luma, std::vector<uint8_t> *rot, std::vector<uint8_t> *out);

// The base's tones in blocks of n: what the glasses get.
void Finish(const std::vector<uint8_t> &base, int pw, Rect c, const ScreenOptions &, int n, std::vector<uint8_t> *out);

// The whole way at once, for a preview without the glasses (auto detail as every pixel).
void Picture(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &, int pw, int ph,
             std::vector<uint8_t> *out);

// The picture's size for the eyes' offset: the columns both eyes see, all rows (as ScreenMirror::Start).
void PictureSize(int dx, int dy, int *pw, int *ph);

// The phone's screen on both eyes, in the columns both eyes see (the display's x from dx), all rows. 16 greys
// go through the library's gray display (a bitmap, changed pixels uploaded); 2 levels as VIDEO records (VM.md
// §3.7) into a 1-bpp bitmap on each arm, which both draw at the same due time: far less to send. Nothing else
// may draw meanwhile (the terminal is stopped first).
class ScreenMirror {
public:
  ~ScreenMirror();
  // After g1::Init. Clears both panels; keep_panels (a transition has blacked out the picture's place): only the
  // columns one eye sees go black, the panels stay on (no power cycle: the transition plays on, nothing waits).
  bool Start(int dx, int dy, std::string *error, bool keep_panels = false);
  // Clears the display; the arms keep nothing. keep_panels: a transition covers the picture (no power cycle).
  void Stop(bool keep_panels = false);
  // One captured frame (RGBA_8888). Greys: blocks while the previous frame is not on the display yet;
  // 2 levels: skipped (kBusy) while the link still carries earlier ones. kLater: shown coarser (auto detail);
  // the same frame again sharpens it.
  int Frame(const uint8_t *rgba, int w, int h, int stride, const ScreenOptions &);
  int frames();
  // Why the panels are off, or empty: "in the case" or "folded"; or the last error.
  std::string why();
  // The next picture comes with a transition (an Fx style): decoded on the arms without drawing, then revealed
  // (the black and white path; 16 greys show at once). Lapses after 4 s.
  void Reveal(int style, std::shared_ptr<Fx> fx);
  // A transition's out took the picture away: the next frame is sent whole (also an unchanged one).
  void Wiped();
  // Where the picture shows on each eye (after Start).
  Fx::Region FxRegion();
  // The cursor on the phone's screen: where it is (1/10000 of the screen's width and height) and whether it shows.
  // Black and white: the arms draw it over the picture themselves, at once (not with the next frame), and keep it
  // over the frames that follow; greys: drawn into the frames.
  void Pointer(int x, int y, bool on);
  // Sends the cursor if it still waits (a busy link, a transition): true while it does (call again soon).
  bool PointerPending();
  // A card over the picture (the help, the quick settings): w x h pixels (0 ... 255; black and white: from 128 lit)
  // at (x, y) of the picture, in every frame from the next one; empty: none.
  void SetOverlay(std::vector<uint8_t> pixels, int w, int h, int x, int y);
  // The part of the phone's screen the glasses show, from the last frame: left, top, right, bottom (1/10000) and
  // its size on the glasses (w, h pixels). False before a frame.
  bool Shows(int out[6]);

  // What was sent: frames, the last frame's bytes, bytes a second (over about 2 s), the last detail, frames
  // skipped while the link was busy, the picture's w, h, x, y, the path's levels (0 none), panels off (1).
  struct Stats {
    int frames = 0, bytes = 0, rate = 0, detail = 1, busy = 0, w = 0, h = 0, x = 0, y = 0, path = 0, hidden = 0;
  };
  Stats stats();
  // The last picture sent (w x h, 0 ... 255): what the glasses show. False before the first.
  bool Preview(std::vector<uint8_t> *out, int *w, int *h);
  // Each eye's panel read back (CRC) against what the library sent: a line per eye. Frames wait meanwhile.
  std::string Check();
  // Heads-up between frames: the head's angle read (Hidden's rule), the panels off or on now. 1: they came on
  // again (the caller sends a frame: the power-on cleared them), 0 otherwise.
  int Look(const ScreenOptions &);

  static constexpr int kShown = 0, kSame = 1, kHidden = 2, kStopped = 3, kBusy = 4, kLater = 5;

private:
  bool Hidden(const ScreenOptions &); // reads the arms' sensors at most once a second, 5 times heads-up (mutex_ held)
  bool ApplyHidden(bool hide);        // the panels off or on for it; true when they came on (mutex_ held)
  void Panels(bool on);
  bool Path(int levels);              // the drawing path for these levels (mutex_ held)
  void DropPath(bool keep_panels = false);
  void BlackSides(); // the columns one eye sees, black on each arm
  bool StartVideo();
  int VideoReady();                                    // kShown when a record may go, else kBusy or kSame
  bool SendVideo(size_t max_bytes);                    // frame_ as a record: false when larger than max_bytes
  Fx::Region Region() const;
  void Count(int bytes, int detail);
  void SendPointer(bool force); // the cursor to the arms when it moved (mutex_ held)
  int FrameVideo(const ScreenOptions &, Rect c, bool still); // Frame's two paths (mutex_ held)
  int FrameGray(const ScreenOptions &, Rect c);
  void Compose(); // the overlay into frame_ (mutex_ held)
  std::vector<uint8_t> over_;
  int over_w_ = 0, over_h_ = 0, over_x_ = 0, over_y_ = 0;

  // The cursor (Pointer) and the last frame's screen and options (where it lands).
  int ptr_cx_ = 5000, ptr_cy_ = 5000;
  bool ptr_on_ = false, ptr_dirty_ = false;
  int64_t ptr_redo_us_ = 0; // a transition revealed the picture without it: drawn again from then
  int src_w_ = 0, src_h_ = 0;
  ScreenOptions src_o_;

  std::mutex mutex_;
  bool started_ = false;
  int dx_ = 0, dy_ = 0;
  int x_ = 0, y_ = 0, w_ = 0, h_ = 0;
  int levels_ = 0; // the path in use: 16 gray, 2 video, 0 none
  scouter_kit::gray::Bitmap *pic_ = nullptr;
  std::vector<uint8_t> luma_, rot_, base_, last_base_, frame_, prev_, lo_, hi_;
  int prev_detail_ = 0; // the detail prev_ went at (steady compares like with like)
  int64_t start_us_ = 0; // Start's end (the first picture's time is logged once)
  int64_t wiped_until_ = 0; // a transition took the picture away: no frames until its reveal
  bool first_logged_ = false;
  void Make(const ScreenOptions &, Rect c, int n); // frame_ at detail n (steady against prev_)
  bool hidden_ = false;
  bool paused_ = false; // Check runs
  int64_t props_us_ = 0;
  bool props_hidden_ = false;
  bool head_up_ = true;    // heads-up: the head counted as up (shown)
  int64_t head_down_us_ = 0; // when it went below the angle's range (the hold's start), 0: not
  std::string hide_why_, error_;
  int frames_ = 0;
  int seen_ = 0; // frames handed in
  std::shared_ptr<Fx> fx_; // a transition asked for (Reveal)
  int reveal_style_ = 0;
  int64_t reveal_until_ = 0;
  Stats stats_;
  int64_t window_us_ = 0;
  int window_bytes_ = 0;
  // The VIDEO path: its arena block (code, 1-bpp bitmap, decoder state, the record's buffer), each arm's
  // entry point, the encoder, the frame number.
  struct Video {
    int64_t base = -1;
    uint32_t code = 0, bitmap = 0, state = 0, buf = 0, stride = 0, size = 0;
    std::array<uint32_t, 2> call{}, hidden{}, epochs{}; // hidden: decodes without drawing (for a reveal)
    // The cursor's code (per arm), its words, masks and scratch; where the arms were last told it is.
    std::array<uint32_t, 2> ptr_call{};
    uint32_t ptr_state = 0, ptr_masks = 0, ptr_scr = 0;
    int ptr_x = 0, ptr_y = 0;
    int ptr_shown = -1; // -1: not told yet
    std::unique_ptr<scouter_kit::g1::VideoEncoder> enc;
    std::vector<uint8_t> pix;
    uint32_t k = 0;
    int64_t lead = 30000; // µs from now to the frame's due time (VideoReady)
    int sent = 0;         // the last record's bytes with its header
    bool redraw = true; // the panels lost their image: every row again
  } video_;
};

} // namespace ssh
