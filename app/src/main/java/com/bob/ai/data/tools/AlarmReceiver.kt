package com.bob.ai.data.tools

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Fired by [DailyToolsExecutor] alarms. Shows a "Báo thức: <label>"
 * notification. The channel ("bob_alarm") is created here if needed.
 *
 * NOTE: this receiver must be registered in AndroidManifest.xml
 * (e.g. `<receiver android:name=".data.tools.AlarmReceiver" android:exported="false"/>`)
 * — that file is owned by another module, so it is not touched here.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val label = intent?.getStringExtra("label")?.ifBlank { null } ?: "Báo thức"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Báo thức",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Thông báo báo thức của Bob" }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Báo thức: $label")
            .setContentText("Đã đến giờ: $label")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(label.hashCode(), notification)
    }

    companion object {
        const val CHANNEL_ID = "bob_alarm"
    }
}
