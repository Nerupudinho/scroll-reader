package com.nerpudino.scrollreader

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plain-text diagnostics log kept in the app's private storage.
 * Viewable, copyable and shareable from the Audio diagnostics screen.
 */
object DiagLog {
    private const val TAG = "ScrollReader"
    private const val FILE = "audio-diagnostics.log"
    private const val MAX_BYTES = 400_000L

    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        try {
            val f = File(ctx.filesDir, FILE)
            if (f.length() > MAX_BYTES) {
                // Keep the newest half.
                val text = f.readText()
                f.writeText(text.substring(text.length / 2).substringAfter('\n'))
            }
            val stamp = time.format(Date())
            val body = msg.lines().joinToString("\n") { "$stamp  $it" }
            f.appendText(body + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "Diag log write failed", e)
        }
    }

    @Synchronized
    fun read(ctx: Context): String = try {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText().orEmpty()
    } catch (e: Exception) {
        ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
