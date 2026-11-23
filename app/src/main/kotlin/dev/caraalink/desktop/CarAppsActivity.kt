package dev.caraalink.desktop

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.caraalink.R
import dev.caraalink.settings.AppSettings
import dev.caraalink.ui.SubScreen
import dev.caraalink.ui.theme.AaBridgeTheme
import dev.caraalink.ui.theme.Dash
import java.util.concurrent.Executors

/**
 * Phone screen for car apps: which apps the car's home screen shows first (in the order
 * picked), whether the last app reopens, and whether the keyboard opens on the car.
 */
class CarAppsActivity : ComponentActivity() {

    private class App(val label: String, val component: String, val icon: ImageBitmap)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AaBridgeTheme {
                var apps by remember { mutableStateOf<List<App>>(emptyList()) }
                var picked by remember { mutableStateOf(AppSettings.carFavorites(this)) }
                var openLast by remember { mutableStateOf(AppSettings.openLastCarApp(this)) }
                var keyboard by remember { mutableStateOf(AppSettings.carKeyboard(this)) }
                remember { loadApps { apps = it } }
                SubScreen(stringResource(R.string.car_apps_title), onBack = { finish() }) {
                    Toggle(stringResource(R.string.car_open_last), openLast) {
                        openLast = it
                        AppSettings.setOpenLastCarApp(this@CarAppsActivity, it)
                    }
                    Toggle(stringResource(R.string.car_keyboard), keyboard) {
                        keyboard = it
                        AppSettings.setCarKeyboard(this@CarAppsActivity, it)
                    }
                    Text(
                        stringResource(R.string.car_apps_pick),
                        color = Dash.Muted, fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                    // SubScreen already scrolls; a lazy list inside it would have no height limit.
                    Column(Modifier.fillMaxWidth()) {
                        apps.forEach { app ->
                            val index = picked.indexOf(app.component)
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    picked = if (index >= 0) picked - app.component else picked + app.component
                                    AppSettings.setCarFavorites(this@CarAppsActivity, picked)
                                }.padding(horizontal = 16.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
                                Text(app.label, color = Dash.Text, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
                                // Position on the car, so the order picked is visible.
                                if (index >= 0) Text("${index + 1}", color = Dash.Teal, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                                Checkbox(
                                    checked = index >= 0, onCheckedChange = null,
                                    colors = CheckboxDefaults.colors(checkedColor = Dash.Teal),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Dash.Text, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }

    private fun loadApps(onLoaded: (List<App>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val pm = packageManager
            val px = (40 * resources.displayMetrics.density).toInt()
            val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    App(
                        it.loadLabel(pm).toString(),
                        "${it.activityInfo.packageName}/${it.activityInfo.name}",
                        it.loadIcon(pm).toBitmap(px, px, Bitmap.Config.ARGB_8888).asImageBitmap(),
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { onLoaded(list) }
        }
    }
}
