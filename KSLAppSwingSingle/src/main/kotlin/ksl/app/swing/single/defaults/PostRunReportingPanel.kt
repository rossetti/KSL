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

package ksl.app.swing.single.defaults

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ksl.app.config.ReportFormat
import ksl.app.session.RunResult
import ksl.app.single.results.StandardReportFormat
import ksl.app.single.results.StandardReportMaterializer
import ksl.app.single.results.StandardReportOutcome
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.swing.single.ReportSaveRecord
import ksl.app.swing.single.SingleAppController
import ksl.utilities.io.dbutil.SimulationSnapshot
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Desktop
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 *  Default *Post-Run Reporting* tab content for `kslSingleApp(...)`.
 *
 *  Replaces the legacy "Output Options" tab in the new tab structure.
 *  Pre-run toggles (database / CSV / auto-render formats) moved to
 *  the Run Control tab via [RunControlTabPanel]; this tab focuses
 *  exclusively on **post-run** actions: materialising one or more
 *  standard reports against the snapshot in hand, with user-chosen
 *  filename stem, format mix, and report sections.
 *
 *  Three states via `CardLayout`:
 *  - **Empty** — no snapshot yet; centred placeholder pointing the
 *    user at Run Control for auto-render settings.
 *  - **Populated** — form active; defaults seeded from the model name
 *    + analysis name + auto-render format set on Run Control.
 *  - (no separate stale-results state — the Single app has no
 *    `editedSinceLastSim` banner pattern yet; if/when it gains one
 *    this panel adds the orange banner.)
 *
 *  Recent saves table at the bottom shows every report written
 *  against the current snapshot — populated by both auto-render and
 *  user clicks here.  Per-row Open (launches the system viewer) and
 *  Remove (drops the record without deleting the file).
 *
 *  @param controller         owning [SingleAppController]
 *  @param onMessage          notification sink (frame routes to its
 *                            global notifications object)
 *  @param latestSnapshot     observable signal — non-null when a
 *                            snapshot is in hand and the Save button
 *                            should enable
 */
