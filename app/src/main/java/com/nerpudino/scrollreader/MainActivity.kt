package com.nerpudino.scrollreader

import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Setup screen: turn the service on, tweak the floating button and speed. */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var enableButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        val on = ReaderService.instance != null
        status.text = if (on) "✅  Scroll Reader is on" else "⚠️  Scroll Reader is off"
        enableButton.text = if (on) "Open accessibility settings" else "Turn on Scroll Reader"
    }

    private fun buildUi(): View {
        val pad = dp(20)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(title("Scroll Reader", 26f))
        col.addView(body("Version ${BuildConfig.VERSION_NAME}").also { it.alpha = 0.6f })
        col.addView(body("Reads the app you're in out loud, then keeps scrolling down and reading until the end. It skips the status bar (time, battery, signal)."))

        status = title("", 18f).also { it.setPadding(0, dp(16), 0, dp(4)) }
        col.addView(status)

        enableButton = Button(this).apply {
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        col.addView(enableButton)

        col.addView(body("In Accessibility settings, open \"Scroll Reader\" (it may be under \"Downloaded apps\" or \"Installed apps\") and switch it on."))

        col.addView(heading("Switch greyed out?"))
        col.addView(body("Android blocks accessibility for apps installed outside the Play Store until you allow it once. Tap below, then the ⋮ menu (top right) → \"Allow restricted settings\", then try again."))
        col.addView(Button(this).apply {
            text = "Open app info"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        })

        col.addView(heading("How to use"))
        col.addView(body("1. Open any app.\n2. Tap the round ▶ button to read from the top of the screen, or long-press it and tap the line you want to start from.\n3. While reading, the control bar gives you:\n   ⏮ Back: restart this part, or tap again for the previous part\n   ⏯ Pause / Resume: carries on from the same word\n   ⏭ Skip: jump to the next part\n   − / + Speed: slower or faster, applies straight away\n   ■ Stop\n4. Drag the dotted handle to move the bar.\n\nOr pull down Quick Settings and tap the \"Read screen\" tile."))

        col.addView(heading("Settings"))
        col.addView(Switch(this).apply {
            text = "Show floating controls"
            isChecked = Prefs.showBubble(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setShowBubble(this@MainActivity, checked)
                ReaderService.instance?.setBubbleVisible(checked)
            }
        })

        val speedLabel = body("")
        col.addView(speedLabel)
        // SeekBar 0..10 -> speed 0.5x..3.0x in 0.25 steps (same steps as the − / + buttons)
        val current = Prefs.speechRate(this)
        fun label(rate: Float) {
            speedLabel.text = "Reading speed: %.2fx".format(rate)
        }
        label(current)
        col.addView(SeekBar(this).apply {
            max = 10
            progress = ((current - 0.5f) / 0.25f).toInt().coerceIn(0, 10)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val rate = 0.5f + p * 0.25f
                    label(rate)
                    if (fromUser) Prefs.setSpeechRate(this@MainActivity, rate)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            col.addView(Button(this).apply {
                text = "Add \"Read screen\" to Quick Settings"
                setOnClickListener { requestTile() }
            })
        }

        return ScrollView(this).apply { addView(col) }
    }

    private fun requestTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val sbm = getSystemService(StatusBarManager::class.java) ?: return
        sbm.requestAddTileService(
            ComponentName(this, ReadTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile),
            mainExecutor,
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Tile is already there"
                else -> "Tile not added"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun title(text: String, size: Float) = TextView(this).apply {
        this.text = text
        textSize = size
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.START
    }

    private fun heading(text: String) = title(text, 18f).also { it.setPadding(0, dp(24), 0, dp(4)) }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setPadding(0, dp(4), 0, dp(4))
        setLineSpacing(0f, 1.15f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
