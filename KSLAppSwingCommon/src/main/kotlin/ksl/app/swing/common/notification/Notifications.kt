/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.common.notification

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import kotlin.time.Duration

/**
 * Stacked transient-notification surface from scenario workflow
 * §4 surface 5.  Toasts appear in the bottom-right of a host
 * [JLayeredPane] (typically `frame.rootPane.layeredPane`), newest
 * on top, max 4 visible.  Older notifications are dropped on
 * overflow and a small *"+N more"* indicator surfaces the count.
 *
 * **Not** wired to validation — validation issues have a permanent
 * home in the banner and field markers (per §4).  This widget
 * announces *events*: file saved, bundle load failed, run skipped,
 * etc.
 *
 * Auto-dismiss: each card schedules a Swing [Timer] for its
 * [NotificationSpec.dismissAfter] duration, or stays until clicked
 * when the duration is null.  Click anywhere on a card to dismiss
 * early.
 *
 * @param container the host layered pane.  Notifications are added
 *   on [JLayeredPane.POPUP_LAYER] so they float above the editor.
 * @param maxVisible cap on simultaneously-visible cards.  Older
 *   cards are dismissed automatically when this is exceeded.
 * @param cardWidth fixed card width in pixels.
 * @param margin gap from the layered pane's edges.
 * @param spacing vertical gap between stacked cards.
 */
