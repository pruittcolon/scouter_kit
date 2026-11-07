# Vox (formerly SSH Scouter)

An Android app that keeps a [mosh](https://mosh.org) session to your server and mirrors its terminal on the
Even Realities G1 running the ScouterKit firmware, with keys from
[keyer](https://github.com/mafik/keyer) over its key-stream service. GPL-3.0 (`LICENSE`), because it
includes mosh; third-party notices in `NOTICE` and `licenses/`.

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

The main screen has tabs: **Home** (one button per source, the display, brightness, shortcuts), **Screen**
(the preview of what the glasses get, size and speed, place, look), **Terminal** (its preview, its place on the
glasses, the saved servers) and **Settings**.

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
  for is on the Screen tab (640, 960, 1280).

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
- **The header** instead, as text lines (*Graphical dashboard* off; `Native.lookUpHeader`): the time, the date and the glasses' batteries, and
  the newest phone notification (a `NotificationListenerService`, once the user allows Notification access),
  then a rule. The SSH session gets as many rows fewer; the voice band takes the header's rows while voice is in
  use. It is redrawn each minute and when notifications or the batteries change.

## The pointer and tabs

- **The pointer**: a cell of the terminal shown inverted (`Native.pointer`), moved by a keyboard paired with the
  phone (Alt+M on, then arrows, Shift for 5 cells, Enter or Space to click, Shift+Enter a right click, Page Up /
  Down to scroll, Escape off), by the Terminal tab's touchpad (drag, tap, hold, two fingers) or by voice. Clicks
  and the wheel go to the session's program as xterm mouse reports in the encoding it asked for (SGR, urxvt or
  X10; `Native.mouse`), and only while it asked for them (mosh's mouse mode): otherwise they would be typed.
- **Tabs** are the windows of the tmux session the terminal shows (its command's `-s NAME`, or `claude` for the
  phone's Claude preset), driven over a second SSH connection (`Bootstrap.run`, `Tabs.kt`): Alt+T a new shell,
  Alt+[ / ] the tab before / after, Alt+W closes the tab shown (never the first), and Claude's or Cursor's tab
  (switched to where one runs, else opened). tmux's own status line shows them; with *Mouse in tmux* (on by
  default, `tmux set -g mouse on` when the pointer starts) clicking a tab there switches to it.
- **The dashboard's tab bar**: up from the terminal's top row the pointer goes onto it (the tab or + it is on
  gets a bright ring and a notch, `Dashboard.Hit`); left and right go a tab at a time, a click shows that tab
  (the + opens another), the wheel goes to the tab before or after, and down goes back into the terminal under it.

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
