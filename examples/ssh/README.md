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

## Sources and switching

The main screen has tabs: **Home** (one button per source, the display, brightness, shortcuts), **Screen**
(the preview of what the glasses get, size and speed, place, look), **Terminal** (its preview, its place on the
glasses, the saved servers) and **Settings**.

- **Sources**, in order: each saved server's terminal, the phone's screen, and each other server's screen. One
  tap on Home switches; the launcher's shortcuts (long-press Vox), pinned home-screen shortcuts, the Quick
  Settings tile "Next source" and the TouchBar (by default: left hold next, left double tap previous, right hold
  display off / on) do the same from anywhere. The phone's screen needs Android's consent once per sharing, in
  the app.
- **Saved servers** (Terminal tab): name, host, port, user, command. The one in use is what Settings, "Server in
  use" edits. Host keys are trusted per server (host:port), so switching does not ask again. A link
  `vox://server?name=Laptop&host=HOST&port=22&user=USER&command=...` (e.g. `adb shell am start -d '...'`) adds or
  updates one after the app asks.
- **A server's screen** (a laptop's): over SSH, with the same key, the app runs `vox-screen WIDTH HEIGHT FPS`
  there, which must write baseline JPEG images one after another to stdout (each the whole desktop inside
  WIDTH x HEIGHT) until stdout closes. They go through the same pipeline as the phone's screen. The width asked
  for is on the Screen tab (640, 960, 1280).

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
