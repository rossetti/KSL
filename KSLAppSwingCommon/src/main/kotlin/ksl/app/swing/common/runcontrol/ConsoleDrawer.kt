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

package ksl.app.swing.common.runcontrol

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Collapsible bottom-of-window drawer that hosts a [ConsoleLogPanel].
 *
 * IDE-style "console drawer": a thin always-visible header strip shows
 * a toggle button and a per-run event-count summary
 * (`N INFO · N WARN · N ERR`).  Clicking the toggle expands the drawer
 * to [expandedHeight] pixels of console body; clicking again collapses
 * back to the header.
 *
 * The drawer is intended for `BorderLayout.SOUTH` (or equivalent) of the
 * main frame.  It owns the console body's layout but not its event
 * subscription — the embedded [ConsoleLogPanel] already collects from
 * the shared `RunEvent` flow.  The drawer keeps its counts in sync by
 * registering listeners on the panel itself
 * ([ConsoleLogPanel.addAfterEventListener] and
 * [ConsoleLogPanel.addOnClearListener]), so there's a single thread of
 * control over both the buffer and the counters — no race window
 * between a "clear" and an event arriving during it.
 *
 * Counts reset to zero whenever the panel's buffer is cleared, which
 * happens (a) on every `RunEvent.Started` when the panel's
 * `autoClearOnRunStart` is on (default), and (b) on every press of the
 * panel's Clear button.  The header counter and the rendered log stay
 * consistent with each other in both cases.
 *
 * @param console the [ConsoleLogPanel] to embed as the drawer's body.
 *   The drawer takes layout ownership; do not parent it elsewhere.
 * @param initiallyExpanded whether the drawer starts open.  Default: false.
 * @param expandedHeight pixel height of the console body when expanded.
 *   Default: 260px — sized for macOS Aqua native button heights so the
 *   filter rail's Clear button at the bottom remains visible regardless
 *   of platform L&F.  Smaller L&Fs (Linux GTK at ~24px button height)
 *   waste a few pixels of glue; that's preferable to clipping Clear
 *   off the bottom on macOS.
 * @param showCaptureToggle whether to render a *Capture stdout*
 *   checkbox in the drawer header.  Defaults to `true` for the
 *   single-run app surface.  Multi-run app surfaces (Scenario,
 *   Experiment, SimOpt) should pass `false`: in those, stdout
 *   capture would interleave output across many simulated runs and
 *   isn't useful for analyst-facing diagnostics.  When hiding the
 *   toggle, also hide the `STDOUT` category chip from the
 *   [ConsoleLogPanel] via its `hiddenCategories` parameter so the
 *   rail isn't cluttered with chrome for a feature that can't be
 *   reached.
 * @param growWindowOnExpand when `true` (the default, added in
 *   E7.11), the drawer grows the enclosing window by [expandedHeight]
 *   pixels on expand and shrinks it back on collapse — so the
 *   drawer doesn't steal vertical space from the content above.
 *   Falls back to the legacy "drawer takes from content" behavior
 *   when growing would push the window off-screen, or when the
 *   window isn't realized yet.  Pass `false` to opt out (e.g. for
 *   tests that need deterministic frame heights).
 */
