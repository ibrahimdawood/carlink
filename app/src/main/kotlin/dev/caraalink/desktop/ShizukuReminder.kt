package dev.caraalink.desktop

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import dev.caraalink.MainActivity
import dev.caraalink.R
import dev.caraalink.settings.AppSettings

/**
 * Shizuku stops when the phone restarts and needs Wi-Fi to start again, which the car
 * doesn't have. So right after a restart, while the driver is likely still at home,
 * remind them to start it. Only for drivers who have used car apps.
 */
class ShizukuReminder : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppSettings.usedCarApps(context)) return
        val shizukuInstalled = runCatching { context.packageManager.getPackageInfo(Desktop.SHIZUKU_PACKAGE, 0) }.isSuccess
        if (!shizukuInstalled) return
        notify(context)
    }

    companion object {
        private const val CHANNEL_ID = "car_apps"
        private const val NOTIFICATION_ID = 7

        fun notify(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.settings_desktop), NotificationManager.IMPORTANCE_DEFAULT),
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(Desktop.SHIZUKU_PACKAGE)
                ?: Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(context, 0, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(context.getString(R.string.shizuku_reminder_title))
                .setContentText(context.getString(R.string.shizuku_reminder_body))
                .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.shizuku_reminder_body)))
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIFICATION_ID, n) }
        }
    }
}
