#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "g1/scouter_kit_g1.hpp"

namespace ssh {

// Transitions between what the glasses show: the old picture goes the moment a switch is asked for ("out"),
// the new one comes once its pixels are on the arms ("in"). Each plays on the arms from a list of drawing
// steps that the host plans here: stored code (one block on each arm) runs the list in a host packet, a frame
// every few milliseconds from a due time both eyes share, so it is as smooth as the panel whatever the link
// does, and both eyes see the same steps. The styles (about 0.15 s out, 0.3 s in):
// - kLeft, kRight (and kTabLeft, kTabRight, quicker): "data stream", a neon edge in bands that lag each other
//   comes in from that side, with shards of the new picture flying ahead of it.
// - kUp, kDown: "raster", a CRT scan line with its glow comes from the top (up) or the bottom (down), with
//   interference lines ahead of it.
// - kJump: "decrypt", tiles in a random order, each with a bright edge for a frame.
// - kOn, kOff: a CRT switching on (a line at the middle opens) or off (the picture closes to a line, then a dot).
class Fx {
public:
  enum Style { kNone, kLeft, kRight, kUp, kDown, kJump, kOn, kOff, kTabLeft, kTabRight, kStyles };
  // Where an animation plays: w x h pixels at each eye's (x, y) on its panel.
  struct Region {
    int w = 0, h = 0;
    std::array<std::array<int, 2>, 2> at{};
  };
  // The picture a reveal shows: w x h (the region's size) at address, 4 or 1 bpp (1: ink at level 15).
  struct Source {
    uint32_t address = 0, stride = 0;
    int bpp = 4;
  };

  // One step: an opcode and up to six operands of 10 bits, 8 bytes on the arms.
  enum Op : uint16_t {
    kFrame,  // the next frame's due time (a step of dt)
    kCopy,   // x, y, w, h, px, py: the source's rectangle at (px, py) of the region
    kLoad,   // x, y, w, h, dx, total: the scratch is total x h, the source's rectangle at (dx, 0) in it
    kBlank,  // w, h: the scratch is w x h, black
    kFill,   // x, y, w, h, level: in the scratch
    kShow,   // px, py: the scratch at (px, py) of the region
    kLabel,  // px, py: the label
    kEnd,
    kGrad,   // x, y, len, levels (2 nibbles), levels (2 more), n | axis << 3: n <= 4 lines (axis 0 columns of
             // len rows, 1 rows of len columns) of the scratch from (x, y), line i at level nibble i (low first)
  };
  struct Step {
    std::array<uint16_t, 8> v{};
  };
  static constexpr int kScratchBytes = 8192; // the scratch: its stride ((w + 1) / 2) x h fits this
  static constexpr int kListSteps = 400;
  static constexpr int kLabelBytes = 4096;
  static constexpr int kOutUs = 22000, kInUs = 28000; // a frame of each phase

  ~Fx();
  // After g1::Init, once; Ensure again before use (an arm that rebooted lost the block).
  bool Start(std::string *error);
  void Stop();
  bool Ready();
  // The old picture goes at once (its pixels need not be on the arms), then the label (4 bpp, the high nibble
  // the left pixel, lw even, at most kLabelBytes) shows in the region's middle (none: empty).
  void Out(Style, const Region &, const std::vector<uint8_t> &label = {}, int lw = 0, int lh = 0);
  // Only the label again, where the last Out put it (after the panels were cleared).
  void Label(const Region &);
  // The new picture comes: its pixels must be on the arms before this (in packet order). from_us: when the
  // first frame is due, on the NowUs() clock (0: as soon as both eyes can start together).
  void In(Style, const Region &, const Source &, int64_t from_us = 0);
  // The time the last animation sent ends (NowUs() clock), so what follows can wait for it.
  int64_t busy_until();

  // The plans (for tests): what each phase draws, frames separated by kFrame and ended by kEnd; bpp: the
  // source's (1: copies go through the scratch, in pieces that fit it).
  static std::vector<Step> PlanOut(Style, int w, int h, int lw, int lh, uint32_t seed);
  static std::vector<Step> PlanIn(Style, int w, int h, uint32_t seed, int bpp = 4);
  static int Frames(const std::vector<Step> &);
  static int Jumps(const std::vector<Step> &); // taken jumps the VM makes for them, at most
  static std::vector<uint8_t> Pack(const std::vector<Step> &);

private:
  bool StartLocked(std::string *error);
  bool Ensure();
  void Run(const Region &, const std::vector<Step> &, const Source *, int frame_us, int64_t from_us);
  int64_t Lead();

  std::mutex mutex_;
  bool started_ = false;
  int64_t base_ = -1;
  uint32_t code_ = 0, params_ = 0, list_ = 0, label_ = 0, scratch_ = 0, entry_ = 0;
  std::array<uint32_t, 2> epochs_{};
  std::vector<uint8_t> label_pixels_;
  int lw_ = 0, lh_ = 0;
  bool blank_ = false; // an Out played and no In since: another Out only changes the label
  int blank_w_ = 0, blank_h_ = 0, blank_lw_ = 0, blank_lh_ = 0;
  uint32_t seed_ = 1;
  int64_t busy_until_ = 0;
};

} // namespace ssh
