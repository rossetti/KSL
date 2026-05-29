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
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.nio.file.Path
import java.nio.file.Paths
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 *  Modal configuration dialog for the Confidence Intervals Plot.
 *
 *  Stays open after *Generate* so the analyst can iterate.  Knobs:
 *
 *  - **Response** (required) — picked via [ChooseResponseDialog].
 *  - **Confidence level** — default 0.95; clamped to (0, 1).
 *  - **Reference point** — optional vertical line on the value axis.
 *    Leave blank to suppress.
 *  - **Caption** / **Title** — optional overrides for the plot
 *    caption and the document title.
 *  - **Axis labels** — defaults to the response name (x) and
 *    `"Experiment"` (y).
 *  - **Output** — per-run format checkboxes + output directory.
 *
 *  Generate routes through [ComparisonReportRenderer.renderCiPlot];
 *  the dialog shows live validation against
 *  [ComparisonSelectionModel.validateForResponse], with *Generate*
 *  disabled and a tooltip explaining why when the selection is
 *  invalid.
 */
object ConfidenceIntervalsAnalysisDialog {

    fun showDialog(
        parent: Component,
        model: ComparisonSelectionModel,
        defaultOutputDir: Path?,
        defaultFormats: Set<ReportFormat>,
        onMessage: (String, ComparisonAnalyzerFrame.Severity) -> Unit
    ) {
        // SwingUtilities.getWindowAncestor walks getParent() starting
        // from parent's parent, so it returns null when parent itself
        // is the top-level Window.
        val owner: Window = (parent as? Window)
            ?: SwingUtilities.getWindowAncestor(parent)
            ?: return
        Dialog(owner, model, defaultOutputDir, defaultFormats, onMessage).isVisible = true
    }

