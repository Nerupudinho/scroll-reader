package com.nerpudino.scrollreader

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log

object SettingsLinks {
    /**
     * Opens Scroll Reader's own on/off page in Accessibility settings (Android 11+).
     * Android only allows this for an app's own service, which is our case.
     * Falls back to the Accessibility list, asking it to highlight Scroll Reader.
     */
    fun openServiceToggle(ctx: Context) {
        val component = ComponentName(ctx, ReaderService::class.java).flattenToString()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                ctx.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
                        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            } catch (e: ActivityNotFoundException) {
                Log.w("ScrollReader", "Direct accessibility page not available", e)
            } catch (e: SecurityException) {
                Log.w("ScrollReader", "Direct accessibility page refused", e)
            }
        }
        val highlight = Bundle().apply { putString(":settings:fragment_args_key", component) }
        ctx.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .putExtra(":settings:fragment_args_key", component)
                .putExtra(":settings:show_fragment_args", highlight)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
