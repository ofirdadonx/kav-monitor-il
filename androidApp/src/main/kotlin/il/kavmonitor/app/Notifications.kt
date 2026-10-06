package il.kavmonitor.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import il.kavmonitor.shared.model.TriggerType
import il.kavmonitor.shared.notify.NotificationTexts
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.util.israelClock
import il.kavmonitor.shared.watch.WatchEdits
import il.kavmonitor.shared.watch.WatchState

object Notifications {
    const val CHANNEL_STATUS = "watch_status"
    const val CHANNEL_ALERT = "bus_alert_v1"
    const val ID_STATUS = 1
    const val ID_FINISHED = 2
    private const val ID_ALERT_BASE = 1000
    private val VIBRATION = longArrayOf(0, 600, 250, 600, 250, 900)

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, context.getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = context.getString(R.string.channel_status_desc); setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERT, context.getString(R.string.channel_alert_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = context.getString(R.string.channel_alert_desc)
                enableVibration(true)
                vibrationPattern = VIBRATION
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
    }

    private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_WATCH, true),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * Ongoing watch notification. Texts come from the shared [NotificationTexts] (unit-tested: short, no codes/vehicle
     * numbers/seconds in the collapsed view); details only in the expanded BigText.
     */
    fun status(context: Context, spec: WatchSpec, state: WatchState?, now: kotlin.time.Instant = kotlin.time.Clock.System.now()): Notification {
        val txt = NotificationTexts.ongoing(spec, state, now)
        val stop = PendingIntent.getService(
            context, 1, WatchService.stopIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_STATUS)
        // time to leave: live countdown to the leave time in the header (no extra updates needed)
        val next = state?.takeIf { spec.trigger == TriggerType.LEAVE_FOR_STOP && it.lastError == null }?.nextLeaveBus
        val leaveAt = next?.leaveAt?.takeIf { next.leaveStatus == LeaveStatus.WAIT && it > now }
        if (leaveAt != null) {
            b.setWhen(leaveAt.toEpochMilliseconds()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            b.setShowWhen(false)
        }
        return b
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(txt.title)
            .setContentText(txt.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(txt.body + "\n" + txt.big))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notif_action_stop), stop)
            .also { nb ->
                if (WatchEdits.canExtend(spec)) {
                    val extend = PendingIntent.getService(
                        context, 2, WatchService.extendIntent(context, 15), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    nb.addAction(0, context.getString(R.string.notif_action_extend), extend)
                }
            }
            .build()
    }

    fun updateStatus(context: Context, spec: WatchSpec, state: WatchState, now: kotlin.time.Instant = kotlin.time.Clock.System.now()) =
        notify(context, ID_STATUS, status(context, spec, state, now))

    fun alert(context: Context, spec: WatchSpec, event: TriggerEvent, estimated: Boolean = false, walkMinutes: Int? = null) {
        val txt = NotificationTexts.alert(spec, event, estimated, walkMinutes)
        val title = txt.title
        val body = txt.body
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(txt.big ?: body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setVibrate(VIBRATION)
            .setAutoCancel(true)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, ID_ALERT_BASE + (event.key.hashCode() and 0x3ff), n)
    }

    fun finished(context: Context, spec: WatchSpec) {
        val n = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(NotificationTexts.finished(spec, spec.durationMinutes + WatchEdits.extraMinutes(spec)).title)
            .setContentText(NotificationTexts.finished(spec, spec.durationMinutes + WatchEdits.extraMinutes(spec)).body)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        notify(context, ID_FINISHED, n)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
