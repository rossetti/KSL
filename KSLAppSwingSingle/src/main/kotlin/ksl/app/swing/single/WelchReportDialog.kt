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
import ksl.app.single.results.WelchReportMaterializer
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
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Pure, Swing-free logic backing [WelchReportDialog].  Separated so the
 * stem/label/enablement decisions are unit-testable without constructing
 * a `JDialog` (which throws `HeadlessException` in a headless JVM).
 */
object WelchReportDialogLogic {

    /** Default filename stem for a Welch report: the analysis name (or app
     *  name when blank/"Untitled"), sanitized, with a `_Welch` suffix. */
    fun defaultStem(analysisName: String, appName: String): String {
        val base = if (analysisName.isBlank() || analysisName == "Untitled") appName else analysisName
        val sanitized = base.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "report" }
        return "${sanitized}_Welch"
    }

    /** Header text listing the responses discovered on disk. */
    fun foundLabel(responseNames: List<String>): String =
        if (responseNames.isEmpty()) {
            "No Welch data found for this run."
        } else {
            val n = responseNames.size
            "Found Welch data for $n response${if (n == 1) "" else "s"}: " +
                responseNames.joinToString(", ")
        }

    /** The Save button enables only with data, at least one format, and a stem. */
    fun saveEnabled(hasAnalyzers: Boolean, anyFormat: Boolean, stemOk: Boolean): Boolean =
        hasAnalyzers && anyFormat && stemOk
}

/**
 * Modal dialog for saving a Welch warm-up (initialization-bias) report
 * from the data captured during the most recent run.  Opened from the
 * *Post-Run Reporting* tab's "Welch Report…" button (which is enabled only
 * when Welch data exists on disk).
 *
 * Mirrors the `WelchConfigDialog` precedent: an `object` entry point plus
 * an `internal` `JDialog` impl.  Reads the on-disk `*_Welch` files via
 * [WelchReportMaterializer], renders one section per response, and records
 * each saved file into the controller's shared "Recent saves" table.
 */
object WelchReportDialog {

    /** Present the dialog modally.  Must be called on the Swing EDT. */
    fun show(controller: SingleAppController, notifier: NotificationSink, owner: Window?) {
        WelchReportDialogImpl(
            controller = controller,
            notifier = notifier,
            owner = owner,
            welchOutputDir = controller.appWorkspace.resolve("output"),
            reportsDir = SingleAppPaths.reportsDir(controller.appWorkspace)
        ).isVisible = true
    }
}

/**
 * The actual `JDialog`.  `internal` (not private) so the headless-guarded
 * smoke test can construct it against temp directories, drive its widgets,
 * and invoke [onSave] without the blocking modal [WelchReportDialog.show].
 *
 * @param welchOutputDir directory holding the `<name>_Welch` capture
 *   subdirectories (production: `<appWorkspace>/output`).
 * @param reportsDir directory the report files are written to
 *   (production: the per-analysis `reports/` dir).
 */
