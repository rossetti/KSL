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

package ksl.app.swing.scenario

import kotlinx.coroutines.launch
import ksl.app.config.ReportFormat
import ksl.app.session.RunResult
import ksl.app.swing.common.notification.NotificationSeverity
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Font
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.EventObject
import javax.swing.AbstractCellEditor
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 *  *Scenario Reports* tab — always-visible counterpart to the
 *  deprecated [ScenarioReportDialog].  Owns per-scenario deep-dive
 *  files and the consolidated summary file lifecycle for the most
 *  recent batch.
 *
 *  Lifecycle (Design X + R1 — see the controller for the full
 *  rationale): the panel subscribes to [ScenarioAppController.lastResult]
 *  and [ScenarioAppController.runningFlow], swapping between an
 *  *empty-state* card (no batch reportable / simulation in progress)
 *  and the *populated* card.  On every transition into the populated
 *  card the per-scenario rows are rebuilt from `BatchCompleted.snapshots`,
 *  with file-handling preferences (policy, format, reports dir) carried
 *  over so the user doesn't have to re-pick them between runs.
 *
 *  External-deletion catch (Q2 in the lifecycle plan): refreshes from
 *  disk when [refreshFromDisk] is invoked — `ScenarioAppFrame`'s
 *  `JTabbedPane.ChangeListener` calls this every time the tab becomes
 *  active.  Sitting on the tab while deleting a file from Finder
 *  needs the explicit *Refresh* button.
 */
