# Vox (formerly SSH Scouter)

An Android app that keeps a [mosh](https://mosh.org) session to your server and mirrors its terminal on the
Even Realities G1 running the ScouterKit firmware, with keys from
[keyer](https://github.com/mafik/keyer) over its key-stream service. GPL-3.0 (`LICENSE`), because it
includes mosh; third-party notices in `NOTICE` and `licenses/`.

New here, or an agent wiring something in? Start with [`AGENT_GUIDE.md`](AGENT_GUIDE.md): what it does, its limits (battery, frame rate, permissions), the architecture and recipes. Built on [ScouterKit](https://github.com/mafik/scouter_kit) by mafik, whose firmware makes all of this possible.

- The terminal fills the binocular area: (640 − dx) / 7 × (480 − dy) / 14 cells (77 × 33 at dx 100, dy 9).
- It shows when you look up past the angle in Settings and hides 5° lower after a second. While the app
  holds keyer, it shows whatever the head's angle, also with the phone in a pocket.
- The phone shows the same terminal while the app is in the foreground. It has no keyboard: keys come from
  keyer only.
- While the terminal shows, each eye shows its arm's battery level beside it (left eye on the left, right eye
  on the right). The arms do this themselves, also without the phone.

## Build

    native/third_party/build.sh          # OpenSSL 3.5.9, protobuf 21.12, mosh 1.4.0 at pinned SHA-256
    ./gradlew assembleGlassesDebug       # -PthirdPartyDir=OUT if build.sh got another OUT

A signed release (application ID `io.github.pruittcolon.scouter` in this fork; no R8, since JNI and JSch find classes by name):

    ./gradlew assembleGlassesRelease

signed with the keystore that `keystore.properties` in this directory names (storeFile, storePassword,
keyAlias, keyPassword; storeFile absolute or relative to this directory; the file and `*.jks` are
git-ignored). Keep that keystore: updates must be signed with the same key. Without the file the release is
unsigned.

Needs the Android SDK (platform 34, NDK 27.3.13750724, CMake 3.31.6), JDK 17 or later, and for
`build.sh` curl, cmake, ninja, make, perl and a host C++ compiler. The `emulated` flavour
(`assembleEmulatedDebug`) runs two emulated arms inside the app and shows their panels: no glasses needed.

Host tests (Linux, against this machine's `mosh-server` and the emulated arms):

    cmake -S native -B BUILD -G Ninja && cmake --build BUILD && ctest --test-dir BUILD

## Set up

Settings is the app's only screen with controls; everything else follows keyer. It first asks for Android's
Bluetooth ("Nearby devices") and notification permissions; without Bluetooth the app starts nothing.

1. Settings, Server: host, port, user, your OpenSSH private key (file or paste, with its passphrase if it
   has one; it is kept encrypted with a key in Android's keystore), the command (empty: the login shell) and
   mosh's UDP ports.
2. Glasses: pair each arm in Android's Bluetooth settings with the glasses in the open charging case (the
   phone takes one of each arm's 3 bond slots), then "Use the bonded glasses" (or "Pair glasses", a scan in the
   open case) picks the pair. The app connects only to
   arms Android lists as bonded and never pairs by itself: if a bond is lost or encryption fails, the
   notification says "pair again in the case". dx and dy align the eyes (they set the overlap and the
   terminal's size); the looking-up angle sets when the terminal shows.
3. keyer: pair it in Android's Bluetooth settings, then "Use the bonded keyer".
4. Open the app once with keyer on and allow it as keyer's companion (Android's dialog; it also exempts the
   app from battery optimisation). The first session shows the server's host key fingerprint to confirm.

keyer on: the app starts by itself, connects the glasses, takes keyer's keys and resumes the mosh session,
with one notification showing the state. keyer off: the app lets go of the glasses and keyer, mosh goes
quiet and the phone can sleep; looking up still shows the last screen, which the arms keep. The glasses stay
connected only while keyer's own link is up. Bluetooth switched off (also when Android keeps Bluetooth Low
Energy on for other apps) ends the session, and the app then does nothing: Android stops reporting keyer to
it until Bluetooth is on again. The mosh session
continues at the next keyer on while Android keeps the app's process; after that a new one starts (and ends
the old one), so run tmux on the server (command `tmux new -A -s main`) to keep your work.

## Staying connected

- **Start by itself** (Settings, Glasses; on by default): with the glasses paired, the session starts when the app
  opens, after the phone starts, after an update and when Bluetooth comes on, and runs until Stop everything.
- **The glasses' start** (`Native.glassesStart`, up to 30 s for both arms) runs on a thread of its own; a failed
  one is tried again after 3, 5, 8, 12, 20, then every 30 s, and one that settings or Stop made stale is dropped.
- **Each arm's link** (`GlassesLink`): a lost link reconnects by itself (autoConnect, as the arm advertises again,
  e.g. out of the case). Setup (MTU, services, the EVT subscription) must finish within 12 s, else a fresh link;
  a failed discovery gets a fresh link twice before "not the scouter firmware"; a write whose callback never
  comes is given up after 3 s (three in a row: a fresh link). A lost bond or refused encryption stops for good
  (no pairing behind the user's back).
- **The terminal's session** (mosh) is tried again after an error on the same schedule; this phone's own server
  needs no network, and when its SSH server is off the app says so (type `ssh` in Termux).

## Sources and switching

The main screen has tabs with icons: **Glasses** (what the glasses show, live: the terminal in the panel's green
or the screen's picture, the source's name and each arm's battery over it; the sources as a grid, the machines
across and terminal above screen, the way Alt+arrows walk them, each tile with its state; round switches for
the display, *Always visible*, voice and the pointer; brightness; a banner with its button for anything that
needs the user; the power button stops everything or starts again), **Screen** (the picture the glasses get,
sharing, then *Look*, *Place*, *Speed and detail* and *Going dark*), **Terminal** (the terminal and its tabs as
chips: tap to show, hold to close; the touchpad; the servers, each with its state; *On the glasses*, *Look-up
view*, *Keys*), **Voice** (the microphone, the speech model, your commands) and **Settings** (*Glasses*,
*TouchBar*, *Keyboard and trackpad*, *SSH key*, *Shortcuts*, *Termux*, *Access*, *Troubleshooting*). Sections
fold under their titles and stay as they were left. A server is added and edited in one dialog (with presets for
this phone's Ubuntu and Termux, mosh's ports and *Forget its host key*).

- **Sources**, in order: each saved server's terminal, the phone's screen, and each other server's screen. One
  tap on Home switches; the launcher's shortcuts (long-press Vox), pinned home-screen shortcuts, the Quick
  Settings tile "Next source" and the TouchBar (by default: left hold next, left double tap previous, right hold
  display off / on) do the same from anywhere. The phone's screen needs Android's consent once per sharing, in
  the app.
- **Each source on its own**: every terminal has its own mosh session (`Native.moshStart(id, …)`), started on its
  own thread and kept, side by side with the others, after a switch away, so switching back is at once and a
  server that does not answer holds up nothing else. Only the user's choice (`Settings.source`) sets what shows:
  one that cannot show yet (a server not answering, the phone's screen not shared, a server's screen that
  stopped) keeps its place and shows a card on the glasses (`Native.card`: its name, why, "trying again by
  itself", how to switch) while it is tried again, soon at first, then less often. Each terminal keeps its own tab
  list and its own connection for it.
- **Saved servers** (Terminal tab): name, host, port, user, command. The one in use is what Settings, "Server in
  use" edits. Host keys are trusted per server (host:port), so switching does not ask again. A link
  `vox://server?name=Laptop&host=HOST&port=22&user=USER&command=...` (e.g. `adb shell am start -d '...'`) adds or
  updates one after the app asks.
- **A server's screen** (a laptop's): over SSH, with the same key, the app runs `vox-screen WIDTH HEIGHT FPS`
  there (looked for in `~/.local/bin`, `~/bin` and `/usr/local/bin` too), which must write baseline JPEG images one after another to stdout (each the whole desktop inside
  WIDTH x HEIGHT) until stdout closes. They go through the same pipeline as the phone's screen. The width asked
  for is on the Screen tab (640, 960, 1280). Another source in front, the server's screen stays connected (its
  newest image waits undecoded) for 5 minutes, so switching back shows it at once.
