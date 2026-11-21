package dev.caraalink.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.caraalink.R
import dev.caraalink.mirror.MirrorConsentActivity
import dev.caraalink.mirror.MirrorInputService
import dev.caraalink.service.UsbLinkForegroundService

/** Opens Android's accessibility settings, where CarLink touch control is switched on. */
fun openTouchControlSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun stopMirror(context: Context, openAa: Boolean) {
    context.startService(
        Intent(context, UsbLinkForegroundService::class.java)
            .setAction(UsbLinkForegroundService.ACTION_STOP_MIRROR)
            .putExtra(UsbLinkForegroundService.EXTRA_OPEN_AA, openAa),
    )
}

/**
 * Home's phone-screen action while a car is connected: show the phone screen on the car,
 * or go back to Android Auto. Offers to switch touch control on first when it's off.
 */
@Composable
fun MirrorAction(mirroring: Boolean) {
    val context = LocalContext.current
    var askTouch by remember { mutableStateOf(false) }
    if (mirroring) {
        GlowButton(stringResource(R.string.mirror_back_to_aa), { stopMirror(context, openAa = true) })
    } else {
        DashLink(stringResource(R.string.mirror_start)) {
            if (MirrorInputService.isEnabled(context)) MirrorConsentActivity.request(context, fromBackground = false)
            else askTouch = true
        }
    }
    if (askTouch) {
        AlertDialog(
            onDismissRequest = { askTouch = false },
            title = { Text(stringResource(R.string.mirror_touch_title)) },
            text = { Text(stringResource(R.string.mirror_touch_body)) },
            confirmButton = {
                TextButton({
                    askTouch = false
                    openTouchControlSettings(context)
                }) { Text(stringResource(R.string.mirror_touch_open)) }
            },
            dismissButton = {
                TextButton({
                    askTouch = false
                    MirrorConsentActivity.request(context, fromBackground = false)
                }) { Text(stringResource(R.string.mirror_touch_skip)) }
            },
        )
    }
}
