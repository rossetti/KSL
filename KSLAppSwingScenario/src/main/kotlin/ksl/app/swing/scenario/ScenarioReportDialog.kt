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

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
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
import javax.swing.JDialog
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
import javax.swing.WindowConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellEditor
import javax.swing.table.TableCellRenderer

/**
 *  Modeless dialog for managing on-demand scenario reports —
 *  per-scenario deep-dive files and a single consolidated summary.
 *
 *  Design A v3 surfaces five user-facing concerns over earlier
 *  iterations: per-row Status, per-row Open and Delete affordances,
 *  the absolute output directory (mutable via *Change…*), an
 *  explicit *Refresh* control, summary-staleness when the checked
 *  set drifts after generation, and a *File handling* combo
 *  governing how Generate treats existing files (Overwrite / Skip
 *  if exists / Append timestamp).
 *
 *  Filesystem is the source of truth.  The dialog re-reads Status
 *  on open, after each Generate / Delete, when the dialog regains
 *  focus, and on *Refresh* click — covering the external-deletion
 *  case without polling.
 */
object ScenarioReportDialog {

    /** Result of a single Generate invocation reported back from the
     *  panel.  [skipped] is `true` when the renderer deliberately
     *  produced no files because the *Skip if exists* policy was
     *  active and a matching file already existed; the dialog uses
     *  it to avoid flipping row status to FAILED for what is in fact
     *  a successful no-op. */
    data class GenerateResult(
        val written: List<Path>,
        val errors: List<String>,
        val skipped: Boolean = false
    )

    /** Result of a Delete invocation.  Errors surface via the panel's
     *  notification channel; the dialog uses [deleted] only to
     *  decide whether to refresh status. */
    data class DeleteResult(val deleted: List<Path>, val errors: List<String>)

    /**
     *  Show (or raise) the dialog over [parent].
     *
     *  Path arguments take the **current** reports directory at the
     *  time of invocation; the dialog passes its current path back
     *  to each callback so a user-supplied Change… mid-session is
     *  honoured.
     */
    fun showDialog(
        parent: Component,
        scenarioNames: List<String>,
        initialReportsDir: Path,
        perScenarioFile: (scenarioName: String, reportsDir: Path) -> Path?,
        summaryFile: (reportsDir: Path) -> Path?,
        onGenerateSummary: (
            pickedNames: List<String>,
            reportsDir: Path,
            policy: ScenarioReports.FileHandlingPolicy
        ) -> GenerateResult,
        onGeneratePerScenario: (
            scenarioName: String,
            reportsDir: Path,
            policy: ScenarioReports.FileHandlingPolicy
        ) -> GenerateResult,
        onDeletePerScenario: (scenarioName: String, reportsDir: Path) -> DeleteResult,
        onDeleteSummary: (reportsDir: Path) -> DeleteResult
    ): JDialog {
        val owner: Window? = (parent as? Window) ?: SwingUtilities.getWindowAncestor(parent)
        val dialog = PickerDialog(
            owner = owner,
            scenarioNames = scenarioNames,
            initialReportsDir = initialReportsDir,
            perScenarioFile = perScenarioFile,
            summaryFile = summaryFile,
            onGenerateSummary = onGenerateSummary,
            onGeneratePerScenario = onGeneratePerScenario,
            onDeletePerScenario = onDeletePerScenario,
            onDeleteSummary = onDeleteSummary
        )
        dialog.isVisible = true
        return dialog
    }

    /** Per-row status displayed in the Status column. */
    private enum class RowStatus { NONE, GENERATED, FAILED }

    /** Status of the consolidated summary section.  STALE is the
     *  v3 addition: the file exists but the checked set has drifted
     *  since the summary was generated. */
    private enum class SummaryStatus { NONE, GENERATED, STALE, FAILED }

    /** Status-strip flavor — drives the strip's foreground color. */
    private enum class StripKind { SUCCESS, WARN, ERROR, NEUTRAL }

