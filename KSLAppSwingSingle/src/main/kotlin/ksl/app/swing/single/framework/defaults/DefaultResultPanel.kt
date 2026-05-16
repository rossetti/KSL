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

package ksl.app.swing.single.framework.defaults

import ksl.app.session.RunResult
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import kotlin.time.Duration
import kotlin.time.DurationUnit

/**
 * Default post-run surface for `kslSingleApp(...)`.  Shown after a
 * terminal [RunResult].
 *
 * **Architecture: reports-on-demand, not stats-in-window.**  The
 * KSL reporting framework (`ksl.utilities.io.report`) already
 * renders comprehensive standard reports — across-replication
 * statistics tables, histograms, frequencies, time-series stats,
 * diagnostics — in HTML, Markdown, and plain text.  Replicating
 * any of that content inline in a Swing panel produces redundant
 * UX (the same numbers twice) without any of the report's
 * formatting, plots, or domain context.
 *
 * So this panel exposes only:
 *  - **Run Info** — runId, status, start/end timestamps, run shape.
 *  - **Standard report buttons** — *Standard HTML report*,
 *    *Standard Markdown report*, *Standard Text report*.  Each
 *    button on click triggers on-demand materialization from the
 *    in-memory snapshot via the framework's
 *    `SimulationSnapshot.ExperimentCompleted.toReport().writeHtml(...)`
 *    family.  Wiring lives in a later N-commit; v1 here invokes a
 *    placeholder callback so the UX is observable.
 *  - **Advanced…** (future) — opens a dialog for per-render
 *    options (plots / time series / diagnostics / title / format).
 *    Not in this panel yet — placeholder button reserved.
 *  - **Back to Configuration** — returns to the parameter panel.
 *
 * Buttons are hidden for `Failed` and `Cancelled` results since
 * no snapshot exists.  *Run Info* shows the cause in those cases.
 *
 * Construct one fresh on every terminal-state transition; the
 * panel does not internally re-render on subsequent results.
 *
 * @param result the terminal result to render.
 * @param onBack invoked when the user clicks *Back to Configuration*.
 * @param onStandardReport invoked when the user clicks one of the
 *   three Standard report buttons — argument is the button's
 *   format identifier (`"HTML"`, `"Markdown"`, `"Text"`).  v1
 *   callers surface a *"Not yet wired"* notification; N4 wires
 *   the real `Document.writeHtml/...` calls behind this hook.
 * @param onAdvanced invoked when the user clicks *Advanced…*.
 *   Reserved for the N5 dialog; v1 callers surface a *"Not yet
 *   wired"* notification.
 */
