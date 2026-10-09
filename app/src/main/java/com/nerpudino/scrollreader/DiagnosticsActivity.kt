package com.nerpudino.scrollreader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Audio diagnostics: plays a test sentence on each audio channel Android offers
 * and logs exactly which device it went to, so we can see which channels reach
 * the car speakers.
 */
class DiagnosticsActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private lateinit var logView: TextView
    private lateinit var status: TextView

    private var focusRequest: AudioFocusRequest? = null
    private var scoActive = false
    private var currentTest: Test? = null

    private data class Test(
        val number: Int,
        val name: String,
        val usage: Int,
        val focus: Int?,      // audio focus to request, or null for none
        val callAudio: Boolean,
    )

    private val tests = listOf(
        Test(1, "Media, no audio focus. This is what Scroll Reader uses today", AudioAttributes.USAGE_MEDIA, null, false),
        Test(2, "Media, with audio focus", AudioAttributes.USAGE_MEDIA, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, false),
        Test(3, "Accessibility channel", AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, false),
        Test(4, "Navigation voice channel, like Google Maps directions", AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, false),
        Test(5, "Phone call channel, over Bluetooth call audio", AudioAttributes.USAGE_VOICE_COMMUNICATION, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, true),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        tts = TextToSpeech(this) { s ->
            ready = s == TextToSpeech.SUCCESS
            if (ready) {
                tts?.setOnUtteranceProgressListener(listener)
                tts?.setSpeechRate(Prefs.speechRate(this))
                status.text = "Ready. Voice engine: ${tts?.defaultEngine}"
            } else {
                status.text = "Voice engine failed to start (code $s)"
            }
            log("Diagnostics opened (v${BuildConfig.VERSION_NAME}). Voice engine: ${tts?.defaultEngine}, ready=$ready")
        }
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    override fun onDestroy() {
        finishTest()
        tts?.shutdown()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tests

    private fun runTest(t: Test) {
        if (!ready) {
            toast("Voice engine isn't ready yet")
            return
        }
        finishTest()
        tts?.stop()
        currentTest = t
        val attrs = AudioProbe.attrs(t.usage)
        log(AudioProbe.snapshot(this, "TEST ${t.number}: ${t.name}"))

        t.focus?.let { requestFocus(attrs, it) }
        if (t.callAudio) {
            startCallAudio()
            status.text = "Test ${t.number}: connecting Bluetooth call audio…"
            // Bluetooth call audio takes a moment to connect.
            main.postDelayed({ speak(t, attrs) }, 2500)
        } else {
            speak(t, attrs)
        }
    }

    private fun speak(t: Test, attrs: AudioAttributes) {
        if (currentTest !== t) return
        tts?.setAudioAttributes(attrs)
        status.text = "Playing test ${t.number}…"
        tts?.speak(
            "Test ${t.number}. ${t.name}. If you can hear this on the car speakers, remember test ${t.number}.",
            TextToSpeech.QUEUE_FLUSH, null, "test-${t.number}"
        )
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(id: String?) {
            main.postDelayed({
                log("$id playing -> ${AudioProbe.activePlayers(this@DiagnosticsActivity)}" +
                    " | call-audio device: ${callDevice()}")
            }, 500)
        }

        override fun onDone(id: String?) {
            main.post {
                log("$id finished")
                status.text = "Done. Which tests did you hear in the car?"
                finishTest()
            }
        }

        override fun onError(id: String?, errorCode: Int) {
            main.post {
                log("$id ERROR code $errorCode")
                status.text = "Error $errorCode"
                finishTest()
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(id: String?) = onError(id, -1)
    }

    private fun requestFocus(attrs: AudioAttributes, gain: Int) {
        val req = AudioFocusRequest.Builder(gain).setAudioAttributes(attrs).build()
        val r = AudioProbe.am(this).requestAudioFocus(req)
        focusRequest = req
        log("Audio focus request -> " + when (r) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "GRANTED"
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "FAILED"
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "DELAYED"
            else -> "result $r"
        })
    }

    @Suppress("DEPRECATION")
    private fun startCallAudio() {
        val am = AudioProbe.am(this)
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val devices = am.availableCommunicationDevices
            log("Call-audio devices available: " + devices.joinToString { AudioProbe.device(it) })
            val bt = devices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
            if (bt == null) {
                log("No Bluetooth call-audio device available (car not connected for calls?)")
            } else {
                log("Switch call audio to ${AudioProbe.device(bt)} -> ${am.setCommunicationDevice(bt)}")
            }
        } else {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
            log("Started Bluetooth SCO (legacy)")
        }
        scoActive = true
    }

    @Suppress("DEPRECATION")
    private fun finishTest() {
        val am = AudioProbe.am(this)
        focusRequest?.let { am.abandonAudioFocusRequest(it) }
        focusRequest = null
        if (scoActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.isBluetoothScoOn = false
                am.stopBluetoothSco()
            }
            am.mode = AudioManager.MODE_NORMAL
            scoActive = false
        }
        currentTest = null
        refreshLog()
    }

    private fun callDevice(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioProbe.device(AudioProbe.am(this).communicationDevice)
        } else {
            "?"
        }

    // ---------------------------------------------------------------- log

    private fun log(msg: String) {
        DiagLog.log(this, msg)
        refreshLog()
    }

    private fun refreshLog() {
        if (!::logView.isInitialized) return
        val text = DiagLog.read(this)
        logView.text = text.lines().takeLast(250).joinToString("\n").ifBlank { "(log is empty)" }
    }

    private fun shareLog() {
        val text = DiagLog.read(this)
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Scroll Reader audio log v${BuildConfig.VERSION_NAME}")
                .putExtra(Intent.EXTRA_TEXT, text),
            "Share audio log"
        ))
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Scroll Reader audio log", DiagLog.read(this)))
        toast("Log copied")
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi(): View {
        val pad = dp(16)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(text("Audio diagnostics", 24f, bold = true))
        col.addView(text(
            "Connect the phone to the car. Tap each test, one at a time, and note which ones you " +
                "hear on the car speakers. Then tap Share log and send it to Claude together " +
                "with the test numbers you heard.\n\n" +
                "Try once with the car on Bluetooth audio, and once with the car on radio/another source.",
            15f
        ))
        status = text("Starting voice engine…", 15f, bold = true).also { it.setPadding(0, dp(12), 0, dp(4)) }
        col.addView(status)

        tests.forEach { t ->
            col.addView(Button(this).apply {
                text = "Test ${t.number}: ${t.name}"
                isAllCaps = false
                setOnClickListener { runTest(t) }
            })
        }
        col.addView(Button(this).apply {
            text = "Record audio state now"
            isAllCaps = false
            setOnClickListener { log(AudioProbe.snapshot(this@DiagnosticsActivity, "MANUAL SNAPSHOT")) }
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }
        row.addView(rowButton("Share log") { shareLog() })
        row.addView(rowButton("Copy log") { copyLog() })
        row.addView(rowButton("Clear log") {
            DiagLog.clear(this)
            refreshLog()
        })
        col.addView(row)

        col.addView(text("Log (newest at the bottom)", 15f, bold = true).also { it.setPadding(0, dp(12), 0, dp(4)) })
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        col.addView(logView)
        return ScrollView(this).apply { addView(col) }
    }

    private fun text(s: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
