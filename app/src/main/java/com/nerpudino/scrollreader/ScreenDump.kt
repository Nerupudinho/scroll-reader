package com.nerpudino.scrollreader

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Writes out exactly what an app exposes to Scroll Reader: every element's type,
 * visible text, hidden label and whether it's tappable. Used to see why an app
 * (e.g. Reddit) isn't being read.
 */
object ScreenDump {
    private const val MAX_NODES = 400
    private const val MAX_DEPTH = 40

    fun dump(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        var count = 0

        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= MAX_NODES || depth > MAX_DEPTH) return
            count++
            val cls = n.className?.toString()?.substringAfterLast('.') ?: "?"
            val text = n.text?.toString()?.let { short(it) }
            val desc = n.contentDescription?.toString()?.let { short(it) }
            val flags = buildString {
                if (n.isClickable) append('C')
                if (n.isLongClickable) append('L')
                if (n.isScrollable) append('S')
                if (!n.isVisibleToUser) append('h')
            }
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append("  ".repeat(depth)).append(cls)
            if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
            if (text != null) sb.append(" text=\"").append(text).append('"')
            if (desc != null) sb.append(" desc=\"").append(desc).append('"')
            sb.append(" @").append(r.top).append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }

        visit(root, 0)
        if (count >= MAX_NODES) sb.append("… stopped at $MAX_NODES elements\n")
        sb.append("Flags: C=tappable L=long-press S=scrollable h=hidden")
        return sb.toString()
    }

    private fun short(s: String): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length > 90) one.take(90) + "…" else one
    }
}
