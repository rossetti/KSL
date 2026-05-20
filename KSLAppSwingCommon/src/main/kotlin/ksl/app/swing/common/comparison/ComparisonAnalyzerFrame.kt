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
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel

/**
 *  Modeless top-level frame for the Comparison Analyzer.
 *
 *  Layout (top → bottom):
 *
 *  - **Header strip** — source label(s) and a brief status line.
 *  - **Selection body** — three resizable side-by-side panels:
 *      1. *Experiments* — checkbox-driven table of available experiments.
 *      2. *Response* — single-select list of responses recorded by the
 *         currently-checked experiments, with a *Recorded by N of M*
 *         column.
 *      3. *Analysis* — radio group (Box Plot / Multiple Comparison
 *         Analysis / Confidence Intervals) with live pre-flight
 *         validation.
 *  - **Output strip** — format checkboxes, output directory path +
 *    browse button, and the *Generate Report* button.
 *
 *  Selection state lives in a [ComparisonSelectionModel] shared
 *  across the three selection components.  Each component reads
 *  from the model on render, calls a mutator on user input, and
 *  refreshes when other components change state via the model's
 *  listener channel.
 *
 *  The constructor accepts a `List<ComparisonDataSourceIfc>` so the
 *  frame is forward-compatible with multi-source workflows.  v1
 *  hosts construct with a single-element list.
 *
 *  Rendering is **not** wired in this commit.  The *Generate Report*
 *  button surfaces a "TODO — C4 wires the renderer" notification.
 *
 *  @param sources             one or more data sources to analyze
 *  @param defaultOutputDir    optional initial value for the output
 *                             directory text field.  Hosts pass a
 *                             workspace-relative path; `null` leaves
 *                             the field blank until the user picks
 *                             a directory.
 *  @param defaultFormats      formats checked by default in the
 *                             output strip.  Defaults to HTML only.
 *  @param onMessage           optional callback for user-facing
 *                             notifications (success / error /
 *                             warning).  Default writes to stderr —
 *                             hosts hook this to their own
 *                             notifications surface.
 */
