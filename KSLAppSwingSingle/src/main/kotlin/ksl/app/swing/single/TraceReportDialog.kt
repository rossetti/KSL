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

package ksl.app.swing.single

import ksl.app.notification.NotificationSink
import ksl.app.single.results.ReportSaveRecord
import ksl.app.single.results.SingleAppPaths
import ksl.app.single.results.StandardReportFormat
import ksl.app.single.results.StandardReportOutcome
import ksl.app.single.results.TraceReportMaterializer
import ksl.observers.ResponseTraceData
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JTextField
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Pure, Swing-free logic backing [TraceReportDialog].  Separated so the
 * stem/label/enablement/parse decisions are unit-testable without
 * constructing a `JDialog` (which throws `HeadlessException` in a headless JVM).
 */
object TraceReportDialogLogic {

    /** Default filename stem for a trace report: the analysis name (or app
     *  name when blank/"Untitled"), sanitized, with a `_Trace` suffix. */
    fun defaultStem(analysisName: String, appName: String): String {
        val base = if (analysisName.isBlank() || analysisName == "Untitled") appName else analysisName
        val sanitized = base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "report" }
        return "${sanitized}_Trace"
    }

    /** Header text listing the responses discovered on disk. */
    fun foundLabel(responseNames: List<String>): String =
        if (responseNames.isEmpty()) {
            "No trace data found for this run."
        } else {
            val n = responseNames.size
            "Found trace data for $n response${if (n == 1) "" else "s"}: " +
                responseNames.joinToString(", ")
        }

    /** The Save button enables only with data, at least one format, and a stem. */
    fun saveEnabled(hasTraces: Boolean, anyFormat: Boolean, stemOk: Boolean): Boolean =
        hasTraces && anyFormat && stemOk

    /**
     * Parses a comma/space-separated list of replication numbers, e.g.
     * "1, 3, 5".  Returns null for blank input (meaning: the first recorded
     * replication of each trace).  Non-numeric tokens are ignored.
     */
    fun parseRepNums(text: String): List<Int>? {
        val tokens = text.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val nums = tokens.mapNotNull { it.toIntOrNull() }
        return nums.ifEmpty { null }
    }
}

/**
 * Modal dialog for saving a response-trace report from the data captured
 * during the most recent run.  Opened from the *Post-Run Reporting* tab's
 * "Trace Report…" button (enabled only when trace data exists on disk).
 *
 * Mirrors [WelchReportDialog].  The one difference: a trace file does not
 * record whether its response is time-weighted, so the dialog resolves that
 * from the controller's probe `responseSnapshot` when building the
 * [ResponseTraceData] list to render.
 */
object TraceReportDialog {

    /** Present the dialog modally.  Must be called on the Swing EDT. */
    fun show(controller: SingleAppController, notifier: NotificationSink, owner: Window?) {
        TraceReportDialogImpl(
            controller = controller,
            notifier = notifier,
            owner = owner,
            traceOutputDir = controller.appWorkspace.resolve("output"),
            reportsDir = SingleAppPaths.reportsDir(controller.appWorkspace)
        ).isVisible = true
    }
}

/**
 * The actual `JDialog`.  `internal` (not private) so the headless-guarded
 * smoke test can construct it against temp directories, drive its widgets,
 * and invoke [onSave] / [saveBlocking] without the blocking modal
 * [TraceReportDialog.show].
 *
 * @param traceOutputDir directory holding the `<name>_Trace` files
 *   (production: `<appWorkspace>/output`).
 * @param reportsDir directory the report files are written to
 *   (production: the per-analysis `reports/` dir).
 */
