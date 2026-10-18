package com.plc.directmaintenance.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.plc.directmaintenance.MainActivity
import com.plc.directmaintenance.model.AlertSeverity
import com.plc.directmaintenance.model.PlcAlert

/**
 * Custom Notification Engine for Real-Time Industrial PLC Alerts.
 * Supports:
 * - Heads-Up banners for CRITICAL and HIGH severity faults
 * - Custom multi-pulse vibration patterns (Siren cadence)
 * - Direct action buttons: [Acknowledge], [Dispatch Tech], [Snooze 10m]
 * - Sticky ongoing alarms for unacknowledged plant floor safety events
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_CRITICAL_ID = "channel_plc_critical_alerts"
        const val CHANNEL_WARNING_ID = "channel_plc_warning_alerts"
        const val CHANNEL_INFO_ID = "channel_plc_maintenance_info"
        const val CHANNEL_SERVICE_ID = "channel_plc_service_status"

        const val EXTRA_ALERT_ID = "extra_alert_id"
        const val EXTRA_PLC_TAG = "extra_plc_tag"
    }

    /**
     * Initializes Android O+ notification channels with custom importance,
     * flashing LED rings, and vibration cadences.
     */
    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Critical Channel (E-Stops, Overpressure, Thermal Fire Risk)
            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL_ID,
                "PLC Critical Alarms (Siren)",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Immediate action required. Industrial plant floor safety hazard."
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 800)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }

            // High Warning Channel (Wear warnings, Filter delta-P)
            val warningChannel = NotificationChannel(
                CHANNEL_WARNING_ID,
                "PLC High Warnings",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent maintenance alerts requiring technician attention."
                enableLights(true)
                lightColor = Color.YELLOW
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 250, 250, 250)
            }

            // Routine Maintenance Channel
            val infoChannel = NotificationChannel(
                CHANNEL_INFO_ID,
                "Routine Maintenance & PMs",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Scheduled lubrication, filter changes, and cycle counts."
            }

            // Foreground Service Persistent Notification Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE_ID,
                "Background PLC Telemetry Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps direct USB-B & RJ-45 connection open to factory floor PLCs."
            }

            notificationManager.createNotificationChannels(
                listOf(criticalChannel, warningChannel, infoChannel, serviceChannel)
            )
        }
    }

    /**
     * Posts a rich, actionable notification to the Android system tray and heads-up overlay.
     */
    fun postCustomRealTimeAlert(alert: PlcAlert) {
        val channelId = when (alert.severity) {
            AlertSeverity.CRITICAL -> CHANNEL_CRITICAL_ID
            AlertSeverity.HIGH -> CHANNEL_WARNING_ID
            else -> CHANNEL_INFO_ID
        }

        val notificationId = alert.id.hashCode()

        // 1. Main Content Intent: Opens app and focuses on offending PLC Tag
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ALERT_ID, alert.id)
            putExtra(EXTRA_PLC_TAG, alert.plcTag)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Action: [ACKNOWLEDGE]
        val ackIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_ACKNOWLEDGE_ALERT
            putExtra(EXTRA_ALERT_ID, alert.id)
        }
        val ackPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 1,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Action: [DISPATCH TECH]
        val dispatchIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_DISPATCH_TECH
            putExtra(EXTRA_ALERT_ID, alert.id)
        }
        val dispatchPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 2,
            dispatchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Action: [SNOOZE 10M]
        val snoozeIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = NotificationActionReceiver.ACTION_SNOOZE_ALERT
            putExtra(EXTRA_ALERT_ID, alert.id)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId + 3,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val severityBadge = when (alert.severity) {
            AlertSeverity.CRITICAL -> "🚨 [CRITICAL ALARM]"
            AlertSeverity.HIGH -> "⚠️ [HIGH WARNING]"
            AlertSeverity.MEDIUM -> "🔧 [MAINTENANCE]"
            AlertSeverity.INFO -> "ℹ️ [INFO]"
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$severityBadge ${alert.title}")
            .setContentText("${alert.machineName} • ${alert.plcTag}")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("${alert.message}\n\n• Machine: ${alert.machineName}\n• PLC Tag: ${alert.plcTag}\n• Temp: ${alert.temperatureC}°C | Pressure: ${alert.pressureBar} bar")
            )
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(false)
            .setOngoing(alert.severity == AlertSeverity.CRITICAL)
            .setPriority(
                if (alert.severity == AlertSeverity.CRITICAL) NotificationCompat.PRIORITY_MAX
                else NotificationCompat.PRIORITY_HIGH
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setColor(if (alert.severity == AlertSeverity.CRITICAL) Color.RED else Color.YELLOW)
            .addAction(android.R.drawable.ic_menu_agenda, "Acknowledge", ackPendingIntent)
            .addAction(android.R.drawable.ic_menu_send, "Dispatch Tech", dispatchPendingIntent)
            .addAction(android.R.drawable.ic_popup_sync, "Snooze 10m", snoozePendingIntent)

        if (alert.severity == AlertSeverity.CRITICAL) {
            builder.setVibrate(longArrayOf(0, 500, 200, 500, 200, 800))
        }

        notificationManager.notify(notificationId, builder.build())
    }

    fun dismissNotification(alertId: String) {
        notificationManager.cancel(alertId.hashCode())
    }
}
