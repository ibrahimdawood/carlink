package dev.caraalink.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Replays car-screen touches on the phone while its screen is mirrored to the car.
 *
 * One finger is held down for as long as the driver's finger is: DOWN starts a stroke
 * that "will continue", each MOVE continues it to the newest point, UP lifts it. A tap,
 * a long press and a drag therefore all keep their real timing. Moves that arrive while
 * a piece is still being dispatched are merged into the next piece, so a slow phone
 * never builds up a backlog of old positions.
 */
class MirrorInputService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var stroke: GestureDescription.StrokeDescription? = null
    private var last: MirrorTouch.Point? = null
    private var lastAt = 0L
    private var busy = false
    private var pendingMove: MirrorTouch.Point? = null
    private var pendingUp = false
    private var generation = 0

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "touch control connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    /** [action] is a MotionEvent action; [x],[y] are phone screen pixels. Main thread. */
    private fun onTouch(action: Int, x: Float, y: Float) {
        val p = MirrorTouch.Point(x, y)
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                // A new touch always starts clean, even if the last UP never arrived.
                stroke = null
                pendingMove = null
                pendingUp = false
                busy = false
                last = p
                lastAt = SystemClock.uptimeMillis()
                dispatchPiece(p, willContinue = true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (stroke == null && !busy) return
                pendingMove = p
                if (!busy) flush()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (stroke == null && !busy) return
                pendingMove = p
                pendingUp = true
                if (!busy) flush()
            }
        }
    }

    private fun flush() {
        val up = pendingUp
        val p = pendingMove ?: last ?: return
        pendingMove = null
        pendingUp = false
        dispatchPiece(p, willContinue = !up)
    }

    private fun dispatchPiece(to: MirrorTouch.Point, willContinue: Boolean) {
        val from = last ?: to
        val now = SystemClock.uptimeMillis()
        // The piece lasts as long as the real finger took, so speed and long presses come through.
        val duration = (now - lastAt).coerceIn(1L, MAX_PIECE_MS)
        val path = Path().apply {
            moveTo(from.x, from.y)
            // A path needs some length; a finger that hasn't moved gets a sub-pixel one.
            if (from == to) lineTo(to.x + 0.1f, to.y) else lineTo(to.x, to.y)
        }
        val previous = stroke
        val next = try {
            previous?.continueStroke(path, 0, duration, willContinue)
                ?: GestureDescription.StrokeDescription(path, 0, duration, willContinue)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "bad stroke piece", e)
            reset()
            return
        }
        last = to
        lastAt = now
        stroke = if (willContinue) next else null
        busy = true
        val piece = ++generation
        val sent = dispatchGesture(GestureDescription.Builder().addStroke(next).build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = onPieceDone(piece)
            override fun onCancelled(gestureDescription: GestureDescription?) = onPieceDone(piece)
        }, main)
        if (!sent) {
            Log.w(TAG, "gesture refused")
            reset()
        }
    }

    /** Callbacks run on [main]. A new touch cancels the old gesture; its late callback must not unblock the new one. */
    private fun onPieceDone(piece: Int) {
        if (piece != generation) return
        busy = false
        if (pendingMove != null || pendingUp) flush()
    }

    private fun reset() {
        stroke = null
        busy = false
        pendingMove = null
        pendingUp = false
    }

    companion object {
        private const val TAG = "MirrorInput"
        private const val MAX_PIECE_MS = 500L

        @Volatile private var instance: MirrorInputService? = null

        /** True while Android has the touch-control service running. */
        val isRunning: Boolean get() = instance != null

        /** True when the driver switched the service on in Android's accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, MirrorInputService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Hands a touch to the service; false when touch control is off. Any thread. */
        fun touch(action: Int, x: Float, y: Float): Boolean {
            val s = instance ?: return false
            s.main.post { s.onTouch(action, x, y) }
            return true
        }

        /** The phone's own Back. */
        fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
    }
}
