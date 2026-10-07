package com.nerpudino.scrollreader

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Walks one app window's accessibility tree and returns the visible text in
 * reading order (top-to-bottom as the app lays it out).
 *
 * The status bar is never in this tree: it belongs to a separate system
 * window, and ReaderService only hands us the app's own window. As a second
 * guard, anything that sits entirely inside the status-bar strip at the top
 * of the screen is dropped too.
 */
object TextCollector {

    private const val MAX_DEPTH = 80

    fun collect(root: AccessibilityNodeInfo, statusBarBottom: Int): List<String> {
        val out = LinkedHashSet<String>() // keeps order, drops exact repeats on one screen
        walk(root, out, statusBarBottom, 0)
        return out.toList()
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableSet<String>,
        statusBarBottom: Int,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        spokenTextOf(node)?.let { text ->
            val insideStatusBar = bounds.bottom <= statusBarBottom
            if (!insideStatusBar && !bounds.isEmpty) out.add(text)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, statusBarBottom, depth + 1)
        }
    }

    private fun spokenTextOf(node: AccessibilityNodeInfo): String? {
        if (node.isPassword) return null

        val text = node.text?.toString()?.let(::clean)
        if (!text.isNullOrEmpty()) return text

        // Content descriptions are usually icon labels ("Search", "Like").
        // Only use them on leaf nodes, so a card's summary description doesn't
        // get read on top of the text inside it.
        if (node.childCount == 0) {
            val desc = node.contentDescription?.toString()?.let(::clean)
            if (!desc.isNullOrEmpty()) return desc
        }
        return null
    }

    private fun clean(raw: String): String? {
        val s = raw.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        // Skip pure symbols / separators like "•", "|", "›".
        if (s.none { it.isLetterOrDigit() }) return null
        return s
    }
}
