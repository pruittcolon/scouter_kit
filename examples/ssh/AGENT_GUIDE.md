# Vox: a guide for people and coding agents

Vox is an Android app that turns Even Realities G1 glasses into a **second screen** for your phone, your servers
and your day. It runs on top of the **ScouterKit** firmware (`g1v0`), which replaces the glasses' stock firmware.
This file says what Vox does, how it is put together, and how to wire a feature in. `README.md` beside it is the
detailed manual; this is the map.

> **Credit.** None of this exists without [ScouterKit](https://github.com/mafik/scouter_kit) by mafik: the
> alternative G1 firmware, the on-arm VM and protocol, the host libraries and the first SSH terminal demo. Vox is
> an app that stands on that work. [keyer](https://github.com/mafik/keyer) (also mafik's) is the optional
> key-stream device. Mosh, sherpa-onnx, NVIDIA Parakeet, Kokoro, Silero VAD, OpenSSL, JSch and others are listed in
> `NOTICE`. Vox is not affiliated with Even Realities, Vizio, Anthropic or Google.

## Read this first: limits and costs

- **Battery.** It is not light. The glasses draw more when the display is on, and every feature that keeps the
  Bluetooth link busy (a live phone screen, Tunes, a refreshed dashboard) costs more. Looking-up-only mode, a low
  brightness (Alt+B sets 10%) and the plain terminal are the gentle settings. The phone pays too (a foreground
  service, screen sharing, speech models). No battery figures are published here: measure on your own glasses.
- **Frame rate.** The glasses are a 640 x 480-ish monochrome-green display over Bluetooth LE. The phone's screen
  and a laptop's screen are **slideshows, not video**: the default is 5 frames a second (the setting goes to 30,
  and the real rate is set by how much changes and how busy the link is). Good for reading, tapping, a map or a
  chat; not for games or video. The firmware's VM can play video from a host with a good link; Vox does not try.
- **Permissions.** A lot, and each has a reason (table below). Nothing is sent to a Vox server: there is none.
  Everything stays on the phone, except what you point it at (your SSH server, ESPN's public scores, a weather
  service, GitHub for updates).
- **It replaces the glasses' firmware.** Flashing `g1v0` is ScouterKit's job (see that project), it is not
  officially supported by Even, and it can brick glasses. Do it deliberately.
- **Hotkeys need a keyboard.** A Bluetooth keyboard (or keyer) paired to the phone. The Alt hotkeys work through
  an Accessibility service you turn on once.

## What it does

| Area | What you get | Where it lives |
|---|---|---|
| Terminal | A mosh/SSH terminal on the glasses (tmux tabs, scrolling, pointer), also the phone's own Termux | `ScouterService`, `native/terminal.cpp`, `Tabs.kt`, `Termux.kt`, `Bootstrap.kt` |
| Look-up display | Shows when you look up past an angle, hides when you look down (hysteresis, 1 s hold); "Always on" / heads-up toggle (Alt+0 or Alt+Z) | `native/terminal.cpp` (VM program), `LookUp.kt`, `Looks.kt` |
| Dashboard | Time, date, batteries, notifications, now playing, weather, calendar, timer, NFL game score, Tunes; styles and a layout editor | `Dashboard.kt`, `Widgets.kt`, `Media.kt` |
| Phone screen | The phone's screen on the glasses with a cursor; the trackpad is a finger at it (tap, long press, drag, two-finger slide both ways with fling) | `PhoneScreen.kt`, `Hotkeys.kt` (VoxKeys), `Finger.kt`, `native/screen.cpp` |
| Laptop screen | A server's screen over SSH (`vox-screen`); the keyboard and trackpad work it (Python + XTEST sent over SSH, X11 only) | `RemoteScreen.kt`, `RemoteInput.kt`, `go/examples/screen` |
| Switching | Alt+arrows / Alt+1..9 between sources, with transitions (CRT, data stream, cut) kept fast | `native/fx.cpp`, `Fx.kt`, `ScouterService.choose` |
| Hotkeys | Hold Alt for the list; Alt+Z, A, S, B, Y, X, C, P, U, M, K, T, W, Q... | `Hotkeys.kt`, `Panels.kt` |
| Voice | Offline voice commands (Parakeet via sherpa-onnx); voice mode (Alt+V) types what is said into the terminal, with a strip at the bottom | `Voice.kt`, `ScouterService.voiceModeSet` |
| Car mode | In a car (Bluetooth), the Next/Previous/Play keys talk to Claude on your server, the answer is spoken (Kokoro voice or Android TTS), nothing is shown on the glasses | `Car.kt` |
| Captions / translation | Alt+E live captions (Parakeet), Alt+I translation into English (Whisper small), phone microphone | `ScouterService.captions*`, `Voice.kt` (`TranslateModel`, `TranslateEngine`) |
| Smart home | Alt+O: Vizio, Roku (ECP), Home Assistant entities | `Home.kt`, `ScouterService.home*` |
| Calls and texts | Alt+J: starred contacts, call, text typed or said | `People.kt`, `ScouterService.people*` |
| Hub | Alt+G: timer, stopwatch, alarm (the phone's clock), reminders, notes, the next events, on the glasses | `Hub.kt`, `Panels.hubLines`, `ScouterService.hub*` |
| Listen mode | All-day transcript from the phone mic: shared mic, disk queue, Live/Eco, soft-voice hearing, check, day files, glasses panel | `Listen.kt`, `ListenService.kt`, `MicHub.kt`, `ListenDb.kt`, `Hearing.kt`, `ListenActivity.kt` |
| Automations, live view | Rules on each new line (words/who/mood → glasses, notify, buzz, note, reminder, ask LFM, Vox command, webhook); VOX//LIVE | `Automations.kt`, `ScouterService.listen*` |
| Meetings, tone, words | Meeting mode (calendar start, bookmarks, action items, catch-up), LLM notes file; SenseVoice tone; talk stats | `Meetings.kt`, `Words.kt` |
| Ask chat, reading in parts | Chat UI, scopes, map/combine/answer/check with progress and ETA, Listen paused while a model works | `Think.kt`, `Llm.hold`, `ListenActivity` (Ask) |
| Search and ask | llama.cpp (vox_llm) with LFM2.5 (quick/deep) and EmbeddingGemma; FTS4 BM25 + vectors fused (RRF); time words | `Search.kt`, `Llm.kt`, `native/llm_jni.cpp`, `native/third_party/fetch_llama.sh` |
| Who speaks | Voiceprints (WeSpeaker via sherpa), identifier ported from Vox Amelior, voice groups, enrollment, naming; speaker-change cuts | `Speakers.kt`, `Turns.kt`, `ListenDb` (people, samples, voices) |
| Text editor | Alt+N (E on the hub): .txt/.md files in the app's external files dir `docs/`, list + wrapped editor, autosave | `Editor.kt`, `ScouterService.editor*` |
| TV remote | A Vizio SmartCast TV's remote: Alt+Y turns the keyboard into it and shows the remote on the glasses; the app's TV tab has the buttons. Alt+F runs the phone's `phone` mirror command in a tmux tab | `Vizio.kt`, `TvPage.kt`, `Panels.tvLines`, `Tabs.tv` |
| Preview mode | Alt+D / Preview button: a short live tour (phone screen, phone terminal, laptop screen, laptop terminal), each after a full-screen title card | `ScouterService.demoStart`, `Intro.kt` |
| Low battery | Alt+B: 10% brightness, again to restore | `ScouterService.lowBattery` |
| Charging case log | The case's USB-C serial output, per-arm charge state | `CaseLogActivity.kt` |
| Reliability | Logs sent to your server, crash logs, server keep-alive, in-app updater | `Reliability.kt` |

## Architecture in one page

```
keyer / Bluetooth keyboard / trackpad ──► VoxKeys (Accessibility) ─► Hotkeys.kt ─► ScouterService (ACTION_*)
                                                                                       │
Phone UI (MainActivity, SettingsActivity, TvActivity) ───── Settings.kt (prefs) ◄──────┤
                                                                                       ▼
                          Native.kt (JNI) ─► native/*.cpp ─► BLE ─► left + right arm (g1v0 VM runs the screens)
```

- **`ScouterService`** is the hub: one foreground service that owns the glasses link, the active source, the panel
  overlay, car mode and the hotkey actions. Work runs on its `work` handler; UI state is in the `Session` object.
- **`native/`** (C++ via JNI): the terminal and screen programs sent to the arms' VM, key translation
  (`keys.cpp`), transitions (`fx.cpp`), Tunes, and the mosh client. Host tests: `native/tests` (run with CMake +
  ctest, they use the emulated arms; no glasses needed).
- **Two build flavours.** `glasses` (real G1) and `emulated` (two emulated arms in the app, no hardware).
- **Package name** is `scouterkit.ssh` (JNI names depend on it); the application ID in this fork is
  `io.github.pruittcolon.scouter`. Change the ID in `app/build.gradle.kts` for your own fork.

## Build, test, release

```
native/third_party/build.sh                 # OpenSSL, protobuf, mosh at pinned SHA-256
./gradlew assembleGlassesDebug              # or assembleEmulatedDebug
cmake -S native -B BUILD -G Ninja && cmake --build BUILD && ctest --test-dir BUILD
```

CI (`.github/workflows/ssh-android.yml`) builds both APKs on every push and publishes them to a rolling release
tag, `scouter-phone-latest`. Signing: repository secrets `SCOUTER_KEYSTORE_B64` and `SCOUTER_KEYSTORE_PASSWORD`;
without them CI makes one key and caches it (updates then keep working as long as the cache lives). **For your own
fork**, change `API` in `Reliability.kt` (`Updater`) to your repo, or the in-app updater will look at the
original author's releases.

## Wiring a feature in (recipes)

**A hotkey.**
1. `Hotkeys.kt`, in `shortcut()`: add the key to the `panel = when (e.keyCode)` list (it maps a key to a string).
2. `ScouterService.hotkey(what)`: handle the string (`what == "lowbat" -> lowBattery()`).
3. `Panels.GROUPS`: add the line so the Alt-hold help shows it.
4. Give feedback with `note("...")`: a one-line message on the glasses.
Alt+T is taken (new tab); free letters are limited, so check `Panels.GROUPS` first.

**A mode that takes over the keyboard** (like the TV remote, Alt+Y). Set a flag in `Session`, early in
`Hotkeys.shortcut()` map keys while the flag is on and return `true` to consume them, and keep Alt shortcuts
working so the mode can be left. See `Session.tvKeys`.

**A dashboard widget.** Add an id in `Dashboard.kt`, a fetch/refresh object in `Widgets.kt` (see `Sports`:
ESPN's public scoreboard, no key), a draw function in `Dashboard.kt`, and include it in the layout editor's list.
Network calls go off the main thread and refresh on a timer that slows down when nothing is happening.

**A source** (something to show on the glasses besides the terminal): add it in `Settings.sources()` and handle it
in `ScouterService.source()/render()`. Sources appear in the Alt+arrows grid automatically.

**A Settings screen.** `SettingsActivity` builds with the helpers in `Ui.kt` (`card`, `button`, `field`, `chip`,
`segmented`, `slider`). New standalone screens (like `TvActivity`) need a manifest entry.

**A persisted value.** `Settings.kt`: `var x: Type by Text("key", default)` or `Number(...)`; booleans use
`prefs.getBoolean`. Secrets (an SSH key) go through `Vault.kt` (Android Keystore); small tokens such as the TV's
live in private preferences.

**A voice command.** `VoiceCommands` in `Voice.kt` (phrase list to an action), then handle the action in
`ScouterService`.

**A native/VM change.** Edit `native/*.cpp`, then run the host tests. The VM programs are written with a small
assembler in C++ (`p.Bge`, `p.Call`...). Mind the arm's compute: anything per frame costs battery.

## Feature notes

**Hotkeys** (hold Alt by itself to see them on the glasses): Alt+arrows / 1..9 screens, Alt+0 (or Z) heads-up / always on, Alt+L display off/on,
Alt+= / - brightness, Alt+B low battery, Alt+S quick settings, Alt+A help, Alt+P / , / . music,
Alt+Space voice command, Alt+V voice mode (talk to the terminal), Alt+Y TV remote, Alt+X keyboard off/on, Alt+K keys to phone/glasses, Alt+M pointer, Alt+T / [ / ]
/ W tabs, Alt+C share the phone screen (it taps Android's Start button for you, and retries while the dialog
loads), Alt+Q close, Alt+U Tunes, Alt+Backspace / H / R / N phone Back / Home / Recents / Notifications.
Ctrl+Left returns to the terminal from a screen (a laptop's screen to that laptop's terminal); Ctrl+Backspace deletes a
word in the terminal. Alt+D runs the preview. The glasses' terminal font lacks some symbols (e.g. U+25B8): check
a character against the font's cmap before using it in panels or cards.

**Look-up display.** The arms' VM watches the head pitch. It shows above the angle and hides below it minus 5 degrees
after a hold, so it does not flicker at the edge. This runs on the glasses, not the phone, and costs almost nothing.
"Always on" (Alt+Z) is a force flag; if the phone link drops the arm suspends it and restores it when the link
returns.

**TV remote (Alt+Y).** Vizio SmartCast over HTTPS port 7345 with its self-signed certificate (accepted for that one
connection). Pair once in Settings, TV remote: Find (scans the Wi-Fi interface's whole network, up to a /22, for
the port), Pair, type the PIN the TV shows. The address and token stay on the phone; a key that gets no answer
scans again and keeps the TV that accepts the token (at most once a minute). Five idle minutes end the mode. The TV is deliberately **not** one of the screens you switch
between; it is only reachable through the Alt+Y hotkey (and the buttons in Settings). Keys: arrows, Enter = OK,
Backspace/Esc = Back, H Home, +/- volume, M mute, Space play/pause, I input, Page Up/Down channel, P power.
Power on a TV that is off in Eco mode (no answer on 7345): Wake-on-LAN to the MACs learned while it was on
(`Vizio.learn`, `Settings.tvMac`), then POWER_ON (never the toggle) until it answers, at most 25 s.

**Car mode.** When the phone connects to a car (Bluetooth profile), the media keys become a voice interface to
Claude: Next starts listening, Next again sends; Play/Pause cancels or quiets; Previous repeats the answer, or
(when idle) opens a new conversation in a new Claude tab on Sonnet, behind the tab in front. The model is set
with ANTHROPIC_MODEL for that process only (`claude --model` can become the saved default and switch the user's own
Claude), and the saved default is restored if it changed. The phone's microphone is used by default
(the glasses' microphone costs their battery). The answer is spoken one sentence at a time (Kokoro downloaded on
request, or Android TTS). It talks to the same tmux Claude session your terminal uses, so it continues that
conversation. The glasses stay dark while you drive.

**Game score.** ESPN's public scoreboard API, no account or key; pick the team's letters in Settings.

**Reliability.** The log can be sent to your server (`~/vox-logs/latest.txt`) so an agent can read it; crashes are
sent automatically on the next start; the watchdog restarts the phone's SSH server; Settings can check GitHub for a
newer build and install it.

## Permissions and why

| Permission / access | Why |
|---|---|
| Bluetooth (connect, scan), Companion device | Talk to the two arms (and keyer) |
| Foreground service (connected device, media projection, microphone) | Keep the link alive; share the phone's screen; listen for car mode and voice |
| Accessibility service (Vox keys) | Hotkeys from any app, taps and swipes for the phone screen, tapping Android's "Start" for screen sharing |
| Notification listener | Notifications on the dashboard; now-playing |
| Microphone | Voice commands and car mode (offline speech recognition) |
| Calendar (read) | The dashboard's next event |
| Internet / network state | SSH/mosh, weather, scores, TV on your Wi-Fi, updates, model downloads |
| Install unknown apps | The in-app updater |
| Battery-optimisation exemption, boot | Keep the session alive |
| Termux run-command (optional) | Start the phone's own SSH server |

## Privacy model

No accounts, no analytics, no servers of ours. SSH keys are encrypted with Android's keystore. Voice recognition
and the Kokoro voice run on the phone; their models are downloaded once from public hosts and checked by SHA-256.
The log you can send goes only to your own server. The TV pairing token and address stay in private preferences.
Do not commit logs, keystores, tokens or addresses: see `.gitignore`.

## Conventions

- Kotlin type-check without the SDK build: any Kotlin 2.x with `android.jar` on the classpath is enough for a
  quick compile of `app/src/main/kotlin`.
- Keep per-frame work off the glasses and on the phone; never poll on a short timer when a callback exists.
- Every new hotkey goes into `Panels.GROUPS` and the README.
- Don't add personal data to the repo (addresses, device identifiers, usernames, home paths).