    private class Dialog(
        owner: Window,
        private val model: ComparisonSelectionModel,
        defaultOutputDir: Path?,
        defaultFormats: Set<ReportFormat>,
        private val onMessage: (String, ComparisonAnalyzerFrame.Severity) -> Unit
    ) : JDialog(owner, "Confidence Intervals — Configure", java.awt.Dialog.ModalityType.APPLICATION_MODAL) {

        // ── State ────────────────────────────────────────────────────────
        private var selectedResponse: String? = null

        // ── Widgets ──────────────────────────────────────────────────────

        private val responseLabel = JLabel("(none chosen)").apply {
            font = font.deriveFont(Font.BOLD, font.size2D)
            foreground = Color(0x66, 0x66, 0x66)
        }
        private val responseDetail = JLabel(" ").apply {
            foreground = Color(0x55, 0x55, 0x55)
            font = font.deriveFont(font.size2D - 1f)
        }
        private val responsePickButton = JButton("Pick Response…").apply {
            addActionListener { onPickResponse() }
        }

        private val levelField = JTextField("0.95", 6)
        private val refPointField = JTextField(8).apply {
            toolTipText = "Leave blank for no reference line"
        }
        private val captionField = JTextField(40).apply {
            toolTipText = "Leave blank to use \"Mean ± <CL>% CI — <response>\""
        }
        private val titleField = JTextField(40).apply {
            toolTipText = "Leave blank to use \"Comparison — Confidence Intervals — <response>\""
        }
        private val xAxisField = JTextField(20).apply {
            toolTipText = "Value axis.  Leave blank to use the response name"
        }
        private val yAxisField = JTextField(20).apply {
            toolTipText = "Alternative axis.  Leave blank to use \"Experiment\""
        }

        private val formatBoxes: Map<ReportFormat, JCheckBox> =
            ReportFormat.entries.associateWith { fmt ->
                JCheckBox(fmt.name, fmt in defaultFormats).apply { isFocusable = false }
            }

        private val outputDirField = JTextField(defaultOutputDir?.toString().orEmpty(), 36)
        private val browseButton = JButton("Browse…").apply {
            addActionListener { onBrowse() }
        }

        private val statusLabel = JLabel(" ").apply {
            border = BorderFactory.createEmptyBorder(2, 12, 4, 12)
            foreground = Color(0x99, 0x55, 0x00)
        }

        private val generateButton = JButton("Generate").apply {
            addActionListener { onGenerate() }
        }
        private val closeButton = JButton("Close").apply {
            addActionListener { dispose() }
        }

        init {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            preferredSize = Dimension(760, 460)
            contentPane.layout = BorderLayout()
            contentPane.add(buildHeader(), BorderLayout.NORTH)
            contentPane.add(buildBody(), BorderLayout.CENTER)
            contentPane.add(buildButtons(), BorderLayout.SOUTH)
            rootPane.defaultButton = generateButton

            refreshStatus()
            pack()
            setLocationRelativeTo(owner)
        }

        private fun buildHeader(): JComponent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 6, 12)
            )
            val title = JLabel("Configure Confidence Intervals Plot").apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            val sub = JLabel(
                "Each checked experiment recording the response contributes one mean ± CI bar."
            ).apply {
                foreground = Color(0x55, 0x55, 0x55)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            add(title)
            add(sub)
            add(statusLabel.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }

        private fun buildBody(): JComponent = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(8, 12, 8, 12)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = Insets(4, 4, 4, 4)
            }
            var row = 0

            fun addRow(labelText: String, widget: JComponent) {
                gbc.gridx = 0; gbc.gridy = row; gbc.weightx = 0.0
                gbc.fill = GridBagConstraints.NONE
                add(JLabel(labelText), gbc)
                gbc.gridx = 1; gbc.weightx = 1.0
                gbc.fill = GridBagConstraints.HORIZONTAL
                add(widget, gbc)
                row++
            }

            // Response picker
            addRow("Response:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(responseLabel)
                add(Box.createHorizontalStrut(12))
                add(responseDetail)
                add(Box.createHorizontalGlue())
                add(responsePickButton)
            })

            // Confidence level
            addRow("CI level:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(levelField)
                add(Box.createHorizontalStrut(8))
                add(JLabel("<html><span style='color:#666666; font-size:90%'>" +
                    "Strict (0, 1).  Default 0.95.</span></html>"))
                add(Box.createHorizontalGlue())
            })

            // Reference point
            addRow("Reference point:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(refPointField)
                add(Box.createHorizontalStrut(8))
                add(JLabel("<html><span style='color:#666666; font-size:90%'>" +
                    "Optional vertical line on the value axis.</span></html>"))
                add(Box.createHorizontalGlue())
            })

            // Caption + title
            addRow("Caption:", captionField)
            addRow("Title:", titleField)

            // Axis labels
            addRow("Axis labels:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(JLabel("x:"))
                add(Box.createHorizontalStrut(4))
                add(xAxisField)
                add(Box.createHorizontalStrut(12))
                add(JLabel("y:"))
                add(Box.createHorizontalStrut(4))
                add(yAxisField)
                add(Box.createHorizontalGlue())
            })

            // Formats + output
            addRow("Formats:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                for ((_, cb) in formatBoxes) { add(cb); add(Box.createHorizontalStrut(6)) }
                add(Box.createHorizontalGlue())
            })
            addRow("Output:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(outputDirField)
                add(Box.createHorizontalStrut(6))
                add(browseButton)
            })

            // Vertical filler
            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
            gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
            add(Box.createGlue(), gbc)
        }

        private fun buildButtons(): JComponent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            add(Box.createHorizontalGlue())
            add(closeButton)
            add(Box.createHorizontalStrut(8))
            add(generateButton)
        }

        // ── Picker / browse handlers ─────────────────────────────────────

        private fun onPickResponse() {
            val totalChecked = model.selectedExperimentNames.size
            val rows = model.availableResponses().map { r ->
                ChooseResponseDialog.Row(
                    name = r.name,
                    category = r.category,
                    recordingExperiments = model.experimentsRecording(r.name).size,
                    totalCheckedExperiments = totalChecked
                )
            }
            if (rows.isEmpty()) {
                statusLabel.text = "No responses are recorded by the checked experiments."
                statusLabel.foreground = Color(0x99, 0x55, 0x00)
                return
            }
            val result = ChooseResponseDialog.showDialog(
                parent = this,
                rows = rows,
                initialSelection = selectedResponse,
                validator = { name -> model.validateForResponse(name, AnalysisType.CONFIDENCE_INTERVALS) }
            )
            if (result is ChooseResponseDialog.Result.Chosen) {
                selectedResponse = result.responseName
                refreshResponseLabels()
                refreshStatus()
            }
        }

        private fun onBrowse() {
            val chooser = JFileChooser().apply {
                dialogTitle = "Output directory for Confidence Intervals report"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                val initial = outputDirField.text.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
                if (initial != null) currentDirectory = initial.toFile()
            }
            if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
            outputDirField.text = chooser.selectedFile.toPath().toAbsolutePath().toString()
        }

        // ── Status & generate ────────────────────────────────────────────

        private fun refreshResponseLabels() {
            val r = selectedResponse
            if (r == null) {
                responseLabel.text = "(none chosen)"
                responseLabel.foreground = Color(0x66, 0x66, 0x66)
                responseDetail.text = " "
                return
            }
            responseLabel.text = r
            responseLabel.foreground = Color.BLACK
            val row = model.availableResponses().firstOrNull { it.name == r }
            val category = row?.category?.let {
                when (it) {
                    ResponseCategory.OBSERVATION -> "Observation"
                    ResponseCategory.TIME_WEIGHTED -> "Time-weighted"
                    ResponseCategory.COUNTER -> "Counter"
                }
            } ?: "—"
            val recording = model.experimentsRecording(r).size
            val totalChecked = model.selectedExperimentNames.size
            responseDetail.text = "$category · Recorded by $recording of $totalChecked"
        }

        private fun refreshStatus() {
            val v = model.validateForResponse(selectedResponse, AnalysisType.CONFIDENCE_INTERVALS)
            generateButton.isEnabled = v.ok
            generateButton.toolTipText = v.reason
            if (v.ok) {
                statusLabel.text = "Ready."
                statusLabel.foreground = Color(0x33, 0x77, 0x33)
            } else {
                statusLabel.text = v.reason ?: " "
                statusLabel.foreground = Color(0x99, 0x55, 0x00)
            }
        }

        private fun parseConfidence(field: JTextField, label: String): Double? {
            val text = field.text.trim()
            val value = text.toDoubleOrNull()
            if (value == null) {
                onMessage("$label must be a number; got \"$text\".", ComparisonAnalyzerFrame.Severity.WARNING)
                return null
            }
            if (value <= 0.0 || value >= 1.0) {
                onMessage(
                    "$label must lie strictly between 0 and 1; got $value.",
                    ComparisonAnalyzerFrame.Severity.WARNING
                )
                return null
            }
            return value
        }

        private fun onGenerate() {
            val response = selectedResponse ?: run {
                statusLabel.text = "Pick a response first."
                return
            }
            val formats = formatBoxes.filterValues { it.isSelected }.keys
            if (formats.isEmpty()) {
                onMessage("Pick at least one report format.", ComparisonAnalyzerFrame.Severity.WARNING)
                return
            }
            val pathText = outputDirField.text.trim()
            if (pathText.isEmpty()) {
                onMessage("Pick an output directory.", ComparisonAnalyzerFrame.Severity.WARNING)
                return
            }
            val level = parseConfidence(levelField, "CI level") ?: return
            // Reference point — empty means "no reference line",
            // non-empty must parse to a valid double.
            val refPointText = refPointField.text.trim()
            val referencePoint: Double? = if (refPointText.isEmpty()) {
                null
            } else {
                val parsed = refPointText.toDoubleOrNull()
                if (parsed == null) {
                    onMessage(
                        "Reference point must be a number or empty; got \"$refPointText\".",
                        ComparisonAnalyzerFrame.Severity.WARNING
                    )
                    return
                }
                parsed
            }

            val outputDir = Paths.get(pathText)
            val sourceLabel = model.sources.joinToString(" · ") { it.sourceLabel }
            val observations = model.gatherObservationsFor(response)
            val outcome = try {
                ComparisonReportRenderer.renderCiPlot(
                    sourceLabel = sourceLabel,
                    responseName = response,
                    observations = observations,
                    outputDir = outputDir,
                    formats = formats,
                    level = level,
                    referencePoint = referencePoint,
                    caption = captionField.text,
                    title = titleField.text,
                    xAxisLabel = xAxisField.text,
                    yAxisLabel = yAxisField.text
                )
            } catch (t: Throwable) {
                onMessage(
                    "Confidence Intervals render failed: ${t.message ?: t::class.simpleName}",
                    ComparisonAnalyzerFrame.Severity.ERROR
                )
                return
            }
            outcome.errors.forEach {
                onMessage("Confidence Intervals: $it", ComparisonAnalyzerFrame.Severity.WARNING)
            }
            if (outcome.written.isNotEmpty()) {
                val files = outcome.written.joinToString(", ") { it.fileName.toString() }
                onMessage(
                    "Wrote ${outcome.written.size} Confidence Intervals file(s) to $outputDir: $files",
                    ComparisonAnalyzerFrame.Severity.INFO
                )
            }
        }
    }
}
