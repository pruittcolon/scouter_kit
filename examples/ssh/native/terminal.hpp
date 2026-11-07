#pragma once

#include <array>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

#include "base/scouter_kit.hpp"
#include "fx.hpp"
#include "g1/scouter_kit_g1.hpp"
#include "mosh_client.hpp"

namespace ssh {

// The terminal on the arms (R22–R26), ported from go/examples/term: a ring of row slots in each arm's arena,
// run records that the arm renders (USER0) and presents in sync with the other eye (USER1), the font's base
// glyphs at the start and others as the screen shows them. The arms decide visibility themselves: TILT on
// PITCH (shown from `angle` up, hidden 5° lower after a 1 s hold), USER2 for the host's wForce (keyer held,
// obeyed while the host is linked) and wPeer (the other eye's decision), on top of the library's PanelPolicy
// (off while folded or in the case, also with wForce). The host sends rows only while an eye shows the
// terminal. While the head is up (the looking-up rule, also when forced), each arm adds its own battery level
// (BATT_PCT, redrawn on BATTERY): the left arm at its panel's centre left, the right arm at the centre right, in
// the font at 2x in a 1 px white frame on black.
class GlassesTerminal {
public:
  // The terminal's fonts on the arms, kept from one run to the next (Stop's keep_fonts, Config::fonts): a terminal
  // that starts again uploads none. blob: the size of the font blob they came from.
  struct Fonts {
    scouter_kit::Font *font = nullptr, *batt = nullptr;
    std::set<uint32_t> loaded;
    size_t blob = 0;
  };
  struct Config {
    int dx = 100, dy = 9, angle = 20;
    bool ignore_worn = false; // debug: without PanelPolicy, to show with the glasses folded on the table
    bool battery = true;      // each eye's battery band beside the terminal
    // A TouchBar gesture (TOUCH_GESTURE, VM.md §6.7) reported by an arm (ReportInputs), from the terminal's
    // thread: it must not call Stop.
    std::function<void(int arm, uint32_t gesture)> on_touch;
    // Pixels of the display left empty above and below the terminal (fewer rows): its top or bottom can be
    // hard to see through the glasses.
    int top = 0, bottom = 0;
    // Looking up, the panel comes on dim and brightens to its level in this long (0: at once); looking down it
    // dims in 5/8 of it, then goes off.
    int fade_ms = 400;
    // The dashboard above the terminal (SetDash), this many pixels high (0: none); the terminal gets fewer rows.
    int dash_h = 0;
    // The transitions (Reveal); none: every screen shows at once.
    std::shared_ptr<Fx> fx;
    // Fonts kept from an earlier run: taken (cleared) when they fit this font blob; else left for the caller.
    Fonts *fonts = nullptr;
  };
  // After g1::Init. `font`: a font blob (departure_mono_nerd.skf); `changed` runs when visibility changes.
  bool Start(const Config &, std::vector<uint8_t> font, std::function<void()> changed, std::string *error);
  // keep: the program and its last screen stay on the arms, which show them by themselves when the head
  // looks up (keyer off: the phone lets go); wForce and wPeer are cleared first. keep_fonts: the fonts stay on
  // the arms, handed over there, for the next run.
  void Stop(bool keep = false, Fonts *keep_fonts = nullptr);
  void Show(std::shared_ptr<const Snapshot>); // a new screen, from any thread
  // The next screen comes with a transition (an Fx style) instead of at once: the arms render it and hold it
  // until both have it, then it is revealed; with_dash: the dashboard with it (it shows nothing new meanwhile).
  // A screen pending now is dropped (it is the old one). Before Start: the first screen. Lapses after 4 s.
  void Reveal(int style, bool with_dash);
  // A transition's out took the picture away (with the dashboard or not): unless a screen is revealed within
  // 1.5 s, the last one is drawn again.
  void Wiped(bool with_dash);
  // Where the terminal shows on each eye (with the dashboard above it, or not), and its picture on the arms.
  Fx::Region FxRegion(bool with_dash) const;
  Fx::Source FxSource(bool with_dash) const;
  void Force(bool on);
  // The dashboard's picture: 4 bpp (the high nibble the left pixel), dash_w() / 2 bytes a row, dash_h() rows.
  // Each arm gets the rows that changed (in its memory); it draws them while the terminal shows, and with it
  // when the terminal comes.
  void SetDash(const std::vector<uint8_t> &pixels);
  int dash_w() const { return dash_h_ ? geo_.W() : 0; }
  int dash_h() const { return dash_h_; }
  bool Shown();
  // Read-back (T3): both arms' rendered areas are equal and not blank, and each eye's panel rows hold that
  // area at the eye's offset (PanelCrc). One line per check; "ok" when all pass.
  std::string Check();
  int cols() const { return geo_.cols; }
  int rows() const { return geo_.rows; }

