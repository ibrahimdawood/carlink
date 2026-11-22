package dev.caraalink.desktop

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Shizuku "user service": runs in its own process as the shell user, the same
 * rights `adb shell` has. That is what lets it make a display other apps can
 * open on, start them there, and put touches on it -- an ordinary app can do
 * none of the three. The approach is scrcpy's `--new-display`.
 *
 * Everything here reaches hidden Android APIs by reflection; this process isn't
 * an app, so Android doesn't block them.
 */
class ShellDisplayService(context: Context) : IShellDisplay.Stub() {

    /** Shizuku calls this one when it can't hand over a Context. */
    constructor() : this(systemContext())

    private val shellContext: Context = ShellContext(context)
    private val lock = Any()
    private var display: VirtualDisplay? = null
    @Volatile private var displayId = -1
    private var downTime = 0L

    override fun create(surface: Surface?, width: Int, height: Int, dpi: Int): Int = synchronized(lock) {
        releaseLocked()
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(shellContext)
        val vd = dm.createVirtualDisplay("CarLink", width, height, dpi, surface, displayFlags())
            ?: return -1
        display = vd
        displayId = vd.display.displayId
        Log.i(TAG, "display $displayId ${width}x$height@$dpi")
        displayId
    }

    override fun resize(width: Int, height: Int, dpi: Int) = synchronized(lock) {
        display?.resize(width, height, dpi)
        Unit
    }

    override fun setSurface(surface: Surface?) = synchronized(lock) {
        display?.surface = surface
        Unit
    }

    override fun release() = synchronized(lock) { releaseLocked() }

    private fun releaseLocked() {
        display?.release()
        display = null
        displayId = -1
    }

    override fun start(component: String?): String? {
        val id = displayId
        if (component.isNullOrBlank() || id < 0) return "no display"
        return try {
            // Fullscreen windowing mode: a phone may otherwise open apps on a new display as floating windows.
            val p = ProcessBuilder("am", "start", "--display", id.toString(), "--windowingMode", "1", "-n", component)
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) out.trim() else null
        } catch (e: Exception) {
            e.toString()
        }
    }

    override fun touch(action: Int, x: Float, y: Float) {
        val id = displayId
        if (id < 0) return
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            this.id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            size = 1f
        })
        val ev = MotionEvent.obtain(downTime, now, action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        inject(ev, id)
        ev.recycle()
    }

    override fun key(keyCode: Int) {
        val id = displayId
        if (id < 0) return
        val now = SystemClock.uptimeMillis()
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            inject(ev, id)
        }
    }

    override fun destroy() {
        release()
        exitProcess(0)
    }

    private fun inject(event: InputEvent, display: Int) {
        try {
            setDisplayId.invoke(event, display)
            injector(event)
        } catch (e: Exception) {
            Log.w(TAG, "inject failed", e)
        }
    }

    /** Shell context that says it's the shell package, which the display service checks against our user id. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PACKAGE
        override fun getOpPackageName() = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
            } else {
                super.getAttributionSource()
            }
    }

    companion object {
        private const val TAG = "CarLinkShell"
        private const val SHELL_PACKAGE = "com.android.shell"

        // VirtualDisplay flags (several hidden). Not ROTATES_WITH_CONTENT: a portrait-only app should
        // get side bars on the wide car screen, not turn the whole picture sideways.
        private const val PUBLIC = 1
        private const val PRESENTATION = 1 shl 1
        private const val OWN_CONTENT_ONLY = 1 shl 3
        private const val SUPPORTS_TOUCH = 1 shl 6
        private const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        private const val TRUSTED = 1 shl 10
        private const val OWN_DISPLAY_GROUP = 1 shl 11
        private const val ALWAYS_UNLOCKED = 1 shl 12
        private const val TOUCH_FEEDBACK_DISABLED = 1 shl 13
        private const val OWN_FOCUS = 1 shl 14
        private const val DEVICE_DISPLAY_GROUP = 1 shl 15

        internal fun displayFlags(sdk: Int = Build.VERSION.SDK_INT): Int {
            var flags = PUBLIC or PRESENTATION or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL
            if (sdk >= 33) flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
            if (sdk >= 34) flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP
            return flags
        }

        private fun systemContext(): Context {
            if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
            val at = Class.forName("android.app.ActivityThread")
            val thread = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
            return at.getMethod("getSystemContext").invoke(thread) as Context
        }

        private val setDisplayId by lazy {
            InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        }

        /** InputManagerGlobal from Android 14, InputManager before. Mode 0 = don't wait. */
        private val injector: (InputEvent) -> Unit by lazy {
            val manager = runCatching {
                Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
            }.getOrNull() ?: Class.forName("android.hardware.input.InputManager").getDeclaredMethod("getInstance").invoke(null)
            val method = manager!!.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            val fn: (InputEvent) -> Unit = { e -> method.invoke(manager, e, 0) }
            fn
        }
    }
}
