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

import ksl.app.comparison.*

import ksl.app.config.ReportFormat
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel

/**
 *  Modeless top-level frame for the Comparison Analyzer.
 *
 *  Workflow (left → right):
 *
 *  1. **Experiments** — checkbox-driven table of available
 *     experiments.  At least one experiment must be checked before
 *     any analysis can be configured.
 *  2. **Analyses** — list of three analysis types (Box Plot,
 *     Multiple Comparison Analysis, Confidence Intervals Plot).
 *     Each row has a *Configure…* button that opens a modal,
 *     analysis-specific dialog.  Inside that dialog the user picks
 *     a response, sets analysis-specific options, picks output
 *     format(s) and directory, and generates the report.
 *
 *  Selection state lives in a [ComparisonSelectionModel] shared
 *  between the *Experiments* panel and the analyses panel.
 *  Response and analysis-specific knobs are owned by the per-
 *  analysis dialogs — the frame itself holds no per-analysis state.
 *
 *  The constructor accepts a `List<ComparisonDataSourceIfc>` so the
 *  frame is forward-compatible with multi-source workflows.  v1
 *  hosts construct with a single-element list.
 *
 *  @param sources             one or more data sources to analyze
 *  @param defaultOutputDir    initial value used by per-analysis
 *                             dialogs for their output directory
 *                             field.  Hosts pass a workspace-
 *                             relative path; `null` leaves the
 *                             field blank until the user picks one.
 *  @param defaultFormats      formats checked by default in each
 *                             per-analysis dialog's output strip.
 *                             Defaults to HTML only.
 *  @param onMessage           optional callback for user-facing
 *                             notifications (success / error /
 *                             warning).  Default writes to stderr —
 *                             hosts hook this to their own
 *                             notifications surface.
 */
@Deprecated(
    message = "Use ComparisonAnalyzerTabPanel — hosts now embed this UI as a tab " +
        "rather than a standalone frame.",
    replaceWith = ReplaceWith("ComparisonAnalyzerTabPanel")
)
class ComparisonAnalyzerFrame(
    sources: List<ComparisonDataSourceIfc>,
    private val defaultOutputDir: Path? = null,
    private val defaultFormats: Set<ReportFormat> = setOf(ReportFormat.HTML),
    private val onMessage: (String, Severity) -> Unit = { msg, sev -> System.err.println("[$sev] $msg") }
) : JFrame("Comparison Analyzer") {

    /**
     *  Severity flavors for the analyzer's notification callback.
     *  Referenced verbatim by [ComparisonAnalyzerTabPanel] and by the
     *  per-analysis dialogs; lives here for now because the analyzer
     *  family was originally frame-centric.  When the deprecated
     *  frame is eventually removed, lift this enum to top-level (e.g.
     *  `ComparisonSeverity`) in the same package — every reference is
     *  inside this package and an IDE rename handles the migration in
     *  one pass.  Until then the nested form is the canonical type.
     */
    enum class Severity { INFO, WARNING, ERROR }

    private val model: ComparisonSelectionModel = ComparisonSelectionModel(sources)

    // Property declarations must precede the init block — the init body
    // calls buildHeader/buildBody which dereference these fields.
    private val statusLabel: JLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 12, 4, 12)
        foreground = Color(0x66, 0x55, 0x00)
    }

    private val experimentPanel = ExperimentSelectionPanel()
    private val analysesPanel = AnalysesPanel()

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(1000, 600)

        contentPane.layout = BorderLayout()
        contentPane.add(buildHeader(sources), BorderLayout.NORTH)
        contentPane.add(buildBody(), BorderLayout.CENTER)

        // Initial state: check every experiment so the analyzer opens
        // with the most permissive selection.  The analyst narrows
        // down rather than building up — typical UX pattern for
        // exploratory tools.
        model.selectAll()

        // Single refresh hook for chrome / button enablement.
        // Components register their own listeners for their internals.
        model.addListener { refreshChrome() }
        refreshChrome()

        pack()
        setLocationRelativeTo(null)
    }

    // ── Header ───────────────────────────────────────────────────────────

    private fun buildHeader(sources: List<ComparisonDataSourceIfc>): JPanel = JPanel().apply {
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
        add(statusLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
    }

    // ── Body — experiments | analyses split ──────────────────────────────

    private fun buildBody(): JComponent {
        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, experimentPanel, analysesPanel).apply {
            resizeWeight = 0.55
            border = BorderFactory.createEmptyBorder()
        }
        return split
    }

    // ── Frame-level chrome ───────────────────────────────────────────────

    private fun refreshChrome() {
        val anyChecked = model.selectedExperimentNames.isNotEmpty()
        statusLabel.text = if (anyChecked) " "
            else "Check at least one experiment before configuring an analysis."
        analysesPanel.setConfigureEnabled(anyChecked)
    }

    // ── Experiment selection panel ───────────────────────────────────────

    private inner class ExperimentSelectionPanel : JPanel(BorderLayout()) {
        private val tableModel = ExperimentTableModel()
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

            // Refresh on every model change so newly-toggled rows
            // reflect their checked state from the source of truth.
            model.addListener { tableModel.fireTableDataChanged() }
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
            val outerModel = model
            val all = JButton("Select All").apply {
                toolTipText = "Check every experiment"
                addActionListener { outerModel.selectAll() }
            }
            val none = JButton("Select None").apply {
                toolTipText = "Uncheck every experiment"
                addActionListener { outerModel.selectNone() }
            }
            add(all); add(Box.createHorizontalStrut(6)); add(none)
            add(Box.createHorizontalGlue())
        }
    }

    private inner class ExperimentTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = model.allExperiments.size
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
            val row = model.allExperiments[r]
            return when (c) {
                0 -> row.name in model.selectedExperimentNames
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
            model.toggleExperiment(model.allExperiments[r].name, v)
        }
    }

    // ── Analyses panel ───────────────────────────────────────────────────

    /**
     *  Right-hand column.  Lists the three analysis types — each row
     *  has a short description plus a *Configure…* button that opens
     *  the analysis-specific dialog.  Buttons are greyed out until
     *  at least one experiment is checked.
     */
    private inner class AnalysesPanel : JPanel() {
        private val boxPlotButton = mkConfigureButton {
            openBoxPlotDialog()
        }
        private val mcaButton = mkConfigureButton {
            openMcaDialog()
        }
        private val ciButton = mkConfigureButton {
            openCiDialog()
        }

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
    }

    // ── Per-analysis dialog launchers (stubbed for commit 1) ─────────────

    private fun openBoxPlotDialog() {
        BoxPlotAnalysisDialog.showDialog(
            parent = this,
            model = model,
            defaultOutputDir = defaultOutputDir,
            defaultFormats = defaultFormats,
            onMessage = onMessage
        )
    }

    private fun openMcaDialog() {
        MultipleComparisonAnalysisDialog.showDialog(
            parent = this,
            model = model,
            defaultOutputDir = defaultOutputDir,
            defaultFormats = defaultFormats,
            onMessage = onMessage
        )
    }

    private fun openCiDialog() {
        ConfidenceIntervalsAnalysisDialog.showDialog(
            parent = this,
            model = model,
            defaultOutputDir = defaultOutputDir,
            defaultFormats = defaultFormats,
            onMessage = onMessage
        )
    }
}
