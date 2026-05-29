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

package ksl.app.swing.experiment

import kotlinx.coroutines.launch
import ksl.app.config.ExecutionMode
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.ExperimentOutputSpec
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.StreamPolicy
import ksl.app.config.experiment.materializeDesign
import ksl.app.notification.NotificationSink
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel
import javax.swing.table.TableCellRenderer

/**
 *  *Simulate* tab — the single home for executing a configured
 *  experiment and watching the run unfold.  Replaces the toolbar
 *  Simulate / Cancel / Mode / Enable-database widgets and the
 *  status-rendering responsibilities the
 *  [DesignPointsPreviewDialog] briefly absorbed in earlier phases.
 *
 *  Layout (top → bottom):
 *
 *  1. Run controls row — Simulate / Cancel buttons.
 *  2. Run options box — Execution mode (Sequential / Concurrent) +
 *     Enable KSL database checkbox.
 *  3. Status line — "idle" or
 *     `"Running: 5 of 16 completed, 1 failed, 2 cancelled; current: point 8"`.
 *  4. Design points table — `#`, factor columns, `reps`, `Status`,
 *     per-row `Cancel` button.  Auto-populates from
 *     `materializeDesign()` when the tab is activated and there's
 *     no in-flight run.  Live-updated via
 *     `controller.designPointStatuses` during runs.
 *  5. CSV import / export row.
 *  6. Edited / Saved badge (shared widget).
 *
 *  Behaviour notes:
 *  - The Cancel column's per-row button is enabled only when the
 *    row's status is RUNNING; it delegates to
 *    `controller.cancelDesignPoint(pointId)`.
 *  - Whole-run Cancel cancels the whole submission (the same
 *    `controller.cancel()` the toolbar used to offer).
 *  - CSV import is enabled only for `DesignSpec.Manual` (the only
 *    family whose points are user-authored).
 *  - Re-materialization on Factors / Design / Replications change
 *    is debounced through the flow collectors so a quick burst of
 *    edits doesn't thrash the table.
 */