    private class PickerDialog(
        owner: Window?,
        private val scenarioNames: List<String>,
        initialReportsDir: Path,
        private val perScenarioFile: (String, Path) -> Path?,
        private val summaryFile: (Path) -> Path?,
        private val onGenerateSummary: (List<String>, Path, ScenarioReports.FileHandlingPolicy) -> GenerateResult,
        private val onGeneratePerScenario: (String, Path, ScenarioReports.FileHandlingPolicy) -> GenerateResult,
        private val onDeletePerScenario: (String, Path) -> DeleteResult,
        private val onDeleteSummary: (Path) -> DeleteResult
    ) : JDialog(owner, "Scenario Reports", Dialog.ModalityType.MODELESS) {

        /** Authoritative checkbox state. */
        private val selectedFlags: BooleanArray = BooleanArray(scenarioNames.size) { true }

        /** Per-row status; refreshed on dialog open, focus, after
         *  Generate / Delete, and on Refresh. */
        private val rowStatus: Array<RowStatus> =
            Array(scenarioNames.size) { RowStatus.NONE }

        /** Per-row "last modified" text for the most recent file
         *  matching that scenario, formatted as HH:mm:ss in the
         *  system zone.  Empty when the row has no file.  Surfaces
         *  freshness even when Status stays at "Generated" across an
         *  Overwrite-policy Generate. */
        private val rowMtime: Array<String> =
            Array(scenarioNames.size) { "" }

        /** Last-modified text for the consolidated summary file, in
         *  the same format as [rowMtime].  Empty when no summary
         *  file exists. */
        private var summaryMtime: String = ""

        /** Formatter used by [rowMtime] / [summaryMtime] and by the
         *  status strip when it reports a timestamp. */
        private val clockFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

        /** Current reports directory. */
        private var currentReportsDir: Path = initialReportsDir.toAbsolutePath()

        /** Set of scenario names that were checked at the time of the
         *  last Generate Summary.  `null` means no summary has been
         *  generated this session (or the summary file is absent on
         *  disk).  Used to detect Stale status when the current
         *  checked set drifts. */
        private var lastGeneratedSummarySelection: Set<String>? = null

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

        // ── Per-scenario section buttons ────────────────────────────────────
        private val generatePerScenarioButton = JButton("Generate Selected").apply {
            toolTipText = "Write one full deep-dive document per checked scenario."
        }
        private val checkAllButton = JButton("Check All")
        private val uncheckAllButton = JButton("Uncheck All")

        // ── Summary section ─────────────────────────────────────────────────
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

        // ── Header buttons ──────────────────────────────────────────────────
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

        // ── File-handling radio group ──────────────────────────────────────
        private val overwriteRadio = JRadioButton("Overwrite", true).apply {
            toolTipText = "Write over any existing file with the same stem (default)."
        }
        private val skipRadio = JRadioButton("Skip if exists").apply {
            toolTipText = "Don't write if the target file already exists.  Status stays as it was."
        }
        private val appendTimestampRadio = JRadioButton("Append timestamp").apply {
            toolTipText = "Write to a new file with a _yyyy-MM-dd_HHmmss suffix.  Old files preserved."
        }

        private val closeButton = JButton("Close")

        /** In-dialog "what just happened" strip.  Populated by every
         *  user-initiated action (Generate, Delete); cleared on
         *  passive refresh (focus-gained, Refresh button click) so
         *  it only ever describes the user's last deliberate action. */
        private val statusStrip = JLabel(" ").apply {
            border = BorderFactory.createEmptyBorder(0, 12, 6, 12)
            foreground = Color(0x55, 0x55, 0x55)
        }

        init {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            minimumSize = Dimension(720, 480)
            preferredSize = Dimension(820, 600)
            contentPane.layout = BorderLayout()
            contentPane.add(buildHeader(), BorderLayout.NORTH)
            contentPane.add(buildBody(), BorderLayout.CENTER)
            contentPane.add(buildFooter(), BorderLayout.SOUTH)

            wireListeners()
            // Listen for window focus so an external file deletion
            // gets reflected when the user returns to the dialog.
            // Focus is a passive refresh — clear the status strip so
            // it only ever describes the user's last deliberate
            // action.
            addWindowFocusListener(object : java.awt.event.WindowFocusListener {
                override fun windowGainedFocus(e: WindowEvent) {
                    clearStatusStrip()
                    refreshAll()
                }
                override fun windowLostFocus(e: WindowEvent) { /* no-op */ }
            })

            refreshAll()
            pack()
            setLocationRelativeTo(owner)
        }

        // ── Layout builders ─────────────────────────────────────────────────

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
                // In-dialog status strip lives just above the footer
                // so it sits close to the Generate buttons whose
                // outcome it reports.
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
                add(closeButton)
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

        // ── Listener wiring ─────────────────────────────────────────────────

        private fun wireListeners() {
            closeButton.addActionListener { dispose() }
            revealReportsDirButton.addActionListener { revealDir(currentReportsDir) }
            changeReportsDirButton.addActionListener { onChangeReportsDirClick() }
            refreshButton.addActionListener {
                clearStatusStrip()                    // passive refresh — not a user "action"
                refreshAll()
            }
            generatePerScenarioButton.addActionListener { onGeneratePerScenarioClick() }
            generateSummaryButton.addActionListener { onGenerateSummaryClick() }
            openSummaryButton.addActionListener {
                summaryFile(currentReportsDir)?.let { openFile(it) }
            }
            deleteSummaryButton.addActionListener { onDeleteSummaryClick() }
            checkAllButton.addActionListener { setAllChecked(true) }
            uncheckAllButton.addActionListener { setAllChecked(false) }
        }

        // ── State refreshers ────────────────────────────────────────────────

        /** Re-read everything from disk.  Cheap; called on focus,
         *  Refresh, and after every state-changing action.  Does NOT
         *  touch [statusStrip] — callers that want to clear or set
         *  the strip do so explicitly. */
        private fun refreshAll() {
            for (i in scenarioNames.indices) {
                val path = perScenarioFile(scenarioNames[i], currentReportsDir)
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

        /** Format the file's mtime as HH:mm:ss in the system zone.
         *  Empty string on any I/O failure so the Status cell stays
         *  legible. */
        private fun mtimeOf(file: Path): String = try {
            clockFormatter.format(Files.getLastModifiedTime(file).toInstant())
        } catch (_: Throwable) {
            ""
        }

        /** Set the status strip's text + color.  Used by every
         *  user-initiated action; the focus listener and Refresh
         *  click call [clearStatusStrip] instead. */
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

        /** One-line outcome summary for a multi-row Generate.  Sentences
         *  are composed so the simple cases read naturally without
         *  appearing terse for edge cases. */
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

        /** Choose a strip flavor based on the action's outcome.  Any
         *  failure → ERROR; any skip without failures → WARN; otherwise
         *  SUCCESS (or NEUTRAL if literally nothing happened). */
        private fun stripKindFor(generated: Int, skipped: Int, failed: Int): StripKind = when {
            failed > 0 -> StripKind.ERROR
            skipped > 0 && generated == 0 -> StripKind.WARN
            skipped > 0 -> StripKind.WARN
            generated > 0 -> StripKind.SUCCESS
            else -> StripKind.NEUTRAL
        }

        /** Refresh the summary's status, applying the Stale check
         *  against [lastGeneratedSummarySelection].  Also updates
         *  [summaryMtime]. */
        private fun refreshSummaryStatus() {
            val f = summaryFile(currentReportsDir)
            val exists = f != null && Files.exists(f)
            summaryMtime = if (exists) mtimeOf(f!!) else ""
            val status: SummaryStatus = when {
                !exists -> {
                    // File gone — drop the staleness baseline; the
                    // next Generate will set it again.
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
            val summaryExists = summaryFile(currentReportsDir)?.let { Files.exists(it) } == true
            openSummaryButton.isEnabled = summaryExists
            deleteSummaryButton.isEnabled = summaryExists

            val anyChecked = selectedFlags.any { it }
            generatePerScenarioButton.isEnabled = anyChecked
            generateSummaryButton.isEnabled = anyChecked
        }

        // ── Action handlers ─────────────────────────────────────────────────

        private fun currentCheckedSet(): Set<String> {
            val out = HashSet<String>(selectedFlags.size)
            for (i in scenarioNames.indices) {
                if (selectedFlags[i]) out.add(scenarioNames[i])
            }
            return out
        }

        private fun currentPolicy(): ScenarioReports.FileHandlingPolicy = when {
            skipRadio.isSelected -> ScenarioReports.FileHandlingPolicy.SKIP_IF_EXISTS
            appendTimestampRadio.isSelected -> ScenarioReports.FileHandlingPolicy.APPEND_TIMESTAMP
            else -> ScenarioReports.FileHandlingPolicy.OVERWRITE
        }

        private fun setAllChecked(checked: Boolean) {
            for (i in selectedFlags.indices) selectedFlags[i] = checked
            tableModel.fireTableDataChanged()
            // Checked-set changed — refresh summary status so Stale
            // reflects the new state.
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
            val policy = currentPolicy()
            var generated = 0
            var skipped = 0
            var failed = 0
            for (name in picked) {
                val result = try {
                    onGeneratePerScenario(name, currentReportsDir, policy)
                } catch (t: Throwable) {
                    GenerateResult(emptyList(), listOf(t.message ?: t::class.simpleName.orEmpty()))
                }
                val i = scenarioNames.indexOf(name)
                if (i >= 0) {
                    when {
                        result.written.isNotEmpty() -> {
                            rowStatus[i] = RowStatus.GENERATED
                            rowMtime[i] = mtimeOf(result.written.first())
                            generated++
                        }
                        result.skipped -> {
                            skipped++
                            // Status unchanged.
                        }
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
            val policy = currentPolicy()
            val result = try {
                onGenerateSummary(picked, currentReportsDir, policy)
            } catch (t: Throwable) {
                GenerateResult(emptyList(), listOf(t.message ?: t::class.simpleName.orEmpty()))
            }
            // Update the staleness baseline only when a file was
            // actually written this click — a Skip should leave the
            // baseline untouched (the previous summary still reflects
            // its own selection).
            if (result.written.isNotEmpty()) {
                lastGeneratedSummarySelection = picked.toSet()
            }
            refreshSummaryStatus()
            refreshButtonEnablement()
            when {
                result.written.isNotEmpty() -> {
                    val tail = if (summaryMtime.isNotEmpty()) " at $summaryMtime" else ""
                    applyStatusStrip("Generated consolidated summary$tail.", StripKind.SUCCESS)
                }
                result.skipped -> applyStatusStrip(
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
            perScenarioFile(name, currentReportsDir)?.let { openFile(it) }
        }

        private fun onDeleteRow(modelRow: Int) {
            if (modelRow !in scenarioNames.indices) return
            val name = scenarioNames[modelRow]
            val target = ScenarioReports.mostRecentPerScenarioFile(currentReportsDir, name)
                ?: return
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
            onDeletePerScenario(name, currentReportsDir)
            // After deletion, refresh against disk — older versions
            // may still exist for this scenario, in which case Status
            // stays Generated.  Only when no matching files remain
            // does the row drop to NONE.
            val remaining = perScenarioFile(name, currentReportsDir)
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
            val target = ScenarioReports.mostRecentSummaryFile(currentReportsDir)
                ?: return
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
            onDeleteSummary(currentReportsDir)
            // Only invalidate the staleness baseline when no
            // consolidated summary files remain on disk; otherwise
            // the baseline still describes the (now-newest)
            // remaining file.
            if (summaryFile(currentReportsDir) == null) {
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
            // Path change resets the summary-staleness baseline —
            // the previous summary (if any) lived under the old path.
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

        // ── Desktop-shell helpers ───────────────────────────────────────────

        private fun openFile(file: Path) {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(file.toFile())
            } catch (_: Throwable) { /* errors surface via panel notifications */ }
        }

        private fun revealDir(dir: Path) {
            try {
                if (!Desktop.isDesktopSupported()) return
                Files.createDirectories(dir)
                Desktop.getDesktop().open(dir.toFile())
            } catch (_: Throwable) { /* errors surface via panel notifications */ }
        }

        // ── Table model ─────────────────────────────────────────────────────

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
                // Open and Delete are editable only when a file exists.
                // Gating editability turns the cell into a "—" placeholder
                // and prevents the editor from firing for a non-existent file.
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
    }

    private const val COL_INCLUDE = 0
    private const val COL_NAME = 1
    private const val COL_STATUS = 2
    private const val COL_OPEN = 3
    private const val COL_DELETE = 4

    // ── Open/Delete column renderer + editor ────────────────────────────────

    /** Paints a `JButton` carrying the cell's text when that text is
     *  non-placeholder (e.g. "Open", "Delete"); paints a centered
     *  `—` `JLabel` otherwise. */
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

    /** Cell editor that fires the configured click handler the
     *  moment the editor activates — gives the cell single-click
     *  semantics rather than JTable's default two-click. */
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
}
