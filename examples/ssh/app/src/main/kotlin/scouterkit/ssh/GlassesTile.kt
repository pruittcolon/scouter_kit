package scouterkit.ssh

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

// The Quick Settings tile: the glasses' display off / on while a session runs; otherwise it opens the app.
class GlassesTile : TileService() {
    override fun onStartListening() = paint()

    override fun onClick() {
        if (ScouterService.running && Session.showing.isNotEmpty()) {
            ScouterService.send(this, ScouterService.ACTION_DISPLAY)
            return
        }
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(open)
    }

    private fun paint() {
        val tile = qsTile ?: return
        val running = ScouterService.running && Session.showing.isNotEmpty()
        tile.state = if (running && !Session.blank) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Glasses"
        tile.subtitle = when {
            !running -> "Not running"
            Session.blank -> "Display off"
            Session.showing == Settings.SCREEN -> "Phone screen"
            else -> "Terminal"
        }
        tile.updateTile()
    }

    companion object {
        // The session changed: the tile repaints when Quick Settings shows it.
        fun refresh(context: Context) {
            runCatching { TileService.requestListeningState(context, ComponentName(context, GlassesTile::class.java)) }
        }
    }
}