class ScenarioReportsTabPanel(
    private val controller: ScenarioAppController,
    /** Surfaces success/error messages through the frame's notification
     *  overlay.  Optional — null silences feedback. */
    private val onMessage: (message: String, severity: NotificationSeverity) -> Unit =
        { _, _ -> }
) : JPanel(CardLayout()) {

    // ── Cards ──────────────────────────────────────────────────────────────
    private val cards = CardLayout()
    private val emptyCard = JPanel()
    private val populatedCard = JPanel(BorderLayout())

    private val emptyStateLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = Color(0x66, 0x66, 0x66)
    }

    // ── Mutable state backing the populated card ───────────────────────────

    /** Current per-row scenario names; rebuilt on each new batch. */
    private var scenarioNames: List<String> = emptyList()

    /** Authoritative checkbox state — reallocated when scenarioNames changes. */
    private var selectedFlags: BooleanArray = BooleanArray(0)

    /** Per-row status; refreshed against disk by [refreshFromDisk]. */
    private var rowStatus: Array<RowStatus> = emptyArray()

    /** Per-row "last modified" text (HH:mm:ss) for the most recent file
     *  matching that scenario.  Empty when no file exists. */
    private var rowMtime: Array<String> = emptyArray()

    /** Last-modified text for the summary file. */
    private var summaryMtime: String = ""

    /** Current reports directory.  Initialised from the controller's
     *  workspace and mutable via *Change…*. */
    private var currentReportsDir: Path = controller.appWorkspace
        .resolve("output").resolve("reports").toAbsolutePath()

    /** Names checked when the user last clicked Generate Summary.
     *  `null` means no summary has been generated this session (or the
     *  file is absent on disk).  Used to detect *Stale* status when the
     *  checked set drifts. */
    private var lastGeneratedSummarySelection: Set<String>? = null

    private val clockFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

    // ── Populated card components ──────────────────────────────────────────

    private val tableModel = ScenarioSelectionTableModel()
    private val table = JTable(tableModel).apply {
        rowHeight = 26
        tableHeader.reorderingAllowed = false
        setRowSelectionAllowed(false)
        setColumnSelectionAllowed(false)
        cellSelectionEnabled = false
        columnModel.getColumn(COL_INCLUDE).preferredWidth = 70
        columnModel.getColumn(COL_INCLUDE).maxWidth = 90
        columnModel.getColumn(COL_NAME).preferredWidth = 260
        columnModel.getColumn(COL_STATUS).preferredWidth = 110
        columnModel.getColumn(COL_OPEN).preferredWidth = 90
        columnModel.getColumn(COL_OPEN).maxWidth = 110
        columnModel.getColumn(COL_DELETE).preferredWidth = 90
        columnModel.getColumn(COL_DELETE).maxWidth = 110
        columnModel.getColumn(COL_OPEN).cellRenderer = OpenButtonCellRenderer()
        columnModel.getColumn(COL_OPEN).cellEditor = OpenButtonCellEditor("Open") { row -> onOpenRow(row) }
        columnModel.getColumn(COL_DELETE).cellRenderer = OpenButtonCellRenderer()
        columnModel.getColumn(COL_DELETE).cellEditor = OpenButtonCellEditor("Delete") { row -> onDeleteRow(row) }
    }

    private val generatePerScenarioButton = JButton("Generate Selected").apply {
        toolTipText = "Write one full deep-dive document per checked scenario."
    }
    private val checkAllButton = JButton("Check All")
    private val uncheckAllButton = JButton("Uncheck All")

    private val generateSummaryButton = JButton("Generate Summary").apply {
        toolTipText = "Write one consolidated document covering every checked scenario."
    }
    private val summaryStatusLabel = JLabel("—")
    private val openSummaryButton = JButton("Open").apply {
        toolTipText = "Open the consolidated summary file in the default app."
    }
    private val deleteSummaryButton = JButton("Delete").apply {
        toolTipText = "Delete the consolidated summary file(s) from disk.  Confirms first."
    }

    private val changeReportsDirButton = JButton("Change…").apply {
        toolTipText = "Pick a different directory to write reports into for this session."
    }
    private val revealReportsDirButton = JButton("Reveal…").apply {
        toolTipText = "Open the reports directory in the file browser."
    }
    private val refreshButton = JButton("Refresh").apply {
        toolTipText = "Re-read the reports directory and update Status columns."
    }
    private val reportsDirLabel = JLabel(currentReportsDir.toString())

    private val htmlRadio = JRadioButton("HTML", true).apply {
        toolTipText = "Generate reports as HTML files (default).  Opens in the default browser."
    }
    private val markdownRadio = JRadioButton("Markdown").apply {
        toolTipText = "Generate reports as .md files."
    }
    private val textRadio = JRadioButton("Text").apply {
        toolTipText = "Generate reports as plain .txt files."
    }

    private val overwriteRadio = JRadioButton("Overwrite", true).apply {
        toolTipText = "Write over any existing file with the same stem (default)."
    }
    private val skipRadio = JRadioButton("Skip if exists").apply {
        toolTipText = "Don't write if the target file already exists.  Status stays as it was."
    }
    private val appendTimestampRadio = JRadioButton("Append timestamp").apply {
        toolTipText = "Write to a new file with a _yyyy-MM-dd_HHmmss suffix.  Old files preserved."
    }

    private val statusStrip = JLabel(" ").apply {
        border = BorderFactory.createEmptyBorder(0, 12, 6, 12)
        foreground = Color(0x55, 0x55, 0x55)
    }

    init {
        layout = cards
        emptyCard.layout = BoxLayout(emptyCard, BoxLayout.Y_AXIS)
        emptyCard.border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
        emptyCard.add(Box.createVerticalGlue())
        emptyStateLabel.alignmentX = Component.CENTER_ALIGNMENT
        emptyCard.add(emptyStateLabel)
        emptyCard.add(Box.createVerticalGlue())

        populatedCard.add(buildHeader(), BorderLayout.NORTH)
        populatedCard.add(buildBody(), BorderLayout.CENTER)
        populatedCard.add(buildFooter(), BorderLayout.SOUTH)

        add(emptyCard, CARD_EMPTY)
        add(populatedCard, CARD_POPULATED)

        wireListeners()
        wireCollectors()
        refreshEnablementAndCard()
    }

    // ── Public API for ScenarioAppFrame ────────────────────────────────────

    /** Re-read the reports directory and refresh per-row Status.  Called
     *  by [ScenarioAppFrame]'s `JTabbedPane.ChangeListener` when this
     *  tab becomes active so external file deletions land in the UI
     *  without the user clicking *Refresh*. */
    fun refreshFromDisk() {
        if (scenarioNames.isEmpty()) return
        clearStatusStrip()
        refreshAll()
    }

    // ── Layout builders (populated card) ───────────────────────────────────

    private fun buildHeader(): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xCC, 0xCC, 0xCC)),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        )
        add(JLabel("Reports written to:").apply {
            alignmentX = Component.LEFT_ALIGNMENT
        })
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            reportsDirLabel.foreground = Color(0x55, 0x55, 0x55)
            add(reportsDirLabel)
            add(Box.createHorizontalStrut(12))
            add(changeReportsDirButton)
            add(Box.createHorizontalStrut(6))
            add(revealReportsDirButton)
            add(Box.createHorizontalStrut(6))
            add(refreshButton)
            add(Box.createHorizontalGlue())
        })
        ButtonGroup().apply {
            add(htmlRadio); add(markdownRadio); add(textRadio)
        }
        add(Box.createVerticalStrut(4))
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Format:"))
            add(Box.createHorizontalStrut(12))
            add(htmlRadio)
            add(Box.createHorizontalStrut(8))
            add(markdownRadio)
            add(Box.createHorizontalStrut(8))
            add(textRadio)
            add(Box.createHorizontalGlue())
        })
    }

    private fun buildBody(): JComponent {
        val perScenarioSection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(8, 12, 8, 12),
                BorderFactory.createTitledBorder("Per-scenario reports")
            )
            val scroll = JScrollPane(table).apply {
                border = BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC))
            }
            add(scroll)
            add(Box.createVerticalStrut(6))
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(generatePerScenarioButton)
                add(Box.createHorizontalStrut(12))
                add(checkAllButton)
                add(Box.createHorizontalStrut(6))
                add(uncheckAllButton)
                add(Box.createHorizontalGlue())
            })
        }

        val summarySection = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(0, 12, 8, 12),
                BorderFactory.createTitledBorder("Consolidated summary (covers checked scenarios)")
            )
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(generateSummaryButton)
                add(Box.createHorizontalStrut(18))
                add(JLabel("Status: "))
                summaryStatusLabel.font = summaryStatusLabel.font.deriveFont(Font.BOLD)
                add(summaryStatusLabel)
                add(Box.createHorizontalStrut(12))
                add(openSummaryButton)
                add(Box.createHorizontalStrut(6))
                add(deleteSummaryButton)
                add(Box.createHorizontalGlue())
            })
        }

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(perScenarioSection)
            add(summarySection)
            add(statusStrip.apply { alignmentX = Component.LEFT_ALIGNMENT })
        }
    }

    private fun buildFooter(): JComponent {
        ButtonGroup().apply {
            add(overwriteRadio); add(skipRadio); add(appendTimestampRadio)
        }
        val policyRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("File handling on Generate:"))
            add(Box.createHorizontalStrut(12))
            add(overwriteRadio)
            add(Box.createHorizontalStrut(8))
            add(skipRadio)
            add(Box.createHorizontalStrut(8))
            add(appendTimestampRadio)
            add(Box.createHorizontalGlue())
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
            add(policyRow)
        }
    }

    // ── Listener wiring ────────────────────────────────────────────────────

    private fun wireListeners() {
        revealReportsDirButton.addActionListener { revealDir(currentReportsDir) }
        changeReportsDirButton.addActionListener { onChangeReportsDirClick() }
        refreshButton.addActionListener {
            clearStatusStrip()
            refreshAll()
        }
        generatePerScenarioButton.addActionListener { onGeneratePerScenarioClick() }
        generateSummaryButton.addActionListener { onGenerateSummaryClick() }
        openSummaryButton.addActionListener {
            mostRecentSummary()?.let { openFile(it) }
        }
        deleteSummaryButton.addActionListener { onDeleteSummaryClick() }
        checkAllButton.addActionListener { setAllChecked(true) }
        uncheckAllButton.addActionListener { setAllChecked(false) }
    }

    private fun wireCollectors() {
        controller.edtScope.launch {
            controller.lastResult.collect { _ -> refreshEnablementAndCard() }
        }
        controller.edtScope.launch {
            controller.runningFlow.collect { _ -> refreshEnablementAndCard() }
        }
    }

    // ── Card / state-orchestration ─────────────────────────────────────────

    /** Recompute card visibility + per-card state from the controller's
     *  current snapshot of state.  Single source of truth so the two
     *  collectors can't race into inconsistent states. */
    private fun refreshEnablementAndCard() {
        val batch = controller.lastResult.value as? RunResult.BatchCompleted
        val hasSnapshots = batch != null && batch.snapshots.isNotEmpty()
        if (hasSnapshots) {
            // Snapshot names drive the table.  When the names change
            // (new batch, deleted scenario, rename), we rebuild the
            // backing arrays so per-row state is consistent.
            val names = batch!!.snapshots.map { it.experiment.exp_name }
            if (names != scenarioNames) {
                rebuildRowsForScenarios(names)
            }
            cards.show(this, CARD_POPULATED)
        } else {
            emptyStateLabel.text = if (controller.runningFlow.value) {
                "<html><div style='text-align:center;'>" +
                    "Simulation in progress.<br>" +
                    "Reports will appear when the batch completes." +
                    "</div></html>"
            } else {
                "<html><div style='text-align:center;'>" +
                    "No completed scenario batch yet.<br>" +
                    "Run the scenarios to populate this tab." +
                    "</div></html>"
            }
            cards.show(this, CARD_EMPTY)
        }
        refreshButtonEnablement()
    }

    private fun rebuildRowsForScenarios(names: List<String>) {
        scenarioNames = names
        selectedFlags = BooleanArray(names.size) { true }
        rowStatus = Array(names.size) { RowStatus.NONE }
        rowMtime = Array(names.size) { "" }
        // New scenarios → previous staleness baseline is no longer
        // meaningful.  Will be reset by the next Generate Summary.
        lastGeneratedSummarySelection = null
        tableModel.fireTableDataChanged()
        refreshAll()
    }

    // ── State refreshers ──────────────────────────────────────────────────

    /** Re-read everything from disk for the current scenarioNames.
     *  Cheap; called by the *Refresh* button, after every state-changing
     *  action, on tab-activation via [refreshFromDisk], and after
     *  rebuilding the row arrays for a new batch.  Does NOT touch
     *  [statusStrip] — callers that want to clear / set the strip do so
     *  explicitly. */
    private fun refreshAll() {
        for (i in scenarioNames.indices) {
            val path = mostRecentPerScenarioFile(scenarioNames[i])
            if (path != null && Files.exists(path)) {
                rowStatus[i] = RowStatus.GENERATED
                rowMtime[i] = mtimeOf(path)
            } else {
                rowStatus[i] = RowStatus.NONE
                rowMtime[i] = ""
            }
        }
        tableModel.fireTableDataChanged()
        refreshSummaryStatus()
        refreshButtonEnablement()
    }

    private fun mtimeOf(file: Path): String = try {
        clockFormatter.format(Files.getLastModifiedTime(file).toInstant())
    } catch (_: Throwable) {
        ""
    }

    private fun applyStatusStrip(text: String, kind: StripKind) {
        statusStrip.text = text
        statusStrip.foreground = when (kind) {
            StripKind.SUCCESS -> Color(0x33, 0x77, 0x33)
            StripKind.WARN    -> Color(0xCC, 0x77, 0x00)
            StripKind.ERROR   -> Color(0xC6, 0x28, 0x28)
            StripKind.NEUTRAL -> Color(0x55, 0x55, 0x55)
        }
    }

    private fun clearStatusStrip() {
        statusStrip.text = " "
        statusStrip.foreground = Color(0x55, 0x55, 0x55)
    }

    private fun summarizeGenerateOutcome(
        kind: String,
        generated: Int,
        skipped: Int,
        failed: Int
    ): String {
        val parts = mutableListOf<String>()
        if (generated > 0) parts.add("Generated $generated $kind(s)")
        if (skipped > 0) parts.add(
            "skipped $skipped (file already exists — Skip-if-exists policy active)"
        )
        if (failed > 0) parts.add("$failed failed — see notifications for detail")
        return if (parts.isEmpty()) "Nothing happened." else parts.joinToString("; ") + "."
    }

    private fun stripKindFor(generated: Int, skipped: Int, failed: Int): StripKind = when {
        failed > 0 -> StripKind.ERROR
        skipped > 0 && generated == 0 -> StripKind.WARN
        skipped > 0 -> StripKind.WARN
        generated > 0 -> StripKind.SUCCESS
        else -> StripKind.NEUTRAL
    }

    private fun refreshSummaryStatus() {
        val f = mostRecentSummary()
        val exists = f != null && Files.exists(f)
        summaryMtime = if (exists) mtimeOf(f!!) else ""
        val status: SummaryStatus = when {
            !exists -> {
                lastGeneratedSummarySelection = null
                SummaryStatus.NONE
            }
            lastGeneratedSummarySelection != null &&
                lastGeneratedSummarySelection != currentCheckedSet() ->
                SummaryStatus.STALE
            else -> SummaryStatus.GENERATED
        }
        applySummaryStatus(status)
    }

    private fun applySummaryStatus(status: SummaryStatus) {
        val timeSuffix = if (summaryMtime.isNotEmpty()) " · $summaryMtime" else ""
        when (status) {
            SummaryStatus.NONE -> {
                summaryStatusLabel.text = "—"
                summaryStatusLabel.foreground = Color(0x66, 0x66, 0x66)
            }
            SummaryStatus.GENERATED -> {
                summaryStatusLabel.text = "Generated$timeSuffix"
                summaryStatusLabel.foreground = Color(0x33, 0x77, 0x33)
            }
            SummaryStatus.STALE -> {
                summaryStatusLabel.text = "Stale$timeSuffix"
                summaryStatusLabel.foreground = Color(0xCC, 0x77, 0x00)
                summaryStatusLabel.toolTipText =
                    "The checked set has changed since the summary was generated.  " +
                        "Click Generate Summary to bring it up to date."
            }
            SummaryStatus.FAILED -> {
                summaryStatusLabel.text = "Failed"
                summaryStatusLabel.foreground = Color(0xC6, 0x28, 0x28)
            }
        }
        if (status != SummaryStatus.STALE) summaryStatusLabel.toolTipText = null
    }

    private fun refreshButtonEnablement() {
        val summaryExists = mostRecentSummary()?.let { Files.exists(it) } == true
        openSummaryButton.isEnabled = summaryExists
        deleteSummaryButton.isEnabled = summaryExists

        val anyChecked = selectedFlags.any { it }
        generatePerScenarioButton.isEnabled = anyChecked
        generateSummaryButton.isEnabled = anyChecked
    }

    // ── Action handlers ───────────────────────────────────────────────────

    private fun currentCheckedSet(): Set<String> {
        val out = HashSet<String>(selectedFlags.size)
        for (i in scenarioNames.indices) {
            if (selectedFlags[i]) out.add(scenarioNames[i])
        }
        return out
    }

    private fun currentFormat(): ReportFormat = when {
        markdownRadio.isSelected -> ReportFormat.MARKDOWN
        textRadio.isSelected -> ReportFormat.TEXT
        else -> ReportFormat.HTML
    }

    private fun currentPolicy(): ScenarioReports.FileHandlingPolicy = when {
        skipRadio.isSelected -> ScenarioReports.FileHandlingPolicy.SKIP_IF_EXISTS
        appendTimestampRadio.isSelected -> ScenarioReports.FileHandlingPolicy.APPEND_TIMESTAMP
        else -> ScenarioReports.FileHandlingPolicy.OVERWRITE
    }

    private fun setAllChecked(checked: Boolean) {
        for (i in selectedFlags.indices) selectedFlags[i] = checked
        tableModel.fireTableDataChanged()
        refreshSummaryStatus()
        refreshButtonEnablement()
    }

    private fun collectPicked(): List<String> {
        val out = ArrayList<String>(selectedFlags.size)
        for (i in scenarioNames.indices) {
            if (selectedFlags[i]) out.add(scenarioNames[i])
        }
        return out
    }

    private fun onGeneratePerScenarioClick() {
        val picked = collectPicked()
        if (picked.isEmpty()) return
        val batch = controller.lastResult.value as? RunResult.BatchCompleted ?: return
        val policy = currentPolicy()
        val format = currentFormat()
        var generated = 0
        var skipped = 0
        var failed = 0
        for (name in picked) {
            val outcome = try {
                ScenarioReports.renderScenarioSummary(
                    result = batch,
                    scenarioName = name,
                    outputDir = currentReportsDir,
                    formats = setOf(format),
                    openHtmlInBrowser = false,
                    existingFilePolicy = policy
                )
            } catch (t: Throwable) {
                onMessage(
                    "Report error: [$name] ${t.message ?: t::class.simpleName}",
                    NotificationSeverity.WARNING
                )
                ScenarioReports.WriteOutcome(emptyList(), listOf(t.message ?: ""), false)
            }
            if (!outcome.skipped) {
                outcome.errors.forEach {
                    onMessage("Report error: [$name] $it", NotificationSeverity.WARNING)
                }
            }
            val i = scenarioNames.indexOf(name)
            if (i >= 0) {
                when {
                    outcome.written.isNotEmpty() -> {
                        rowStatus[i] = RowStatus.GENERATED
                        rowMtime[i] = mtimeOf(outcome.written.first())
                        generated++
                    }
                    outcome.skipped -> skipped++
                    else -> {
                        rowStatus[i] = RowStatus.FAILED
                        failed++
                    }
                }
                tableModel.fireTableRowsUpdated(i, i)
            }
        }
        refreshButtonEnablement()
        applyStatusStrip(
            summarizeGenerateOutcome("per-scenario report", generated, skipped, failed),
            stripKindFor(generated, skipped, failed)
        )
    }

    private fun onGenerateSummaryClick() {
        val picked = collectPicked()
        if (picked.isEmpty()) return
        val batch = controller.lastResult.value as? RunResult.BatchCompleted ?: return
        val policy = currentPolicy()
        val format = currentFormat()
        val outcome = try {
            ScenarioReports.renderScenarioSummaries(
                result = batch,
                outputDir = currentReportsDir,
                formats = setOf(format),
                scenarioNames = picked.toSet(),
                openHtmlInBrowser = false,
                existingFilePolicy = policy
            )
        } catch (t: Throwable) {
            onMessage(
                "Report error: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.WARNING
            )
            ScenarioReports.WriteOutcome(emptyList(), listOf(t.message ?: ""), false)
        }
        if (!outcome.skipped) {
            outcome.errors.forEach { onMessage("Report error: $it", NotificationSeverity.WARNING) }
        }
        if (outcome.written.isNotEmpty()) {
            lastGeneratedSummarySelection = picked.toSet()
        }
        refreshSummaryStatus()
        refreshButtonEnablement()
        when {
            outcome.written.isNotEmpty() -> {
                val tail = if (summaryMtime.isNotEmpty()) " at $summaryMtime" else ""
                applyStatusStrip("Generated consolidated summary$tail.", StripKind.SUCCESS)
            }
            outcome.skipped -> applyStatusStrip(
                "Skipped consolidated summary — file already exists (Skip-if-exists policy active).",
                StripKind.WARN
            )
            else -> applyStatusStrip(
                "Could not generate consolidated summary — see notifications for detail.",
                StripKind.ERROR
            )
        }
    }

    private fun onOpenRow(modelRow: Int) {
        if (modelRow !in scenarioNames.indices) return
        val name = scenarioNames[modelRow]
        mostRecentPerScenarioFile(name)?.let { openFile(it) }
    }

    private fun onDeleteRow(modelRow: Int) {
        if (modelRow !in scenarioNames.indices) return
        val name = scenarioNames[modelRow]
        val target = mostRecentPerScenarioFile(name) ?: return
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Delete '${target.fileName}'?\n\nThis cannot be undone.\n" +
                "Older versions of this scenario's report (if any) are kept; " +
                "use Reveal… for bulk cleanup.",
            "Delete Report",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice != JOptionPane.YES_OPTION) return
        val deletedName = target.fileName.toString()
        deleteFileQuietly(target)
        val remaining = mostRecentPerScenarioFile(name)
        if (remaining != null && Files.exists(remaining)) {
            rowStatus[modelRow] = RowStatus.GENERATED
            rowMtime[modelRow] = mtimeOf(remaining)
        } else {
            rowStatus[modelRow] = RowStatus.NONE
            rowMtime[modelRow] = ""
        }
        tableModel.fireTableRowsUpdated(modelRow, modelRow)
        applyStatusStrip("Deleted '$deletedName'.", StripKind.SUCCESS)
    }

    private fun onDeleteSummaryClick() {
        val target = mostRecentSummary() ?: return
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Delete '${target.fileName}'?\n\nThis cannot be undone.\n" +
                "Older consolidated summaries (if any) are kept; " +
                "use Reveal… for bulk cleanup.",
            "Delete Summary",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        if (choice != JOptionPane.YES_OPTION) return
        val deletedName = target.fileName.toString()
        deleteFileQuietly(target)
        if (mostRecentSummary() == null) {
            lastGeneratedSummarySelection = null
        }
        refreshSummaryStatus()
        refreshButtonEnablement()
        applyStatusStrip("Deleted '$deletedName'.", StripKind.SUCCESS)
    }

    private fun onChangeReportsDirClick() {
        val chooser = JFileChooser(currentReportsDir.toFile()).apply {
            dialogTitle = "Pick a reports directory"
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isMultiSelectionEnabled = false
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val picked = chooser.selectedFile?.toPath()?.toAbsolutePath() ?: return
        val validationError = validateWritable(picked)
        if (validationError != null) {
            JOptionPane.showMessageDialog(
                this,
                "Can't use that directory:\n  $picked\n\n$validationError",
                "Directory Not Usable",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        currentReportsDir = picked
        reportsDirLabel.text = picked.toString()
        lastGeneratedSummarySelection = null
        refreshAll()
    }

    private fun validateWritable(dir: Path): String? = try {
        Files.createDirectories(dir)
        val probe = Files.createTempFile(dir, ".ksl-write-probe", ".tmp")
        Files.deleteIfExists(probe)
        null
    } catch (t: Throwable) {
        t.message ?: t::class.simpleName ?: "Not writable"
    }

    // ── File / desktop helpers ────────────────────────────────────────────

    private fun mostRecentPerScenarioFile(name: String): Path? =
        ScenarioReports.mostRecentPerScenarioFile(currentReportsDir, name)

    private fun mostRecentSummary(): Path? =
        ScenarioReports.mostRecentSummaryFile(currentReportsDir)

    private fun deleteFileQuietly(file: Path) {
        try {
            Files.deleteIfExists(file)
        } catch (t: Throwable) {
            onMessage(
                "Could not delete ${file.fileName}: ${t.message ?: t::class.simpleName}",
                NotificationSeverity.WARNING
            )
        }
    }

    private fun openFile(file: Path) {
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.toFile())
        } catch (_: Throwable) { /* errors surface via the notification overlay if relevant */ }
    }

    private fun revealDir(dir: Path) {
        try {
            if (!Desktop.isDesktopSupported()) return
            Files.createDirectories(dir)
            Desktop.getDesktop().open(dir.toFile())
        } catch (_: Throwable) { /* errors surface via the notification overlay if relevant */ }
    }

    // ── Table model + renderers ───────────────────────────────────────────

    private enum class RowStatus { NONE, GENERATED, FAILED }
    private enum class SummaryStatus { NONE, GENERATED, STALE, FAILED }
    private enum class StripKind { SUCCESS, WARN, ERROR, NEUTRAL }

    private inner class ScenarioSelectionTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = scenarioNames.size
        override fun getColumnCount(): Int = 5
        override fun getColumnName(c: Int): String = when (c) {
            COL_INCLUDE -> "Include?"
            COL_NAME -> "Scenario"
            COL_STATUS -> "Status"
            COL_OPEN -> "Open"
            COL_DELETE -> "Delete"
            else -> ""
        }
        override fun getColumnClass(c: Int): Class<*> = when (c) {
            COL_INCLUDE -> java.lang.Boolean::class.java
            else -> String::class.java
        }
        override fun isCellEditable(r: Int, c: Int): Boolean = when (c) {
            COL_INCLUDE -> true
            COL_OPEN, COL_DELETE -> rowStatus[r] == RowStatus.GENERATED
            else -> false
        }
        override fun getValueAt(r: Int, c: Int): Any = when (c) {
            COL_INCLUDE -> selectedFlags[r]
            COL_NAME -> scenarioNames[r]
            COL_STATUS -> when (rowStatus[r]) {
                RowStatus.NONE -> "—"
                RowStatus.GENERATED ->
                    if (rowMtime[r].isNotEmpty()) "Generated · ${rowMtime[r]}" else "Generated"
                RowStatus.FAILED -> "Failed"
            }
            COL_OPEN -> if (rowStatus[r] == RowStatus.GENERATED) "Open" else "—"
            COL_DELETE -> if (rowStatus[r] == RowStatus.GENERATED) "Delete" else "—"
            else -> ""
        }
        override fun setValueAt(value: Any?, r: Int, c: Int) {
            if (c != COL_INCLUDE) return
            val v = (value as? Boolean) ?: return
            selectedFlags[r] = v
            fireTableCellUpdated(r, c)
            refreshSummaryStatus()
            refreshButtonEnablement()
        }
    }

    private class OpenButtonCellRenderer : TableCellRenderer {
        private val button = JButton().apply {
            isFocusable = false
            isFocusPainted = false
        }
        private val placeholder = JLabel("—", SwingConstants.CENTER).apply {
            foreground = Color(0x66, 0x66, 0x66)
        }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean,
            hasFocus: Boolean, row: Int, column: Int
        ): Component {
            val text = value?.toString().orEmpty()
            return if (text != "—" && text.isNotEmpty()) {
                button.text = text
                button.background = UIManager.getColor("Button.background")
                button
            } else {
                placeholder.background = table.background
                placeholder
            }
        }
    }

    private class OpenButtonCellEditor(
        private val buttonLabel: String,
        private val onClick: (modelRow: Int) -> Unit
    ) : AbstractCellEditor(), TableCellEditor {
        private val button = JButton(buttonLabel).apply {
            isFocusable = false
            isFocusPainted = false
        }

        override fun getCellEditorValue(): Any = buttonLabel

        override fun getTableCellEditorComponent(
            table: JTable, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            val modelRow = table.convertRowIndexToModel(row)
            SwingUtilities.invokeLater {
                fireEditingStopped()
                onClick(modelRow)
            }
            return button
        }

        override fun isCellEditable(e: EventObject?): Boolean = true
    }

    companion object {
        private const val COL_INCLUDE = 0
        private const val COL_NAME = 1
        private const val COL_STATUS = 2
        private const val COL_OPEN = 3
        private const val COL_DELETE = 4

        private const val CARD_EMPTY = "empty"
        private const val CARD_POPULATED = "populated"
    }
}
