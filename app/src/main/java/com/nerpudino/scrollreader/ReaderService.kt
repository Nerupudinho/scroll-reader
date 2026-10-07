package com.nerpudino.scrollreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.TileService
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.abs

/**
 * Reads the app currently on screen, then scrolls down and reads what's new,
 * over and over until the page stops changing.
 *
 * Loop:  collect text -> speak the new lines -> (speech done) -> scroll -> wait -> repeat
 */
class ReaderService : AccessibilityService() {

    companion object {
        private const val TAG = "ScrollReader"
        private const val MAX_SCREENS = 80          // safety stop for endless feeds
        private const val SETTLE_MS = 900L          // let the list finish moving before reading
        private const val SHADE_CLOSE_MS = 700L     // time for the notification shade to close
        private const val STALE_LIMIT = 2           // scrolls with nothing new => end of page

        @Volatile
        var instance: ReaderService? = null
            private set

        fun refreshTile(ctx: Context) {
            try {
                TileService.requestListeningState(
                    ctx, ComponentName(ctx, ReadTileService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Tile refresh failed", e)
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var bubble: ImageView? = null
    private var session: Session? = null
    private var sessionCounter = 0

    val isReading: Boolean get() = session != null

    /** State for one read-through of one app. */
    private class Session(val id: Int, val packageName: String?) {
        var screens = 0
        var staleRounds = 0
        var previousScreen: List<String> = emptyList()
        var seen: Set<String> = emptySet()   // text from the last two screens
        var lastUtteranceId: String? = null
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(utteranceListener)
            } else {
                Log.w(TAG, "Text-to-speech failed to start: $status")
            }
        }
        if (Prefs.showBubble(this)) showBubble()
        refreshTile(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing to do on events; reading is started by the bubble or the tile.
    }

    override fun onInterrupt() {
        stopReading(announce = false)
    }

    override fun onDestroy() {
        stopReading(announce = false)
        hideBubble()
        tts?.shutdown()
        tts = null
        instance = null
        refreshTile(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- public controls

    fun toggle() {
        if (isReading) stopReading(announce = true) else startReading()
    }

    /** From the Quick Settings tile: close the shade first so the app under it gets read. */
    fun toggleFromTile() {
        if (isReading) {
            stopReading(announce = true)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
        main.postDelayed({ startReading() }, SHADE_CLOSE_MS)
    }

    fun setBubbleVisible(visible: Boolean) {
        if (visible) showBubble() else hideBubble()
    }

    // ---------------------------------------------------------------- reading loop

    private fun startReading() {
        if (isReading) return
        val engine = tts
        if (engine == null || !ttsReady) {
            toast("Voice engine isn't ready yet. Try again in a moment.")
            return
        }
        val root = findAppRoot()
        if (root == null) {
            toast("Couldn't find an app on screen to read.")
            return
        }
        engine.setSpeechRate(Prefs.speechRate(this))
        session = Session(++sessionCounter, root.packageName?.toString())
        updateUi()
        readScreen()
    }

    fun stopReading(announce: Boolean) {
        if (session == null) return
        endSession(if (announce) "Stopped" else null)
    }

    private fun endSession(message: String?) {
        session = null
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        if (message != null) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "notice")
        }
        updateUi()
    }

    private fun readScreen() {
        val s = session ?: return
        val root = findAppRoot()
        if (root == null) {
            endSession("Lost the screen. Stopped.")
            return
        }
        val pkg = root.packageName?.toString()
        if (s.packageName != null && pkg != s.packageName) {
            endSession("App changed. Stopped.")
            return
        }

        val items = TextCollector.collect(root, statusBarHeight())
        // Skip anything already read on the last two screens: this removes the
        // overlap between scrolls and fixed headers/tab bars that never move.
        val fresh = items.filter { it !in s.seen }
        s.seen = s.previousScreen.toSet() + items
        s.previousScreen = items

        if (fresh.isEmpty()) {
            s.staleRounds++
            if (s.staleRounds >= STALE_LIMIT) {
                endSession("End of page")
            } else {
                scrollThenRead(s)
            }
            return
        }

        s.staleRounds = 0
        s.screens++
        speak(s, fresh)
    }

    private fun speak(s: Session, lines: List<String>) {
        val engine = tts
        if (engine == null) {
            endSession(null)
            return
        }
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val chunks = lines.flatMap { it.chunked(max) }
        chunks.forEachIndexed { i, chunk ->
            val id = "${s.id}:${s.screens}:$i"
            if (i == chunks.lastIndex) s.lastUtteranceId = id
            engine.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            main.post { onUtteranceFinished(utteranceId) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { onUtteranceFinished(utteranceId) }
        }
    }

    private fun onUtteranceFinished(utteranceId: String?) {
        val s = session ?: return
        if (utteranceId == null || utteranceId != s.lastUtteranceId) return
        s.lastUtteranceId = null
        if (s.screens >= MAX_SCREENS) {
            endSession("Stopped after $MAX_SCREENS screens")
            return
        }
        scrollThenRead(s)
    }

    // ---------------------------------------------------------------- scrolling

    /**
     * Prefer the app's own "scroll down" action on its main vertical list
     * (exact, one full screen). If the app doesn't offer one (web pages, many
     * custom views), fall back to a real finger-swipe gesture, which works
     * almost everywhere. Horizontal lists and tab pagers are never used, so
     * reading can't flip you to another tab.
     */
    private fun scrollThenRead(s: Session) {
        val window = findAppWindow()
        val root = window?.root
        if (window == null || root == null) {
            endSession("Lost the screen. Stopped.")
            return
        }

        val list = findVerticalScrollable(root)
        if (list != null &&
            list.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
        ) {
            readAfterSettle(s)
            return
        }

        val area = Rect().also { window.getBoundsInScreen(it) }
        swipeUp(area) { ok ->
            if (session !== s) return@swipeUp
            if (ok) readAfterSettle(s) else endSession("Couldn't scroll this screen")
        }
    }

    private fun readAfterSettle(s: Session) {
        main.postDelayed({ if (session === s) readScreen() }, SETTLE_MS)
    }

    private fun findVerticalScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val scrollDownId = AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val r = Rect()

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 80 || !n.isVisibleToUser) return
            if (n.actionList.any { it.id == scrollDownId }) {
                n.getBoundsInScreen(r)
                val area = r.width() * r.height()
                if (area > bestArea) {
                    best = n
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) {
                n.getChild(i)?.let { visit(it, depth + 1) }
            }
        }

        visit(root, 0)
        return best
    }

    /** A slow drag from ~72% to ~30% of the app's height: moves content up ~40%. */
    private fun swipeUp(area: Rect, done: (Boolean) -> Unit) {
        if (area.isEmpty) {
            done(false)
            return
        }
        val x = area.exactCenterX()
        val startY = area.top + area.height() * 0.72f
        val endY = area.top + area.height() * 0.30f
        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 450L))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true)
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false)
        }, main)
        if (!accepted) done(false)
    }

    // ---------------------------------------------------------------- finding the app

    /**
     * The app's own window. The status bar, navigation bar, keyboard and this
     * service's floating button are all different window types, so they're
     * left out by picking TYPE_APPLICATION only.
     */
    private fun findAppWindow(): AccessibilityWindowInfo? {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        return apps.firstOrNull { it.isActive }
            ?: apps.firstOrNull { it.isFocused }
            ?: apps.maxByOrNull { w ->
                val r = Rect()
                w.getBoundsInScreen(r)
                r.width() * r.height()
            }
    }

    private fun findAppRoot(): AccessibilityNodeInfo? = findAppWindow()?.root

    @SuppressLint("DiscouragedApi", "InternalInsetResource")
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    // ---------------------------------------------------------------- floating button

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = dp(54)
        val dm = resources.displayMetrics

        val view = ImageView(this).apply {
            setImageResource(R.drawable.ic_play)
            val pad = dp(15)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xE61F4E79.toInt())
            }
            contentDescription = "Start reading"
        }

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.bubbleX(this@ReaderService, dm.widthPixels - size - dp(8))
            y = Prefs.bubbleY(this@ReaderService, dm.heightPixels / 3)
        }

        view.setOnTouchListener(BubbleTouch(wm, params))
        try {
            wm.addView(view, params)
            bubble = view
            updateUi()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't show floating button", e)
        }
    }

    private fun hideBubble() {
        val view = bubble ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't remove floating button", e)
        }
        bubble = null
    }

    /** Tap = start/stop. Drag = move the button (position is remembered). */
    private inner class BubbleTouch(
        private val wm: WindowManager,
        private val p: WindowManager.LayoutParams,
    ) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(this@ReaderService).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = p.x
                    startY = p.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) dragging = true
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        wm.updateViewLayout(v, p)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        Prefs.setBubblePos(this@ReaderService, p.x, p.y)
                    } else {
                        v.performClick()
                        toggle()
                    }
                }
            }
            return true
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun updateUi() {
        bubble?.let {
            it.setImageResource(if (isReading) R.drawable.ic_stop else R.drawable.ic_play)
            it.contentDescription = if (isReading) "Stop reading" else "Start reading"
        }
        refreshTile(this)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
