package com.nerpudino.scrollreader

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Describes where Android is sending audio: which outputs exist (phone speaker,
 * Bluetooth media, Bluetooth call audio...), which one a given kind of sound
 * would go to, and which players are active right now and on which device.
 */
object AudioProbe {

    /** What Scroll Reader's voice uses today (Android's default for text-to-speech). */
    val DEFAULT_TTS_ATTRS: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun device(d: AudioDeviceInfo?): String {
        if (d == null) return "none"
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() }
        return typeName(d.type) + (name?.let { " \"$it\"" } ?: "")
    }

    fun typeName(t: Int): String = when (t) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "PHONE SPEAKER"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "PHONE SPEAKER(safe)"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "PHONE EARPIECE"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT MEDIA (A2DP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT CALL (SCO)"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE AUDIO headset"
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE AUDIO speaker"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED HEADPHONES"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED HEADSET"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB HEADSET"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DEVICE"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING AID"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "REMOTE SUBMIX"
        AudioDeviceInfo.TYPE_TELEPHONY -> "TELEPHONY"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> "type$t"
    }

    fun usageName(u: Int): String = when (u) {
        AudioAttributes.USAGE_MEDIA -> "media"
        AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility"
        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
        AudioAttributes.USAGE_ASSISTANT -> "assistant"
        AudioAttributes.USAGE_VOICE_COMMUNICATION -> "voice-call"
        AudioAttributes.USAGE_NOTIFICATION -> "notification"
        AudioAttributes.USAGE_ALARM -> "alarm"
        AudioAttributes.USAGE_GAME -> "game"
        AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "sonification"
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
        AudioAttributes.USAGE_UNKNOWN -> "unknown"
        else -> "usage$u"
    }

    private fun modeName(m: Int) = when (m) {
        AudioManager.MODE_NORMAL -> "NORMAL"
        AudioManager.MODE_RINGTONE -> "RINGTONE"
        AudioManager.MODE_IN_CALL -> "IN_CALL"
        AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
        else -> "mode$m"
    }

    /** Where a sound with these attributes would go right now (Android 13+). */
    fun routeFor(ctx: Context, attrs: AudioAttributes): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return "(needs Android 13)"
        return try {
            am(ctx).getAudioDevicesForAttributes(attrs).joinToString { device(it) }.ifEmpty { "none" }
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    /** Players making sound right now, and the device each one is playing on. */
    fun activePlayers(ctx: Context): String {
        val list = try {
            am(ctx).activePlaybackConfigurations
        } catch (e: Exception) {
            return "error: ${e.message}"
        }
        if (list.isEmpty()) return "none"
        return list.joinToString("; ") { c ->
            val dev = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device(c.audioDeviceInfo) else "?"
            "${usageName(c.audioAttributes.usage)} -> $dev"
        }
    }

    /** One-line summary used while reading. */
    fun compact(ctx: Context): String =
        "voice route: ${routeFor(ctx, DEFAULT_TTS_ATTRS)} | playing: ${activePlayers(ctx)}"

    /** Full picture of the audio system. */
    fun snapshot(ctx: Context, title: String): String {
        val am = am(ctx)
        val sb = StringBuilder("--- $title ---\n")
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        sb.append("Outputs connected: ").append(outs.joinToString { device(it) }).append('\n')
        val hasA2dp = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        val hasSco = outs.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        sb.append("Bluetooth media profile (A2DP) connected: ").append(if (hasA2dp) "YES" else "NO").append('\n')
        sb.append("Bluetooth call profile (SCO/HFP) connected: ").append(if (hasSco) "YES" else "NO").append('\n')
        sb.append("Audio mode: ").append(modeName(am.mode))
            .append(", music active: ").append(am.isMusicActive).append('\n')
        @Suppress("DEPRECATION")
        sb.append("A2DP on: ").append(am.isBluetoothA2dpOn)
            .append(", SCO on: ").append(am.isBluetoothScoOn).append('\n')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            sb.append("Call-audio device: ").append(device(am.communicationDevice)).append('\n')
        }
        fun vol(stream: Int) = "${am.getStreamVolume(stream)}/${am.getStreamMaxVolume(stream)}"
        sb.append("Volumes: media ").append(vol(AudioManager.STREAM_MUSIC))
            .append(", accessibility ").append(vol(AudioManager.STREAM_ACCESSIBILITY))
            .append(", call ").append(vol(AudioManager.STREAM_VOICE_CALL)).append('\n')
        sb.append("Route for media (what the reader uses): ").append(routeFor(ctx, DEFAULT_TTS_ATTRS)).append('\n')
        sb.append("Route for accessibility: ").append(routeFor(ctx, attrs(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY))).append('\n')
        sb.append("Route for navigation voice: ").append(routeFor(ctx, attrs(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))).append('\n')
        sb.append("Active players: ").append(activePlayers(ctx)).append('\n')
        sb.append("Phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(')')
        return sb.toString()
    }

    fun attrs(usage: Int): AudioAttributes = AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}
