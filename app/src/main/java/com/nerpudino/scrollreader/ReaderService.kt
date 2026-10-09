package com.nerpudino.scrollreader

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.quicksettings.TileService
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast

/**
 * Reads the app on screen block by block, scrolling down for more when it
 * runs out, until the page stops changing.
 *
 * Everything read so far sits in one queue, so the controls can move through it:
 *   Pause/Resume  - resumes at the word where it stopped
 *   Back          - restart this block, or (if near its start) go to the previous block
 *   Skip          - next block (scrolls for more when needed)
 *   − / +         - speed, applied immediately from the current word
 */
class ReaderService : AccessibilityService(), FloatingControls.Listener {

    companion object {
        private const val TAG = "ScrollReader"
        private const val MAX_SCREENS = 80          // safety stop for endless feeds
        private const val SETTLE_MS = 900L          // let the list finish moving before reading
        private const val SHADE_CLOSE_MS = 700L     // time for the notification shade to close
        private const val STALE_LIMIT = 2           // scrolls with nothing new => end of page
        private const val RATE_STEP = 0.25f
        private const val BACK_RESTART_CHARS = 20   // past this far into a block, Back restarts it

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
    private var controls: FloatingControls? = null

    // Audio diagnostics: log where the voice goes and every Bluetooth/route change.
    private var lastPlayers = ""
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (added.any { it.isSink }) {
                diag("Output connected: " + added.filter { it.isSink }.joinToString { AudioProbe.device(it) } +
                    "\n  " + AudioProbe.compact(this@ReaderService))
            }
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.isSink }) {
                diag("Output DISCONNECTED: " + removed.filter { it.isSink }.joinToString { AudioProbe.device(it) } +
                    "\n  " + AudioProbe.compact(this@ReaderService))
            }
        }
    }
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            if (session == null) return
            val now = AudioProbe.activePlayers(this@ReaderService)
            if (now != lastPlayers) {
                lastPlayers = now
                diag("Players now: $now")
            }
        }
    }

    private fun diag(msg: String) = DiagLog.log(this, msg)

    // Screen: keep it awake while reading; if it goes off anyway, pause and resume on unlock.
    private var wakeLock: PowerManager.WakeLock? = null
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val s = session ?: return
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> diag("Screen turned OFF while reading (paused=${s.paused})")
                Intent.ACTION_SCREEN_ON -> {
                    diag("Screen turned on")
                    if (!isLocked()) resumeAfterScreen(s)
                }
                Intent.ACTION_USER_PRESENT -> {
                    diag("Phone unlocked")
                    resumeAfterScreen(s)
                }
            }
        }
    }

    private fun screenUsable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive && !isLocked()

    private fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Needed to scroll but the screen is off/locked: pause in place instead of stopping. */
    private fun pauseForScreen(s: Session) {
        diag("Can't scroll: screen off or locked. Pausing at block ${s.pos}.")
        s.loading = false
        s.paused = true
        s.screenPaused = true
        silence(s)
        controls?.highlight(null)
        tts?.speak("Paused. Unlock your phone to continue.", TextToSpeech.QUEUE_FLUSH, null, "notice")
        updateUi()
    }

    private fun resumeAfterScreen(s: Session) {
        if (!s.screenPaused) return
        s.screenPaused = false
        s.paused = false
        diag("Resuming after unlock")
        updateUi()
        // Give the app a moment to come back to the front before scrolling.
        main.postDelayed({ if (session === s && !s.paused) speakCurrent(s) }, 1000)
    }

    /** Keep the screen on only while actively reading (not while paused). */
    @Suppress("DEPRECATION")
    private fun applyScreenHold() {
        val s = session
        val hold = s != null && !s.paused && Prefs.keepScreenOn(this)
        controls?.setKeepScreenOn(hold)
        if (hold) {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "ScrollReader:reading")
                    .apply {
                        setReferenceCounted(false)
                        acquire(3 * 60 * 60 * 1000L) // safety cap: 3 hours
                    }
            }
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
        }
    }

    private var session: Session? = null
    private var sessionCounter = 0

    val isReading: Boolean get() = session != null

    /** State for one read-through of one app. */
    private class Session(val id: Int, val packageName: String?) {
        val queue = ArrayList<Line>()
        var pos = 0                 // block being read
        var offset = 0              // character in that block to resume from
        var speakBase = 0           // offset the current utterance started at
        var speakingId: String? = null
        var utterances = 0

        var batch = 0               // screens loaded so far (0 = first screen)
        var staleRounds = 0
        var previousTexts: Set<String> = emptySet()
        var seen: Set<String> = emptySet()   // text from the last two screens

        var paused = false
        var screenPaused = false    // paused automatically because the screen went off
        var loading = false
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        tts = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(utteranceListener)
                tts?.setSpeechRate(Prefs.speechRate(this))
                diag("Service on (v${BuildConfig.VERSION_NAME}). Voice engine: ${tts?.defaultEngine}" +
                    ", installed: ${tts?.engines?.joinToString { it.name }}")
            } else {
                diag("Voice engine FAILED to start: $status")
                Log.w(TAG, "Text-to-speech failed to start: $status")
            }
        }
        controls = FloatingControls(this, this).also {
            if (Prefs.showBubble(this)) it.show()
        }
        try {
            val am = AudioProbe.am(this)
            am.registerAudioDeviceCallback(deviceCallback, main)
            am.registerAudioPlaybackCallback(playbackCallback, main)
        } catch (e: Exception) {
            Log.w(TAG, "Audio callbacks failed", e)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
        refreshTile(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopReading(announce = false)
    }

    override fun onDestroy() {
        stopReading(announce = false)
        controls?.destroy()
        controls = null
        try {
            val am = AudioProbe.am(this)
            am.unregisterAudioDeviceCallback(deviceCallback)
            am.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Audio callbacks removal failed", e)
        }
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Screen receiver removal failed", e)
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        tts?.shutdown()
        tts = null
        instance = null
        refreshTile(this)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- outside controls

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
        main.postDelayed({ startReading(fromY = null) }, SHADE_CLOSE_MS)
    }

    fun setBubbleVisible(visible: Boolean) {
        if (visible) controls?.show() else controls?.hide()
    }

    fun stopReading(announce: Boolean) {
        if (session == null) return
        endSession(if (announce) "Stopped" else null)
    }

    // ---------------------------------------------------------------- FloatingControls.Listener

    override fun onPlayTap() = startReading(fromY = null)

    override fun onPlayLongPress() {
        if (!checkReady()) return
        controls?.startPicking()
    }

    override fun onPickedPoint(y: Int) {
        controls?.stopPicking()
        // Give the system a moment to remove the layer before reading the app.
        main.postDelayed({ startReading(fromY = y) }, 200)
    }

    override fun onPickCancelled() {
        controls?.stopPicking()
    }

    override fun onPauseResume() {
        val s = session ?: return
        s.paused = !s.paused
        s.screenPaused = false
        if (s.paused) {
            silence(s)
        } else {
            speakCurrent(s)
        }
        updateUi()
    }

    override fun onSkip() {
        val s = session ?: return
        silence(s)
        s.pos = (s.pos + 1).coerceAtMost(s.queue.size)
        s.offset = 0
        moveTo(s)
    }

    override fun onBack() {
        val s = session ?: return
        silence(s)
        if (s.offset > BACK_RESTART_CHARS) {
            s.offset = 0                                   // restart this block
        } else {
            s.pos = (s.pos - 1).coerceAtLeast(0)           // previous block
            s.offset = 0
        }
        if (s.pos >= s.queue.size) s.pos = (s.queue.size - 1).coerceAtLeast(0)
        moveTo(s)
    }

    override fun onSlower() = changeRate(-RATE_STEP)
    override fun onFaster() = changeRate(+RATE_STEP)

    override fun onStop() {
        stopReading(announce = true)
    }

    // ---------------------------------------------------------------- reading

    private fun checkReady(): Boolean {
        if (tts == null || !ttsReady) {
            toast("Voice engine isn't ready yet. Try again in a moment.")
            return false
        }
        return true
    }

    /**
     * Start reading the app on screen.
     * [fromY] = screen position the user tapped; blocks above it are skipped.
     * null = start from the top of what's visible now (never scrolls up).
     */
    private fun startReading(fromY: Int?) {
        if (isReading || !checkReady()) return
        val root = findAppRoot()
        if (root == null) {
            toast("Couldn't find an app on screen to read.")
            return
        }
        tts?.setSpeechRate(Prefs.speechRate(this))
        val s = Session(++sessionCounter, root.packageName?.toString())
        session = s

        val lines = collectScreen(s, root)
        val start = if (fromY == null) {
            lines
        } else {
            // First block whose bottom is below the tap: the one tapped, or the next one down.
            lines.filter { it.bounds.bottom > fromY }
        }
        s.queue.addAll(start)
        lastPlayers = ""
        diag(AudioProbe.snapshot(this, "READING STARTED in ${s.packageName} (${start.size} blocks)"))
        updateUi()
        speakCurrent(s)
    }

    /** Text on the current screen that wasn't on the previous two screens. */
    private fun collectScreen(s: Session, root: AccessibilityNodeInfo): List<Line> {
        val items = TextCollector.collect(root, statusBarHeight(), s.batch)
        val texts = items.map { it.text }
        val fresh = items.filter { it.text !in s.seen }
        s.seen = s.previousTexts + texts
        s.previousTexts = texts.toSet()
        return fresh
    }

    private fun speakCurrent(s: Session) {
        if (session !== s || s.paused || s.loading) return
        if (s.pos >= s.queue.size) {
            loadMore(s) { speakCurrent(s) }
            return
        }
        val line = s.queue[s.pos]
        highlight(s)
        if (s.offset >= line.text.length) s.offset = 0
        val max = TextToSpeech.getMaxSpeechInputLength() - 1
        val text = line.text.substring(s.offset).take(max)
        val id = "${s.id}:${++s.utterances}"
        s.speakingId = id
        s.speakBase = s.offset
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    /** After Back/Skip: read from the new spot, or just move the highlight if paused. */
    private fun moveTo(s: Session) {
        if (!s.paused) {
            speakCurrent(s)
            return
        }
        if (s.pos < s.queue.size) {
            highlight(s)
        } else if (!s.loading) {
            loadMore(s) { highlight(s) }
        }
    }

    /** Stop the voice but keep our place (s.offset holds the current word). */
    private fun silence(s: Session) {
        s.speakingId = null
        tts?.stop()
    }

    private fun changeRate(delta: Float) {
        val rate = (Prefs.speechRate(this) + delta)
            .coerceIn(Prefs.MIN_RATE, Prefs.MAX_RATE)
        Prefs.setSpeechRate(this, rate)
        tts?.setSpeechRate(rate)
        val s = session ?: return
        // Re-start from the current word so the new speed applies right away.
        if (s.speakingId != null && !s.paused) {
            silence(s)
            speakCurrent(s)
        }
        updateUi()
    }

    private fun highlight(s: Session) {
        val line = s.queue.getOrNull(s.pos)
        // Only boxes from the screen currently showing are still in the right place.
        controls?.highlight(line?.takeIf { it.batch == s.batch }?.bounds)
    }

    private val utteranceListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            // Give the engine's player a moment to appear, then record where it's playing.
            main.postDelayed({
                diag("Voice started #$utteranceId -> ${AudioProbe.compact(this@ReaderService)}")
            }, 400)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            diag("Voice ERROR #$utteranceId code $errorCode")
            main.post { onUtteranceFinished(utteranceId) }
        }

        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
            main.post {
                val s = session ?: return@post
                if (utteranceId != null && utteranceId == s.speakingId) {
                    s.offset = s.speakBase + start
                }
            }
        }

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
        if (utteranceId == null || utteranceId != s.speakingId) return
        s.speakingId = null
        s.pos++
        s.offset = 0
        speakCurrent(s)
    }

    private fun endSession(message: String?) {
        diag("Reading ended: ${message ?: "(silent stop)"}")
        session = null
        main.removeCallbacksAndMessages(null)
        tts?.stop()
        if (message != null) {
            tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "notice")
        }
        controls?.highlight(null)
        controls?.setPassThrough(false)
        updateUi()
    }

    // ---------------------------------------------------------------- scrolling

    /** Scroll down, then add whatever new text appeared to the queue. */
    private fun loadMore(s: Session, then: () -> Unit) {
        if (s.batch + 1 >= MAX_SCREENS) {
            endSession("Stopped after $MAX_SCREENS screens")
            return
        }
        if (!screenUsable()) {
            pauseForScreen(s)
            return
        }
        s.loading = true
        controls?.highlight(null)
        scroll { ok ->
            if (session !== s) return@scroll
            if (!ok) {
                if (!screenUsable()) pauseForScreen(s) else endSession("Couldn't scroll this screen")
                return@scroll
            }
            main.postDelayed({ afterScroll(s, then) }, SETTLE_MS)
        }
    }

    private fun afterScroll(s: Session, then: () -> Unit) {
        if (session !== s) return
        if (!screenUsable()) {
            pauseForScreen(s)
            return
        }
        val root = findAppRoot()
        if (root == null) {
            endSession("Lost the screen. Stopped.")
            return
        }
        if (s.packageName != null && root.packageName?.toString() != s.packageName) {
            endSession("App changed. Stopped.")
            return
        }
        s.batch++
        val fresh = collectScreen(s, root)
        if (fresh.isEmpty()) {
            s.staleRounds++
            if (s.staleRounds >= STALE_LIMIT) {
                endSession("End of page")
            } else {
                loadMore(s, then)
            }
            return
        }
        s.staleRounds = 0
        s.queue.addAll(fresh)
        s.loading = false
        then()
    }

    /**
     * Prefer the app's own "scroll down" action on its main vertical list.
     * Otherwise a real finger-swipe. Horizontal lists and tab pagers are never
     * used, so reading can't flip you to another tab.
     */
    private fun scroll(done: (Boolean) -> Unit) {
        val window = findAppWindow()
        val root = window?.root
        if (window == null || root == null) {
            done(false)
            return
        }
        val list = findVerticalScrollable(root)
        if (list != null &&
            list.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
        ) {
            done(true)
            return
        }
        val area = Rect().also { window.getBoundsInScreen(it) }
        controls?.setPassThrough(true)
        swipeUp(area) { ok ->
            controls?.setPassThrough(false)
            done(ok)
        }
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
        val path = Path().apply {
            moveTo(x, area.top + area.height() * 0.72f)
            lineTo(x, area.top + area.height() * 0.30f)
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
     * The app's own window. The status bar, navigation bar, keyboard and our
     * own floating controls are all different window types, so they're left
     * out by picking TYPE_APPLICATION only.
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

    // ---------------------------------------------------------------- helpers

    private fun updateUi() {
        val s = session
        if (s == null) {
            controls?.showIdle()
        } else {
            controls?.showReading(s.paused, Prefs.speechRate(this))
        }
        applyScreenHold()
        refreshTile(this)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