class ComparisonAnalyzerFrame(
    sources: List<ComparisonDataSourceIfc>,
    private val defaultOutputDir: Path? = null,
    private val defaultFormats: Set<ReportFormat> = setOf(ReportFormat.HTML),
    private val onMessage: (String, Severity) -> Unit = { msg, sev -> System.err.println("[$sev] $msg") }
) : JFrame("Comparison Analyzer") {

    enum class Severity { INFO, WARNING, ERROR }

    private val model: ComparisonSelectionModel = ComparisonSelectionModel(sources)

    // Property declarations must precede the init block — the init body
    // calls buildHeader/buildBody which dereference these fields, and
    // Kotlin initialises class-body members in declaration order
    // interleaved with init blocks.  Earlier code had statusLabel /
    // experimentPanel / responsePanel / analysisChooser declared *below*
    // init, which produced an NPE the moment buildHeader touched
    // statusLabel.
    private val statusLabel: JLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 12, 4, 12)
        foreground = Color(0x66, 0x55, 0x00)
    }

    private val experimentPanel = ExperimentSelectionPanel()
    private val responsePanel = ResponseSelectionPanel()
    private val analysisChooser = AnalysisTypeChooser()
    private val outputStrip = OutputStrip()

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        preferredSize = Dimension(1100, 700)

        contentPane.layout = BorderLayout()
        contentPane.add(buildHeader(sources), BorderLayout.NORTH)
        contentPane.add(buildBody(), BorderLayout.CENTER)
        contentPane.add(outputStrip, BorderLayout.SOUTH)

        // Initial state: check every experiment so the analyzer opens
        // with the most permissive selection.  The analyst narrows
        // down rather than building up — typical UX pattern for
        // exploratory tools.
        model.selectAll()

        // Single refresh hook for everything that depends on model
        // state.  Components register their own listeners; this is
        // for the frame-level chrome (status line, button enablement).
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

    // ── Body — three-column split ────────────────────────────────────────

    private fun buildBody(): JComponent {
        val midRight = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, responsePanel, analysisChooser).apply {
            resizeWeight = 0.6
            border = BorderFactory.createEmptyBorder()
        }
        val outer = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, experimentPanel, midRight).apply {
            resizeWeight = 0.45
            border = BorderFactory.createEmptyBorder()
        }
        return outer
    }

    // ── Frame-level chrome ───────────────────────────────────────────────

    private fun refreshChrome() {
        val v = model.validate()
        outputStrip.setGenerateEnabled(v.ok)
        statusLabel.text = if (v.ok) " " else (v.reason ?: " ")
        outputStrip.generateTooltip = v.reason
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
                toolTipText = "Uncheck every experiment (clears the chosen response too)"
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

    // ── Response selection — compact widget opens a modal picker ─────────

    /**
     *  Compact widget shown in the middle column.  Reads the
     *  currently-selected response from the model and renders
     *  `<name>` + metadata; the *Change…* button opens
     *  [ChooseResponseDialog] for full selection.  Designed for
     *  models with hundreds of responses: the inline panel never
     *  shows the full list.
     */
    private inner class ResponseSelectionPanel : JPanel() {

        private val nameLabel = JLabel("(no response chosen)").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        private val detailLabel = JLabel(" ").apply {
            foreground = Color(0x55, 0x55, 0x55)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        private val changeButton = JButton("Change…").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            toolTipText = "Open the response picker"
        }
        private val emptyHint = JLabel(
            "<html><i>No response is recorded by the checked experiments.<br>" +
                "Check experiments that share a response name.</i>"
        ).apply {
            foreground = Color(0x66, 0x66, 0x66)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 4, 8, 4),
                BorderFactory.createTitledBorder("Response")
            )
            add(nameLabel)
            add(Box.createVerticalStrut(4))
            add(detailLabel)
            add(Box.createVerticalStrut(10))
            add(changeButton)
            add(Box.createVerticalStrut(10))
            add(emptyHint)
            add(Box.createVerticalGlue())

            changeButton.addActionListener { openPicker() }
            model.addListener { refresh() }
            refresh()
        }

        private fun refresh() {
            val anyResponses = model.availableResponses().isNotEmpty()
            changeButton.isEnabled = anyResponses
            emptyHint.isVisible = !anyResponses

            val selected = model.selectedResponse
            if (selected == null) {
                nameLabel.text = "(no response chosen)"
                nameLabel.foreground = Color(0x66, 0x66, 0x66)
                detailLabel.text = " "
            } else {
                nameLabel.text = selected
                nameLabel.foreground = java.awt.Color.BLACK
                val row = model.availableResponses().firstOrNull { it.name == selected }
                val category = row?.category?.let {
                    when (it) {
                        ResponseCategory.OBSERVATION -> "Observation"
                        ResponseCategory.TIME_WEIGHTED -> "Time-weighted"
                        ResponseCategory.COUNTER -> "Counter"
                    }
                } ?: "—"
                val recording = model.experimentsRecording(selected).size
                val totalChecked = model.selectedExperimentNames.size
                detailLabel.text = "$category · Recorded by $recording of $totalChecked"
            }
        }

        private fun openPicker() {
            val totalChecked = model.selectedExperimentNames.size
            val pickerRows = model.availableResponses().map { r ->
                ChooseResponseDialog.Row(
                    name = r.name,
                    category = r.category,
                    recordingExperiments = model.experimentsRecording(r.name).size,
                    totalCheckedExperiments = totalChecked
                )
            }
            val result = ChooseResponseDialog.showDialog(
                parent = this,
                rows = pickerRows,
                initialSelection = model.selectedResponse,
                validator = { name -> model.validateForResponse(name, model.analysis) }
            )
            if (result is ChooseResponseDialog.Result.Chosen) {
                model.setResponse(result.responseName)
            }
        }
    }

    // ── Analysis type chooser ────────────────────────────────────────────

    private inner class AnalysisTypeChooser : JPanel() {
        private val boxPlot = mkRadio("Box Plot",
            "Per-replication distributions of the chosen response across the checked experiments.",
            AnalysisType.BOX_PLOT)
        private val mca = mkRadio("Multiple Comparison Analysis",
            "Pairwise differences, MCB max/min intervals, screening tables — the rigorous " +
                "\"which experiment wins?\" report.",
            AnalysisType.MULTIPLE_COMPARISON)
        private val ciPlot = mkRadio("Confidence Intervals Plot",
            "Side-by-side mean ± CI for each checked experiment that records the response.",
            AnalysisType.CONFIDENCE_INTERVALS)

        init {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 4, 8, 8),
                BorderFactory.createTitledBorder("Analysis")
            )
            val group = ButtonGroup().apply {
                add(boxPlot.first); add(mca.first); add(ciPlot.first)
            }
            @Suppress("UNUSED_EXPRESSION") group
            for ((radio, caption) in listOf(boxPlot, mca, ciPlot)) {
                radio.alignmentX = Component.LEFT_ALIGNMENT
                caption.alignmentX = Component.LEFT_ALIGNMENT
                add(radio); add(caption); add(Box.createVerticalStrut(8))
            }
            add(Box.createVerticalGlue())

            boxPlot.first.isSelected = true
            model.addListener { refreshEnablement() }
            refreshEnablement()
        }

        private fun mkRadio(label: String, description: String, type: AnalysisType): Pair<JRadioButton, JLabel> {
            val outerModel = model
            val radio = JRadioButton(label).apply {
                isFocusable = false
                addActionListener { outerModel.setAnalysis(type) }
            }
            val caption = JLabel("<html><div style='width:240px; color:#666666; font-size:90%'>$description</div>").apply {
                border = BorderFactory.createEmptyBorder(0, 24, 0, 4)
            }
            return radio to caption
        }

        private fun refreshEnablement() {
            // Reflect the model's analysis selection back to the radios
            // (e.g. after a programmatic change).
            boxPlot.first.isSelected = model.analysis == AnalysisType.BOX_PLOT
            mca.first.isSelected = model.analysis == AnalysisType.MULTIPLE_COMPARISON
            ciPlot.first.isSelected = model.analysis == AnalysisType.CONFIDENCE_INTERVALS

            // Per-type pre-flight: grey out radios that can't possibly
            // satisfy their requirement with the current selection.
            // Box plot and CI plot only need at least one participant;
            // MCA has the stricter equal-reps requirement.
            val response = model.selectedResponse
            val participants = if (response != null) model.experimentsRecording(response).size else 0
            boxPlot.first.isEnabled = response != null && participants >= 1
            ciPlot.first.isEnabled = response != null && participants >= 1
            mca.first.isEnabled = response != null &&
                participants >= 2 &&
                model.validate(AnalysisType.MULTIPLE_COMPARISON).ok
        }
    }

    // ── Output strip ─────────────────────────────────────────────────────

    private inner class OutputStrip : JPanel() {
        private val formatBoxes: Map<ReportFormat, JCheckBox> =
            ReportFormat.entries.associateWith { fmt ->
                JCheckBox(fmt.name, fmt in defaultFormats)
            }
        private val outputDirField = JTextField(
            defaultOutputDir?.toString().orEmpty(), 36
        )
        private val browseButton = JButton("Browse…").apply {
            addActionListener { onBrowse() }
        }
        private val generateButton = JButton("Generate Report").apply {
            addActionListener { onGenerate() }
        }

        var generateTooltip: String? = null
            set(value) {
                field = value
                generateButton.toolTipText = value
            }

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(6, 12, 6, 12)
            )
            add(JLabel("Formats:"))
            add(Box.createHorizontalStrut(4))
            for ((_, cb) in formatBoxes) {
                add(cb)
                add(Box.createHorizontalStrut(4))
            }
            add(Box.createHorizontalStrut(16))
            add(JLabel("Output:"))
            add(Box.createHorizontalStrut(4))
            add(outputDirField)
            add(Box.createHorizontalStrut(4))
            add(browseButton)
            add(Box.createHorizontalGlue())
            add(generateButton)
        }

        fun setGenerateEnabled(enabled: Boolean) {
            generateButton.isEnabled = enabled
        }

        private fun onBrowse() {
            val chooser = JFileChooser().apply {
                dialogTitle = "Output directory for Comparison Analyzer reports"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                val initial = outputDirField.text.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                if (initial != null) currentDirectory = initial.toFile()
            }
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
            outputDirField.text = chooser.selectedFile.toPath().toAbsolutePath().toString()
        }

        private fun onGenerate() {
            val formats = formatBoxes.filterValues { it.isSelected }.keys
            if (formats.isEmpty()) {
                onMessage("Pick at least one report format.", Severity.WARNING)
                return
            }
            val pathText = outputDirField.text.trim()
            if (pathText.isEmpty()) {
                onMessage("Pick an output directory.", Severity.WARNING)
                return
            }
            val outputDir = Paths.get(pathText)
            val outcome = try {
                ComparisonReportRenderer.render(model, outputDir, formats)
            } catch (t: Throwable) {
                onMessage(
                    "Report generation failed: ${t.message ?: t::class.simpleName}",
                    Severity.ERROR
                )
                return
            }
            outcome.errors.forEach { onMessage("Report error: $it", Severity.WARNING) }
            if (outcome.written.isNotEmpty()) {
                val files = outcome.written.joinToString(", ") { it.fileName.toString() }
                onMessage(
                    "Wrote ${outcome.written.size} report file(s) to $outputDir: $files",
                    Severity.INFO
                )
            }
        }
    }
}