- **Transitions** (*Transitions*, Terminal tab, *On the glasses*, on by default; `native/fx.hpp`, `Fx.kt`): a switch plays on the
  glasses in about half a second. The old picture goes the moment it is asked for (about 0.15 s), a label says
  where the glasses are going ("LAPTOP // TERMINAL"), and the new picture comes in once its pixels are on the
  arms (about 0.3 s). Each way of switching has its own: Alt+Left / Right (and next / previous) a data stream, a
  neon edge in staggered bands from that side with shards of the new picture flying ahead of it; Alt+Up / Down a
  CRT scan line with its glow from the top or the bottom, with interference lines ahead; a jump (Alt+1 ... 9, a
  click on the dashboard, voice, a shortcut) tiles that decrypt in a random order; the tabs a quicker stream
  toward the tab they go to; the display a CRT switching off (to a line, then a dot) and on (opening from the
  middle). Between two terminals only the terminal moves: the dashboard stays, like a task bar. A switch within
  1.5 s of the one before is a cut (`Fx::kCut`: one frame out with the label, one in), so toggling quickly is not
  held up by animations.
  Quick by design: under a transition the screen mirror starts and stops without power-cycling the panels (the
  out has blacked its place out; only the columns one eye sees are drawn black), so the animation and its label
  play on; the terminal's held first screen is due as soon as it is in (its reveal waits for both arms anyway);
  the phone's screen is captured at once on a switch (not at the next tick) with its app's look already applied.
  Each step is logged (`switch:`, `fx: out / in`, `screen: first picture`, `terminal: started in`) to time it.
  How: the phone plans each animation as a list of drawing steps (8 bytes each: copy a rectangle of the new
  picture, a black or bright bar, a neon edge's levels, the label), and stored code on each arm runs it in a host
  packet, a frame every 22 or 28 ms from a due time both eyes share, so it is as smooth as the panel whatever the
  link does and both eyes draw the same steps. The new picture is the terminal's area (its next screen held:
  rendered, not drawn, until both arms have it; `wHold`) or the screen mirror's 1-bpp bitmap (its next frame
  decoded off the panel). The 16-grey screen path shows at once after the out. If no picture comes for a switch,
  the last one is drawn again after a while.
