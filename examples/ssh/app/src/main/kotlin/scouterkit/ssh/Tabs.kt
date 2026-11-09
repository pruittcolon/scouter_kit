package scouterkit.ssh

// Tabs: the tmux windows of the session the terminal shows, driven over SSH beside mosh (Bootstrap.run): a new
// shell, Claude or Cursor (switched to when one runs already), the next and previous, one by number, closing
// the one shown (never the session's first), and tmux's mouse for the pointer.
object Tabs {
    class Tab(val index: Int, val name: String, val active: Boolean)

    // tmux list-windows' lines (index|name|active) as tabs.
    fun parse(out: String): List<Tab> = out.lines().mapNotNull { line ->
        val p = line.split("|")
        val index = p.getOrNull(0)?.trim()?.toIntOrNull() ?: return@mapNotNull null
        Tab(index, p.getOrElse(1) { "" }.trim(), p.getOrNull(2)?.trim() == "1")
    }

    // The tmux session a server's command attaches to: its "-s NAME", or "claude" for the phone's Claude preset
    // (bash -ic claude: tmux session claude); null without tmux.
    fun session(s: Settings.Server): String? =
        Regex("(?:^|\\s)-s\\s*(\\S+)").find(s.command)?.groupValues?.get(1)?.trim('\'', '"')
            ?: if (s.command.contains("claude")) "claude" else null

    // The window running a program whose name matches `pattern` (awk), else a new one named `name` running `cmd`.
    private fun findOrNew(t: String, pattern: String, name: String, cmd: String) =
        "w=$(tmux list-windows -t $t -F '#{window_index} #{pane_current_command}' | awk '$2 ~ /$pattern/ {print $1; exit}'); " +
            "if [ -n \"\$w\" ]; then tmux select-window -t $t:\$w; else tmux new-window -t $t: -n $name '$cmd'; fi"

    private const val PATH = "PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:\$PATH\"; export PATH; "

    // The phone's TV mirror (its `phone` command: Firefox by itself on the TV) in a tab named tv of `session`:
    // switched to when it is there, else opened; in a session "tv" of its own when `session` is not running.
    // Closing the tab stops the mirror (it ends on the hangup).
    fun tv(session: String): String {
        val t = "'" + session.replace("'", "") + "'"
        // Its full path: tmux starts the tab with tmux's own PATH, which may lack ~/.local/bin.
        return PATH + "p=\$(command -v phone || echo phone); if tmux has-session -t $t 2>/dev/null; then " +
            "w=$(tmux list-windows -t $t -F '#{window_index} #{window_name}' | awk '$2 == \"tv\" {print $1; exit}'); " +
            "if [ -n \"\$w\" ]; then tmux select-window -t $t:\$w; else tmux new-window -t $t: -n tv \"\$p\"; fi; " +
            "else tmux has-session -t tv 2>/dev/null || tmux new-session -d -s tv -n tv \"\$p\"; fi; echo ok"
    }

    fun tvStop(): String = PATH + "phone stop"

    // The shell command for an action ("new", "claude", "cursor", "next", "prev", "close", "close:N", "select:N",
    // "mouse", "mouse_off"), or null. Closing never closes the session's first tab.
    fun command(action: String, session: String): String? {
        val t = "'" + session.replace("'", "") + "'"
        val path = "PATH=\"\$HOME/.local/bin:\$HOME/bin:/usr/local/bin:\$PATH\"; export PATH; "
        return path + when {
            action == "new" -> "tmux new-window -t $t:"
            action == "claude" -> findOrNew(t, "claude", "claude", "claude")
            action == "cursor" -> findOrNew(t, "cursor|agent", "cursor", "cursor-agent || cursor")
            action == "next" -> "tmux next-window -t $t"
            action == "prev" -> "tmux previous-window -t $t"
            action == "close" -> "[ \"$(tmux display -p -t $t '#{window_index}')\" != 0 ] && tmux kill-window -t $t"
            action.startsWith("close:") -> action.removePrefix("close:").filter { it.isDigit() }.ifEmpty { return null }.let { n ->
                "[ \"$(tmux list-windows -t $t -F '#{window_index}' | head -n 1)\" != $n ] && tmux kill-window -t $t:$n"
            }
            action.startsWith("select:") -> "tmux select-window -t $t:${action.removePrefix("select:").filter { it.isDigit() }}"
            action == "list" -> "tmux list-windows -t $t -F '#{window_index}|#{window_name}|#{window_active}'"
            // Scrolling the pane's history N lines (copy mode, which ends by itself at the bottom).
            action.startsWith("scroll:up:") -> action.removePrefix("scroll:up:").filter { it.isDigit() }.ifEmpty { return null }.let { n ->
                "tmux copy-mode -e -t $t && tmux send-keys -t $t -X -N $n scroll-up"
            }
            action.startsWith("scroll:down:") -> action.removePrefix("scroll:down:").filter { it.isDigit() }.ifEmpty { return null }.let { n ->
                "[ \"$(tmux display -p -t $t '#{pane_in_mode}')\" = 1 ] && tmux send-keys -t $t -X -N $n scroll-down"
            }
            action == "mouse" -> "tmux set -g mouse on"
            action == "mouse_off" -> "tmux set -g mouse off"
            else -> return null
        }
    }
}