class DefaultResultPanel(
    result: RunResult,
    onBack: () -> Unit,
    onStandardReport: (format: String) -> Unit,
    onAdvanced: () -> Unit
) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        body.add(runInfoStrip(result))
        body.add(Box.createVerticalStrut(8))
        if (hasSnapshot(result)) {
            body.add(standardReportsStrip(onStandardReport, onAdvanced))
            body.add(Box.createVerticalStrut(8))
        }

        val backStrip = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            border = BorderFactory.createEmptyBorder(4, 0, 0, 0)
            add(JButton("Back to Configuration").apply { addActionListener { onBack() } })
        }

        add(JScrollPane(body).apply {
            border = BorderFactory.createEmptyBorder()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)
        add(backStrip, BorderLayout.SOUTH)
    }

    private fun hasSnapshot(result: RunResult): Boolean =
        result is RunResult.Completed || result is RunResult.BatchCompleted

    companion object {
        // Soft pastel backgrounds for the status badge.  Foreground colors
        // match the SeverityIcon palette established in ksl.app.swing.common.validation.
        private val BADGE_OK_BG: Color = Color(0xE8, 0xF5, 0xE9)   // soft green
        private val BADGE_OK_FG: Color = Color(0x1B, 0x5E, 0x20)   // dark green
        private val BADGE_WARN_BG: Color = Color(0xFF, 0xF3, 0xE0)
        private val BADGE_WARN_FG: Color = Color(0xE6, 0x5C, 0x00)
        private val BADGE_ERR_BG: Color = Color(0xFF, 0xEB, 0xEE)
        private val BADGE_ERR_FG: Color = Color(0xC6, 0x28, 0x28)
    }

    // ── Run info strip (single-line) ───────────────────────────────────────

    /**
     * Renders the run's metadata as a single horizontal strip:
     * status badge + brief outcome summary + duration.  Replaces
     * the prior key/value block; runId and start/end timestamps
     * are intentionally omitted (low value in-window for a
     * Single app — they're in the report).
     */
    private fun runInfoStrip(result: RunResult): JComponent {
        val (badge, body) = describeResult(result)
        val strip = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            border = BorderFactory.createEmptyBorder(2, 2, 2, 2)
        }
        strip.add(badge)
        strip.add(JLabel(body))
        return strip
    }

    private fun describeResult(result: RunResult): Pair<JComponent, String> = when (result) {
        is RunResult.Completed -> Pair(
            statusBadge("Completed", BADGE_OK_BG, BADGE_OK_FG),
            "${result.summary.completedReplications} / ${result.summary.requestedReplications} replications" +
                "  ·  ${formatDuration(result.summary.wallClockDuration)}" +
                "  ·  ${result.summary.endingStatus}"
        )
        is RunResult.BatchCompleted -> Pair(
            statusBadge("Batch completed", BADGE_OK_BG, BADGE_OK_FG),
            "${result.summary.completedItems} / ${result.summary.totalItems} items" +
                (if (result.summary.failedItems > 0) " (${result.summary.failedItems} failed)" else "") +
                "  ·  ${formatDuration(result.summary.endTime - result.summary.beginTime)}"
        )
        is RunResult.Cancelled -> Pair(
            statusBadge("Cancelled", BADGE_WARN_BG, BADGE_WARN_FG),
            result.reason
        )
        is RunResult.Failed -> Pair(
            statusBadge("Failed", BADGE_ERR_BG, BADGE_ERR_FG),
            result.error.toString()
        )
        is RunResult.OptimizationCompleted -> Pair(
            statusBadge("Optimization completed", BADGE_OK_BG, BADGE_OK_FG),
            "${result.summary.completedItems} / ${result.summary.totalItems} iterations" +
                "  ·  ${formatDuration(result.summary.endTime - result.summary.beginTime)}"
        )
    }

    private fun statusBadge(label: String, bg: Color, fg: Color): JComponent = JLabel(label).apply {
        font = font.deriveFont(Font.BOLD)
        foreground = fg
        background = bg
        isOpaque = true
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    private fun formatDuration(d: Duration): String {
        val seconds = d.toDouble(DurationUnit.SECONDS)
        return when {
            seconds < 1.0 -> "%.3f s".format(seconds)
            seconds < 60.0 -> "%.1f s".format(seconds)
            else -> {
                val totalSec = seconds.toInt()
                val m = totalSec / 60
                val s = totalSec % 60
                "%d m %02d s".format(m, s)
            }
        }
    }

    // ── Standard reports strip ─────────────────────────────────────────────

    private fun standardReportsStrip(
        onStandardReport: (format: String) -> Unit,
        onAdvanced: () -> Unit
    ): JComponent {
        val strip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = BorderFactory.createTitledBorder("Reports")
        }
        listOf(
            "Standard HTML report"     to "HTML",
            "Standard Markdown report" to "Markdown",
            "Standard Text report"     to "Text"
        ).forEach { (label, format) ->
            strip.add(JButton(label).apply { addActionListener { onStandardReport(format) } })
        }
        strip.add(JButton("Advanced…").apply { addActionListener { onAdvanced() } })
        return strip
    }
}
