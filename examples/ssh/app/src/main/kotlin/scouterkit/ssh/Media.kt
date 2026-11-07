package scouterkit.ssh

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

// What plays on the phone (Spotify, YouTube, a podcast app, any app with a media session), for the dashboard's
// now-playing widget and its buttons. Android lists the sessions to Vox's notification listener (the access the
// dashboard's notifications already need); none without it.
object Media {
    class Now(val app: String, val title: String, val artist: String, val playing: Boolean, val position: Long,
              val at: Long, val speed: Float, val duration: Long, val art: Bitmap?) {
        // Where it is now (ms), counting on from the last report while it plays.
        fun now(): Long {
            if (!playing || position < 0) return position.coerceAtLeast(0)
            val p = position + ((SystemClock.elapsedRealtime() - at) * speed).toLong()
            return if (duration > 0) p.coerceAtMost(duration) else p
        }
    }

    @Volatile var now: Now? = null
    @Volatile var onChange: (() -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var appContext: Context? = null
    private var artFor: String? = null
    private var art: Bitmap? = null

    private val sessions = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list ?: emptyList()) }
    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = read()
        override fun onMetadataChanged(metadata: MediaMetadata?) = read()
        override fun onSessionDestroyed() = refresh()
    }

    // Watching starts (again: after notification access was given); quiet without the access.
    fun start(context: Context) = main.post {
        appContext = context.applicationContext
        if (manager != null) return@post refresh()
        val m = context.getSystemService(MediaSessionManager::class.java) ?: return@post
        val me = ComponentName(context, VoxNotifications::class.java)
        try {
            m.addOnActiveSessionsChangedListener(sessions, me, main)
            manager = m
            pick(m.getActiveSessions(me))
        } catch (e: SecurityException) {
            manager = null // no notification access yet
        }
    }

    fun stop() = main.post {
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessions) }
        manager = null
        controller?.unregisterCallback(callback)
        controller = null
        now = null
    }

    private fun refresh() {
        val c = appContext ?: return
        val m = manager ?: return
        runCatching { pick(m.getActiveSessions(ComponentName(c, VoxNotifications::class.java))) }
    }

    // The session to show: one that plays, else the first (Android lists the most recent first).
    private fun pick(list: List<MediaController>) {
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        read()
    }

    private fun read() {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState
        if (c == null || (meta == null && state == null)) {
            now = null
            onChange?.invoke()
            return
        }
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        val key = "${c.packageName}|$title|$artist"
        if (key != artFor) { // the cover, small, once per track
            artFor = key
            val big = meta?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: meta?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            art = big?.let { runCatching { Bitmap.createScaledBitmap(it, 96, 96, true) }.getOrNull() }
        }
        val label = runCatching {
            val pm = appContext!!.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        now = Now(label, title, artist, state?.state == PlaybackState.STATE_PLAYING, state?.position ?: -1,
            state?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(), state?.playbackSpeed ?: 1f,
            meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L, art)
        onChange?.invoke()
    }

    // A button: "toggle" (play / pause), "next", "prev". False: nothing plays.
    fun control(what: String): Boolean {
        val t = controller?.transportControls ?: return false
        when (what) {
            "toggle" -> if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "prev" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }
}
