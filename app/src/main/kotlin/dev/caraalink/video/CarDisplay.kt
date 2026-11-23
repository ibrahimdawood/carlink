package dev.caraalink.video

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager

/**
 * Owns what the car sees. One private virtual display (never seen by DeX)
 * renders into the encoder's surface and shows Android Auto or a status screen.
 * In [Mode.MIRROR] that display lets go of the surface and a screen-capture display
 * of the phone's own screen renders into it instead.
 * All work happens on the main thread.
 */
class CarDisplay(
    private val context: Context,
    private val onOpenAa: () -> Unit,
    private val onMirror: () -> Unit,
    private val onDesktop: () -> Unit,
) {
    enum class Mode { STATUS, AA, MIRROR, DESKTOP }

    /** Modes where something outside this display (screen capture, the Shizuku helper) renders into the encoder. */
    private val handedOff get() = mode == Mode.MIRROR

    private val main = Handler(Looper.getMainLooper())
    private var carDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    @Volatile var mode = Mode.STATUS
        private set
    /** Last status screen shown; stays put while Android Auto is on screen. */
    @Volatile var screen = CarScreen.STARTING
        private set
    private var touchDownAt = 0L

    private var encoderSurface: Surface? = null
    @Volatile var frameWidth = 0
        private set
    @Volatile var frameHeight = 0
        private set
    private var projection: MediaProjection? = null
    private var mirrorDisplay: VirtualDisplay? = null

    private fun dpiFor(w: Int, h: Int) = (minOf(w, h) / 720f * 213).toInt().coerceIn(120, 320)

    /** Point the car display at a (new) encoder surface of [w]x[h]. */
    fun attach(surface: Surface, w: Int, h: Int) = main.post {
        encoderSurface = surface
        frameWidth = w
        frameHeight = h
        val dpi = dpiFor(w, h)
        // While mirroring, the phone's screen owns the encoder surface.
        val ownSurface = if (handedOff) null else surface
        val existing = carDisplay
        if (existing == null) {
            val dm = context.getSystemService(DisplayManager::class.java)
            carDisplay = dm.createVirtualDisplay("CarAALink", w, h, dpi, ownSurface, 0)
        } else {
            existing.resize(w, h, dpi)
            existing.surface = ownSurface
        }
        mirrorDisplay?.let {
            it.resize(w, h, dpi)
            it.surface = surface
        }
        showCurrent()
    }

    fun showStatus(screen: CarScreen) = main.post {
        leaveMirror()
        this.screen = screen
        mode = Mode.STATUS
        showCurrent()
    }

    /**
     * Shows the phone's own screen on the car, letterboxed, through [projection]
     * (already granted). Android Auto, if it was up, ends as on any exit from it.
     * [onEnded] runs on the main thread when the driver stops sharing from the phone.
     */
    fun showMirror(projection: MediaProjection, onEnded: () -> Unit) = main.post {
        leaveMirror()
        // Mode first: the Android Auto presentation ends its session only on a real exit from AA.
        mode = Mode.MIRROR
        // Leaving the mirror goes to Closed, and nothing reopens Android Auto behind it meanwhile.
        screen = CarScreen.CLOSED
        presentation?.dismiss()
        presentation = null
        carDisplay?.surface = null
        this.projection = projection
        // Android 14 requires the callback before the capture display exists.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (this@CarDisplay.projection !== projection) return
                Log.i(TAG, "screen sharing stopped from the phone")
                onEnded()
            }
        }, main)
        val surface = encoderSurface
        val w = frameWidth.takeIf { it > 0 } ?: 1280
        val h = frameHeight.takeIf { it > 0 } ?: 720
        mirrorDisplay = try {
            projection.createVirtualDisplay(
                "CarLinkMirror", w, h, dpiFor(w, h),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "screen capture display failed", e)
            null
        }
        if (mirrorDisplay == null) main.post { onEnded() }
    }

    private var desktopShell: dev.caraalink.desktop.IShellDisplay? = null
    /** The helper's display; kept while Android Auto is up so apps stay as they were. */
    private var desktopId = -1
    private var desktopOnReady: ((id: Int, fresh: Boolean) -> Unit)? = null
    private var desktopOnFailed: ((String) -> Unit)? = null
    private var desktopButtons: Triple<() -> Unit, () -> Unit, () -> Unit>? = null

    /**
     * Full-screen apps: a bar with Back, the app list and Android Auto, and beside it the
     * Shizuku helper's display rendering into this screen. [onReady] gets that display's id
     * once it shows, with fresh = true when it was just made (nothing on it yet).
     * [onFailed] runs when the helper couldn't make one. Main thread for all.
     */
    fun showDesktop(
        shell: dev.caraalink.desktop.IShellDisplay,
        onReady: (id: Int, fresh: Boolean) -> Unit,
        onFailed: (String) -> Unit,
        onBack: () -> Unit,
        onApps: () -> Unit,
        onAndroidAuto: () -> Unit,
    ) = main.post {
        leaveMirror()
        if (desktopShell?.asBinder() !== shell.asBinder()) {
            // A new helper (first time, or the old one died): its display starts from scratch.
            desktopId = -1
        }
        desktopShell = shell
        desktopOnReady = onReady
        desktopOnFailed = onFailed
        desktopButtons = Triple(onBack, onApps, onAndroidAuto)
        mode = Mode.DESKTOP
        screen = CarScreen.CLOSED
        showCurrent()
    }

    /** The app area's surface came or went (on the main thread). */
    private fun onDesktopSurface(surface: Surface?, w: Int, h: Int) {
        val shell = desktopShell ?: return
        if (surface == null) {
            // Leaving car apps or redrawing the screen: the apps keep running without a picture.
            runCatching { shell.setSurface(null) }
            return
        }
        if (mode != Mode.DESKTOP) return
        val dpi = dpiFor(frameWidth.takeIf { it > 0 } ?: w, frameHeight.takeIf { it > 0 } ?: h)
        try {
            if (desktopId >= 0) {
                shell.resize(w, h, dpi)
                shell.setSurface(surface)
                desktopOnReady?.invoke(desktopId, false)
                return
            }
            val id = shell.create(surface, w, h, dpi)
            if (id < 0) {
                desktopOnFailed?.invoke("display not created")
                return
            }
            desktopId = id
            desktopOnReady?.invoke(id, true)
        } catch (e: Exception) {
            Log.e(TAG, "car apps display failed", e)
            desktopId = -1
            desktopOnFailed?.invoke(e.toString())
        }
    }

    /** Ends car apps for good (session over): the helper's display and its apps close. */
    private fun releaseDesktop() {
        val shell = desktopShell ?: return
        desktopShell = null
        desktopId = -1
        runCatching { shell.release() }
    }

    /** Ends screen sharing and gives the encoder surface back to the car display. */
    private fun leaveMirror() {
        val p = projection ?: return
        projection = null
        mirrorDisplay?.release()
        mirrorDisplay = null
        runCatching { p.stop() }
        carDisplay?.surface = encoderSurface
    }

    /**
     * Shows a live Android Auto session at [videoWidth]x[videoHeight] (the
     * resolution actually negotiated with the phone -- see
     * AaServiceDiscovery.configFromSettings()). [onSurface]/[onTouch] wire
     * the decoder and touch forwarding; the caller (CarSession) owns the
     * actual AaSessionProbe/AaVideoDecoder/AaAudioPlayer.
     */
    fun showAa(
        videoWidth: Int,
        videoHeight: Int,
        widthMargin: Int = 0,
        heightMargin: Int = 0,
        onSurface: (Surface?) -> Unit,
        onTouch: (pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit,
    ) = main.post {
        leaveMirror()
        aaVideoSize = videoWidth to videoHeight
        aaMargin = widthMargin to heightMargin
        aaOnSurface = onSurface
        aaOnTouch = onTouch
        mode = Mode.AA
        showCurrent()
    }

    private var aaVideoSize: Pair<Int, Int>? = null
    private var aaMargin: Pair<Int, Int> = 0 to 0
    private var aaOnSurface: ((Surface?) -> Unit)? = null
    private var aaOnTouch: ((pointers: List<Triple<Int, Int, Int>>, actionIndex: Int, action: Int) -> Unit)? = null

    private fun showCurrent() {
        if (handedOff) return
        val display = carDisplay?.display ?: return
        presentation?.dismiss()
        presentation = try {
            when {
                mode == Mode.AA && aaVideoSize != null -> aaPresentation(display)
                mode == Mode.DESKTOP && desktopShell != null -> desktopButtons!!.let { (back, apps, aa) ->
                    DesktopCarPresentation(
                        context, display,
                        onSurface = { surface, w, h -> onDesktopSurface(surface, w, h) },
                        onTouch = { action, x, y -> runCatching { desktopShell?.touch(action, x, y) } },
                        onBack = back, onApps = apps, onAndroidAuto = aa,
                    )
                }
                else -> {
                    mode = Mode.STATUS
                    CarStatusPresentation(context, display, screen, onOpenAa, onMirror, onDesktop)
                }
            }.also { it.show() }
        } catch (e: WindowManager.InvalidDisplayException) {
            Log.e(TAG, "presentation failed", e)
            null
        }
    }

    private fun aaPresentation(display: Display): Presentation {
        val (w, h) = aaVideoSize!!
        val (wm, hm) = aaMargin
        return AaCarPresentation(
            context, display, w, h, wm, hm,
            onSurface = { s -> aaOnSurface?.invoke(s) },
            onTouch = { pointers, actionIndex, action -> aaOnTouch?.invoke(pointers, actionIndex, action) },
        )
    }

    /** Car touch in car-display pixels, delivered to whatever is on screen. */
    fun touch(action: Int, x: Float, y: Float) = main.post {
        val view = presentation?.window?.decorView ?: return@post
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) touchDownAt = now
        val ev = MotionEvent.obtain(touchDownAt, now, action, x, y, 0)
        view.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Draws the current screen again, so a still picture still gives the encoder a fresh frame. */
    fun redraw() = main.post { presentation?.window?.decorView?.invalidate() }

    fun release() = main.post {
        leaveMirror()
        releaseDesktop()
        presentation?.dismiss()
        presentation = null
        carDisplay?.release()
        carDisplay = null
    }

    companion object {
        private const val TAG = "CarDisplay"
    }
}
