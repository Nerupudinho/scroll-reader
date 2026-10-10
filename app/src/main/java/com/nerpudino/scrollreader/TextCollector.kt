package com.nerpudino.scrollreader

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** One readable block of text and where it sits on screen. */
data class Line(val text: String, val bounds: Rect, val batch: Int)

/**
 * Walks one app window's accessibility tree and returns the visible text in
 * reading order (top-to-bottom as the app lays it out).
 *
 * The status bar is never in this tree: it belongs to a separate system
 * window, and ReaderService only hands us the app's own window. As a second
 * guard, anything that sits entirely inside the status-bar strip at the top
 * of the screen is dropped too.
 *
 * Nothing is classified or filtered as "header" or "ad": all visible text is
 * read, and the user skips with the Skip button.
 *
 * Buttons and icons (Reply, Forward, Archive, Delete, Mark unread, Gemini...)
 * are skipped when skipControls is on. That uses what the app itself reports,
 * not a guess about the content:
 *  - anything the app marks as a button (Button, ImageButton, Material buttons,
 *    Compose buttons and HTML <button>s all report a "...Button" class), and
 *  - icons: an element with only a hidden label and no visible text, that is
 *    tappable or sits inside something tappable.
 * Plain text, links, tappable list rows (e.g. inbox emails) and image captions
 * are still read.
 */
object TextCollector {

    private const val MAX_DEPTH = 80

    fun collect(
        root: AccessibilityNodeInfo,
        statusBarBottom: Int,
        batch: Int,
        skipControls: Boolean,
    ): List<Line> {
        val out = LinkedHashMap<String, Line>() // keeps order, drops exact repeats on one screen
        walk(root, out, statusBarBottom, batch, skipControls, parentTappable = false, depth = 0)
        return out.values.toList()
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableMap<String, Line>,
        statusBarBottom: Int,
        batch: Int,
        skipControls: Boolean,
        parentTappable: Boolean,
        depth: Int,
    ) {
        if (depth > MAX_DEPTH) return
        if (!node.isVisibleToUser) return

        // A button's own label and everything inside it are skipped together.
        if (skipControls && isButton(node)) return

        val tappable = node.isClickable || node.isLongClickable
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val skipIcons = skipControls && (tappable || parentTappable)
        spokenTextOf(node, skipIcons)?.let { text ->
            val insideStatusBar = bounds.bottom <= statusBarBottom
            if (!insideStatusBar && !bounds.isEmpty && text !in out) {
                out[text] = Line(text, bounds, batch)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, out, statusBarBottom, batch, skipControls, tappable || parentTappable, depth + 1)
        }
    }

    /** Button, ImageButton, MaterialButton, RadioButton, ToggleButton, Compose/HTML buttons... */
    private fun isButton(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.endsWith("Button") == true

    private fun spokenTextOf(node: AccessibilityNodeInfo, skipIcons: Boolean): String? {
        if (node.isPassword) return null

        val text = node.text?.toString()?.let(::clean)
        if (!text.isNullOrEmpty()) return text

        // Content descriptions are hidden labels, usually on icons ("Archive", "Delete").
        // Only use them on leaf nodes, so a card's summary description doesn't get read
        // on top of the text inside it. A tappable icon is a control: skip it if asked.
        if (node.childCount == 0 && !skipIcons) {
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
