package dev.caraalink.desktop

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.caraalink.R
import dev.caraalink.service.UsbLinkForegroundService
import java.util.concurrent.Executors

/**
 * The car's home screen in full-screen apps mode: it opens on the car display and
 * lists the phone's apps. Tapping one opens it full screen on the car; the car's
 * Home key comes back here.
 */
class CarLauncherActivity : ComponentActivity() {

    private class App(val label: String, val component: String, val icon: ImageBitmap)

    /** Bumped on every return to the list, so favorites changed on the phone show up. */
    private val resumes = mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.value++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var apps by remember { mutableStateOf<List<App>?>(null) }
            var showAll by remember { mutableStateOf(false) }
            if (apps == null) loadApps { apps = it }
            val all = apps.orEmpty()
            @Suppress("UNUSED_VARIABLE") val tick = resumes.value
            val picked = dev.caraalink.settings.AppSettings.carFavorites(this)
            val favorites = picked.mapNotNull { c -> all.firstOrNull { it.component == c } }
            if (favorites.isEmpty() || showAll) {
                Launcher(all, big = false, toggle = if (favorites.isEmpty()) null else (R.string.car_favorites to { showAll = false }))
            } else {
                Launcher(favorites, big = true, toggle = R.string.car_all_apps to { showAll = true })
            }
        }
    }

    private fun loadApps(onLoaded: (List<App>) -> Unit) {
        Executors.newSingleThreadExecutor().execute {
            val pm = packageManager
            val iconPx = (64 * resources.displayMetrics.density).toInt()
            val list = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                PackageManager.MATCH_ALL,
            )
                .filter { it.activityInfo.packageName != packageName }
                .map {
                    App(
                        it.loadLabel(pm).toString(),
                        "${it.activityInfo.packageName}/${it.activityInfo.name}",
                        it.loadIcon(pm).toBitmap(iconPx, iconPx, Bitmap.Config.ARGB_8888).asImageBitmap(),
                    )
                }
                .sortedBy { it.label.lowercase() }
            runOnUiThread { onLoaded(list) }
        }
    }

    private fun open(app: App) {
        dev.caraalink.settings.AppSettings.setLastCarApp(this, app.component)
        Desktop.launch(app.component) {
            Toast.makeText(this, getString(R.string.desktop_open_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun backToAndroidAuto() {
        startService(
            Intent(this, UsbLinkForegroundService::class.java)
                .setAction(UsbLinkForegroundService.ACTION_STOP_DESKTOP)
                .putExtra(UsbLinkForegroundService.EXTRA_OPEN_AA, true),
        )
    }

    /** [big] tiles for the few favorites; [toggle] is the tile that switches to the other list. */
    @Composable
    private fun Launcher(apps: List<App>, big: Boolean, toggle: Pair<Int, () -> Unit>?) {
        val tile = if (big) 150.dp else 112.dp
        val icon = if (big) 84.dp else 64.dp
        Column(Modifier.fillMaxSize().background(BACKGROUND).padding(horizontal = 24.dp, vertical = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.app_name).uppercase(), color = ACCENT, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(40.dp)).background(BRAND).clickable { backToAndroidAuto() }
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(stringResource(R.string.mirror_back_to_aa), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(tile),
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(apps, key = { it.component }) { app ->
                    Column(
                        Modifier.clip(RoundedCornerShape(16.dp)).clickable { open(app) }.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(app.icon, contentDescription = null, modifier = Modifier.size(icon))
                        Text(app.label, color = TEXT, fontSize = if (big) 17.sp else 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                toggle?.let { (label, onClick) ->
                    item(key = "toggle") {
                        Column(
                            Modifier.clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(icon).clip(RoundedCornerShape(18.dp)).background(TILE),
                                contentAlignment = Alignment.Center,
                            ) {
                                androidx.compose.material3.Icon(
                                    androidx.compose.ui.res.painterResource(R.drawable.ic_apps), contentDescription = null,
                                    tint = ACCENT, modifier = Modifier.size(icon / 2),
                                )
                            }
                            Text(stringResource(label), color = TEXT, fontSize = if (big) 17.sp else 14.sp,
                                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val BACKGROUND = Color(0xFF0A0E12)
        val ACCENT = Color(0xFF5EEAD4)
        val BRAND = Color(0xFF0D9488)
        val TEXT = Color(0xFFE8F1F2)
        val TILE = Color(0xFF121A20)
    }
}
