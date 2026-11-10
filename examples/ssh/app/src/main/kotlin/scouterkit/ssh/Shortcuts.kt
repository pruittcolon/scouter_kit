package scouterkit.ssh

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// Shortcuts to the sources (Settings.sources): the launcher's (long-press Vox), pinned ones on the home screen,
// and the Quick Settings tile "Next source". Each runs through ShortcutActivity.
object Shortcuts {
    const val ACTION = "scouterkit.ssh.SHORTCUT"
    const val EXTRA = "source"
    const val DISPLAY = "display" // the display off / on
    const val NEXT = Settings.ACTION_NEXT

    // The launcher's list: the sources first, then the display.
    fun update(context: Context) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val max = manager.maxShortcutCountPerActivity.coerceIn(1, 5)
            val list = Settings(context).sources().take(max - 1).mapIndexed { i, (key, name) -> shortcut(context, key, name, i) } +
                shortcut(context, DISPLAY, "Display off / on", max)
            manager.setDynamicShortcuts(list)
        }
    }

    // On the home screen (the launcher asks first); false where pinning is not supported.
    fun pin(context: Context, key: String, name: String): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported) return false
        return runCatching { manager.requestPinShortcut(shortcut(context, key, name, 0), null) }.getOrDefault(false)
    }

    fun intent(context: Context, key: String): Intent = Intent(context, ShortcutActivity::class.java)
        .setAction(ACTION)
        .putExtra(EXTRA, key)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    private fun shortcut(context: Context, key: String, name: String, rank: Int): ShortcutInfo =
        ShortcutInfo.Builder(context, "src-$key")
            .setShortLabel(name.take(24))
            .setLongLabel(name)
            .setIcon(Icon.createWithResource(context, icon(key)))
            .setIntent(intent(context, key))
            .setRank(rank)
            .build()

    private fun icon(key: String) = when {
        key.startsWith("term:") -> R.drawable.ic_sc_terminal
        key == Settings.PHONE -> R.drawable.ic_sc_phone
        key.startsWith("screen:") -> R.drawable.ic_sc_laptop
        key == NEXT -> R.drawable.ic_sc_next
        else -> R.drawable.ic_sc_display
    }
}

// A shortcut's tap: the source (or the display) through the service, with no screen of its own. The phone's
// screen, while it is not shared yet, opens the app, where Android asks.
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getStringExtra(Shortcuts.EXTRA).orEmpty()
        val settings = Settings(this)
        when {
            key.isEmpty() -> {}
            key == Shortcuts.DISPLAY -> ScouterService.send(this, ScouterService.ACTION_DISPLAY)
            key == Settings.PHONE && !(Session.capturing && ScouterService.running) ->
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHARE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> {
                if (settings.left.isNotEmpty() && settings.right.isNotEmpty()) settings.glassesAllowed = true
                ScouterService.send(this, ScouterService.ACTION_SOURCE, Shortcuts.EXTRA to key)
            }
        }
        finish()
    }
}

// Quick Settings: the next source on the glasses (the one showing as the subtitle).
class SourceTile : TileService() {
    override fun onStartListening() = paint()

    override fun onClick() {
        val settings = Settings(this)
        if (settings.left.isNotEmpty() && settings.right.isNotEmpty()) settings.glassesAllowed = true
        ScouterService.send(this, ScouterService.ACTION_SOURCE, Shortcuts.EXTRA to Shortcuts.NEXT)
        paint()
    }

    private fun paint() {
        val tile = qsTile ?: return
        val running = ScouterService.running && Session.showing.isNotEmpty()
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Next source"
        tile.subtitle = if (running) Session.source.ifEmpty { "Vox" } else "Vox"
        tile.updateTile()
    }

    companion object {
        fun refresh(context: Context) {
            runCatching {
                TileService.requestListeningState(context, android.content.ComponentName(context, SourceTile::class.java))
            }
        }
    }
}
