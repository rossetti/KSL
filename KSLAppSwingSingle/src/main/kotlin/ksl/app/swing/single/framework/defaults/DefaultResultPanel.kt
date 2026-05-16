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
import java.awt.Dimension
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

    // ── Run info strip ─────────────────────────────────────────────────────

    private fun runInfoStrip(result: RunResult): JComponent {
        val info: List<Pair<String, String>> = when (result) {
            is RunResult.Completed -> listOf(
                "Run id"       to result.summary.runId,
                "Status"       to "Completed",
                "Started"      to result.summary.beginTime.toString(),
                "Ended"        to result.summary.endTime.toString(),
                "Replications" to "${result.summary.completedReplications} of ${result.summary.requestedReplications}",
                "Ending"       to result.summary.endingStatus.toString()
            )
            is RunResult.BatchCompleted -> listOf(
                "Run id"  to result.summary.runId,
                "Status"  to "Batch completed",
                "Started" to result.summary.beginTime.toString(),
                "Ended"   to result.summary.endTime.toString(),
                "Items"   to "${result.summary.completedItems} of ${result.summary.totalItems} (${result.summary.failedItems} failed)"
            )
            is RunResult.Cancelled -> listOf(
                "Status" to "Cancelled",
                "Reason" to result.reason
            )
            is RunResult.Failed -> listOf(
                "Status" to "Failed",
                "Cause"  to result.error.toString()
            )
            is RunResult.OptimizationCompleted -> listOf(
                "Run id"     to result.summary.runId,
                "Status"     to "Optimization completed",
                "Iterations" to "${result.summary.completedItems} of ${result.summary.totalItems}"
            )
        }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Run Info")
        }
        for ((label, value) in info) panel.add(infoRow(label, value))
        return panel
    }

    private fun infoRow(label: String, value: String): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
        add(JLabel("$label:").apply {
            preferredSize = Dimension(140, preferredSize.height)
            font = font.deriveFont(Font.BOLD)
        }, BorderLayout.WEST)
        add(JLabel(value), BorderLayout.CENTER)
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
