#pragma once

#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "g1/scouter_kit_g1.hpp"

namespace ssh {

// Tunes: a synthwave scene in the glasses' greys, played by the arms themselves (a program, a frame every 33 ms):
// a striped sun on the horizon whose stripes slide down, mountains and stars, and a perspective grid floor whose
// lines roll toward the viewer. The phone only sends the music's pulse (the panel's brightness breathes with it)
// and speed (the floor rolls faster), a few words now and then. It takes the whole picture (the terminal and the
// screen mirror stop meanwhile).
class TunesScene {
public:
  ~TunesScene();
  // After g1::Init; the picture as the screen mirror's (the columns both eyes see). ignore_worn: no panel policy.
  bool Start(int dx, int dy, bool ignore_worn, std::string *error);
  void Stop();
  // pulse, speed: 0 ... 100.
  void Beat(int pulse, int speed);

  // The scene's drawing list and its sizes (for tests): ops of 6 u16 (FILL x y w h level, LINE x0 y0 x1 y1 level).
  struct Scene {
    int w = 0, h = 0, horizon = 0, sun_x = 0, sun_r = 0, stripe_top = 0;
    std::vector<std::array<uint16_t, 6>> ops;
    std::vector<uint16_t> half; // the sun's half width per row, from stripe_top to the horizon
  };
  static Scene Make(int w, int h, uint32_t seed);
  static constexpr uint16_t kFill = 1, kLine = 2;

private:
  std::mutex mutex_;
  bool started_ = false;
  int64_t base_ = -1;
  uint32_t state_ = 0, code_ = 0;
  std::array<bool, 2> present_{};
  int pulse_ = -1, speed_ = -1;
};

} // namespace ssh
