package dev.caraalink.mirror

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import dev.caraalink.R
import dev.caraalink.service.UsbLinkForegroundService

/**
 * Invisible activity that shows Android's "Start recording or casting?" prompt and
 * hands the answer to [UsbLinkForegroundService]. Android asks every time; an app
 * can't remember the answer.
 */
class MirrorConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java).cancel(CONSENT_NOTIFICATION_ID)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return
        val service = Intent(this, UsbLinkForegroundService::class.java)
        if (resultCode == RESULT_OK && data != null) {
            service.setAction(UsbLinkForegroundService.ACTION_START_MIRROR)
                .putExtra(UsbLinkForegroundService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(UsbLinkForegroundService.EXTRA_RESULT_DATA, data)
            startForegroundService(service)
        } else {
            startService(service.setAction(UsbLinkForegroundService.ACTION_MIRROR_DENIED))
        }
        finish()
    }

    companion object {
        private const val REQUEST = 7
        private const val CHANNEL_ID = "mirror_consent"
        private const val CONSENT_NOTIFICATION_ID = 6

        /**
         * Asks for screen sharing. From the app it opens the prompt directly. From the car
         * the app is in the background, where Android only lets it open a screen while touch
         * control (an accessibility service) is on; otherwise a notification opens it.
         */
        fun request(context: Context, fromBackground: Boolean) {
            val intent = Intent(context, MirrorConsentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!fromBackground || MirrorInputService.isRunning) {
                context.startActivity(intent)
                return
            }
            val nm = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.mirror_consent_channel), NotificationManager.IMPORTANCE_HIGH),
                )
            }
            val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bridge)
                .setContentTitle(context.getString(R.string.mirror_consent_title))
                .setContentText(context.getString(R.string.mirror_consent_body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(CONSENT_NOTIFICATION_ID, n)
        }
    }
}
