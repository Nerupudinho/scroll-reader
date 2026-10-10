package com.nerpudino.scrollreader

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "settings"
    private const val KEY_BUBBLE = "show_bubble"
    private const val KEY_RATE = "speech_rate"
    private const val KEY_BUBBLE_X = "bubble_x"
    private const val KEY_BUBBLE_Y = "bubble_y"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_VOICE_CHANNEL = "voice_channel"
    private const val KEY_SKIP_CONTROLS = "skip_controls"

    const val CHANNEL_ACCESSIBILITY = "accessibility"
    const val CHANNEL_MEDIA = "media"

    const val MIN_RATE = 0.5f
    const val MAX_RATE = 3.0f

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun showBubble(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_BUBBLE, true)
    fun setShowBubble(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_BUBBLE, value).apply()

    /** 1.0 = normal speed. */
    fun speechRate(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_RATE, 1.0f).coerceIn(MIN_RATE, MAX_RATE)

    fun setSpeechRate(ctx: Context, value: Float) =
        prefs(ctx).edit().putFloat(KEY_RATE, value.coerceIn(MIN_RATE, MAX_RATE)).apply()

    /** Keep the screen awake while reading, so scrolling keeps working. */
    fun keepScreenOn(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_KEEP_SCREEN_ON, true)
    fun setKeepScreenOn(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /**
     * Audio channel the voice plays on. Accessibility is the default: in the car,
     * media was captured by a remote-submix route and never reached the speakers,
     * while accessibility went straight to the car's Bluetooth.
     */
    fun voiceChannel(ctx: Context): String =
        prefs(ctx).getString(KEY_VOICE_CHANNEL, CHANNEL_ACCESSIBILITY) ?: CHANNEL_ACCESSIBILITY

    fun setVoiceChannel(ctx: Context, value: String) =
        prefs(ctx).edit().putString(KEY_VOICE_CHANNEL, value).apply()

    /** Skip buttons and icons (Reply, Forward, Archive, Delete...). */
    fun skipControls(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SKIP_CONTROLS, true)
    fun setSkipControls(ctx: Context, value: Boolean) =
        prefs(ctx).edit().putBoolean(KEY_SKIP_CONTROLS, value).apply()

    fun bubbleX(ctx: Context, default: Int): Int = prefs(ctx).getInt(KEY_BUBBLE_X, default)
    fun bubbleY(ctx: Context, default: Int): Int = prefs(ctx).getInt(KEY_BUBBLE_Y, default)
    fun setBubblePos(ctx: Context, x: Int, y: Int) =
        prefs(ctx).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
}
