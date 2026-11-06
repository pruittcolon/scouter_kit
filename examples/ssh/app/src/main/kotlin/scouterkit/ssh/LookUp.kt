package scouterkit.ssh

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The look-up view's header above the terminal (Native.lookUpHeader): the time, the date and the glasses'
// batteries on its first line, the newest phone notification on its second (Settings.lookUpClock,
// lookUpNotifications), then a rule.
object LookUp {
    class Note(val app: String, val title: String, val text: String, val posted: Long)

    // The phone's notifications, newest first (VoxNotifications), and who to tell when they change.
    @Volatile var notes: List<Note> = emptyList()
    @Volatile var onChange: (() -> Unit)? = null

    fun lines(context: Context, settings: Settings): Array<String> {
        if (settings.dashRows > 0) return emptyArray() // the graphical dashboard instead
        val cols = settings.cols
        val out = mutableListOf<String>()
        if (settings.lookUpClock) {
            val now = Date()
            val time = SimpleDateFormat(if (android.text.format.DateFormat.is24HourFormat(context)) "H:mm" else "h:mm a",
                Locale.getDefault()).format(now)
            val date = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now)
            val b = Session.battery
            val batt = listOfNotNull(b.getOrNull(0)?.takeIf { it >= 0 }?.let { "L $it%" },
                b.getOrNull(1)?.takeIf { it >= 0 }?.let { "R $it%" }).joinToString("  ")
            val count = if (settings.lookUpNotifications && notes.isNotEmpty()) " ${notes.size}  " else ""
            out += fit(" $time  $date", "$count$batt ", cols)
        }
        if (settings.lookUpNotifications) {
            val n = notes.firstOrNull()
            out += " " + clip(if (n == null) "No new notifications" else listOf(n.app, n.title, n.text)
                .filter { it.isNotBlank() }.joinToString(" · "), cols - 2)
        }
        return out.toTypedArray()
    }

    // The header's rows on the glasses (its lines and the rule; 0 without a header).
    fun rows(settings: Settings): Int {
        if (settings.dashRows > 0) return 0
        val lines = (if (settings.lookUpClock) 1 else 0) + (if (settings.lookUpNotifications) 1 else 0)
        return if (lines == 0) 0 else lines + 1
    }

    // left, then right at the line's end.
    private fun fit(left: String, right: String, cols: Int): String {
        val room = cols - left.length - right.length
        return if (room >= 1) left + " ".repeat(room) + right else clip(left, cols)
    }

    private fun clip(s: String, n: Int): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= n) one else one.take(maxOf(0, n - 1)) + "…"
    }

    // Whether the user has let Vox read notifications (Android's Notification access).
    fun allowed(context: Context): Boolean {
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            ?: return false
        val me = ComponentName(context, VoxNotifications::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

// The phone's notifications for the look-up header: the clearable ones from other apps, newest first (no
// ongoing ones such as music or this app's own). Only while the user allows it (Notification access).
class VoxNotifications : NotificationListenerService() {
    companion object {
        val QUIET = setOf(Notification.CATEGORY_SERVICE, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_STATUS, Notification.CATEGORY_SYSTEM, Notification.CATEGORY_NAVIGATION,
            Notification.CATEGORY_STOPWATCH, Notification.CATEGORY_LOCATION_SHARING)
    }

    override fun onListenerConnected() {
        refresh()
        // After an update or the phone's start Android binds this listener, and may not tell the app it was
        // replaced (BluetoothSwitch): Vox starts by itself (Settings.autoStart) from here as well.
        if (!ScouterService.running && Settings(this).startsByItself())
            runCatching { ScouterService.send(this) }.onFailure { android.util.Log.w(ScouterService.TAG, "start: ${it.message}") }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    override fun onListenerDisconnected() {
        LookUp.notes = emptyList()
        LookUp.onChange?.invoke()
    }

    private fun refresh() {
        val all = runCatching { activeNotifications }.getOrNull() ?: return
        val ranking = runCatching { currentRanking }.getOrNull()
        LookUp.notes = all.filter { keep(it, ranking) }.sortedByDescending { it.postTime }.map { sbn ->
            val e = sbn.notification.extras
            LookUp.Note(label(sbn.packageName), e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                e.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), sbn.postTime)
        }.filter { it.title.isNotBlank() || it.text.isNotBlank() }
        LookUp.onChange?.invoke()
    }

    // What a person would call a notification: from another app, clearable, not ongoing, a service's, media's,
    // a progress bar, navigation or the system's, and not silent or minimised (its channel below default
    // importance: Samsung's "ongoing activity" and media notices are).
    private fun keep(sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?): Boolean {
        val n = sbn.notification
        val noise = Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_FOREGROUND_SERVICE or
            Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR
        if (sbn.packageName == packageName || sbn.packageName == "android" || sbn.packageName == "com.android.systemui") return false
        if (sbn.isOngoing || !sbn.isClearable || (n.flags and noise) != 0) return false
        if (n.category in QUIET || n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        val r = NotificationListenerService.Ranking()
        if (ranking != null && ranking.getRanking(sbn.key, r) && r.importance < android.app.NotificationManager.IMPORTANCE_DEFAULT)
            return false
        return true
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrElse { pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() } }
}