class SimulateTabPanel(
    private val controller: ExperimentAppController,
    private val notifier: NotificationSink,
    private val onSimulateRequested: () -> Unit
) : JPanel(BorderLayout(0, 6)) {

    // ── Run controls ──────────────────────────────────────────────

    private val simulateButton = JButton("▶ Simulate").apply { isEnabled = false }
    private val cancelButton = JButton("■ Cancel run").apply { isEnabled = false }
    private val resetButton = JButton("Reset").apply {
        isEnabled = false
        toolTipText = "Clear the per-design-point statuses from the previous run + " +
            "re-enumerate the design points.  Safe escape hatch if anything looks " +
            "stuck after a completed / cancelled run."
    }

    // ── Run options ───────────────────────────────────────────────

    private val sequentialRadio = JRadioButton(
        "Sequential",
        controller.executionMode.value == ExecutionMode.SEQUENTIAL
    )
    private val concurrentRadio = JRadioButton(
        "Concurrent",
        controller.executionMode.value == ExecutionMode.CONCURRENT
    )
    private val enableDbCheckbox = JCheckBox(
        "Enable KSL database",
        controller.outputConfig.value.enableKSLDatabase
    ).apply {
        toolTipText = "Capture each design point's results in the shared KSL SQLite database " +
            "(<workspace>/output/<analysisName>/).  Required for downstream Regression + " +
            "Comparison Analyzer / Reports to have anything to read."
    }
    private val perPointSubdirsCheckbox = JCheckBox(
        "Use per-design-point output folders",
        controller.experimentOutput.value.usePerPointSubdirs
    ).apply {
        toolTipText = "<html>When checked, each design point gets its own " +
            "&lt;name&gt;_DP_&lt;n&gt;_OutputDir under the analysis output directory " +
            "(each containing kslOutput.txt and any per-point CSV / plot artifacts).<br>" +
            "When unchecked (default), all per-point models share the analysis output " +
            "directory; per-point logs use the filename kslOutput_DP_&lt;n&gt;.txt.<br>" +
            "Turn on for per-point CSV / configuration workflows (Phase E11).</html>"
    }

    // Random streams (moved from Design tab in E7.10 — stream policy
    // is a runtime variance-reduction technique, not a design spec).
    private val indepRadio = javax.swing.JRadioButton("Independent (default)", true)
    private val crnRadio = javax.swing.JRadioButton("Common Random Numbers (CRN)")
    private val streamGroup = javax.swing.ButtonGroup().apply { add(indepRadio); add(crnRadio) }
    private val advancedStreamsToggle = JCheckBox("Advanced...")
    private val startingAdvanceField = javax.swing.JTextField("0", 6)
    private val spacingField = javax.swing.JTextField("", 6)  // blank = null = cumulative
    private val crnHelpLabel = JLabel(
        "<html><body width='600'><i>CRN reuses the same random-stream block at every " +
            "design point &mdash; reduces variance for cross-point comparisons but biases " +
            "per-point standard errors.  Independent (default) gives each point a fresh " +
            "non-overlapping block.</i></body></html>"
    ).apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        foreground = Color(0x55, 0x55, 0x55)
        isVisible = false   // shown only when CRN is the active selection
    }
    private val advancedStreamsRow: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        add(JLabel("startingStreamAdvance:"))
        add(startingAdvanceField)
        add(JLabel("  streamAdvanceSpacing (blank = cumulative):"))
        add(spacingField)
        isVisible = false
    }

    // ── Status ────────────────────────────────────────────────────

    private val statusLabel = JLabel("Status: idle").apply {
        border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
    }

    // ── Design points table ───────────────────────────────────────

    /** The points captured at last materialisation.  Distinct from
     *  the controller's status map because the table needs to
     *  render rows even when no run has happened yet (statuses =
     *  PENDING for everyone).  Declared BEFORE tableModel so the
     *  JTable's construction-time getColumnCount() call has a
     *  populated factorNames. */
    private var enumeratedPoints: List<EnumeratedPoint> = emptyList()
    private var factorNames: List<String> = emptyList()

    private val tableModel = DesignPointsTableModel()
    private val table = JTable(tableModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        fillsViewportHeight = true
        rowHeight = 24
        autoCreateRowSorter = false
    }

    // ── Footer ────────────────────────────────────────────────────

    // CSV export + import buttons removed in E7.11 — the Simulate
    // tab is an execution surface, not an editor.  Export lives on
    // the Materialize preview dialog (Design tab); Import lives on
    // the Custom design points sub-tab (Design tab).
    private val docStateLabel = DocumentStateLabel(controller.isDirty, controller.edtScope)

    // ── Re-entrancy guard ─────────────────────────────────────────

    @Volatile private var suppressEvents: Boolean = false

    init {
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        add(buildTopStack(), BorderLayout.NORTH)
        add(JScrollPane(table).apply { preferredSize = Dimension(700, 280) }, BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)

        wireRunControls()
        wireRunOptions()
        // CSV button wiring removed in E7.11 — no CSV buttons on
        // this tab anymore.
        setUpCellRenderersAndEditors()

        observeControllerFlows()
    }

    // ───────────────────────────────────────────────────────────────
    // Layout builders
    // ───────────────────────────────────────────────────────────────

    private fun buildTopStack(): JComponent {
        val stack = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        buttonRow.add(simulateButton)
        buttonRow.add(cancelButton)
        buttonRow.add(resetButton)
        stack.add(buttonRow)

        val optionsPanel = JPanel()
        optionsPanel.layout = BoxLayout(optionsPanel, BoxLayout.Y_AXIS)
        optionsPanel.border = BorderFactory.createTitledBorder("Run options")
        val modeRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        modeRow.add(JLabel("Execution mode:"))
        modeRow.add(sequentialRadio)
        modeRow.add(concurrentRadio)
        ButtonGroup().apply { add(sequentialRadio); add(concurrentRadio) }
        optionsPanel.add(modeRow)
        val streamsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        streamsRow.add(JLabel("Random streams:"))
        streamsRow.add(indepRadio)
        streamsRow.add(crnRadio)
        streamsRow.add(advancedStreamsToggle)
        optionsPanel.add(streamsRow)
        optionsPanel.add(crnHelpLabel)
        optionsPanel.add(advancedStreamsRow)

        val outputRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        outputRow.add(JLabel("Output:"))
        outputRow.add(enableDbCheckbox)
        optionsPanel.add(outputRow)
        val outputRow2 = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        outputRow2.add(JLabel(" "))   // alignment placeholder
        outputRow2.add(perPointSubdirsCheckbox)
        optionsPanel.add(outputRow2)
        stack.add(optionsPanel)
        stack.add(statusLabel)
        return stack
    }

    private fun buildFooter(): JComponent {
        val footer = JPanel(BorderLayout())
        footer.add(docStateLabel, BorderLayout.EAST)
        return footer
    }

    // ───────────────────────────────────────────────────────────────
    // Listener wiring
    // ───────────────────────────────────────────────────────────────

    private fun wireRunControls() {
        simulateButton.addActionListener {
            if (!controller.runningFlow.value) onSimulateRequested()
        }
        cancelButton.addActionListener { controller.cancel() }
        resetButton.addActionListener {
            // Clear the controller's per-point status map back to all
            // PENDING, then re-materialise the table.  Acts as a
            // "start fresh" affordance after a completed / failed /
            // cancelled run.
            if (controller.runningFlow.value) return@addActionListener
            controller.resetDesignPointStatuses()
            rematerialise()
        }
    }

    private fun wireRunOptions() {
        sequentialRadio.addActionListener {
            if (!suppressEvents && sequentialRadio.isSelected) {
                controller.setExecutionMode(ExecutionMode.SEQUENTIAL)
            }
        }
        concurrentRadio.addActionListener {
            if (!suppressEvents && concurrentRadio.isSelected) {
                controller.setExecutionMode(ExecutionMode.CONCURRENT)
            }
        }
        enableDbCheckbox.addActionListener {
            if (!suppressEvents) controller.setEnableKSLDatabase(enableDbCheckbox.isSelected)
        }
        perPointSubdirsCheckbox.addActionListener {
            if (!suppressEvents) {
                controller.setExperimentOutput(
                    ExperimentOutputSpec(usePerPointSubdirs = perPointSubdirsCheckbox.isSelected)
                )
            }
        }
        wireStreamPolicy()
    }

    private fun wireStreamPolicy() {
        val push = {
            if (!suppressEvents) {
                val next: StreamPolicy = if (crnRadio.isSelected) {
                    StreamPolicy.CommonRandomNumbers
                } else {
                    parseAdvancedOrCurrent()
                }
                controller.setStreamPolicy(next)
                advancedStreamsToggle.isEnabled = indepRadio.isSelected
                if (!indepRadio.isSelected) advancedStreamsRow.isVisible = false
                crnHelpLabel.isVisible = crnRadio.isSelected
            }
        }
        indepRadio.addActionListener { push() }
        crnRadio.addActionListener { push() }
        advancedStreamsToggle.addActionListener {
            advancedStreamsRow.isVisible = advancedStreamsToggle.isSelected && indepRadio.isSelected
            revalidate()
            repaint()
        }
        startingAdvanceField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (!suppressEvents) commitStreamAdvanced()
            }
        })
        startingAdvanceField.addActionListener { if (!suppressEvents) commitStreamAdvanced() }
        spacingField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (!suppressEvents) commitStreamAdvanced()
            }
        })
        spacingField.addActionListener { if (!suppressEvents) commitStreamAdvanced() }
    }

    private fun parseAdvancedOrCurrent(): StreamPolicy.Independent {
        val starting = startingAdvanceField.text.trim().toIntOrNull()?.coerceAtLeast(0) ?: 0
        val spacing = spacingField.text.trim().takeIf { it.isNotEmpty() }
            ?.toIntOrNull()?.coerceAtLeast(1)
        return StreamPolicy.Independent(
            startingStreamAdvance = starting,
            streamAdvanceSpacing = spacing
        )
    }

    private fun commitStreamAdvanced() {
        if (!indepRadio.isSelected) return
        controller.setStreamPolicy(parseAdvancedOrCurrent())
    }

    // wireCsvButtons removed in E7.11.

    private fun setUpCellRenderersAndEditors() {
        // Status column renderer: color-coded chip.  Re-applied
        // whenever the table column model is reset (factor count
        // changes → fireTableStructureChanged → column model rebuilt).
        table.tableHeader.reorderingAllowed = false
        table.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val row = table.rowAtPoint(e.point)
                val col = table.columnAtPoint(e.point)
                if (row < 0 || col < 0) return
                if (col != tableModel.cancelColumn) return
                // E7.11 #8 — per-point cancellation is not available
                // in Sequential mode.  The renderer shows an em-dash
                // there; click is a no-op.
                if (controller.executionMode.value == ksl.app.config.ExecutionMode.SEQUENTIAL) {
                    return
                }
                val point = enumeratedPoints.getOrNull(row) ?: return
                val status = controller.designPointStatuses.value[point.pointId]
                if (status == ExperimentAppController.DesignPointStatus.RUNNING) {
                    val ok = controller.cancelDesignPoint(point.pointId)
                    if (!ok) {
                        notifier.warn(
                            "Could not cancel design point ${point.pointId} " +
                                "(already completed, or no run in progress)."
                        )
                    }
                }
            }
        })
    }

    private fun applyColumnRenderers() {
        // Called after every fireTableStructureChanged because the
        // column model is rebuilt.  Renderers must be re-attached.
        val statusCol = table.columnModel.getColumn(tableModel.statusColumn)
        statusCol.cellRenderer = StatusCellRenderer()
        statusCol.preferredWidth = 110

        val cancelCol = table.columnModel.getColumn(tableModel.cancelColumn)
        cancelCol.cellRenderer = CancelButtonCellRenderer()
        cancelCol.preferredWidth = 100
    }

    // ───────────────────────────────────────────────────────────────
    // Controller observation
    // ───────────────────────────────────────────────────────────────

    private fun observeControllerFlows() {
        // Re-materialise whenever factors, design, or replications
        // change.  Cheap because materializeDesign() just iterates
        // the design — no model build.
        controller.edtScope.launch {
            controller.factors.collect { rematerialise() }
        }
        controller.edtScope.launch {
            controller.designSpec.collect { rematerialise() }
        }
        controller.edtScope.launch {
            controller.replications.collect { rematerialise() }
        }
        controller.edtScope.launch {
            controller.runParameterOverrides.collect { rematerialise() }
        }
        controller.edtScope.launch {
            controller.executionMode.collect { mode ->
                suppressEvents = true
                try {
                    sequentialRadio.isSelected = mode == ExecutionMode.SEQUENTIAL
                    concurrentRadio.isSelected = mode == ExecutionMode.CONCURRENT
                } finally { suppressEvents = false }
                // Status label format + Cancel cell renderer both
                // depend on execution mode — refresh on change.
                refreshStatusLabel()
                table.repaint()
            }
        }
        controller.edtScope.launch {
            controller.outputConfig.collect { cfg ->
                suppressEvents = true
                try {
                    if (enableDbCheckbox.isSelected != cfg.enableKSLDatabase) {
                        enableDbCheckbox.isSelected = cfg.enableKSLDatabase
                    }
                } finally { suppressEvents = false }
            }
        }
        controller.edtScope.launch {
            controller.experimentOutput.collect { spec ->
                suppressEvents = true
                try {
                    if (perPointSubdirsCheckbox.isSelected != spec.usePerPointSubdirs) {
                        perPointSubdirsCheckbox.isSelected = spec.usePerPointSubdirs
                    }
                } finally { suppressEvents = false }
            }
        }
        controller.edtScope.launch {
            controller.streamPolicy.collect { policy ->
                suppressEvents = true
                try {
                    when (policy) {
                        is StreamPolicy.Independent -> {
                            indepRadio.isSelected = true
                            startingAdvanceField.text = policy.startingStreamAdvance.toString()
                            spacingField.text = policy.streamAdvanceSpacing?.toString() ?: ""
                            advancedStreamsToggle.isEnabled = true
                            crnHelpLabel.isVisible = false
                        }
                        is StreamPolicy.CommonRandomNumbers -> {
                            crnRadio.isSelected = true
                            advancedStreamsToggle.isEnabled = false
                            advancedStreamsRow.isVisible = false
                            crnHelpLabel.isVisible = true
                        }
                    }
                } finally { suppressEvents = false }
            }
        }
        controller.edtScope.launch {
            // Track running-state edges so we can rematerialise the
            // table when a run ends.  Without this, the table keeps
            // showing the prior run's enumerated points and a user
            // who changed factors / design between runs would see
            // stale rows until they manually touched something.
            var wasRunning = controller.runningFlow.value
            controller.runningFlow.collect { running ->
                refreshButtonEnablement(running)
                if (wasRunning && !running) {
                    // Run just ended.  Re-enumerate so the table
                    // matches the document's current design (in case
                    // the user edited something during the run).
                    rematerialise()
                }
                wasRunning = running
            }
        }
        controller.edtScope.launch {
            controller.modelReference.collect { refreshButtonEnablement(controller.runningFlow.value) }
        }
        controller.edtScope.launch {
            // E7.11 #1 — factors changes ALSO need to re-evaluate
            // the Simulate-button gate.  Without this, loading a TOML
            // (which sets modelReference then factors) could leave
            // the button stuck disabled because the modelReference
            // collector fired before factors was populated and no
            // later collector re-evaluated the gate.
            controller.factors.collect { refreshButtonEnablement(controller.runningFlow.value) }
        }
        controller.edtScope.launch {
            controller.designPointStatuses.collect {
                // Re-render Status + Cancel columns; refresh status line.
                if (enumeratedPoints.isNotEmpty()) {
                    val statusCol = tableModel.statusColumn
                    val cancelCol = tableModel.cancelColumn
                    for (row in enumeratedPoints.indices) {
                        tableModel.fireTableCellUpdated(row, statusCol)
                        tableModel.fireTableCellUpdated(row, cancelCol)
                    }
                }
                refreshStatusLabel()
                // Reset enablement depends on whether any non-
                // PENDING statuses are present — refresh it on
                // every status flow change.
                refreshButtonEnablement(controller.runningFlow.value)
            }
        }
    }

    private fun refreshButtonEnablement(running: Boolean) {
        val hasModel = controller.modelReference.value != null
        val hasFactors = controller.factors.value.isNotEmpty()
        simulateButton.isEnabled = !running && hasModel && hasFactors
        cancelButton.isEnabled = running
        // Reset enabled only when not running AND at least one
        // status row is non-PENDING (something to clear).
        resetButton.isEnabled = !running && controller.designPointStatuses.value.values.any {
            it != ExperimentAppController.DesignPointStatus.PENDING
        }
        sequentialRadio.isEnabled = !running
        concurrentRadio.isEnabled = !running
        enableDbCheckbox.isEnabled = !running
        perPointSubdirsCheckbox.isEnabled = !running
        indepRadio.isEnabled = !running
        crnRadio.isEnabled = !running
        advancedStreamsToggle.isEnabled = !running && indepRadio.isSelected
        startingAdvanceField.isEnabled = !running
        spacingField.isEnabled = !running
    }

    /**
     *  E7.11 #6 — mode-differentiated status format.  Concurrent
     *  shows a running count (many points active simultaneously);
     *  sequential shows the single currently-running point + a
     *  pending count (more meaningful when one point runs at a
     *  time and others wait in a queue).
     */
    private fun refreshStatusLabel() {
        val statuses = controller.designPointStatuses.value
        val running = controller.runningFlow.value
        val total = enumeratedPoints.size.coerceAtLeast(statuses.size)

        if (!running && statuses.isEmpty()) {
            statusLabel.text = if (total > 0)
                "Status: idle ($total design points configured)"
            else
                "Status: idle"
            return
        }

        var completed = 0
        var failed = 0
        var cancelled = 0
        var cancelling = 0
        var pending = 0
        var runningCount = 0
        var currentRunning: Int? = null
        for ((id, s) in statuses) {
            when (s) {
                ExperimentAppController.DesignPointStatus.COMPLETED  -> completed++
                ExperimentAppController.DesignPointStatus.FAILED     -> failed++
                ExperimentAppController.DesignPointStatus.CANCELLED  -> cancelled++
                ExperimentAppController.DesignPointStatus.CANCELLING -> cancelling++
                ExperimentAppController.DesignPointStatus.RUNNING -> {
                    runningCount++
                    currentRunning = id
                }
                ExperimentAppController.DesignPointStatus.PENDING    -> pending++
            }
        }

        // Build the parts list.  Drop zero-count entries to keep the
        // line short.
        val parts = mutableListOf<String>()
        parts += "$completed completed"
        if (failed > 0) parts += "$failed failed"
        if (cancelled > 0) parts += "$cancelled cancelled"
        if (cancelling > 0) parts += "$cancelling cancelling"

        val isSequential = controller.executionMode.value == ksl.app.config.ExecutionMode.SEQUENTIAL

        if (running) {
            val prefix = if (isSequential)
                "Simulating $total design points (sequential)"
            else
                "Simulating $total design points"
            val tail = if (isSequential) {
                val cur = currentRunning?.let { "current: point $it of $total" } ?: ""
                val pend = if (pending > 0) "$pending pending" else ""
                listOf(cur, pend).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                if (runningCount > 0) "$runningCount running" else ""
            }
            val parted = parts.joinToString(" · ")
            statusLabel.text = if (tail.isEmpty()) "$prefix: $parted"
            else "$prefix: $parted · $tail"
        } else {
            // Run ended — show the summary.
            statusLabel.text = "Last run: ${parts.joinToString(" · ")}"
        }
    }

    /** Re-build [enumeratedPoints] + the table model from the current
     *  document state.  Safe to call from the EDT.  Skips
     *  materialization when a run is in flight (the table reflects
     *  the running design; structural change would be confusing). */
    private fun rematerialise() {
        if (controller.runningFlow.value) return
        val factors = controller.factors.value
        factorNames = factors.map { it.name }
        if (factors.isEmpty() || controller.modelReference.value == null) {
            enumeratedPoints = emptyList()
            tableModel.fireTableStructureChanged()
            refreshStatusLabel()
            return
        }
        try {
            val cfg = controller.currentConfiguration()
            val design = cfg.materializeDesign()
            enumeratedPoints = design.designIterator().asSequence().mapIndexed { i, dp ->
                val rawByName = LinkedHashMap<String, Double>()
                val rawArr = dp.values()
                val designFactorNames = dp.design.factorNames
                for ((idx, name) in designFactorNames.withIndex()) {
                    rawByName[name] = rawArr.getOrNull(idx) ?: 0.0
                }
                EnumeratedPoint(
                    pointId = dp.number,
                    rowIndex = i,
                    rawValues = rawByName,
                    reps = dp.numReplications
                )
            }.toList()
            tableModel.fireTableStructureChanged()
            applyColumnRenderers()
            refreshStatusLabel()
        } catch (ex: Exception) {
            enumeratedPoints = emptyList()
            tableModel.fireTableStructureChanged()
            // Don't onMessage on every keystroke; the user's
            // in-flight edits frequently fail materializeDesign
            // until the document is internally consistent.
        }
    }

    // updateImportButtonEnablement removed in E7.11.

    // ───────────────────────────────────────────────────────────────
    // Table model + renderers
    // ───────────────────────────────────────────────────────────────

    private data class EnumeratedPoint(
        val pointId: Int,
        val rowIndex: Int,
        val rawValues: Map<String, Double>,
        val reps: Int
    )

    private inner class DesignPointsTableModel : AbstractTableModel() {
        val repsColumn: Int get() = 1 + factorNames.size
        val statusColumn: Int get() = repsColumn + 1
        val cancelColumn: Int get() = statusColumn + 1
        override fun getRowCount(): Int = enumeratedPoints.size
        override fun getColumnCount(): Int = 4 + factorNames.size
        override fun getColumnName(column: Int): String = when (column) {
            0 -> "#"
            repsColumn -> "reps"
            statusColumn -> "Status"
            cancelColumn -> "Action"
            else -> factorNames[column - 1]
        }
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0, repsColumn -> java.lang.Integer::class.java
            statusColumn -> ExperimentAppController.DesignPointStatus::class.java
            cancelColumn -> String::class.java
            else -> java.lang.Double::class.java
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = false
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val p = enumeratedPoints[rowIndex]
            return when (columnIndex) {
                0 -> p.pointId
                repsColumn -> p.reps
                statusColumn -> controller.designPointStatuses.value[p.pointId]
                    ?: ExperimentAppController.DesignPointStatus.PENDING
                cancelColumn -> "Cancel"
                else -> p.rawValues[factorNames[columnIndex - 1]] ?: 0.0
            }
        }
    }

    private class StatusCellRenderer : javax.swing.table.DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): java.awt.Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val status = value as? ExperimentAppController.DesignPointStatus
            text = when (status) {
                ExperimentAppController.DesignPointStatus.CANCELLING -> "Cancelling…"
                null -> ""
                else -> status.name.lowercase().replaceFirstChar { it.uppercase() }
            }
            background = when (status) {
                ExperimentAppController.DesignPointStatus.RUNNING    -> Color(0xCF, 0xE0, 0xF6)
                ExperimentAppController.DesignPointStatus.COMPLETED  -> Color(0xD4, 0xED, 0xD0)
                ExperimentAppController.DesignPointStatus.FAILED     -> Color(0xF7, 0xCF, 0xCC)
                ExperimentAppController.DesignPointStatus.CANCELLED  -> Color(0xFB, 0xE3, 0xC3)
                ExperimentAppController.DesignPointStatus.CANCELLING -> Color(0xFD, 0xEE, 0xC9) // paler orange
                ExperimentAppController.DesignPointStatus.PENDING, null -> Color(0xEF, 0xEF, 0xEF)
            }
            foreground = Color(0x22, 0x22, 0x22)
            // Italic for the transient CANCELLING state.
            font = if (status == ExperimentAppController.DesignPointStatus.CANCELLING)
                font.deriveFont(java.awt.Font.ITALIC)
            else font.deriveFont(java.awt.Font.PLAIN)
            isOpaque = true
            return this
        }
    }

    /** Cell renderer for the per-row Action column.
     *  E7.11 #7 — disabled also when status is CANCELLING (the
     *  cancellation request is already in flight; clicking again
     *  would no-op).
     *  E7.11 #8 — in Sequential mode, renders an em-dash with a
     *  tooltip instead of a button, since per-point cancellation
     *  isn't supported by the sequential substrate.
     */
    private inner class CancelButtonCellRenderer : TableCellRenderer {
        private val btn = JButton("Cancel").apply { isFocusPainted = false }
        private val dashLabel = JLabel("—", javax.swing.SwingConstants.CENTER).apply {
            foreground = Color(0x99, 0x99, 0x99)
            toolTipText = "Per-design-point cancellation requires Concurrent mode.  " +
                "Use the whole-run Cancel button above to stop the entire run."
        }
        override fun getTableCellRendererComponent(
            table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
            row: Int, column: Int
        ): java.awt.Component {
            if (controller.executionMode.value == ksl.app.config.ExecutionMode.SEQUENTIAL) {
                return dashLabel
            }
            val point = enumeratedPoints.getOrNull(row)
            val status = point?.let { controller.designPointStatuses.value[it.pointId] }
                ?: ExperimentAppController.DesignPointStatus.PENDING
            btn.isEnabled = status == ExperimentAppController.DesignPointStatus.RUNNING
            return btn
        }
    }
}