class Notifications(
    private val container: JLayeredPane,
    private val maxVisible: Int = DEFAULT_MAX_VISIBLE,
    private val cardWidth: Int = DEFAULT_CARD_WIDTH,
    private val margin: Int = DEFAULT_MARGIN,
    private val spacing: Int = DEFAULT_SPACING
) {

    private val cards: MutableList<Card> = mutableListOf()
    private var overflowLabel: JLabel? = null
    private var droppedCount: Int = 0

    init {
        container.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) = repositionAll()
        })
    }

    /** Displays a notification.  Returns the underlying card component. */
    fun show(spec: NotificationSpec): JComponent {
        val card = createCard(spec)
        cards.add(card)
        container.add(card.panel, JLayeredPane.POPUP_LAYER)

        spec.dismissAfter?.let { duration ->
            val ms = duration.inWholeMilliseconds.coerceIn(MIN_DISMISS_MS, Int.MAX_VALUE.toLong()).toInt()
            val timer = Timer(ms) { dismiss(card) }.apply {
                isRepeats = false
                start()
            }
            card.timer = timer
        }

        evictOverflow()
        repositionAll()
        return card.panel
    }

    /** Convenience for `show(NotificationSpec(message, severity))`. */
    fun show(message: String, severity: NotificationSeverity = NotificationSeverity.INFO): JComponent =
        show(NotificationSpec(message, severity))

    /** Removes every currently-displayed card. */
    fun dismissAll() {
        for (card in cards.toList()) dismiss(card, repositionAfter = false)
        droppedCount = 0
        renderOverflow()
        container.revalidate()
        container.repaint()
    }

    /** Test-only: count of currently-displayed notification cards. */
    internal val visibleCountForTest: Int get() = cards.size

    /** Test-only: count of cards dropped due to overflow. */
    internal val droppedCountForTest: Int get() = droppedCount

    /** Test-only: text on the "+N more" indicator, or null when absent. */
    internal val overflowTextForTest: String? get() = overflowLabel?.text

    /** Test-only: dismiss the topmost card programmatically. */
    internal fun dismissNewestForTest() {
        cards.lastOrNull()?.let { dismiss(it) }
    }

    /** Test-only: dismiss the bottom (oldest) card programmatically. */
    internal fun dismissOldestForTest() {
        cards.firstOrNull()?.let { dismiss(it) }
    }

    private fun dismiss(card: Card, repositionAfter: Boolean = true) {
        if (!cards.remove(card)) return
        card.timer?.stop()
        container.remove(card.panel)
        if (repositionAfter) {
            repositionAll()
            container.revalidate()
            container.repaint()
        }
    }

    private fun evictOverflow() {
        while (cards.size > maxVisible) {
            val oldest = cards.removeFirst()
            oldest.timer?.stop()
            container.remove(oldest.panel)
            droppedCount++
        }
        renderOverflow()
    }

    private fun renderOverflow() {
        val needed = droppedCount > 0 && cards.size >= maxVisible
        if (!needed) {
            overflowLabel?.let { container.remove(it) }
            overflowLabel = null
            return
        }
        val label = overflowLabel ?: JLabel("", SwingConstants.CENTER).apply {
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
            foreground = Color(0x55, 0x55, 0x55)
            background = Color(0xEE, 0xEE, 0xEE)
            isOpaque = true
        }.also { overflowLabel = it; container.add(it, JLayeredPane.POPUP_LAYER) }
        label.text = "+$droppedCount more"
    }

    private fun repositionAll() {
        renderOverflow()
        // Stack from bottom up: newest card sits at the top of the stack
        // (highest on screen), oldest at the bottom (closest to the edge).
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return
        var y = h - margin
        val x = w - cardWidth - margin
        for (card in cards) {
            val cardHeight = card.panel.preferredSize.height.coerceAtLeast(DEFAULT_CARD_MIN_HEIGHT)
            y -= cardHeight
            card.panel.setBounds(x, y, cardWidth, cardHeight)
            y -= spacing
        }
        overflowLabel?.let { label ->
            val labelHeight = label.preferredSize.height.coerceAtLeast(20)
            y -= labelHeight
            label.setBounds(x, y, cardWidth, labelHeight)
        }
        container.revalidate()
        container.repaint()
    }

    private fun createCard(spec: NotificationSpec): Card {
        val (bg, fg, border) = colorsFor(spec.severity)
        val panel = JPanel(BorderLayout())
        panel.background = bg
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(border, 1),
            BorderFactory.createEmptyBorder(8, 12, 8, 8)
        )
        val message = JLabel("<html>${escapeHtml(spec.message)}</html>").apply {
            foreground = fg
        }
        val closeBtn = JButton("×").apply {
            foreground = fg
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            margin = java.awt.Insets(0, 4, 0, 4)
            preferredSize = Dimension(24, 24)
            toolTipText = "Dismiss"
        }
        panel.add(message, BorderLayout.CENTER)
        panel.add(closeBtn, BorderLayout.EAST)
        panel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        panel.size = panel.preferredSize.also { it.width = cardWidth }

        val card = Card(panel = panel, timer = null)
        closeBtn.addActionListener { dismiss(card) }
        panel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { dismiss(card) }
        })
        return card
    }

    private fun colorsFor(severity: NotificationSeverity): Triple<Color, Color, Color> = when (severity) {
        NotificationSeverity.INFO -> Triple(Color(0xE3, 0xF2, 0xFD), Color(0x0D, 0x47, 0xA1), Color(0x90, 0xCA, 0xF9))
        NotificationSeverity.WARNING -> Triple(Color(0xFF, 0xF3, 0xE0), Color(0xE6, 0x5C, 0x00), Color(0xFF, 0xB7, 0x4D))
        NotificationSeverity.ERROR -> Triple(Color(0xFF, 0xEB, 0xEE), Color(0xC6, 0x28, 0x28), Color(0xEF, 0x9A, 0x9A))
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private class Card(val panel: JPanel, var timer: Timer?)

    companion object {
        const val DEFAULT_MAX_VISIBLE: Int = 4
        const val DEFAULT_CARD_WIDTH: Int = 320
        const val DEFAULT_MARGIN: Int = 16
        const val DEFAULT_SPACING: Int = 8
        private const val DEFAULT_CARD_MIN_HEIGHT: Int = 48
        private const val MIN_DISMISS_MS: Long = 10L
    }
}
