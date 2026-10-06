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
import il.kavmonitor.shared.model.WatchSpec
import il.kavmonitor.shared.trigger.LeaveStatus
import il.kavmonitor.shared.trigger.TriggerEvent
import il.kavmonitor.shared.trigger.TriggerReason
import il.kavmonitor.shared.util.israelClock
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

    private fun triggerLabel(context: Context, spec: WatchSpec) = when (spec.trigger) {
        TriggerType.DEPARTS_ORIGIN -> context.getString(R.string.notif_status_departs, spec.origin.name)
        TriggerType.ARRIVES_AT_STOP -> context.getString(R.string.notif_status_arrives, spec.stop.name)
        TriggerType.LEAVE_FOR_STOP -> context.getString(R.string.notif_status_leave, spec.stop.name)
    }

    /** Time-to-leave line for the ongoing notification (null for other triggers / before the first poll). */
    private fun leaveLine(context: Context, spec: WatchSpec, state: WatchState?): Pair<String, Long?>? {
        if (spec.trigger != TriggerType.LEAVE_FOR_STOP || state?.lastSuccessAt == null) return null
        val bus = state.nextLeaveBus ?: return context.getString(R.string.notif_leave_none) to null
        val leave = bus.leaveAt ?: return null
        val eta = bus.eta?.israelClock() ?: "—"
        val kind = context.getString(if (bus.hasFreshGps) R.string.notif_eta_live else R.string.notif_eta_scheduled)
        val now = System.currentTimeMillis()
        return if (bus.leaveStatus == LeaveStatus.LEAVE_NOW || leave.toEpochMilliseconds() <= now) {
            context.getString(R.string.notif_leave_now, eta, kind) to null
        } else {
            val mins = kotlin.math.ceil((leave.toEpochMilliseconds() - now) / 60_000.0).toInt()
            context.getString(R.string.notif_leave_next, leave.israelClock(), mins, eta, kind) to leave.toEpochMilliseconds()
        }
    }

    fun status(context: Context, spec: WatchSpec, state: WatchState?): Notification {
        val until = state?.endsAt?.israelClock() ?: ""
        val line = when {
            state?.lastSuccessAt != null ->
                context.getString(R.string.notif_status_line, state.lastSuccessAt!!.israelClock(true), state.busesSeen, until)
            else -> context.getString(R.string.notif_status_waiting, until)
        }
        val leave = leaveLine(context, spec, state)
        val main = leave?.first?.let { it + "\n" + line } ?: line
        val text = state?.lastError?.let { context.getString(R.string.notif_status_error, it) + "\n" + main } ?: main
        val stop = PendingIntent.getService(
            context, 1, WatchService.stopIntent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(context, CHANNEL_STATUS)
        leave?.second?.let { leaveAtMillis ->
            // live countdown to the leave time in the notification header, without extra updates
            b.setWhen(leaveAtMillis).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        }
        return b
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(context.getString(R.string.notif_status_title, spec.lineName, triggerLabel(context, spec)))
            .setContentText(leave?.first ?: line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.notif_action_stop), stop)
            .build()
    }

    fun updateStatus(context: Context, spec: WatchSpec, state: WatchState) = notify(context, ID_STATUS, status(context, spec, state))

    fun alert(context: Context, spec: WatchSpec, event: TriggerEvent) {
        val title = when (event.reason) {
            TriggerReason.LEFT_ORIGIN_DROPPED, TriggerReason.LEFT_ORIGIN_GPS, TriggerReason.LEFT_ORIGIN_SCHEDULED ->
                context.getString(R.string.alert_departed_title, spec.lineName, spec.origin.name)
            TriggerReason.AT_STOP_GPS, TriggerReason.AT_STOP_ETA, TriggerReason.DROPPED_AT_STOP ->
                context.getString(R.string.alert_arrived_title, spec.lineName, spec.stop.name)
            TriggerReason.LEAVE_NOW ->
                context.getString(R.string.alert_leave_title, spec.lineName, spec.stop.name, event.eta?.israelClock() ?: "—")
        }
        val body = if (event.reason == TriggerReason.LEAVE_NOW) {
            val lead = event.leadMinutes ?: spec.leave?.leadMinutes ?: 0
            val buffer = spec.leave?.bufferMinutes ?: 0
            context.getString(
                R.string.alert_leave_body, event.vehicleRef ?: "—", (lead - buffer).coerceAtLeast(0), buffer,
                context.getString(if (event.live) R.string.alert_eta_live else R.string.alert_eta_scheduled),
            )
        } else {
            context.getString(R.string.alert_body, event.vehicleRef ?: "—", event.scheduledDeparture?.israelClock() ?: "—")
        }
        val n = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
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
            .setContentTitle(context.getString(R.string.notif_finished_title, spec.lineName))
            .setContentText(context.getString(R.string.notif_finished_body, spec.durationMinutes))
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
