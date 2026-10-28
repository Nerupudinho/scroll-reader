package com.nerpudino.scrollreader

import android.app.Activity
import android.os.Bundle

/** Invisible: used by the "Turn on / off" app-icon shortcut and the Quick Settings tile. */
class ToggleShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsLinks.openServiceToggle(this)
        finish()
    }
}
