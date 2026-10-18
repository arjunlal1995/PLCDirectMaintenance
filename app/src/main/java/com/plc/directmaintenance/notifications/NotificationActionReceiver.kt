package com.plc.directmaintenance.notifications

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class NotificationActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACKNOWLEDGE_ALERT = "com.plc.directmaintenance.ACTION_ACKNOWLEDGE_ALERT"
        const val ACTION_DISPATCH_TECH = "com.plc.directmaintenance.ACTION_DISPATCH_TECH"
        const val ACTION_SNOOZE_ALERT = "com.plc.directmaintenance.ACTION_SNOOZE_ALERT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val alertId = intent.getStringExtra(NotificationHelper.EXTRA_ALERT_ID) ?: return
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            ACTION_ACKNOWLEDGE_ALERT -> {
                notificationManager.cancel(alertId.hashCode())
                Toast.makeText(
                    context,
                    "✓ Alert #$alertId Acknowledged. Siren silenced.",
                    Toast.LENGTH_LONG
                ).show()
            }

            ACTION_DISPATCH_TECH -> {
                notificationManager.cancel(alertId.hashCode())
                Toast.makeText(
                    context,
                    "🚀 Maintenance Tech assigned to Alert #$alertId. ETA: 4 mins.",
                    Toast.LENGTH_LONG
                ).show()
            }

            ACTION_SNOOZE_ALERT -> {
                notificationManager.cancel(alertId.hashCode())
                Toast.makeText(
                    context,
                    "⏰ Alert #$alertId Snoozed for 10 minutes.",
                    Toast.LENGTH_SHORT
                ).show()

                Handler(Looper.getMainLooper()).postDelayed({
                    val helper = NotificationHelper(context)
                }, 10 * 60 * 1000L)
            }
        }
    }
}
