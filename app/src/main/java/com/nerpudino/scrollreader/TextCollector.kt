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
 *  - anything the app marks as a button with a short label (Button, ImageButton,
 *    Material/Compose buttons and HTML <button>s report a "...Button" class), and
 *  - icons: a tappable element with only a short hidden label and no visible text.
 * Long labels (6+ words or 40+ characters) are always read: some apps, like
 * Reddit, mark a whole post card as a button or put its text in a hidden label.
 * Plain text, links, tappable list rows (e.g. inbox emails) and image captions
 * are still read.
 */
object TextCollector {

    private const val MAX_DEPTH = 80

    /** Labels this long are content (a post title, a summary), not a control's name. */
    fun isLong(label: String): Boolean = label.length >= 40 || label.split(' ').size >= 6

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

    /** Returns how many blocks this node and its children added. */
    private fun walk(
        node: AccessibilityNodeInfo,
        out: MutableMap<String, Line>,
        statusBarBottom: Int,
        batch: Int,
        skipControls: Boolean,
        parentTappable: Boolean,
        depth: Int,
    ): Int {
        if (depth > MAX_DEPTH) return 0
        if (!node.isVisibleToUser || node.isPassword) return 0

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        fun add(text: String): Int {
            val insideStatusBar = bounds.bottom <= statusBarBottom
            if (insideStatusBar || bounds.isEmpty || text in out) return 0
            out[text] = Line(text, bounds, batch)
            return 1
        }

        val text = node.text?.toString()?.let(::clean)
        val desc = node.contentDescription?.toString()?.let(::clean)

        if (skipControls && isButton(node)) {
            val label = text ?: desc
            if (label != null) {
                // Short label ("Reply", "Archive") = a real button: skip it and its insides.
                // Long label = a whole card marked as a button (e.g. a Reddit post): read it.
                return if (isLong(label)) add(label) else 0
            }
            // No label of its own: a container marked as a button. Judge what's inside.
        }

        val tappable = node.isClickable || node.isLongClickable
        var added = 0
        if (text != null) added += add(text)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            added += walk(child, out, statusBarBottom, batch, skipControls, tappable || parentTappable, depth + 1)
        }

        // Hidden label: icon names ("Archive"), image captions, or a card's whole text
        // (Reddit puts post text here). Used only when nothing inside was readable,
        // so a card's summary isn't read on top of its own text.
        if (text == null && added == 0 && desc != null) {
            val isIconLike = skipControls && (tappable || parentTappable) && !isLong(desc)
            if (!isIconLike) added += add(desc)
        }
        return added
    }

    /** Button, ImageButton, MaterialButton, RadioButton, ToggleButton, Compose/HTML buttons... */
    private fun isButton(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.endsWith("Button") == true

    fun clean(raw: String): String? {
        val s = raw.replace(Regex("\\s+"), " ").trim()
        if (s.isEmpty()) return null
        // Skip pure symbols / separators like "•", "|", "›".
        if (s.none { it.isLetterOrDigit() }) return null
        return s
    }
}