  struct Geometry {
    int cols, rows, cw, ch, ascent, x, y;
    int bw, bh, bcw, bascent; // the battery band (even width) and its font's advance and ascent
    int W() const { return (cols * cw + 1) & ~1; } // even: 4-bpp bytes
  };
  struct Layout {
    uint32_t base, state, dirty, ring, area, batt, batt_text, dash, end;
  };
  struct Row {
    std::vector<uint8_t> rec;
    int cursor = -1;
    std::string Key() const;
  };
  struct Frame {
    int top = 0, cur_slot = -1, cur_x = 0;
    bool full = false;
    std::vector<uint8_t> dirty;
    std::map<int, std::vector<uint8_t>> recs;
  };
  // What the arms hold: the row each slot was last rendered with, and the slot of row 0 (screen.go).
  struct Screen {
    std::vector<std::string> slots;
    int top = 0;
    bool fresh = true;
    bool Next(const std::vector<Row> &rows, Frame *f);
  };

private:
  struct ArmEvent {
    int arm;
    scouter_kit::g1::Event e;
  };
  void Run();
  void Events(int arm);
  void Handle(const ArmEvent &);
  void Send();
  void SyncPeers(int from);
  void Restart(int arm);
  void WriteWord(int arm, int word, uint32_t value);
  void SetHold(uint32_t hold);
  std::vector<Row> Rows(const Snapshot &);
  std::vector<uint8_t> Encode(const Cell *cells, int n);
  uint32_t Glyph(uint32_t cp);
  bool LoadGlyphs(const std::set<uint32_t> &chars);
  void Adopt(scouter_kit::Font *font, const std::set<uint32_t> &chars);
  bool Grow();

  Geometry geo_{};
  Layout l_{};
  int dash_h_ = 0;
  std::mutex dash_mutex_;
  std::array<std::vector<uint8_t>, 2> dash_sent_; // what each arm holds
  int angle_ = 20;
  bool battery_ = true;
  std::function<void(int, uint32_t)> on_touch_;
  std::array<std::array<int, 2>, 2> pos_{};
  std::array<scouter_kit::g1::Arm *, 2> arms_{};
  std::array<bool, 2> present_{};
  std::vector<uint8_t> blob_;
  scouter_kit::Font *font_ = nullptr;
  scouter_kit::Font *batt_font_ = nullptr; // digits and % at 2x, for the battery band
  std::set<uint32_t> has_, loaded_, want_, never_;
  std::map<uint32_t, uint32_t> glyphs_;
  uint32_t space_ = 0, unknown_ = 0;
  Screen screen_;
  uint32_t frame_ = 0;
  std::function<void()> changed_;

  std::mutex mutex_; // guards the fields below
  std::condition_variable cv_;
  bool stop_ = false;
  std::deque<ArmEvent> events_;
  std::shared_ptr<const Snapshot> pending_, last_; // the screen to send, and the one sent last
  int64_t first_us_ = 0, last_us_ = 0; // when the pending screen first and last changed
  bool force_ = false, force_sent_ = false;
  std::array<int, 2> shown_{}; // as the arms last said: 0 hidden, 1 shown
  bool desired_ = false;       // the latest decision an arm took itself
  uint32_t inflight_ = 0;      // the frame sent and not shown by every arm yet
  int64_t inflight_us_ = 0;
  std::array<bool, 2> done_{};
  bool notify_ = false; // visibility changed: call changed_ outside the lock
  // Transitions: the one asked for (Reveal), and the frame held for it on the arms until both have it.
  std::shared_ptr<Fx> fx_;
  int reveal_style_ = 0;
  bool reveal_dash_ = false;
  int64_t reveal_until_ = 0;
  uint32_t held_frame_ = 0;
  int held_style_ = 0;
  bool held_dash_ = false;
  uint32_t hold_sent_ = 0; // wHold as the arms have it
  int64_t wiped_until_ = 0; // Wiped: when the last screen is drawn again (0: not wiped)
  bool wiped_dash_ = false;

  std::thread thread_;
  std::array<std::thread, 2> event_threads_;
};

} // namespace ssh