class PostRunReportingPanel(
    private val controller: SingleAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit,
    private val latestSnapshot: StateFlow<RunResult?>
) : JPanel(BorderLayout()) {

    private val cards = CardLayout()
    private val cardsPanel = JPanel(cards)
    private val emptyCard = makeEmptyCard()
    private val populatedCard = JPanel(BorderLayout())

    // Form widgets
    private val stemField = JTextField(36)
    private val folderLabel = JLabel(" ").apply {
        font = font.deriveFont(font.size * 0.95f)
        foreground = Color(0x55, 0x55, 0x55)
    }
    private val htmlBox = JCheckBox("HTML", true)
    private val markdownBox = JCheckBox("Markdown", false)
    private val textBox = JCheckBox("Text", false)
    private val summaryBox = JCheckBox("Run summary", true)
    private val acrossRepBox = JCheckBox("Across-replication statistics", true)
    private val diagnosticsBox = JCheckBox("Include diagnostics (covariance, lag-1, Von Neumann, …)", false)
    private val histogramsBox = JCheckBox("Histograms", true)
    private val frequenciesBox = JCheckBox("Frequencies", true)
    private val timeSeriesBox = JCheckBox("Time-series", true)
    private val timeSeriesCiCombo = JComboBox(arrayOf(0.90, 0.95, 0.99)).apply {
        selectedItem = 0.95
    }
    private val saveButton = JButton("Save report")
    private val statusLabel = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
    }

    // Recent-saves widgets
    private val recentTableModel = RecentSavesTableModel()
    private val recentTable = JTable(recentTableModel).apply {
        rowHeight = 22
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        tableHeader.reorderingAllowed = false
    }
    private val openRowButton = JButton("Open").apply { isEnabled = false }
    private val removeRowButton = JButton("Remove").apply { isEnabled = false }
    private val openFolderButton = JButton("Open folder")
    private val recentCountLabel = JLabel(" ").apply {
        font = font.deriveFont(font.size * 0.95f)
    }

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        cardsPanel.add(emptyCard, CARD_EMPTY)
        cardsPanel.add(buildPopulated(), CARD_POPULATED)
        add(cardsPanel, BorderLayout.CENTER)

        wireFormBehaviour()
        wireRecentBehaviour()
        wireSubscribers()
        refreshFolderLabel()
        refreshCard()
        refreshSaveEnabled()
        refreshSectionAvailability()
    }

    // ── Layout helpers ─────────────────────────────────────────────────────

    private fun makeEmptyCard(): JPanel = JPanel(BorderLayout()).apply {
        add(
            JLabel(
                "<html><div style='text-align:center;'>" +
                    "Run the simulation to enable post-run reporting.<br>" +
                    "Auto-render settings live on the <i>Run Control</i> tab." +
                    "</div></html>",
                SwingConstants.CENTER
            ).apply { foreground = Color(0x88, 0x88, 0x88) },
            BorderLayout.CENTER
        )
    }

    private fun buildPopulated(): JPanel {
        val outer = JPanel()
        outer.layout = BoxLayout(outer, BoxLayout.Y_AXIS)
        outer.add(buildReportOutputBlock())
        outer.add(Box.createVerticalStrut(6))
        outer.add(buildFormatsBlock())
        outer.add(Box.createVerticalStrut(6))
        outer.add(buildSectionsBlock())
        // Action row sits directly under Sections — the form's
        // natural reading flow is Output → Formats → Sections →
        // "now Save".  Recent saves goes below, as a navigation aid
        // rather than an interrupter.
        outer.add(Box.createVerticalStrut(6))
        outer.add(buildActionRow())
        outer.add(Box.createVerticalStrut(8))
        outer.add(buildRecentSavesBlock())
        outer.add(Box.createVerticalGlue())
        return outer
    }

    private fun buildReportOutputBlock(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Report Output"),
            BorderFactory.createEmptyBorder(4, 8, 8, 8)
        )
        alignmentX = Component.LEFT_ALIGNMENT
        val gbc = GridBagConstraints().apply {
            insets = Insets(3, 4, 3, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }
        gbc.gridx = 0; gbc.gridy = 0; add(JLabel("Filename stem:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        add(stemField, gbc)
        gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        gbc.gridx = 1; gbc.gridy = 1
        add(
            JLabel("Extensions appended per selected format.").apply {
                foreground = Color(0x88, 0x88, 0x88)
                font = font.deriveFont(font.size * 0.9f)
            },
            gbc
        )
        gbc.gridx = 0; gbc.gridy = 2; add(JLabel("Folder:"), gbc)
        gbc.gridx = 1
        gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        add(folderLabel, gbc)
    }

    private fun buildFormatsBlock(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Formats"),
            BorderFactory.createEmptyBorder(4, 8, 8, 8)
        )
        alignmentX = Component.LEFT_ALIGNMENT
        add(htmlBox)
        add(Box.createHorizontalStrut(12))
        add(markdownBox)
        add(Box.createHorizontalStrut(12))
        add(textBox)
        add(Box.createHorizontalGlue())
    }

    private fun buildSectionsBlock(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Sections"),
            BorderFactory.createEmptyBorder(4, 8, 8, 8)
        )
        alignmentX = Component.LEFT_ALIGNMENT
        val gbc = GridBagConstraints().apply {
            insets = Insets(2, 4, 2, 4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.NONE
        }
        gbc.gridx = 0; gbc.gridy = 0; add(summaryBox, gbc)
        gbc.gridy = 1; add(acrossRepBox, gbc)
        gbc.gridy = 2
        gbc.insets = Insets(2, 24, 2, 4)
        add(diagnosticsBox, gbc)
        gbc.insets = Insets(2, 4, 2, 4)
        gbc.gridy = 3; add(histogramsBox, gbc)
        gbc.gridy = 4; add(frequenciesBox, gbc)
        gbc.gridy = 5
        val tsRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(timeSeriesBox)
            add(Box.createHorizontalStrut(8))
            add(JLabel("confidence:"))
            add(Box.createHorizontalStrut(4))
            add(timeSeriesCiCombo)
        }
        add(tsRow, gbc)
    }

    private fun buildRecentSavesBlock(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Recent saves"),
            BorderFactory.createEmptyBorder(4, 8, 8, 8)
        )
        alignmentX = Component.LEFT_ALIGNMENT
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(recentCountLabel)
            add(Box.createHorizontalGlue())
            add(openFolderButton)
        }
        val scroll = JScrollPane(recentTable).apply {
            preferredSize = Dimension(0, 140)
        }
        val rowActions = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentY = Component.TOP_ALIGNMENT
            add(openRowButton)
            add(Box.createVerticalStrut(4))
            add(removeRowButton)
            add(Box.createVerticalGlue())
            border = BorderFactory.createEmptyBorder(4, 8, 4, 0)
            preferredSize = Dimension(100, 0)
        }
        for (b in listOf(openRowButton, removeRowButton, openFolderButton)) {
            b.alignmentX = Component.LEFT_ALIGNMENT
            b.maximumSize = Dimension(110, b.preferredSize.height)
        }
        recentTable.columnModel.getColumn(COL_TIME).preferredWidth = 60
        recentTable.columnModel.getColumn(COL_NAME).preferredWidth = 320
        recentTable.columnModel.getColumn(COL_ORIGIN).preferredWidth = 70
        recentTable.columnModel.getColumn(COL_ORIGIN).cellRenderer =
            object : DefaultTableCellRenderer() {
                init { horizontalAlignment = SwingConstants.CENTER }
            }
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(rowActions, BorderLayout.EAST)
    }

    private fun buildActionRow(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(0, 8, 0, 8)
        alignmentX = Component.LEFT_ALIGNMENT
        add(saveButton)
        add(Box.createHorizontalStrut(12))
        add(statusLabel)
        add(Box.createHorizontalGlue())
    }

    // ── Behaviour wiring ───────────────────────────────────────────────────

    private fun wireFormBehaviour() {
        for (b in listOf(htmlBox, markdownBox, textBox)) {
            b.addActionListener { refreshSaveEnabled() }
        }
        for (b in listOf(summaryBox, acrossRepBox, histogramsBox, frequenciesBox, timeSeriesBox)) {
            b.addActionListener { refreshSaveEnabled() }
        }
        stemField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = refreshSaveEnabled()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = refreshSaveEnabled()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = refreshSaveEnabled()
        })
        saveButton.addActionListener { handleSave() }
    }

    private fun wireRecentBehaviour() {
        recentTable.selectionModel.addListSelectionListener {
            if (!it.valueIsAdjusting) refreshRowActionEnablement()
        }
        openRowButton.addActionListener { openSelectedRow() }
        removeRowButton.addActionListener {
            val sel = recentTable.selectedRow
            if (sel >= 0) controller.removeReportSaveRecord(sel)
        }
        openFolderButton.addActionListener { openReportsFolder() }
    }

    private fun wireSubscribers() {
        controller.edtScope.launch {
            latestSnapshot.collect {
                refreshCard()
                refreshDefaults()
                refreshSaveEnabled()
                refreshSectionAvailability()
            }
        }
        controller.edtScope.launch {
            controller.outputConfig.collect {
                refreshFolderLabel()
                refreshDefaults()
            }
        }
        controller.edtScope.launch {
            controller.recentReportSaves.collect { list ->
                recentTableModel.replace(list)
                refreshRecentHeader()
                refreshRowActionEnablement()
            }
        }
    }

    // ── State refresh ──────────────────────────────────────────────────────

    private fun refreshCard() {
        val card = if (currentSnapshot() != null) CARD_POPULATED else CARD_EMPTY
        cards.show(cardsPanel, card)
    }

    /**
     *  Seed the form defaults from controller state.  Called when the
     *  snapshot changes or the analysisName changes — but **only when
     *  the stem field is empty or matches a prior auto-default**.  A
     *  user who's typed a custom stem doesn't want it stomped by a
     *  background refresh.
     */
    private fun refreshDefaults() {
        // Format checkboxes follow Run Control's auto-render set on
        // first snapshot — once the user touches them they stop
        // tracking (we don't currently distinguish, so simply set
        // them if the snapshot just arrived and the user hasn't yet
        // interacted).  Simple approach: only seed when the form is
        // visiting for the first time of this snapshot session.
        val currentStem = stemField.text
        val newDefault = defaultStem()
        if (currentStem.isBlank() || currentStem == lastDefaultStem) {
            stemField.text = newDefault
        }
        lastDefaultStem = newDefault
    }

    private fun refreshFolderLabel() {
        // Blank label until a snapshot is in hand — the populated
        // card isn't visible without a snapshot anyway (the empty
        // card covers it), but if a stale refresh fires we don't
        // want a "pending"-shaped path leaking through.
        val dir = computeReportsDir() ?: run {
            folderLabel.text = " "
            return
        }
        folderLabel.text = dir.toString()
    }

    private fun refreshSaveEnabled() {
        val anyFormat = htmlBox.isSelected || markdownBox.isSelected || textBox.isSelected
        val anySection = summaryBox.isSelected || acrossRepBox.isSelected ||
            histogramsBox.isSelected || frequenciesBox.isSelected || timeSeriesBox.isSelected
        val stemOk = stemField.text.trim().isNotEmpty()
        val hasSnapshot = currentSnapshot() != null
        saveButton.isEnabled = hasSnapshot && anyFormat && anySection && stemOk
    }

    /**
     *  Grey out section checkboxes whose underlying snapshot list is
     *  empty (no data of that kind was emitted by the model).  The
     *  user can see *why* the checkbox is disabled rather than
     *  wondering — a `"(no … data this run)"` suffix appears on the
     *  label text.
     */
    private fun refreshSectionAvailability() {
        val snapshot = currentSnapshot() ?: run {
            for (b in listOf(histogramsBox, frequenciesBox, timeSeriesBox)) {
                b.isEnabled = true
                stripEmptyNote(b)
            }
            return
        }
        applyAvailability(histogramsBox, snapshot.histograms.isNotEmpty(), "histogram")
        applyAvailability(frequenciesBox, snapshot.frequencies.isNotEmpty(), "frequency")
        applyAvailability(timeSeriesBox, snapshot.timeSeries.isNotEmpty(), "time-series")
        timeSeriesCiCombo.isEnabled = timeSeriesBox.isEnabled && timeSeriesBox.isSelected
        // Time-series CI: also gate on the checkbox state.
        timeSeriesBox.addActionListener { timeSeriesCiCombo.isEnabled = timeSeriesBox.isSelected }
    }

    private fun applyAvailability(box: JCheckBox, available: Boolean, datumName: String) {
        if (available) {
            box.isEnabled = true
            stripEmptyNote(box)
        } else {
            box.isEnabled = false
            box.isSelected = false
            val labelBase = box.text.substringBeforeLast(EMPTY_NOTE_SEPARATOR)
            box.text = "$labelBase$EMPTY_NOTE_SEPARATOR(no $datumName data this run)"
        }
        refreshSaveEnabled()
    }

    private fun stripEmptyNote(box: JCheckBox) {
        if (EMPTY_NOTE_SEPARATOR in box.text) {
            box.text = box.text.substringBeforeLast(EMPTY_NOTE_SEPARATOR).trim()
        }
    }

    private fun refreshRecentHeader() {
        val list = controller.recentReportSaves.value
        recentCountLabel.text =
            "Recent saves (${list.size}/${SingleAppController.MAX_RECENT_REPORT_SAVES})"
    }

    private fun refreshRowActionEnablement() {
        val sel = recentTable.selectedRow
        val record = controller.recentReportSaves.value.getOrNull(sel)
        openRowButton.isEnabled = record != null
        removeRowButton.isEnabled = record != null
    }

    // ── Snapshot accessors ─────────────────────────────────────────────────

    private fun currentSnapshot(): SimulationSnapshot.ExperimentCompleted? {
        val result = latestSnapshot.value ?: return null
        return StandardReportMaterializer.extractSnapshot(result)
    }

    // ── Save handler ───────────────────────────────────────────────────────

    private fun handleSave() {
        val result = latestSnapshot.value ?: return
        val dir = ensureReportsDir() ?: return
        val stem = stemField.text.trim()
        val formats = buildList {
            if (htmlBox.isSelected) add(ReportFormat.HTML)
            if (markdownBox.isSelected) add(ReportFormat.MARKDOWN)
            if (textBox.isSelected) add(ReportFormat.TEXT)
        }
        if (formats.isEmpty()) return
        val sections = StandardReportMaterializer.SectionOptions(
            showRunSummary = summaryBox.isSelected,
            showAcrossReplicationStats = acrossRepBox.isSelected,
            showHistograms = histogramsBox.isSelected && histogramsBox.isEnabled,
            showFrequencies = frequenciesBox.isSelected && frequenciesBox.isEnabled,
            showTimeSeries = timeSeriesBox.isSelected && timeSeriesBox.isEnabled,
            showDiagnostics = diagnosticsBox.isSelected,
            timeSeriesConfidenceLevel = (timeSeriesCiCombo.selectedItem as? Double) ?: 0.95
        )
        val title = composeReportTitle()
        var saved = 0
        for (fmt in formats) {
            val standardFormat = when (fmt) {
                ReportFormat.HTML -> StandardReportFormat.HTML
                ReportFormat.MARKDOWN -> StandardReportFormat.MARKDOWN
                ReportFormat.TEXT -> StandardReportFormat.TEXT
            }
            val target = dir.resolve("$stem.${standardFormat.fileExtension}")
            if (Files.exists(target) && !confirmOverwrite(target)) continue
            val outcome = StandardReportMaterializer.materialize(
                result = result,
                format = standardFormat,
                reportsDir = dir,
                fileStem = stem,
                title = title,
                sections = sections
            )
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
                is StandardReportOutcome.Failed ->
                    onMessage(outcome.reason, NotificationSeverity.ERROR)
            }
        }
        if (saved > 0) {
            statusLabel.text = "✓ Saved $saved file(s) to $dir"
            statusLabel.foreground = STATUS_GREEN
            onMessage("Saved $saved report file(s).", NotificationSeverity.INFO)
        }
    }

    private fun confirmOverwrite(path: Path): Boolean {
        val choice = JOptionPane.showConfirmDialog(
            this,
            "${path.fileName} already exists.\nOverwrite?",
            "File Exists",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.YES_OPTION
    }

    private fun openSelectedRow() {
        val sel = recentTable.selectedRow
        val record = controller.recentReportSaves.value.getOrNull(sel) ?: return
        if (!Files.exists(record.path)) {
            onMessage(
                "File no longer exists at ${record.path}",
                NotificationSeverity.WARNING
            )
            return
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(record.path.toFile())
        } catch (t: Throwable) {
            onMessage(
                "Could not open file: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
        }
    }

    private fun openReportsFolder() {
        val dir = computeReportsDir() ?: run {
            onMessage(
                "Reports folder does not exist yet — save a report first.",
                NotificationSeverity.INFO
            )
            return
        }
        if (!Files.exists(dir)) {
            onMessage(
                "Reports folder does not exist yet — save a report first.",
                NotificationSeverity.INFO
            )
            return
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir.toFile())
        } catch (t: Throwable) {
            onMessage(
                "Could not open folder: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.ERROR
            )
        }
    }

    // ── Path / naming helpers ──────────────────────────────────────────────

    /**
     *  Resolve the per-analysis reports directory
     *  `<workspace>/reports/<sanitized-analysisName>/`.  Returns
     *  `null` only when no snapshot is in hand (caller uses this to
     *  blank the folder label rather than render a misleading path).
     *  No per-run subdirectory — all of an analysis's reports live
     *  together under one folder, browsable in the file manager.
     */
    private fun computeReportsDir(): Path? {
        if (currentSnapshot() == null) return null
        return analysisReportsDir()
    }

    /** Path to the reports directory for the current analysis,
     *  without checking for a snapshot.  Used by the Save handler
     *  which has already verified the snapshot is present.  No
     *  per-analysis subdirectory — the workspace folder is itself
     *  analysis-name-derived (see [SingleAppController.appWorkspace]),
     *  so a nested layer would just repeat the name. */
    private fun analysisReportsDir(): Path {
        return controller.appWorkspace.resolve("reports")
    }

    private fun ensureReportsDir(): Path? = try {
        if (currentSnapshot() == null) {
            onMessage("No snapshot available.", NotificationSeverity.WARNING)
            null
        } else {
            analysisReportsDir().also { Files.createDirectories(it) }
        }
    } catch (t: Throwable) {
        onMessage(
            "Could not create reports directory: ${t.message ?: t::class.simpleName}",
            NotificationSeverity.ERROR
        )
        null
    }

    /** Default filename stem = sanitized analysis name (or model
     *  name when analysisName is blank/"Untitled").  Matches the
     *  auto-render naming so an untouched Save just writes the same
     *  file (overwrite-confirmed); a user who wants to preserve a
     *  particular run varies the stem before clicking Save. */
    private fun defaultStem(): String {
        val analysis = controller.outputConfig.value.analysisName
        return sanitizeAnalysisStem(analysis)
    }

    private fun sanitizeAnalysisStem(analysisName: String): String {
        val baseName = if (analysisName.isBlank() || analysisName == "Untitled") {
            controller.appName
        } else {
            analysisName
        }
        return baseName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "report" }
    }

    private fun composeReportTitle(): String {
        val analysis = controller.outputConfig.value.analysisName
        val model = controller.appName
        return if (analysis.isBlank() || analysis == "Untitled") {
            "Standard Report — $model"
        } else {
            "Standard Report — $model ($analysis)"
        }
    }

    // ── Recent-saves table model ───────────────────────────────────────────

    private inner class RecentSavesTableModel : AbstractTableModel() {
        private var rows: List<ReportSaveRecord> = emptyList()
        fun replace(newRows: List<ReportSaveRecord>) {
            rows = newRows
            fireTableDataChanged()
        }
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 3
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getColumnName(column: Int): String = when (column) {
            COL_TIME -> "Time"
            COL_NAME -> "File"
            COL_ORIGIN -> "Origin"
            else -> ""
        }
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val r = rows.getOrNull(rowIndex) ?: return ""
            return when (columnIndex) {
                COL_TIME -> TIME_FORMATTER.format(r.timestamp)
                COL_NAME -> r.fileName
                COL_ORIGIN -> when (r.origin) {
                    ReportSaveRecord.Origin.AUTO -> "auto"
                    ReportSaveRecord.Origin.MANUAL -> "manual"
                }
                else -> ""
            }
        }
    }

    private var lastDefaultStem: String = ""

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_POPULATED = "populated"

        private const val COL_TIME = 0
        private const val COL_NAME = 1
        private const val COL_ORIGIN = 2

        private val STATUS_GREEN = Color(0x2E, 0x7D, 0x32)

        private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        private const val EMPTY_NOTE_SEPARATOR = "  "
    }
}
