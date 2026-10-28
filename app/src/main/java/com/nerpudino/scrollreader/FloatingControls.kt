package com.nerpudino.scrollreader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Everything Scroll Reader draws on top of other apps:
 *  - Idle:    the ▶ button. Tap = read from the top of the screen.
 *             Long-press = pick the line to start from. Drag = move.
 *             Drop it near the left/right edge and it tucks in as a slim tab
 *             (like Wispr); drop it anywhere else and it floats there.
 *  - Reading: a control bar  [drag] ⏮ ⏯ ⏭  −  1.0×  +  ■  (beside the docked edge)
 *  - A highlight box around the text being read.
 *  - A full-screen "tap where to start" layer while picking.
 *
 * Whatever happens, the controls are kept fully inside the screen, so the
 * button can't get lost off an edge.
 */
class FloatingControls(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onPlayTap()
        fun onPlayLongPress()
        fun onPauseResume()
        fun onBack()
        fun onSkip()
        fun onSlower()
        fun onFaster()
        fun onStop()
        fun onPickedPoint(y: Int)
        fun onPickCancelled()
    }

    private companion object {
        const val TAG = "ScrollReader"
        const val BG = 0xE61F4E79.toInt()
        const val HIGHLIGHT = 0xFFFFC107.toInt()
        const val HIGHLIGHT_FILL = 0x22FFC107

        const val BUBBLE_DP = 54
        const val TAB_W_DP = 26
        const val TAB_H_DP = 64
        const val DOCK_ZONE = 0.20f   // drop within 20% of the screen width from an edge = dock there
    }

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private val container = FrameLayout(ctx)
    private val params: WindowManager.LayoutParams
    private var shown = false

    /** One view for the idle button: a round bubble when floating, a slim tab when docked. */
    private val idle: ImageView
    private val bar: LinearLayout
    private val pauseButton: ImageView
    private val speedLabel: TextView

    private var dock = Prefs.dockSide(ctx)

    private var highlightView: View? = null
    private var highlightParams: WindowManager.LayoutParams? = null
    private var picker: View? = null

    init {
        idle = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_play)
            contentDescription = "Start reading. Long press to choose where to start."
            layoutParams = FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP))
        }
        idle.setOnTouchListener(
            DragTouch(
                onTap = { listener.onPlayTap() },
                onLongPress = { listener.onPlayLongPress() },
                onDragStart = { styleIdle(Prefs.DOCK_NONE) },
                onDrop = { dropIdle() },
            )
        )

        speedLabel = TextView(ctx).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            minWidth = dp(40)
            contentDescription = "Reading speed"
        }
        pauseButton = iconButton(R.drawable.ic_pause, "Pause") { listener.onPauseResume() }

        val drag = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_drag)
            alpha = 0.7f
            val p = dp(10)
            setPadding(dp(4), p, 0, p)
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(48))
            contentDescription = "Drag to move"
        }
        drag.setOnTouchListener(
            DragTouch(onTap = null, onLongPress = null, onDragStart = {}, onDrop = { dropBar() })
        )

        bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(6), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(BG)
            }
            addView(drag)
            addView(iconButton(R.drawable.ic_prev, "Back") { listener.onBack() })
            addView(pauseButton)
            addView(iconButton(R.drawable.ic_next, "Skip") { listener.onSkip() })
            addView(textButton("−", "Slower") { listener.onSlower() })
            addView(speedLabel)
            addView(textButton("+", "Faster") { listener.onFaster() })
            addView(iconButton(R.drawable.ic_stop, "Stop") { listener.onStop() })
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        params = overlayParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        container.addView(idle)
        loadPosition()
    }

    // ---------------------------------------------------------------- public

    fun show() {
        if (shown) return
        try {
            clampAndSnap()
            wm.addView(container, params)
            shown = true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show controls", e)
        }
    }

    fun hide() {
        if (!shown) return
        safeRemove(container)
        shown = false
    }

    fun destroy() {
        hide()
        highlight(null)
        stopPicking()
    }

    /** Back to the default spot (tucked into the right edge). */
    fun resetPosition() {
        Prefs.resetControlsPosition(ctx)
        loadPosition()
        relayout()
    }

    /** Screen size or rotation changed: pull the controls back inside the screen. */
    fun refit() {
        clampAndSnap()
        relayout()
    }

    fun showIdle() {
        if (idle.parent == null) {
            container.removeAllViews()
            container.addView(idle)
        }
        styleIdle(dock)
        clampAndSnap()
        relayout()
    }

    fun showReading(paused: Boolean, rate: Float) {
        if (bar.parent == null) {
            container.removeAllViews()
            container.addView(bar)
            clampAndSnap()
            relayout()
        }
        pauseButton.setImageResource(if (paused) R.drawable.ic_play else R.drawable.ic_pause)
        pauseButton.contentDescription = if (paused) "Resume" else "Pause"
        speedLabel.text = formatRate(rate)
    }

    /** Keep the screen awake while the controls window is visible. */
    fun setKeepScreenOn(on: Boolean) = setFlag(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON, on)

    /** While a swipe gesture runs, let touches go straight through the controls. */
    fun setPassThrough(on: Boolean) = setFlag(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, on)

    /** Draw a box around [bounds] (screen coordinates), or remove it when null. */
    fun highlight(bounds: Rect?) {
        if (bounds == null || bounds.isEmpty) {
            highlightView?.let { safeRemove(it) }
            highlightView = null
            highlightParams = null
            return
        }
        val pad = dp(4)
        val view = highlightView ?: View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setStroke(dp(3), HIGHLIGHT)
                setColor(HIGHLIGHT_FILL)
            }
        }
        val p = highlightParams ?: overlayParams(
            0, 0,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        p.x = bounds.left - pad
        p.y = bounds.top - pad
        p.width = bounds.width() + pad * 2
        p.height = bounds.height() + pad * 2
        try {
            if (highlightView == null) {
                wm.addView(view, p)
                highlightView = view
                highlightParams = p
                // Keep the controls above the highlight.
                if (shown) {
                    safeRemove(container)
                    wm.addView(container, params)
                }
            } else {
                wm.updateViewLayout(view, p)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Highlight failed", e)
        }
    }

    /** Full-screen layer: the next tap tells us where to start reading. */
    fun startPicking() {
        if (picker != null) return
        val layer = FrameLayout(ctx).apply {
            setBackgroundColor(0x55000000)
            addView(TextView(ctx).apply {
                text = "Tap where you want reading to start"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(BG)
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                ).apply { topMargin = dp(80) }
            })
            addView(TextView(ctx).apply {
                text = "✕  Cancel"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(dp(20), dp(12), dp(20), dp(12))
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(BG)
                }
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply { bottomMargin = dp(96) }
                setOnClickListener { listener.onPickCancelled() }
            })
        }
        layer.setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                v.performClick()
                listener.onPickedPoint(e.rawY.toInt())
            }
            true
        }
        val p = overlayParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        try {
            wm.addView(layer, p)
            picker = layer
        } catch (e: Exception) {
            Log.w(TAG, "Picker failed", e)
        }
    }

    fun stopPicking() {
        picker?.let { safeRemove(it) }
        picker = null
    }

    // ---------------------------------------------------------------- position & docking

    private fun loadPosition() {
        dock = Prefs.dockSide(ctx)
        val screen = screenSize()
        params.x = Prefs.bubbleX(ctx, screen.x - dp(BUBBLE_DP + 8))
        params.y = Prefs.bubbleY(ctx, screen.y / 3)
        styleIdle(dock)
        clampAndSnap()
    }

    /** Round bubble when floating; a slim tab with rounded inner corners when docked. */
    private fun styleIdle(side: String) {
        val r = dp(14).toFloat()
        when (side) {
            Prefs.DOCK_LEFT, Prefs.DOCK_RIGHT -> {
                idle.layoutParams = FrameLayout.LayoutParams(dp(TAB_W_DP), dp(TAB_H_DP))
                val p = dp(5)
                idle.setPadding(p, p, p, p)
                idle.background = GradientDrawable().apply {
                    setColor(BG)
                    // top-left, top-right, bottom-right, bottom-left (x, y pairs)
                    cornerRadii = if (side == Prefs.DOCK_RIGHT) {
                        floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    } else {
                        floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    }
                }
            }
            else -> {
                idle.layoutParams = FrameLayout.LayoutParams(dp(BUBBLE_DP), dp(BUBBLE_DP))
                val p = dp(15)
                idle.setPadding(p, p, p, p)
                idle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(BG)
                }
            }
        }
        idle.requestLayout()
    }

    /** Idle button dropped: tuck into the nearer edge if close to it, else float there. */
    private fun dropIdle() {
        val screen = screenSize()
        val size = measured()
        val centerX = params.x + size.x / 2
        dock = when {
            centerX < screen.x * DOCK_ZONE -> Prefs.DOCK_LEFT
            centerX > screen.x * (1 - DOCK_ZONE) -> Prefs.DOCK_RIGHT
            else -> Prefs.DOCK_NONE
        }
        styleIdle(dock)
        clampAndSnap()
        relayout()
        save()
    }

    /** Control bar dropped: keep it on screen; remember the height for the tab too. */
    private fun dropBar() {
        clampAndSnap()
        relayout()
        save()
    }

    private fun save() {
        Prefs.setDockSide(ctx, dock)
        Prefs.setBubblePos(ctx, params.x, params.y)
    }

    /** Keep the whole window inside the screen; stick to the docked edge if docked. */
    private fun clampAndSnap() {
        val screen = screenSize()
        val size = measured()
        val maxX = (screen.x - size.x).coerceAtLeast(0)
        val maxY = (screen.y - size.y).coerceAtLeast(0)
        params.x = when (dock) {
            Prefs.DOCK_LEFT -> 0
            Prefs.DOCK_RIGHT -> maxX
            else -> params.x.coerceIn(0, maxX)
        }
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun measured(): android.graphics.Point {
        container.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        return android.graphics.Point(container.measuredWidth, container.measuredHeight)
    }

    /** Current screen size in pixels (follows rotation). */
    private fun screenSize(): android.graphics.Point =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            android.graphics.Point(b.width(), b.height())
        } else {
            val dm = ctx.resources.displayMetrics
            android.graphics.Point(dm.widthPixels, dm.heightPixels)
        }

    // ---------------------------------------------------------------- helpers

    private fun setFlag(flag: Int, on: Boolean) {
        val newFlags = if (on) params.flags or flag else params.flags and flag.inv()
        if (newFlags != params.flags) {
            params.flags = newFlags
            relayout()
        }
    }

    private fun formatRate(rate: Float): String {
        val r = (rate * 100).toInt() / 100f
        return if (r == r.toInt().toFloat()) "${r.toInt()}×" else "${"%.2f".format(r).trimEnd('0')}×"
    }

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
        } else {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun iconButton(res: Int, desc: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(res)
        contentDescription = desc
        val p = dp(11)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(dp(46), dp(48))
        setOnClickListener { onClick() }
    }

    private fun textButton(label: String, desc: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        contentDescription = desc
        setTextColor(0xFFFFFFFF.toInt())
        textSize = 24f
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(48))
        setOnClickListener { onClick() }
    }

    private fun relayout() {
        if (!shown) return
        try {
            wm.updateViewLayout(container, params)
        } catch (e: Exception) {
            Log.w(TAG, "Relayout failed", e)
        }
    }

    private fun safeRemove(v: View) {
        try {
            wm.removeView(v)
        } catch (e: Exception) {
            Log.w(TAG, "Remove failed", e)
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Tap / long-press / drag on a handle; dragging moves the whole control window. */
    private inner class DragTouch(
        private val onTap: (() -> Unit)?,
        private val onLongPress: (() -> Unit)?,
        private val onDragStart: () -> Unit,
        private val onDrop: () -> Unit,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPressRunnable = Runnable {
            longPressed = true
            onLongPress?.invoke()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    longPressed = false
                    if (onLongPress != null) main.postDelayed(longPressRunnable, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        main.removeCallbacks(longPressRunnable)
                        onDragStart()
                    }
                    if (dragging) {
                        // Follow the finger, but never past the screen edges.
                        val screen = screenSize()
                        val size = measured()
                        params.x = (startX + dx.toInt()).coerceIn(0, (screen.x - size.x).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (screen.y - size.y).coerceAtLeast(0))
                        relayout()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPressRunnable)
                    when {
                        dragging -> onDrop()
                        !longPressed -> {
                            v.performClick()
                            onTap?.invoke()
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPressRunnable)
                    if (dragging) onDrop()
                }
            }
            return true
        }
    }
}
