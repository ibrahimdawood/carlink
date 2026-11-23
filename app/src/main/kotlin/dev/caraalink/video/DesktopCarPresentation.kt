package dev.caraalink.video

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import dev.caraalink.R

/**
 * Car screen in full-screen apps mode: a narrow bar on the driver's side with Back,
 * the app list and Android Auto, and next to it the apps themselves. The apps run on
 * the Shizuku helper's display, which renders into this screen's [SurfaceView]; the
 * bar is ours, so it's always there whatever the app shows.
 */
class DesktopCarPresentation(
    context: Context,
    display: Display,
    /** The app area's surface and its size in pixels; null surface when it goes away. */
    private val onSurface: (surface: Surface?, width: Int, height: Int) -> Unit,
    /** A touch in the app area, in app-display pixels. */
    private val onTouch: (action: Int, x: Float, y: Float) -> Unit,
    private val onBack: () -> Unit,
    private val onApps: () -> Unit,
    private val onAndroidAuto: () -> Unit,
) : Presentation(context, display) {

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BACKGROUND)
            // The bar stays on the left (the driver's side) whatever the phone's language.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(BAR)
            setPadding(0, dp(8), 0, dp(8))
        }
        bar.addView(iconButton(R.drawable.ic_back) { onBack() }, weighted())
        bar.addView(iconButton(R.drawable.ic_apps) { onApps() }, weighted())
        bar.addView(iconButton(R.drawable.ic_car) { onAndroidAuto() }, weighted())
        root.addView(bar, LinearLayout.LayoutParams(dp(BAR_DP), LinearLayout.LayoutParams.MATCH_PARENT))

        val apps = SurfaceView(context)
        apps.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                onSurface(holder.surface, width, height)
            override fun surfaceDestroyed(holder: SurfaceHolder) = onSurface(null, 0, 0)
        })
        apps.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    onTouch(if (ev.actionMasked == MotionEvent.ACTION_CANCEL) MotionEvent.ACTION_UP else ev.actionMasked, ev.x, ev.y)
            }
            true
        }
        root.addView(apps, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        setContentView(root)
    }

    private fun weighted() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

    private fun pressable(view: View, onClick: () -> Unit) = view.apply {
        isClickable = true
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat() }
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> (v.background as GradientDrawable).setColor(PRESSED)
                MotionEvent.ACTION_UP -> {
                    (v.background as GradientDrawable).setColor(Color.TRANSPARENT)
                    onClick()
                }
                MotionEvent.ACTION_CANCEL -> (v.background as GradientDrawable).setColor(Color.TRANSPARENT)
            }
            true
        }
    }

    private fun iconButton(icon: Int, onClick: () -> Unit) = pressable(
        FrameLayout(context).apply {
            addView(
                ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(ACCENT)
                },
                FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER),
            )
        },
        onClick,
    )

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    companion object {
        /** Width of the bar; wide enough for a thumb on a small car screen. */
        const val BAR_DP = 64
        private val BACKGROUND = Color.parseColor("#0A0E12")
        private val BAR = Color.parseColor("#121A20")
        private val ACCENT = Color.parseColor("#5EEAD4")
        private val PRESSED = Color.parseColor("#0F2A2E")
    }
}
