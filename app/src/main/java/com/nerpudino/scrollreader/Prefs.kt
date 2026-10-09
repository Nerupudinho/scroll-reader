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

    fun bubbleX(ctx: Context, default: Int): Int = prefs(ctx).getInt(KEY_BUBBLE_X, default)
    fun bubbleY(ctx: Context, default: Int): Int = prefs(ctx).getInt(KEY_BUBBLE_Y, default)
    fun setBubblePos(ctx: Context, x: Int, y: Int) =
        prefs(ctx).edit().putInt(KEY_BUBBLE_X, x).putInt(KEY_BUBBLE_Y, y).apply()
}