internal class TraceReportDialogImpl(
    private val controller: SingleAppController,
    private val notifier: NotificationSink,
    owner: Window?,
    private val traceOutputDir: Path,
    private val reportsDir: Path
) : JDialog(owner, "Trace Report", ModalityType.APPLICATION_MODAL) {

    /**
     * The traces to render, resolved by matching the probe snapshot's
     * responses to on-disk `<name>_Trace` files.  The probe supplies the
     * time-weighted flag (absent from the trace file) and the un-mangled name.
     */
    private val traces: List<ResponseTraceData> = resolveTraces()

    private val seed = controller.outputConfig.value

    internal val stemField = JTextField(
        TraceReportDialogLogic.defaultStem(seed.analysisName, controller.appName), 28
    )
    internal val htmlBox = JCheckBox("HTML", true)
    internal val markdownBox = JCheckBox("Markdown", false)
    internal val textBox = JCheckBox("Text", false)

    private val firstRepRadio = JRadioButton("First only", true)
    private val customRepRadio = JRadioButton("Custom:")
    private val repNumsField = JTextField(12).apply {
        toolTipText = "Comma-separated replication numbers, e.g. 1, 3, 5"
        isEnabled = false
    }
    private val startTimeField = JTextField("0.0", 8)
    private val endTimeField = JTextField("", 8).apply {
        toolTipText = "Blank = to the end of the replication"
    }

    internal val saveButton = JButton(object : AbstractAction("Save") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onSave()
    })
    private val statusLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    init {
        ButtonGroup().apply { add(firstRepRadio); add(customRepRadio) }
        customRepRadio.addActionListener { repNumsField.isEnabled = true }
        firstRepRadio.addActionListener { repNumsField.isEnabled = false }

        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout()
        contentPane.add(buildBody(), BorderLayout.CENTER)
        contentPane.add(buildButtonRow(), BorderLayout.SOUTH)

        for (b in listOf(htmlBox, markdownBox, textBox)) b.addActionListener { refreshSaveEnabled() }
        stemField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = refreshSaveEnabled()
            override fun removeUpdate(e: DocumentEvent?) = refreshSaveEnabled()
            override fun changedUpdate(e: DocumentEvent?) = refreshSaveEnabled()
        })
        refreshSaveEnabled()

        pack()
        size = Dimension(560, maxOf(300, size.height))
        setLocationRelativeTo(owner)
    }

    /** Build the ResponseTraceData list from probe responses with on-disk traces. */
    private fun resolveTraces(): List<ResponseTraceData> =
        controller.responseSnapshot.mapNotNull { probe ->
            val file = traceOutputDir.resolve(probe.name.replace(':', '_') + "_Trace")
            if (Files.exists(file)) {
                ResponseTraceData(file, isTimeWeighted = probe.isTimeWeighted, name = probe.name)
            } else null
        }

    private fun buildBody(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
        }
        // Fixed-width HTML so a long response list wraps instead of widening.
        val foundText = TraceReportDialogLogic.foundLabel(traces.map { it.name })
        body.add(leftRow(JLabel("<html><body style='width:500px'>$foundText</body></html>")))
        body.add(Box.createVerticalStrut(10))

        body.add(leftRow(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("Filename stem:"))
            add(stemField)
        }))
        body.add(Box.createVerticalStrut(6))

        body.add(leftRow(boldLabel("Formats")))
        body.add(leftRow(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(htmlBox); add(markdownBox); add(textBox)
        }))
        body.add(Box.createVerticalStrut(6))

        body.add(leftRow(boldLabel("Replications")))
        body.add(leftRow(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(firstRepRadio); add(customRepRadio); add(repNumsField)
        }))
        body.add(Box.createVerticalStrut(6))

        body.add(leftRow(boldLabel("Time window")))
        body.add(leftRow(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("start:")); add(startTimeField)
            add(Box.createHorizontalStrut(8))
            add(JLabel("end:")); add(endTimeField)
        }))
        return body
    }

    private fun buildButtonRow(): JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(0, 12, 8, 12)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(statusLabel) }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(JButton(object : AbstractAction("Close") {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) = dispose()
            }))
            add(saveButton)
        }, BorderLayout.EAST)
        this@TraceReportDialogImpl.rootPane.defaultButton = saveButton
    }

    private fun refreshSaveEnabled() {
        val anyFormat = htmlBox.isSelected || markdownBox.isSelected || textBox.isSelected
        saveButton.isEnabled = TraceReportDialogLogic.saveEnabled(
            hasTraces = traces.isNotEmpty(),
            anyFormat = anyFormat,
            stemOk = stemField.text.trim().isNotEmpty()
        )
    }

    /** Guards against re-entry while a generation is already running. */
    private var generating = false

    private fun selectedFormats(): List<StandardReportFormat> = buildList {
        if (htmlBox.isSelected) add(StandardReportFormat.HTML)
        if (markdownBox.isSelected) add(StandardReportFormat.MARKDOWN)
        if (textBox.isSelected) add(StandardReportFormat.TEXT)
    }

    private fun currentOptions(): TraceReportMaterializer.Options {
        val repNums = if (customRepRadio.isSelected) {
            TraceReportDialogLogic.parseRepNums(repNumsField.text)
        } else null
        val start = startTimeField.text.trim().toDoubleOrNull() ?: 0.0
        val end = endTimeField.text.trim().toDoubleOrNull() ?: Double.MAX_VALUE
        return TraceReportMaterializer.Options(repNums = repNums, startTime = start, endTime = end)
    }

    /**
     * EDT entry point for the Save button.  Trace reports can be plot-heavy,
     * so generation runs off the EDT on a [SwingWorker] — the button is
     * disabled and a "Generating…" status shows meanwhile, so the dialog never
     * freezes and double-clicks can't pile up.  Overwrite prompts and the
     * final record/UI update happen on the EDT.
     */
    internal fun onSave() {
        if (generating || traces.isEmpty()) return
        val stem = stemField.text.trim()
        val formats = selectedFormats().filter { fmt ->
            val target = reportsDir.resolve("$stem.${fmt.fileExtension}")
            !Files.exists(target) || confirmOverwrite(target)
        }
        if (stem.isEmpty() || formats.isEmpty()) return

        generating = true
        saveButton.isEnabled = false
        statusLabel.text = "Generating report…"
        statusLabel.foreground = Color(0x55, 0x55, 0x55)

        val options = currentOptions()
        val worker = object : javax.swing.SwingWorker<List<StandardReportOutcome>, Unit>() {
            override fun doInBackground(): List<StandardReportOutcome> = renderReports(stem, formats, options)
            override fun done() {
                generating = false
                val outcomes = try {
                    get()
                } catch (t: Throwable) {
                    notifier.error("Trace report generation failed: ${t.message ?: t::class.simpleName}")
                    emptyList()
                }
                val saved = record(outcomes)
                if (saved > 0) {
                    statusLabel.text = "✓ Saved $saved file(s) — see Recent saves"
                    statusLabel.foreground = Color(0x2E, 0x7D, 0x32)
                    statusLabel.toolTipText = reportsDir.toString()
                    notifier.info("Saved $saved trace report file(s).")
                } else {
                    statusLabel.text = " "
                }
                refreshSaveEnabled()
            }
        }
        worker.execute()
    }

    /**
     * Render the [formats] to disk and return one outcome each.  Pure I/O and
     * rendering — no Swing, no controller mutation — so it is safe to call off
     * the EDT (and synchronously from tests).
     */
    internal fun renderReports(
        stem: String,
        formats: List<StandardReportFormat>,
        options: TraceReportMaterializer.Options
    ): List<StandardReportOutcome> {
        Files.createDirectories(reportsDir)
        val title = "Response Trace — ${controller.appName}"
        return formats.map { fmt ->
            TraceReportMaterializer.materialize(
                traces = traces,
                format = fmt,
                reportsDir = reportsDir,
                fileStem = stem,
                title = title,
                options = options
            )
        }
    }

    /** Record each successful outcome into Recent saves; returns the count saved. */
    private fun record(outcomes: List<StandardReportOutcome>): Int {
        var saved = 0
        for (outcome in outcomes) {
            when (outcome) {
                is StandardReportOutcome.Ok -> {
                    controller.addReportSaveRecord(
                        ReportSaveRecord(
                            timestamp = LocalDateTime.now(),
                            fileName = outcome.file.name,
                            path = outcome.file.toPath(),
                            origin = ReportSaveRecord.Origin.MANUAL
                        )
                    )
                    saved++
                }
                is StandardReportOutcome.Failed -> notifier.error(outcome.reason)
            }
        }
        return saved
    }

    /** Synchronous render + record — used by tests (no SwingWorker). */
    internal fun saveBlocking(): Int =
        record(renderReports(stemField.text.trim(), selectedFormats(), currentOptions()))

    private fun confirmOverwrite(path: Path): Boolean =
        JOptionPane.showConfirmDialog(
            this,
            "${path.fileName} already exists.\nOverwrite?",
            "File Exists",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        ) == JOptionPane.YES_OPTION

    private fun leftRow(component: JComponent): JComponent =
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(component)
        }

    private fun boldLabel(text: String): JLabel =
        JLabel(text).apply { font = font.deriveFont(Font.BOLD) }
}
