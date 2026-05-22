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

package ksl.app.swing.common.comparison

import ksl.app.config.ReportFormat
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 *  *Comparison Analyzer* tab — always-visible counterpart to the
 *  deprecated [ComparisonAnalyzerFrame].  Same body (Experiments
 *  selection + per-analysis Configure buttons) restructured as a
 *  `JPanel` so it can live alongside *Scenarios* and *Scenario Reports*
 *  in a host frame's `JTabbedPane`.
 *
 *  Hosts feed it data via [setSources]: an empty / `null` list shows
 *  the empty-state card ("No completed batch with per-replication
 *  data yet."); a non-empty list rebuilds the populated card with a
 *  fresh [ComparisonSelectionModel].  The Scenario app subscribes to
 *  `lastResult` and calls `setSources` on each new batch; future hosts
 *  wire similarly.  Per-analysis defaults (output dir, formats) come
 *  from caller-supplied providers so they always reflect host state at
 *  the moment a Configure… dialog opens.
 *
 *  Q2 in the lifecycle plan — refresh on tab activation: this panel
 *  has no per-row disk state, so [refreshFromDisk] is a no-op.  Kept
 *  in the API for symmetry with the *Scenario Reports* tab and so the
 *  host's tab-change listener can call it uniformly.
 */
class ComparisonAnalyzerTabPanel(
    private val defaultOutputDirProvider: () -> Path? = { null },
    private val defaultFormatsProvider: () -> Set<ReportFormat> = { setOf(ReportFormat.HTML) },
    private val onMessage: (String, ComparisonAnalyzerFrame.Severity) -> Unit =
        { msg, sev -> System.err.println("[$sev] $msg") }
) : JPanel(CardLayout()) {

    private val cards = CardLayout()
    private val emptyCard = JPanel()
    private val populatedCard = JPanel(BorderLayout())

    private val emptyStateLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = Color(0x66, 0x66, 0x66)
        text = DEFAULT_EMPTY_TEXT
    }

    /** Current selection model; non-null only while the populated
     *  card is showing.  Rebuilt on every successful [setSources]. */
    private var model: ComparisonSelectionModel? = null

    init {
        layout = cards
        emptyCard.layout = BoxLayout(emptyCard, BoxLayout.Y_AXIS)
        emptyCard.border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
        emptyCard.add(Box.createVerticalGlue())
        emptyStateLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyCard.add(emptyStateLabel)
        emptyCard.add(Box.createVerticalGlue())

        add(emptyCard, CARD_EMPTY)
        add(populatedCard, CARD_POPULATED)
        cards.show(this, CARD_EMPTY)
    }

    // ── Public API for hosts ───────────────────────────────────────────────

    /**
     *  Swap in new comparison sources.  An empty list shows the
     *  empty-state card; a non-empty list rebuilds the populated card
     *  with a fresh [ComparisonSelectionModel] (every experiment
     *  checked, matching the analyzer's exploratory UX).
     *
     *  Hosts call this in response to lifecycle events (new run
     *  completes, scenarios cleared / deleted, etc.).
     */
    fun setSources(sources: List<ComparisonDataSourceIfc>) {
        if (sources.isEmpty()) {
            showEmpty(DEFAULT_EMPTY_TEXT)
            return
        }
        rebuildPopulatedCard(sources)
        cards.show(this, CARD_POPULATED)
    }

    /** Override the empty-state copy.  Useful when the host wants
     *  to differentiate (e.g.) "no batch yet" from "simulation in
     *  progress".  Has no effect while the populated card is showing;
     *  the next [setSources] with an empty list will use the new
     *  text. */
    fun setEmptyStateText(text: String) {
        emptyStateLabel.text = text
        // If already on the empty card, repaint with the new text.
        cards.show(this, currentCardName())
    }

    /** Q2 hook: called by the host's tab-change listener when this
     *  tab becomes active.  No-op for this panel — it has no
     *  per-row disk state — but kept in the API for symmetry. */
    fun refreshFromDisk() { /* no-op by design */ }

    // ── Internals ──────────────────────────────────────────────────────────

    private fun currentCardName(): String =
        if (model == null) CARD_EMPTY else CARD_POPULATED

    private fun showEmpty(text: String) {
        emptyStateLabel.text = text
        model = null
        populatedCard.removeAll()
        populatedCard.revalidate()
        populatedCard.repaint()
        cards.show(this, CARD_EMPTY)
    }

    private fun rebuildPopulatedCard(sources: List<ComparisonDataSourceIfc>) {
        val freshModel = ComparisonSelectionModel(sources)
        model = freshModel

        populatedCard.removeAll()
        populatedCard.add(buildHeader(sources), BorderLayout.NORTH)
        val statusLabel = JLabel(" ").apply {
            border = BorderFactory.createEmptyBorder(2, 12, 4, 12)
            foreground = Color(0x66, 0x55, 0x00)
        }
        val analyses = AnalysesPanel(freshModel)
        val experiments = ExperimentSelectionPanel(freshModel)
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, experiments, analyses).apply {
            resizeWeight = 0.55
            border = BorderFactory.createEmptyBorder()
        }
        populatedCard.add(split, BorderLayout.CENTER)
        populatedCard.add(statusLabel, BorderLayout.SOUTH)

        fun refreshChrome() {
            val anyChecked = freshModel.selectedExperimentNames.isNotEmpty()
            statusLabel.text = if (anyChecked) " "
                else "Check at least one experiment before configuring an analysis."
            analyses.setConfigureEnabled(anyChecked)
        }
        freshModel.addListener { refreshChrome() }

        // Initial state: check every experiment so the analyzer opens
        // with the most permissive selection.  Same UX as the
        // deprecated frame.
        freshModel.selectAll()
        refreshChrome()

        populatedCard.revalidate()
        populatedCard.repaint()
    }

    private fun buildHeader(sources: List<ComparisonDataSourceIfc>): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(8, 12, 6, 12)
        )
        val title = JLabel("Comparison Analyzer").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        add(title)
        for (src in sources) {
            add(JLabel("Source: ${src.sourceLabel}").apply {
                alignmentX = Component.LEFT_ALIGNMENT
                foreground = Color(0x44, 0x44, 0x44)
            })
        }
    }

    // ── Experiment selection panel ─────────────────────────────────────────

    private inner class ExperimentSelectionPanel(
        private val selectionModel: ComparisonSelectionModel
    ) : JPanel(BorderLayout()) {
        private val tableModel = ExperimentTableModel(selectionModel)
        private val table = JTable(tableModel).apply {
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            rowHeight = 22
            autoCreateRowSorter = true
            putClientProperty("terminateEditOnFocusLost", true)
        }

        init {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 8, 8, 4),
                BorderFactory.createTitledBorder("Experiments")
            )
            applyColumnWidths()
            add(buildToolbar(), BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            selectionModel.addListener { tableModel.fireTableDataChanged() }
        }

        private fun applyColumnWidths() {
            val cm = table.columnModel
            cm.getColumn(0).preferredWidth = 36
            cm.getColumn(0).maxWidth = 50
            cm.getColumn(1).preferredWidth = 180
            cm.getColumn(2).preferredWidth = 120
            cm.getColumn(3).preferredWidth = 60
            cm.getColumn(4).preferredWidth = 90
        }

        private fun buildToolbar(): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
            val all = JButton("Select All").apply {
                toolTipText = "Check every experiment"
                addActionListener { selectionModel.selectAll() }
            }
            val none = JButton("Select None").apply {
                toolTipText = "Uncheck every experiment"
                addActionListener { selectionModel.selectNone() }
            }
            add(all); add(Box.createHorizontalStrut(6)); add(none)
            add(Box.createHorizontalGlue())
        }
    }

    private class ExperimentTableModel(
        private val selectionModel: ComparisonSelectionModel
    ) : AbstractTableModel() {
        override fun getRowCount(): Int = selectionModel.allExperiments.size
        override fun getColumnCount(): Int = 5
        override fun getColumnName(c: Int): String = when (c) {
            0 -> "Run?"
            1 -> "Experiment"
            2 -> "Model"
            3 -> "Reps"
            4 -> "# Responses"
            else -> ""
        }
        override fun getColumnClass(c: Int): Class<*> = when (c) {
            0 -> java.lang.Boolean::class.java
            3, 4 -> java.lang.Integer::class.java
            else -> String::class.java
        }
        override fun isCellEditable(r: Int, c: Int): Boolean = c == 0
        override fun getValueAt(r: Int, c: Int): Any? {
            val row = selectionModel.allExperiments[r]
            return when (c) {
                0 -> row.name in selectionModel.selectedExperimentNames
                1 -> row.name
                2 -> row.modelIdentifier
                3 -> row.numReplications
                4 -> row.responses.size
                else -> null
            }
        }
        override fun setValueAt(value: Any?, r: Int, c: Int) {
            if (c != 0) return
            val v = (value as? Boolean) ?: return
            selectionModel.toggleExperiment(selectionModel.allExperiments[r].name, v)
        }
    }

    // ── Analyses panel ─────────────────────────────────────────────────────

    private inner class AnalysesPanel(
        private val selectionModel: ComparisonSelectionModel
    ) : JPanel() {
        private val boxPlotButton = mkConfigureButton { openBoxPlotDialog() }
        private val mcaButton = mkConfigureButton { openMcaDialog() }
        private val ciButton = mkConfigureButton { openCiDialog() }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 4, 8, 8),
                BorderFactory.createTitledBorder("Analyses")
            )
            add(analysisRow(
                title = "Box Plot",
                description = "Per-replication distributions of a response across the checked " +
                    "experiments.  Boxes are ordered left → right by the experiments column.",
                configureButton = boxPlotButton
            ))
            add(Box.createVerticalStrut(8))
            add(analysisRow(
                title = "Multiple Comparison Analysis",
                description = "Pairwise differences, MCB max/min intervals, screening tables — " +
                    "the rigorous \"which experiment wins?\" report.  Requires ≥2 experiments " +
                    "with equal replication counts.",
                configureButton = mcaButton
            ))
            add(Box.createVerticalStrut(8))
            add(analysisRow(
                title = "Confidence Intervals Plot",
                description = "Side-by-side mean ± confidence interval for each checked " +
                    "experiment that records the response.",
                configureButton = ciButton
            ))
            add(Box.createVerticalGlue())
        }

        private fun mkConfigureButton(onClick: () -> Unit): JButton = JButton("Configure…").apply {
            isFocusable = false
            addActionListener { onClick() }
        }

        private fun analysisRow(title: String, description: String, configureButton: JButton): JComponent =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
                alignmentX = Component.LEFT_ALIGNMENT
                val text = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JLabel(title).apply {
                        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                    add(Box.createVerticalStrut(2))
                    add(JLabel(
                        "<html><div style='width:340px; color:#666666; font-size:90%'>$description</div>"
                    ).apply { alignmentX = Component.LEFT_ALIGNMENT })
                }
                add(text)
                add(Box.createHorizontalGlue())
                add(configureButton)
            }

        fun setConfigureEnabled(enabled: Boolean) {
            boxPlotButton.isEnabled = enabled
            mcaButton.isEnabled = enabled
            ciButton.isEnabled = enabled
            val tip = if (enabled) null else "Check at least one experiment first."
            boxPlotButton.toolTipText = tip
            mcaButton.toolTipText = tip
            ciButton.toolTipText = tip
        }

        private fun openBoxPlotDialog() {
            BoxPlotAnalysisDialog.showDialog(
                parent = this@ComparisonAnalyzerTabPanel,
                model = selectionModel,
                defaultOutputDir = defaultOutputDirProvider(),
                defaultFormats = defaultFormatsProvider(),
                onMessage = onMessage
            )
        }

        private fun openMcaDialog() {
            MultipleComparisonAnalysisDialog.showDialog(
                parent = this@ComparisonAnalyzerTabPanel,
                model = selectionModel,
                defaultOutputDir = defaultOutputDirProvider(),
                defaultFormats = defaultFormatsProvider(),
                onMessage = onMessage
            )
        }

        private fun openCiDialog() {
            ConfidenceIntervalsAnalysisDialog.showDialog(
                parent = this@ComparisonAnalyzerTabPanel,
                model = selectionModel,
                defaultOutputDir = defaultOutputDirProvider(),
                defaultFormats = defaultFormatsProvider(),
                onMessage = onMessage
            )
        }
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_POPULATED = "populated"
        private const val DEFAULT_EMPTY_TEXT =
            "<html><div style='text-align:center;'>" +
                "No completed batch with per-replication data yet.<br>" +
                "Run the scenarios to populate this tab." +
                "</div></html>"
    }
}
