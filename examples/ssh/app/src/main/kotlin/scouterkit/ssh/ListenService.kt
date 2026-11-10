package scouterkit.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

// Listen mode's foreground service (a microphone service): keeps Listen running all day and says so in its
// notification (Pause 15 min, Stop). Android lets a microphone service start only while Vox is on the screen (or from
// its notification); started otherwise (after a reboot or an update) it puts up "Tap to keep listening" instead.
class ListenService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var lastNote = 0L
    private val watcher: () -> Unit = { main.post { noteSoon() } }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val settings = Settings(this)
        when (intent?.action) {
            ACTION_STOP -> {
                settings.listenOn = false
                Listen.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> Listen.pause(15 * 60_000L)
            ACTION_RESUME -> Listen.resume()
        }
        if (!settings.listenOn) {
            stopSelf()
            return START_NOT_STICKY
        }
        channels(this)
        try {
            startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            Log.w(ScouterService.TAG, "listen: Android did not let the microphone service start now: ${e.message}")
            resumeNote(this)
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        getSystemService(NotificationManager::class.java).cancel(RESUME_NOTE)
        Listen.watch(watcher)
        Listen.start(this)
        noteNow()
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        Listen.unwatch(watcher)
        Listen.stop()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // The notification follows Listen, at most every 5 s.
    private fun noteSoon() {
        val wait = 5000 - (System.currentTimeMillis() - lastNote)
        main.removeCallbacksAndMessages(null)
        if (wait <= 0) noteNow() else main.postDelayed({ noteNow() }, wait)
    }

    private fun noteNow() {
        if (!running) return
        lastNote = System.currentTimeMillis()
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTE, note()) }
    }

    private fun note(): Notification {
        fun act(action: String, code: Int) = PendingIntent.getService(this, code,
            Intent(this, ListenService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 20, Intent(this, ListenActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = Listen.paused
        val title = if (paused) "Listen mode paused" else "Vox is listening"
        val text = "${Listen.today} lines today · " + if (paused) "back at " +
            java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(Listen.pausedUntil)) else Listen.state
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (paused) b.addAction(Notification.Action.Builder(null, "Listen again", act(ACTION_RESUME, 21)).build())
        else b.addAction(Notification.Action.Builder(null, "Pause 15 min", act(ACTION_PAUSE, 22)).build())
        b.addAction(Notification.Action.Builder(null, "Stop", act(ACTION_STOP, 23)).build())
        return b.build()
    }

    companion object {
        const val ACTION_STOP = "scouterkit.ssh.listen.STOP"
        const val ACTION_PAUSE = "scouterkit.ssh.listen.PAUSE"
        const val ACTION_RESUME = "scouterkit.ssh.listen.RESUME"
        private const val CHANNEL = "listen"
        private const val NOTE = 7301
        private const val RESUME_NOTE = 7302
        @Volatile var running = false
            private set

        private fun channels(c: Context) {
            val nm = c.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listen mode", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "While Vox listens all day; tap to see what it heard"
                })
        }

        // Listen mode on (it stays on until Stop), or started again.
        fun start(c: Context) {
            Settings(c).listenOn = true
            runCatching { c.startForegroundService(Intent(c, ListenService::class.java)) }.onFailure {
                Log.w(ScouterService.TAG, "listen: could not start: ${it.message}")
                resumeNote(c)
            }
        }

        fun stop(c: Context) {
            Settings(c).listenOn = false
            Listen.stop()
            if (running) runCatching { c.startService(Intent(c, ListenService::class.java).setAction(ACTION_STOP)) }
        }

        // Listen mode is on but its service could not start: a tap on this opens Vox, which starts it.
        fun resumeNote(c: Context) {
            channels(c)
            val open = PendingIntent.getActivity(c, 24, Intent(c, ListenActivity::class.java)
                .putExtra(ListenActivity.EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Tap to keep listening")
                .setContentText("Android needs Vox opened once to use the microphone again (after a restart or an update).")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { c.getSystemService(NotificationManager::class.java).notify(RESUME_NOTE, n) }
        }

        // Listen mode should be on: start it if it is not (Vox's screen opened, the glasses' service started).
        fun ensure(c: Context) {
            if (Settings(c).listenOn && !running) start(c)
        }
    }
}