internal class WelchReportDialogImpl(
    private val controller: SingleAppController,
    private val notifier: NotificationSink,
    owner: Window?,
    private val welchOutputDir: Path,
    private val reportsDir: Path
) : JDialog(owner, "Welch Report", ModalityType.APPLICATION_MODAL) {

    private val analyzers = WelchReportMaterializer.discoverAnalyzers(welchOutputDir)
    private val seed = controller.outputConfig.value

    internal val stemField = JTextField(
        WelchReportDialogLogic.defaultStem(seed.analysisName, controller.appName), 28
    )
    internal val htmlBox = JCheckBox("HTML", true)
    internal val markdownBox = JCheckBox("Markdown", false)
    internal val textBox = JCheckBox("Text", false)
    // Report-section choices are made here (on demand), not carried in
    // OutputConfig — so they start from sensible static defaults.
    private val partialSumsBox = JCheckBox("Partial-sums plot", true)
    private val biasTestBox = JCheckBox("Initialization bias test (Schruben)", false)

    internal val saveButton = JButton(object : AbstractAction("Save") {
        override fun actionPerformed(e: java.awt.event.ActionEvent?) = onSave()
    })
    private val statusLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    init {
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
        size = Dimension(540, maxOf(260, size.height))
        setLocationRelativeTo(owner)
    }

    private fun buildBody(): JComponent {
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
        }
        // Fixed-width HTML so a long response list wraps instead of forcing
        // the dialog wider.
        val foundText = WelchReportDialogLogic.foundLabel(analyzers.map { it.responseName })
        body.add(leftRow(JLabel("<html><body style='width:480px'>$foundText</body></html>")))
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

        body.add(leftRow(boldLabel("Sections")))
        body.add(leftRow(JLabel("Welch + cumulative-average plot (always included)").apply {
            foreground = Color(0x66, 0x66, 0x66)
        }))
        body.add(leftRow(partialSumsBox))
        body.add(leftRow(biasTestBox))
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
        this@WelchReportDialogImpl.rootPane.defaultButton = saveButton
    }

    private fun refreshSaveEnabled() {
        val anyFormat = htmlBox.isSelected || markdownBox.isSelected || textBox.isSelected
        saveButton.isEnabled = WelchReportDialogLogic.saveEnabled(
            hasAnalyzers = analyzers.isNotEmpty(),
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

    /**
     * EDT entry point for the Save button.  Report generation can take a
     * few seconds (plot rendering, and the MSER computation when the bias
     * test is on), so it runs off the EDT on a [SwingWorker] — the button
     * is disabled and a "Generating…" status shows meanwhile, so the dialog
     * never freezes and double-clicks can't pile up.  Overwrite prompts and
     * the final record/UI update happen on the EDT.
     */
    internal fun onSave() {
        if (generating || analyzers.isEmpty()) return
        val stem = stemField.text.trim()
        // Confirm overwrites up front, on the EDT, before going async.
        val formats = selectedFormats().filter { fmt ->
            val target = reportsDir.resolve("$stem.${fmt.fileExtension}")
            !Files.exists(target) || confirmOverwrite(target)
        }
        if (stem.isEmpty() || formats.isEmpty()) return

        generating = true
        saveButton.isEnabled = false
        statusLabel.text = "Generating report…"
        statusLabel.foreground = Color(0x55, 0x55, 0x55)

        val worker = object : javax.swing.SwingWorker<List<StandardReportOutcome>, Unit>() {
            override fun doInBackground(): List<StandardReportOutcome> = renderReports(stem, formats)
            override fun done() {
                generating = false
                val outcomes = try {
                    get()
                } catch (t: Throwable) {
                    notifier.error("Welch report generation failed: ${t.message ?: t::class.simpleName}")
                    emptyList()
                }
                val saved = record(outcomes)
                if (saved > 0) {
                    statusLabel.text = "✓ Saved $saved file(s) — see Recent saves"
                    statusLabel.foreground = Color(0x2E, 0x7D, 0x32)
                    statusLabel.toolTipText = reportsDir.toString()
                    notifier.info("Saved $saved Welch report file(s).")
                } else {
                    statusLabel.text = " "
                }
                refreshSaveEnabled()
            }
        }
        worker.execute()
    }

    /**
     * Render the [formats] to disk and return one outcome each.  Pure I/O
     * and rendering — no Swing, no controller mutation — so it is safe to
     * call off the EDT (and synchronously from tests).
     */
    internal fun renderReports(stem: String, formats: List<StandardReportFormat>): List<StandardReportOutcome> {
        Files.createDirectories(reportsDir)
        val options = WelchReportMaterializer.Options(
            includePartialSums = partialSumsBox.isSelected,
            includeBiasTest = biasTestBox.isSelected,
            includeBatchMeans = false,
            deletionPoint = -1
        )
        val title = "Warm-Up Analysis — ${controller.appName}"
        return formats.map { fmt ->
            WelchReportMaterializer.materialize(
                analyzers = analyzers,
                format = fmt,
                reportsDir = reportsDir,
                fileStem = stem,
                title = title,
                options = options
            )
        }
    }

    /**
     * Closes the Welch analyzers this dialog opened at construction (each holds a `.wdf`
     * file handle) before releasing the window, so a disposed dialog leaves no open handle —
     * on Windows an open handle blocks deleting the captured Welch files.
     */
    override fun dispose() {
        analyzers.forEach { runCatching { it.close() } }
        super.dispose()
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
    internal fun saveBlocking(): Int = record(renderReports(stemField.text.trim(), selectedFormats()))

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
