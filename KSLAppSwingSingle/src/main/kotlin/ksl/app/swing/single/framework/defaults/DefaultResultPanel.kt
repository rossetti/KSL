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
import ksl.utilities.io.dbutil.AcrossRepStatTableData
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
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.table.DefaultTableModel

/**
 * Default result panel for `kslSingleApp(...)` — swaps in after a
 * terminal [RunResult].
 *
 * Layout (top to bottom):
 *  - **Run info strip** — runId, terminal state, elapsed time,
 *    replications completed vs. requested.
 *  - **Responses table** — one row per
 *    [AcrossRepStatTableData] from the snapshot.  Empty when the
 *    terminal state is Failed or Cancelled.
 *  - **Artifacts strip** — three placeholder buttons
 *    (*Open Report (HTML)*, *Open Database*, *Open in File
 *    Browser*).  Each surfaces a notification via
 *    [onPlaceholderArtifact] when clicked; real wiring lands in
 *    a later N-commit alongside `OutputConfig` orchestrator
 *    threading.
 *  - **Footer** — *Back to Configuration* button on the right.
 *
 * Construct one fresh on every terminal state transition; the
 * panel does not internally re-render on subsequent results.
 *
 * @param result the terminal result to render.
 * @param onBack invoked when the user clicks *Back to Configuration*.
 * @param onPlaceholderArtifact invoked when an artifact button is
 *   clicked — argument is the button label so the caller can
 *   surface a contextual *"Not yet wired"* notification.
 */
class DefaultResultPanel(
    result: RunResult,
    onBack: () -> Unit,
    onPlaceholderArtifact: (String) -> Unit
) : JPanel(BorderLayout()) {

    init {
        border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        body.add(runInfoStrip(result))
        body.add(Box.createVerticalStrut(8))
        body.add(responsesSection(result))
        body.add(Box.createVerticalStrut(8))
        if (hasArtifacts(result)) {
            body.add(artifactsStrip(onPlaceholderArtifact))
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

    private fun hasArtifacts(result: RunResult): Boolean =
        result is RunResult.Completed || result is RunResult.BatchCompleted

    // ── Run info strip ─────────────────────────────────────────────────────

    private fun runInfoStrip(result: RunResult): JComponent {
        val info: List<Pair<String, String>> = when (result) {
            is RunResult.Completed -> listOf(
                "Run id"      to result.summary.runId,
                "Status"      to "Completed",
                "Started"     to result.summary.beginTime.toString(),
                "Ended"       to result.summary.endTime.toString(),
                "Replications" to "${result.summary.completedReplications} of ${result.summary.requestedReplications}",
                "Ending"      to result.summary.endingStatus.toString()
            )
            is RunResult.BatchCompleted -> listOf(
                "Run id"        to result.summary.runId,
                "Status"        to "Batch completed",
                "Started"       to result.summary.beginTime.toString(),
                "Ended"         to result.summary.endTime.toString(),
                "Items"         to "${result.summary.completedItems} of ${result.summary.totalItems} (${result.summary.failedItems} failed)"
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
                "Run id" to result.summary.runId,
                "Status" to "Optimization completed",
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

    // ── Responses table ────────────────────────────────────────────────────

    private fun responsesSection(result: RunResult): JComponent {
        val stats = extractAcrossRepStats(result)
        val container = JPanel(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder("Across-Replication Statistics")
        }
        if (stats.isEmpty()) {
            container.add(
                JLabel("No across-replication statistics available.", SwingConstants.CENTER).apply {
                    border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
                    foreground = java.awt.Color(0x66, 0x66, 0x66)
                },
                BorderLayout.CENTER
            )
            return container
        }
        val model = DefaultTableModel(arrayOf("Name", "Mean", "Std Dev", "95% CI Half-width", "Min", "Max", "n"), 0)
        for (stat in stats) {
            model.addRow(arrayOf<Any?>(
                stat.stat_name,
                fmtDouble(stat.average),
                fmtDouble(stat.std_dev),
                fmtDouble(stat.half_width),
                fmtDouble(stat.minimum),
                fmtDouble(stat.maximum),
                stat.stat_count?.toInt()?.toString() ?: ""
            ))
        }
        val table = JTable(model).apply {
            autoCreateRowSorter = true
            fillsViewportHeight = true
        }
        container.add(JScrollPane(table).apply { preferredSize = Dimension(0, 220) }, BorderLayout.CENTER)
        return container
    }

    private fun extractAcrossRepStats(result: RunResult): List<AcrossRepStatTableData> = when (result) {
        is RunResult.Completed -> result.snapshot.acrossRepStats
        is RunResult.BatchCompleted -> result.snapshots.flatMap { it.acrossRepStats }
        else -> emptyList()
    }

    private fun fmtDouble(value: Double?): String =
        if (value == null) "" else "%.4f".format(value)

    // ── Artifacts strip ────────────────────────────────────────────────────

    private fun artifactsStrip(onClick: (String) -> Unit): JComponent {
        val strip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            border = BorderFactory.createTitledBorder("Artifacts")
        }
        listOf("Open Report (HTML)", "Open Database", "Open in File Browser").forEach { label ->
            strip.add(JButton(label).apply { addActionListener { onClick(label) } })
        }
        return strip
    }

}