- **The look per app** (*Remember the look per app*, Screen tab, on by default; `Looks.kt`): the screen's look (place,
  zoom, bars, greys, detail, invert, contrast and the rest) is kept per phone app, per laptop window and per source.
  VoxKeys notes which app is in front (window changes' package names only; it reads no screen content); for a
  server's screen the app asks X11 for the active window's class over the kept SSH connection every 2.5 s
  (`xprop`; DISPLAY :0 unless set). A change made while an app or window shows is kept for it, and its look comes
  back when it shows again; one without its own takes its source's (made from the look in use the first time).
  *Look for* names it; *Forget its look* and *Use for every app* are there too. Changes made in Vox itself count
  for the app shown before Vox.
- **Cleaner black and white**: *Steady* (on by default) now also holds black-and-white pixels until they are 16
  levels past the threshold, against the last frame sent at the same detail, so nothing shimmers and still screens
  send nothing; *Clean edges* (on) drops lone specks and fills pinholes at full detail; *Cut the top* / *Cut the
  bottom* (Place, with a preset for the phone's bars) remove the status and navigation bars before anything else.
  *Text modes on the phone* opens Google's Reading mode or Android's text contrast settings to try.
- **Coming back to the terminal** from a screen or with the display on again starts its program anew but keeps its
  fonts on the arms meanwhile (27 KB that would take about a second to send again).

## The look-up view

- **The fade**: looking up, each arm's program powers the panel on dark and raises `BRIGHTNESS` to the level it
  had in 8 steps on a square curve (*Fade in*, Terminal tab, 0 ... 800 ms; 400 by default); looking down it
  lowers it in 5 steps over 5/8 of that, switches the panel off and writes the level back for the next power-on.
  The arm reports the change before fading, so the other eye follows at once.
- **The dashboard** (by default; `Dashboard.kt`, `GlassesTerminal::SetDash`): a strip above the terminal (*Dashboard
  height*, 88 px by default; the terminal gets fewer rows) that the phone draws at the glasses' pixels in their 16
  levels: a thin clock with a halo and the date, a card with the newest notification (or, while voice is in use,
  what is heard), a ring gauge per arm's battery, corner brackets and a ticked rule. Each arm keeps the picture
  in its memory (only the bytes of each row that changed are sent), draws it with the terminal when the head goes
  up (so it fades in with it) and again on USER3 when the phone sends a new one. The Terminal tab shows it as the
  glasses get it.
- **Styles and your own** (*Look-up screen*, Terminal tab; `Dashboard.STYLES`): *Classic* (the tab bar; clock,
  notifications, batteries), *Media* (clock, now playing, batteries), *Everything*, *Focus* (no tab bar; clock and
  wide notifications), *Minimal* (clock, the phone's and the glasses' batteries), and *Your own*: the tab bar on or
  off and the widgets (clock and date, notifications, now playing, weather, calendar, timer, the glasses' batteries,
  the phone's battery) on
  or off, in your order, notifications and now playing ×1 to ×3 wide (`Settings.dashLayout`, JSON). Each style shows
  as a picture in the glasses' green; voice and a quick reply take the widest widget's place while in use.
- **Weather, calendar, timer** (`Widgets.kt`): the weather from Open-Meteo (no account or key) for the city typed
  on the Terminal tab (looked up once, then asked every 30 minutes; °F or °C by the phone's country unless set):
  an icon, the temperature, the sky in words, the day's high and low. The calendar: the next events of the phone's
  calendars over 36 hours (READ_CALENDAR, asked for on the Terminal tab), each with its time. The timer: the time
  left with a line that empties, −1 min, start / pause and +1 min for the pointer (a right click resets it); voice
  "timer 5 minutes", "set a timer for ten minutes", "pause the timer", "stop the timer"; when it ends the glasses
  say "Time's up". *Day* shows clock, weather, calendar and batteries.
- **Tunes** (`native/tunes.cpp`, `Tunes.kt`; the Glasses tab's Tunes, Alt+U, "tunes mode" / "stop tunes"): a
  synthwave scene the arms play by themselves in place of everything else, a frame every 33 ms: a sun cut by the
  horizon whose lower stripes slide down, mountains at its sides, stars, and a perspective grid floor whose lines roll
  toward you, nearer brighter. The phone sends only the music's pulse (the panel's brightness breathes with it) and
  speed (the floor rolls faster), a few bytes each 60 ms. It follows (Settings, *Tunes*) the beat (a jump in the
  bass of Android's output mix, a Visualizer: RECORD_AUDIO; nothing is recorded or kept), the volume, or a steady
  tempo (60 ... 160 BPM, no permission) while something plays. The *Tunes bar* is the look-up screen's quiet version:
  a small striped sun on a horizon with the music's twelve bands rising beside it (redrawn about five times a second
  while it shows); the *Tunes* style puts it between now playing and the clock.
- **Now playing** (`Media.kt`): the phone's media sessions (Spotify, YouTube, podcast and video players), listed to
  Vox's notification listener: the app, the title, the artist, the cover in the glasses' greys and a progress line
  (redrawn each second while it plays and shows), with before, play / pause and next as places the pointer clicks.
  Voice: "play", "pause", "next song", "previous song" (also for your own commands).
- **The header** instead, as text lines (*Graphical dashboard* off; `Native.lookUpHeader`): the time, the date and the glasses' batteries, and
  the newest phone notification (a `NotificationListenerService`, once the user allows Notification access),
  then a rule. The SSH session gets as many rows fewer; the voice band takes the header's rows while voice is in
  use. It is redrawn each minute and when notifications or the batteries change.

## The pointer and tabs

- **The pointer**: an arrow the arms draw over the terminal and the dashboard (`Native.pointerAt`,
  `GlassesTerminal::Pointer`), moved pixel by pixel: the host sends its place at most every 15 ms and stored code on
  each arm puts back the content under the old place (from the area's ring and the dashboard's picture) and draws
  the arrow (a bright body, a dark edge) over the new one; the program paints it again after every redraw. Moved
  by the trackpad (like a mouse), by a keyboard paired with the
  phone (Alt+M on, then arrows, Shift for 5 cells, Enter or Space to click, Shift+Enter a right click, Page Up /
  Down to scroll, Escape off), by the Terminal tab's touchpad (drag, tap, hold, two fingers) or by voice. Clicks
  and the wheel go to the session's program as xterm mouse reports in the encoding it asked for (SGR, urxvt or
  X10; `Native.mouse`), and only while it asked for them (mosh's mouse mode): otherwise they would be typed.
- **Tabs** are the windows of the tmux session the terminal shows (its command's `-s NAME`, or `claude` for the
  phone's Claude preset), driven over a second SSH connection (`Bootstrap.run`, `Tabs.kt`): Alt+T a new shell,
  Alt+[ / ] the tab before / after, Alt+W closes the tab shown (never the first), and Claude's or Cursor's tab
  (switched to where one runs, else opened). tmux's own status line shows them; with *Mouse in tmux* (on by
  default, `tmux set -g mouse on` when the pointer starts) clicking a tab there switches to it.
- **The dashboard's tab bar**: above the terminal's top row the arrow is over the dashboard; the place under its
  tip gets a bright ring and a notch (`Dashboard.Hit`); a click shows that tab
  (the + opens another, a right click closes it, never the session's first), the wheel goes to the tab before or
  after, and down goes back into the terminal under it. **PHONE** at its right end brings up the phone's screen
  (Android asks in the app first while it is not shared).
- **The cursor on the phone's screen** (pocket mode): with the phone's screen on the glasses the trackpad drives an
  arrow the arms draw themselves over the picture (`ScreenMirror::Pointer`: stored code in the VIDEO path's block
  puts the 16 x 20 box back from the 1-bpp bitmap and draws the arrow at its new place, a few bytes a move, and
  draws it again after each frame), so it glides at once instead of with the 5 frames a second. It moves in
  glasses pixels at the trackpad's speed, through the picture's zoom, crop and turn (`Locate`, `Shows`), and stays
  on the part that shows. A click taps the phone there, a right click is a long press, two fingers and the wheel
  scroll the app as swipes (queued, so a quick stroke is not cut short) (VoxKeys: Android's accessibility
  gestures; *Vox* must be on in Accessibility). Alt+Backspace is Back, Alt+H Home, Alt+R Recents, Alt+N the
  notifications. Typing goes to the phone's app as usual (the pointer off: Enter and Space are its clicks while
  it is on). *Pocket mode* (Settings, *Keyboard and trackpad*, on by default) keeps the phone awake (dimmed) while
  its screen is on the glasses, so it can stay in a pocket; a key or the trackpad wakes it if it went dark. With
  16 greys the arrow goes into the frames instead.
- **Notifications on the dashboard**: the pointer goes on from the terminal's top row up into the newest three
  notifications (then up again to the tab bar). A click opens the notification's app on the phone (as tapping it
  would: `NoteActivity` sends its intent) and the glasses switch to the phone's screen with the cursor in the
  middle, to click through and type. A right click on one with its own Reply (messages) writes a quick reply on
  the dashboard instead: typed (VoxKeys takes the keys meanwhile) or said (Alt+Space), Enter sends it through the
  notification (Android's RemoteInput), Escape cancels.
- **Scrolling** (two fingers on the trackpad, its wheel; Settings, *Cursor speed*: speed, acceleration, scroll speed,
  natural scrolling): to the terminal's program as the mouse's wheel when it takes the mouse, else tmux's own history
  (copy mode, ended by itself at the bottom) over the server's kept SSH connection, and tmux's mouse is switched on
  then (*Mouse in tmux*) so the next scroll goes as the wheel at once; over the dashboard the tab before or after.
  With the pointer off the wheel turns at the terminal's middle.
- **Typing on the glasses** (*Type on the glasses*, Settings, *Keyboard and trackpad*, on by default; VoxKeys): while
  the terminal is the glasses' source (looking up or down), a keyboard paired with the phone types into it whatever app is in front
  (Keyboard.kt's key stream, as keyboard mode does); the Alt shortcuts stay shortcuts. Alt+K sends the keys to the
  phone's apps and back (the glasses say which). With the phone's screen on the glasses the keys are its app's.
  Arrows, Enter and Space drive the pointer only after Alt+M; on by the trackpad, every key types.
- **A mouse or a keyboard's trackpad** paired with the phone (*Trackpad on the glasses*, Settings, *Keyboard and trackpad*, on
  by default; VoxKeys, Android 14+): while the glasses show the terminal (or a card), VoxKeys takes the trackpad's
  events from the phone's apps and they drive the glasses' pointer: it glides a cell at a time
  (fractions kept, quicker strokes go further; *Trackpad speed*), goes up onto the dashboard and there a place at a
  time (tabs, +, PHONE, the notifications), a tap or click clicks, a two-finger tap (the secondary button)
  right-clicks, the wheel and two-finger swipes scroll; a click while the pointer is off only shows it. On the
  phone's screen it drives the glasses' cursor there (above). It is taken only while the glasses show the terminal
  or the phone's screen (Android 14+ motion event sources: no app gets it meanwhile); otherwise it is the phone's own
  pointer. With the phone's
  cursor on, typing goes to the phone's app: Space, and Enter once something was typed; an arrow key makes the keys
  the cursor's again.
- **Keyboard off / on**: Alt+X; while off, the keyboard's keys do nothing anywhere (Alt+X aside), for a keyboard
  in a bag or on the table. A note on the glasses says which.
- **Sharing the phone's screen** (Alt+C, or choosing the phone's screen any way while it is not shared): Android's
  question comes up and VoxKeys taps its Start (Start now, Share screen) itself, looking for it at each window
  change and every 200 ms for 15 s, only in the system UI's dialog that names Vox; then the app that was in front
  comes back.
- **Closing**: Alt+Q closes the source shown (a terminal's connection, the phone's sharing, a server's screen) and
  the glasses go to the next one still open; a closed one stays closed until it is chosen again.
- **Look-up only**: Alt+Z (or Alt+L) switches between *Always visible* and look-up only (the terminal shows when
  looking up): a note says which, then the terminal fades away with the head level (or stays). The left TouchBar
  held 3 s turns *Always visible* off too; only the app or Alt+Z turns it back on, so a stray touch cannot. The
  left hold's own action comes when it is let go. Each arm checks every 5 s that the phone is still linked; while
  its link is down the head decides there, and once it is back *Always visible* holds again (the arm takes it back
  itself: the phone never learns it was lost).
- **Panels over everything** (`Panels.kt`): Alt+A the help, every hotkey and what it does (the same list as the
  app's Settings, *Hotkeys*), also while Alt is held by itself (after 0.3 s, gone when it is let go; a key pressed
  with it closes it and does its own thing); Alt+S the quick settings of what shows (a screen: picture, invert, threshold,
  contrast, zoom, fit, turn, cuts, frames a second, brightness, pocket mode, cursor speed; the terminal:
  brightness, always visible, the look-up dashboard and its style, speeds, typing, animations, Tunes), one at a
  time: Up / Down the setting, Left / Right its value, live, Esc or Enter done (the trackpad: scroll, click
  closes). A note at the bottom says what a hotkey did (Alt+= / - brightness, Alt+P play or pause, Alt+, / . the
  track before / after). On the terminal they are its own lines (`Native.overlay`, over the band and the card);
  on a screen a picture composed into its frames (`ScreenMirror::SetOverlay`, the capture asked for a frame), so
  what is under them keeps running and they come and go at once. The terminal shows whatever the head's angle
  while one is open.
- **The charging case's log** (Settings, *Troubleshooting*, *Charging case log*; `CaseLogActivity.kt`): the case's
  USB-C port (a USB-to-serial chip inside it, the one Even's case updater uses) carries what its firmware prints at
  115200 baud: each arm's NFC charging state, the arm's battery report (VRECT, level, charge flag, current), card
  lost, no answer, two tags seen, the case's battery and temperature. With the case on the phone by a USB-C data
  cable the screen reads it (CH34x, CP210x, FTDI or CDC set up directly, no library), sums each arm up with what
  each finding means, and keeps a copy in Android/data/<package>/files/caselog.txt. Android offers Vox when such
  a chip is plugged in; the screen looks at the port every second and lists what Android sees there (each
  device's chip and interfaces, bytes received), so a charge-only cable or a wrong set-up shows as such.
- **Display off and on** (Alt+0): the CRT close plays to its end before the panels go off (`PanelsOffAfterFx`), and
  the picture opens from the middle when it comes back.

- **Game score** (dashboard widget *Game score*, style *Game day*; `Sports` in `Widgets.kt`): an NFL team's game
  (Terminal tab, *Game score*: its letters, NE by default) from ESPN's public scoreboard, no key: away over home
  with logos (lit by their brightness so dark logos show on the glasses), scores (records before kickoff), the side
  with the ball and the red zone, the quarter and clock with down and distance, or kickoff and TV, or the final.
  Asked every 50 s around a game, else every 15 minutes, only while a dashboard shows the widget.

- **Keeping itself going** (`Reliability.kt`, Settings, *Troubleshooting*; nothing of it runs on the glasses):
  Vox's log (logcat of its own process, with a header of what it was doing) is shared, or sent to the phone's own
  server where Claude reads it (`~/vox-logs/latest.txt`, over SSH with the log on stdin); a crash keeps its log and
  sends it the next time Vox runs. The watchdog (every minute) checks the phone's SSH server's port and, when it
  stopped, starts it again through Termux (the user's `ssh` shortcut, sshd in the Ubuntu proot, where there is one;
  else Termux's sshd), at most every three minutes. *Check for an update* asks the project's release page (GitHub,
  no account) and *Install it* downloads the newer build and hands it to Android's package installer, which asks
  once and keeps the app's data (Vox needs "install unknown apps" for that, once).
- **Looking up, near the angle**: the arm reads the head again when its range is left; while the terminal shows, a
  reading back inside the hysteresis band keeps it up (the range written again) instead of hiding it.

## Voice

Commands by voice through the glasses' microphone (the right arm; liblc3 decodes it, `SCOUTER_G1_MIC`), recognised
on the phone, offline, by sherpa-onnx 1.13.8 (`native/third_party/fetch_sherpa.sh`; its Kotlin API in
`scouter_kit/third_party/sherpa_onnx_kotlin`) with NVIDIA's Parakeet TDT 0.6B v2 (English, int8). The Voice tab
downloads the model once (about 660 MB from Hugging Face and GitHub at pinned revisions, each file checked by
SHA-256, resumed where it stopped).

- **Listening**: push to talk (the right TouchBar's double tap by default, Alt+Space or Alt+V, the app's Listen
  buttons; again stops it), or always (Settings on the Voice tab: phrases must start with "Vox"). Silero VAD cuts
  phrases at half a second of quiet; *Realtime words* reads the phrase so far again every 0.6 s while it is spoken.
- **The band**: while voice is in use the terminal's top rows show what was heard and what it did, above a rule
  (the rows under it come back when it goes, a few seconds after; `Native.voiceBand`).
- **Commands**: the sources by name ("laptop screen", "phone terminal"), next, back, terminal, screen, display
  off / on, brighter, dimmer, always visible, "type …", "run …" and "claude …" (typed, the last two with Enter),
  Enter, Escape, Tab, Control C, Up, Down, dictation (everything said is typed; "send" is Enter), and the user's
  own phrases (Voice tab: a phrase and what it does).

### Car mode

Claude by voice in the car (`Car.kt`; Voice tab, *Car mode*: when the car connects, always, or off). It comes on
when a car's Bluetooth audio connects (Bluetooth class car audio or hands-free) and takes the car's media keys: a
media session of its own, which Android gives the keys once Vox has played a moment of silence (the last app to
start playing gets them). Vox plays it again half a second after any other player starts (an
`AudioPlaybackCallback`: Spotify resuming after an answer, a new track) and, on Android 13+, whenever Android says
the keys went elsewhere (if it lets Vox know).

Next listens on the phone's own microphone by default (*Listen with*; the glasses' battery spared: `PhoneMic`,
16 kHz, voice-recognition tuning), or the glasses' (Parakeet either way, loaded ahead when Car mode comes on); the
listening chime plays once the microphone hears, so nothing said is lost. In the back, Android lets an app hear
only through a microphone foreground service started while the app was in front: ScouterService adds that type
whenever Vox comes to the front with the microphone allowed (and drops it again if Android refuses it later). Next again sends (a chime at once; what was still being said is
read too). Nothing else ends a question but play / pause (sends) and two minutes. The words go, by default
(*Talk to: this Claude*), into the Claude session running in the server's tmux (the conversation the glasses show,
which goes on there): a small Python script on the server finds the pane running Claude, refuses while Claude
waits for an OK on its screen, types the words with a note that they came by voice from the car and asks for a
short spoken answer, and reads Claude's reply from that session's transcript (`~/.claude/projects/*/*.jsonl`: the
text after its last tool use, once the turn ends; four minutes at most). *A separate chat* uses
`claude -p --output-format json` instead (a spoken-style system prompt, web search and fetch allowed, its own
conversation resumed by session id until "new conversation").

The answer is read aloud through the car with the music paused (transient focus; it resumes after): in Kokoro's
voice once downloaded (Kokoro 82M v1.0, int8, about 150 MB from Hugging Face at a pinned revision, each file
checked by SHA-256, sherpa-onnx's `OfflineTts` made sentence by sentence and played as it comes; nine speakers to
choose from), else Android's text-to-speech (its best installed voice). Chimes, made in code: Car mode on, listening
(rising), sent (falling), a soft tick while Claude thinks, a low pair for a problem, a step down when nothing was
sent. Next while Claude speaks or thinks asks again; play / pause stops the answer or cancels; Previous says the
answer again. Idle, play / pause goes to the music app and Previous starts a new conversation: a new tmux tab in
front running a fresh `claude --model sonnet` (quicker for talk), where the next question goes (a chat: its
session forgotten). While Car mode is on the glasses' display stays off (driving): it goes dark when Car mode comes
on or the glasses connect, panels and the phone waking don't light it, nothing of Car mode's is drawn on them, and
it comes back when Car mode ends (only the user turning it on lights it meanwhile). The car's screen shows what Vox does, or what
plays.

## From the phone alone (Termux, the phone's screen)

No keyer needed: the main screen's buttons start a session that runs until Stop (also in the notification),
with the app in the back.

- **Termux on this phone, the easy way: `termux/scouter.sh`.** In Termux:
  `curl -fsSLO https://raw.githubusercontent.com/OWNER/REPO/BRANCH/examples/ssh/termux/scouter.sh && bash scouter.sh`
  (or copy the file over). It installs `openssh`, `mosh` and `tmux`, asks for the app's public key (Settings,
  Terminal server, "Copy the public key", then paste), starts `sshd` on 127.0.0.1:8022 (keys only) and prints what
  to enter in the app (or tap the "Termux on this phone" preset: tmux session `main`; `tmux attach -t main` in
  Termux is the same session). It installs itself as `scouter`: `scouter` starts the server again (after a reboot),
  `scouter key` lets another key in, `scouter status`, `scouter stop`, `scouter autostart on|off` (start it when
  Termux opens). Works with every Termux build, the Google Play one included.
- **Termux on this phone, from the app.** Settings, "Termux": allow "Run commands in Termux"
  (Termux needs `allow-external-apps = true` in `~/.termux/termux.properties`), then "Set up". It installs
  `openssh` and `mosh` in Termux if missing, adds this phone's public key to Termux's `authorized_keys`, starts
  `sshd` on 127.0.0.1:8022 only and fills in the server (127.0.0.1, 8022). Termux's host key fingerprints come
  from Termux itself, so the first session trusts them without asking. Before each session the app starts
  `sshd` again if it is not running. Command presets: a plain Termux shell, or Claude Code's tmux session in a
  proot-distro Ubuntu (`proot-distro login ubuntu -- bash -ic claude`). Type in Termux itself: it is the same
  tmux session.
- **"Terminal on glasses"**: the mosh session on the glasses; by default it shows whatever the head's angle
  ("Started from the phone" in Settings switches back to the looking-up rule), and the phone stays awake while
  it is live.
- **The phone's screen, the look and the place** (home screen, "Phone screen"): *B&W · fast* sends each frame as
  a VIDEO record (VM.md §3.7: only the changed rows, decoded on the arms, both eyes at one due time; frames wait
  while the link is busy) and is much smoother than 4, 8 or 16 greys (the gray display's uploads of changed
  pixels; fewer greys change fewer pixels). *Detail* sends the picture in 2 x 2 to 4 x 4 blocks, which encode
  much smaller; *Auto* sends every pixel when the record fits the frame budget (KB), coarser blocks while the
  screen moves, and every pixel again once it stops. The preview above these controls is the picture the glasses
  got (or, while they show nothing, the one they would get) with its size per frame and per second; it is a
  secure surface, left out of the screen sharing. *Check the glasses* reads each eye's panel back (CRC) against
  what was sent. *Fit*
  keeps the shape, *Fill* crops to fill, *Stretch* distorts to fill; the layout editor places the picture anywhere
  on the glasses (drag, corner to resize, double-tap to fill); zoom, pan, rotation (90° steps), contrast boost,
  sharpen and invert apply at once. *Depth* (the eyes' offset, dx) moves the picture nearer or farther.
  *Terminal: space at the top / bottom* (home screen) leaves pixels of the glasses' display empty above or below
  the terminal, where it is hard to see: the terminal gets fewer rows (the SSH session is resized to match) and
  starts again at once.
- **"Phone screen on glasses"**: Android's screen sharing (it asks every time); the screen goes on both eyes in
  the columns both eyes see, as 16 greys (or black and white), inverted by default since black is see-through.
  The whole screen with its shape kept, or a full-width band (Settings, "Phone screen on the glasses"). Turning
  the phone resizes the capture. Unchanged frames are not sent, and the rate drops by itself while the link is
  busy. In the case (the arms' IN_CASE property now, not only what they said when the session began) or folded
  the panels go off. Ending the screen sharing (Android's status bar, Stop, or
  "Terminal on glasses") brings the terminal back; the mosh session waits paused meanwhile.

## TV remote and low battery

- **Alt+Y** turns the keyboard into a Vizio SmartCast TV's remote (the TV is not one of the screens you switch between): arrows, Enter = OK, Backspace/Esc = Back, H = Home, +/- = volume, M = mute, Space = play/pause, I = input, Page Up/Down = channel, P = power. Alt+Y again gives the keyboard back; the glasses say which. Pair once in Settings, Troubleshooting, TV remote (Find the TV, Pair, type the PIN the TV shows). The TV's address and token stay in Vox's private settings on the phone. The same screen has the buttons for the phone.
- **Alt+B** is low battery: the glasses at 10% brightness, and again back to the level they had.
- **Alt+D** (or the **Preview** button on the main screen) is the preview mode: a live walk through the features on the glasses, the phone's screen only, each step flashing its name first (look-up display, hotkeys, terminal, the phone's screen, clicking a notification, music player, ESPN scores, low battery). It posts its own notification and starts Spotify if it is installed; what it changed is put back. Alt+D or Stop ends it. Off while driving.
- The preview opens each feature with a full-screen title card (noise decoding into big letters, a frame closing round them, a CRT collapse), adds the laptop's screen when one is set up, and clicks through every dashboard layout. The Alt-hold hotkey list has a cyberpunk look (banner, tags, scanlines).
