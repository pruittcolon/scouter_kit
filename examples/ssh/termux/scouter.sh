#!/data/data/com.termux/files/usr/bin/bash
# SSH Scouter's server on this phone, in Termux: the app (G1 glasses) connects to 127.0.0.1:8022 with its
# own key and shows a terminal from here on the glasses. Run it in Termux (F-Droid, GitHub or Google Play):
#
#   bash scouter.sh            set up the first time (packages, the app's key), then start the server
#
# It installs itself as the command `scouter`:
#   scouter                    start the server if it is not running, and show what to enter in the app
#   scouter key [LINE]         let another public key in (the app: Settings, Terminal server, Share the public key)
#   scouter status             is the server up, which keys are in
#   scouter stop               stop the server
#   scouter autostart on|off   start the server whenever Termux opens a shell (a marked line in ~/.bashrc)
#
# The server listens on 127.0.0.1 only (other devices cannot reach it) and takes keys only (no passwords).
# The glasses show the tmux session "main": in Termux, `tmux attach -t main` is the same session, so what you
# type here shows on the glasses.
set -u

PORT=8022
SESSION=main
PREFIX=${PREFIX:-/data/data/com.termux/files/usr}
KEYS=$HOME/.ssh/authorized_keys
MARK="# scouter: SSH Scouter's server"
SELF=$PREFIX/bin/scouter

say() { printf '\033[1;36m●\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m●\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m●\033[0m %s\n' "$*" >&2; exit 1; }

# Termux itself: its pkg, and not root (proot-distro's Linux runs as a fake root).
in_termux() { [ -x "$PREFIX/bin/pkg" ] && [ "$(id -u)" != 0 ]; }

listening() { (exec 3<>/dev/tcp/127.0.0.1/$PORT) 2>/dev/null; }

valid_key() { printf '%s' "$1" | grep -Eq '^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp(256|384|521)|sk-[a-z0-9@.-]+) [A-Za-z0-9+/=]+( [^ ]+)?$'; }

packages() {
  local missing=()
  command -v sshd >/dev/null || missing+=(openssh)
  command -v mosh-server >/dev/null || missing+=(mosh)
  command -v tmux >/dev/null || missing+=(tmux)
  [ ${#missing[@]} -eq 0 ] && return 0
  say "Installing ${missing[*]} (a minute the first time)"
  export DEBIAN_FRONTEND=noninteractive
  yes | pkg install -y "${missing[@]}" -o Dpkg::Options::=--force-confold >/dev/null 2>"$HOME/.scouter-install.log" \
    || fail "pkg install ${missing[*]} failed: see ~/.scouter-install.log (try: pkg update, then run this again)"
}

add_key() {
  local line=${1:-}
  if [ -z "$line" ]; then
    echo
    echo "  In the SSH Scouter app: Settings, Terminal server:"
    echo "    1. Create a key on this phone (once)"
    echo "    2. Share the public key, then Copy"
    echo "  Then paste it here (long-press, Paste) and press Enter:"
    printf '  > '
    IFS= read -r line
  fi
  line=$(printf '%s' "$line" | tr -d '\r' | sed 's/^ *//; s/ *$//')
  valid_key "$line" || fail "That is not a public key line (it starts with ecdsa-sha2-nistp256, ssh-ed25519 or ssh-rsa)."
  mkdir -p "$HOME/.ssh" && chmod 700 "$HOME/.ssh"
  touch "$KEYS" && chmod 600 "$KEYS"
  if grep -qxF "$line" "$KEYS"; then say "That key was already in."
  else printf '%s\n' "$line" >> "$KEYS" && say "Key added."; fi
}

start() {
  if listening; then say "The server is running on 127.0.0.1:$PORT."; return 0; fi
  # Android may pause Termux in the background; the wake lock (Termux's notification) keeps the server up.
  command -v termux-wake-lock >/dev/null && termux-wake-lock
  ssh-keygen -A >/dev/null 2>&1
  sshd -p "$PORT" -o ListenAddress=127.0.0.1 -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no \
    || fail "sshd did not start"
  for _ in 1 2 3 4 5 6 7 8 9 10; do listening && break; sleep 0.3; done
  listening && say "The server is running on 127.0.0.1:$PORT." || fail "sshd started but does not answer on $PORT"
}

stop() {
  if pkill -f "sshd -p $PORT" 2>/dev/null; then say "Server stopped."; else say "The server was not running."; fi
}

status() {
  if listening; then say "Server: running on 127.0.0.1:$PORT"; else warn "Server: not running (scouter starts it)"; fi
  local n=0
  [ -f "$KEYS" ] && n=$(grep -cE '^(ssh|ecdsa|sk)-' "$KEYS")
  say "Keys let in: $n ($KEYS)"
  tmux has-session -t "$SESSION" 2>/dev/null && say "tmux session \"$SESSION\": open" || say "tmux session \"$SESSION\": starts with the glasses"
}

app_values() {
  echo
  echo "  In the SSH Scouter app, Settings, Terminal server (or tap the \"Termux on this phone\" preset):"
  echo "    Host     127.0.0.1"
  echo "    Port     $PORT"
  echo "    User     $(whoami)"
  echo "    Command  tmux new -A -s $SESSION"
  echo "  Then on the home screen: Terminal. To type on the phone: tmux attach -t $SESSION"
  echo
}

autostart() {
  touch "$HOME/.bashrc"
  case "${1:-}" in
    on)
      grep -qF "$MARK" "$HOME/.bashrc" || printf '%s\n%s\n' "$MARK" "(scouter start >/dev/null 2>&1 &)" >> "$HOME/.bashrc"
      say "The server now starts whenever Termux opens a shell." ;;
    off)
      local tmp
      tmp=$(mktemp)
      awk -v m="$MARK" '$0 == m { skip = 1; next } skip { skip = 0; next } { print }' "$HOME/.bashrc" > "$tmp" && cat "$tmp" > "$HOME/.bashrc"
      rm -f "$tmp"
      say "Autostart off." ;;
    *) fail "scouter autostart on|off" ;;
  esac
}

install_self() {
  local src
  src=$(readlink -f "$0" 2>/dev/null || echo "$0")
  [ "$src" = "$SELF" ] && return 0
  cp "$src" "$SELF" && chmod 755 "$SELF" && say "Installed the command: scouter"
}

in_termux || fail "Run this in Termux itself (not inside proot-distro or another Linux)."

case "${1:-}" in
  "")
    packages
    install_self
    # A key the app made has the comment scouter-ssh-<phone model>; another one (an imported key) goes in with
    # `scouter key`.
    grep -qs ' scouter-ssh' "$KEYS" || add_key
    start
    app_values ;;
  start) start ;;
  key) packages; add_key "${2:-}"; start ;;
  stop) stop ;;
  status) status ;;
  autostart) autostart "${2:-}" ;;
  -h|--help|help) sed -n '2,19p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) fail "unknown command: $1 (scouter help)" ;;
esac