class ConsoleDrawer(
    private val console: ConsoleLogPanel,
    initiallyExpanded: Boolean = false,
    private val expandedHeight: Int = 260,
    showCaptureToggle: Boolean = true,
    private val growWindowOnExpand: Boolean = true
) : JPanel(BorderLayout()) {

    private var expanded: Boolean = initiallyExpanded
    private var infoCount: Int = 0
    private var warnCount: Int = 0
    private var errCount: Int = 0

    private val toggleButton: JButton = JButton(toggleLabel()).apply {
        isFocusable = false
        margin = java.awt.Insets(2, 10, 2, 10)
        addActionListener { setExpanded(!expanded) }
    }
    private val countsLabel: JLabel = JLabel(countsText()).apply {
        border = BorderFactory.createEmptyBorder(0, 6, 0, 0)
    }
    private val captureCheckbox: JCheckBox? = if (showCaptureToggle) {
        JCheckBox("Capture stdout").apply {
            isFocusable = false
            isSelected = StdoutCapture.isInstalled()
            toolTipText = "Tee System.out / System.err into the console " +
                "from this point forward.  Captured lines appear under " +
                "the STDOUT category (filter chip: Out)."
            addActionListener {
                if (isSelected) startCapture() else stopCapture()
            }
        }
    } else null
    private val header: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)
        )
        add(toggleButton)
        add(Box.createHorizontalStrut(12))
        add(countsLabel)
        add(Box.createHorizontalGlue())
        if (captureCheckbox != null) {
            add(captureCheckbox)
        }
    }
    private val body: JPanel = JPanel(BorderLayout()).apply {
        add(console, BorderLayout.CENTER)
        isVisible = initiallyExpanded
    }

    init {
        add(header, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)

        // Drive the counters from the panel's own event pipeline so
        // clear() (whether via the Clear button or auto-clear on run
        // start) and event-arrival are serialized through a single
        // thread of control on the EDT.
        console.addOnClearListener {
            resetCounts()
            refreshCountsLabel()
        }
        console.addAfterEventListener { ev ->
            when (ConsoleLogPanel.severityOf(ev)) {
                ConsoleSeverity.INFO -> infoCount++
                ConsoleSeverity.WARNING -> warnCount++
                ConsoleSeverity.ERROR -> errCount++
            }
            refreshCountsLabel()
        }
    }

    /** Programmatic open/close.  EDT-only. */
    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        val delta = if (value) +expandedHeight else -expandedHeight
        expanded = value
        body.isVisible = expanded
        toggleButton.text = toggleLabel()
        // E7.11 — grow the enclosing window by the expanded-height
        // delta so the drawer doesn't steal vertical space from
        // content above.  Falls back to legacy "drawer takes from
        // content" behavior when the window isn't realized OR when
        // growing would push the bottom edge off-screen.
        if (growWindowOnExpand) {
            val window = SwingUtilities.getWindowAncestor(this)
            if (window != null && window.isDisplayable) {
                val screen = window.graphicsConfiguration?.bounds
                val proposed = (window.height + delta)
                    .coerceAtLeast(window.minimumSize.height)
                val clamped = if (value && screen != null) {
                    // Don't grow past the bottom edge of the screen.
                    val maxHeight = screen.height - (window.y - screen.y).coerceAtLeast(0)
                    proposed.coerceAtMost(maxHeight.coerceAtLeast(window.minimumSize.height))
                } else {
                    proposed
                }
                if (clamped != window.height) {
                    window.setSize(window.width, clamped)
                }
            }
        }
        revalidate()
        repaint()
    }

    /** Whether the drawer body is currently expanded. */
    fun isExpanded(): Boolean = expanded

    override fun getPreferredSize(): Dimension {
        val parent = super.getPreferredSize()
        val headerH = header.preferredSize.height
        return Dimension(parent.width, if (expanded) headerH + expandedHeight else headerH)
    }

    override fun getMinimumSize(): Dimension {
        val headerH = header.preferredSize.height
        return Dimension(0, headerH)
    }

    override fun getMaximumSize(): Dimension {
        val headerH = header.preferredSize.height
        return Dimension(Int.MAX_VALUE, if (expanded) headerH + expandedHeight else headerH)
    }

    private fun resetCounts() {
        infoCount = 0
        warnCount = 0
        errCount = 0
    }

    private fun refreshCountsLabel() {
        countsLabel.text = countsText()
        // Tint label red when errors present, orange when warnings present,
        // else default.  Provides a glance signal of "something interesting
        // happened" without expanding the drawer.
        countsLabel.foreground = when {
            errCount > 0 -> COLOR_ERR
            warnCount > 0 -> COLOR_WARN
            else -> COLOR_DEFAULT
        }
        countsLabel.font = if (errCount > 0 || warnCount > 0)
            countsLabel.font.deriveFont(Font.BOLD)
        else countsLabel.font.deriveFont(Font.PLAIN)
    }

    private fun countsText(): String =
        "$infoCount INFO · $warnCount WARN · $errCount ERR"

    private fun toggleLabel(): String =
        if (expanded) "▾ Console" else "▴ Console"

    /**
     * Install [StdoutCapture] with this drawer's console as the sink
     * and announce the change with a synthetic INFO line.  Called by
     * the capture-toggle action listener; no-op if the toggle is
     * hidden (the listener is never registered).
     */
    private fun startCapture() {
        StdoutCapture.install { text, fromErr ->
            console.injectStdOutLine(text, fromErr)
        }
        console.injectStdOutLine(
            "[Capture] stdout/stderr capture started.",
            fromErr = false
        )
    }

    /** Counterpart of [startCapture]: announce, then uninstall. */
    private fun stopCapture() {
        console.injectStdOutLine(
            "[Capture] stdout/stderr capture stopped.",
            fromErr = false
        )
        StdoutCapture.uninstall()
    }

    companion object {
        private val COLOR_DEFAULT: Color = Color(0x55, 0x55, 0x55)
        private val COLOR_WARN: Color = Color(0xE6, 0x5C, 0x00)
        private val COLOR_ERR: Color = Color(0xC6, 0x28, 0x28)
    }
}
