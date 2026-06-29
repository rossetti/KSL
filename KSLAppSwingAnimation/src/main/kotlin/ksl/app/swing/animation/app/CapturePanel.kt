/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.animation.app

import kotlinx.coroutines.launch
import ksl.animation.CaptureMode
import ksl.animation.ElementKind
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel

/**
 * The **Capture** tab: authors *what* and *when* the next run records into its `.atf` trace, by mutating
 * the controller's capture spec. Mirrors the Layout editor (V1): per-kind **tables** with a **Capture**
 * column whose value is *Default* / *Include* / *Exclude* (so inclusion/exclusion is unambiguous), with
 * multi-select bulk actions and select/clear-all. Responses are split into separate **Responses** (tally)
 * and **Time-Weighted Responses** tabs. Plus the mode toggle, capture-window fields, and a validation strip.
 * Headless-constructible; every control routes through the controller's capture mutators.
 */
class CapturePanel(private val controller: AnimationAppController) : JPanel(BorderLayout()) {

    private val editors = mutableListOf<CaptureKindEditor>()
    private val validationLabel = JLabel()

    private val windowEnabled = JCheckBox("Limit capture to a time window")
    private val windowStart = JTextField(8)
    private val windowEnd = JTextField(8)

    private val allButton = JRadioButton("Capture all elements")
    private val selectedButton = JRadioButton("Capture only selected elements")

    init {
        add(buildModeRow(), BorderLayout.NORTH)
        add(buildSelectorTabs(), BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)
        syncFromController()
        recomputeValidation()
        controller.edtScope.launch {
            controller.captureSpec.collect { syncFromController(); recomputeValidation() }
        }
    }

    // ── Mode ────────────────────────────────────────────────────────────────

    private fun buildModeRow(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        border = BorderFactory.createTitledBorder("What to capture")
        ButtonGroup().apply { add(allButton); add(selectedButton) }
        allButton.toolTipText = "Capture every element except those set to Exclude"
        selectedButton.toolTipText = "Capture only elements set to Include"
        allButton.addActionListener { controller.setCaptureMode(CaptureMode.ALL); afterEdit() }
        selectedButton.addActionListener { controller.setCaptureMode(CaptureMode.SELECTED); afterEdit() }
        add(allButton)
        add(selectedButton)
    }

    // ── Per-kind tables (Responses split into tally vs time-weighted) ─────────────

    private fun buildSelectorTabs(): JComponent {
        val tabs = JTabbedPane()
        for (kind in ElementKind.entries) {
            val names = controller.inventory.namesOf(kind)
            if (names.isEmpty()) continue
            if (kind == ElementKind.RESPONSE) {
                val tally = names.filterNot { controller.inventory.isTimeWeighted(it) }
                val tw = names.filter { controller.inventory.isTimeWeighted(it) }
                if (tally.isNotEmpty()) addEditorTab(tabs, "Responses", kind, tally)
                if (tw.isNotEmpty()) addEditorTab(tabs, "Time-Weighted Responses", kind, tw)
            } else {
                addEditorTab(tabs, kind.label(), kind, names)
            }
        }
        if (editors.isEmpty()) {
            return JPanel(BorderLayout()).apply {
                add(JLabel("This model exposes no animatable elements to select.", JLabel.CENTER), BorderLayout.CENTER)
            }
        }
        return tabs
    }

    private fun addEditorTab(tabs: JTabbedPane, title: String, kind: ElementKind, names: List<String>) {
        val editor = CaptureKindEditor(kind, names)
        editors += editor
        tabs.addTab("$title (${names.size})", editor)
    }

    /** One table of inventory [names] for [kind], each with a Default/Include/Exclude capture state. */
    private inner class CaptureKindEditor(val kind: ElementKind, val names: List<String>) : JPanel(BorderLayout()) {
        val model = StateTableModel()
        val table = JTable(model).apply {
            selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
            columnModel.getColumn(1).cellEditor = DefaultCellEditor(JComboBox(STATES))
            columnModel.getColumn(1).maxWidth = 110
        }

        init {
            add(JScrollPane(table), BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                add(JButton("Include selected").apply { addActionListener { setSelected(INCLUDE) } })
                add(JButton("Exclude selected").apply { addActionListener { setSelected(EXCLUDE) } })
                add(JButton("Default selected").apply { addActionListener { setSelected(DEFAULT) } })
                add(JButton("Clear all").apply {
                    toolTipText = "Set every element here back to Default"
                    addActionListener { names.forEach { applyState(it, DEFAULT) }; afterEdit() }
                })
            }, BorderLayout.SOUTH)
        }

        private fun setSelected(state: String) {
            table.selectedRows.filter { it in names.indices }.forEach { applyState(names[it], state) }
            afterEdit()
        }

        private fun applyState(name: String, state: String) = when (state) {
            INCLUDE -> { controller.addInclude(kind, name); controller.removeExclude(kind, name) }
            EXCLUDE -> { controller.addExclude(kind, name); controller.removeInclude(kind, name) }
            else -> { controller.removeInclude(kind, name); controller.removeExclude(kind, name) }
        }

        fun stateOf(name: String): String {
            val spec = controller.captureSpec.value
            return when {
                spec.exclude.any { it.kind == kind && it.name == name } -> EXCLUDE
                spec.include.any { it.kind == kind && it.name == name } -> INCLUDE
                else -> DEFAULT
            }
        }

        fun refresh() { if (names.isNotEmpty()) model.fireTableRowsUpdated(0, names.size - 1) }

        inner class StateTableModel : javax.swing.table.AbstractTableModel() {
            private val cols = arrayOf("Name", "Capture")
            override fun getRowCount() = names.size
            override fun getColumnCount() = 2
            override fun getColumnName(c: Int) = cols[c]
            override fun isCellEditable(r: Int, c: Int) = c == 1
            override fun getValueAt(r: Int, c: Int): Any = if (c == 0) names[r] else stateOf(names[r])
            override fun setValueAt(value: Any?, r: Int, c: Int) {
                if (c == 1) { applyState(names[r], value as? String ?: DEFAULT); afterEdit() }
            }
        }
    }

    // ── Capture window + validation strip ───────────────────────────────────────

    private fun buildSouth(): JComponent = JPanel(BorderLayout()).apply {
        add(buildWindowRow(), BorderLayout.NORTH)
        validationLabel.border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        add(validationLabel, BorderLayout.SOUTH)
    }

    private fun buildWindowRow(): JComponent = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
        border = BorderFactory.createTitledBorder("When to capture")
        windowEnabled.addActionListener { onWindowChanged() }
        add(windowEnabled)
        add(JLabel("start")); add(windowStart)
        add(JLabel("end")); add(windowEnd)
        add(JButton("Apply window").apply { addActionListener { onWindowChanged() } })
    }

    private fun onWindowChanged() {
        val on = windowEnabled.isSelected
        windowStart.isEnabled = on
        windowEnd.isEnabled = on
        if (!on) controller.clearCaptureWindow()
        else {
            val start = windowStart.text.trim().toDoubleOrNull()
            val end = windowEnd.text.trim().toDoubleOrNull()
            if (start != null && end != null && start >= 0.0 && end >= start) controller.setCaptureWindow(start, end)
        }
        afterEdit()
    }

    // ── Sync / validation ──────────────────────────────────────────────────────

    private fun syncFromController() {
        val spec = controller.captureSpec.value
        allButton.isSelected = spec.mode == CaptureMode.ALL
        selectedButton.isSelected = spec.mode == CaptureMode.SELECTED
        val window = spec.captureWindow
        windowEnabled.isSelected = window != null
        windowStart.isEnabled = window != null
        windowEnd.isEnabled = window != null
        if (window != null) { windowStart.text = window.startTime.toString(); windowEnd.text = window.endTime.toString() }
        editors.forEach { it.refresh() }
    }

    private fun afterEdit() { editors.forEach { it.refresh() }; recomputeValidation() }

    private fun recomputeValidation() {
        val report = controller.captureValidation()
        validationLabel.text = if (report.isValid) "✓ Capture selection is valid."
        else "⚠ " + report.issues.joinToString("; ") { "${it.name}: ${it.message}" }
    }

    private fun ElementKind.label(): String =
        name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    private fun editorFor(kind: ElementKind, name: String): CaptureKindEditor? =
        editors.firstOrNull { it.kind == kind && name in it.names }

    // ── Test hooks ────────────────────────────────────────────────────────────────

    /** Element names offered for [kind] across its tab(s). */
    internal fun namesShownForTest(kind: ElementKind): List<String> =
        editors.filter { it.kind == kind }.flatMap { it.names }

    /** Tab titles, e.g. to confirm the Responses / Time-Weighted Responses split. */
    internal fun tabNamesForTest(): List<String> = editors.map { "${it.kind}:${it.names.size}" }

    /** Sets the capture state for [name] via its table cell, as a user would. */
    internal fun setStateForTest(kind: ElementKind, name: String, state: String) {
        val ed = editorFor(kind, name) ?: error("no editor for $kind/$name")
        ed.model.setValueAt(state, ed.names.indexOf(name), 1)
    }

    internal fun includeForTest(kind: ElementKind, name: String) = setStateForTest(kind, name, INCLUDE)
    internal fun excludeForTest(kind: ElementKind, name: String) = setStateForTest(kind, name, EXCLUDE)

    /** The capture state shown for [name] ("Default"/"Include"/"Exclude"). */
    internal fun stateForTest(kind: ElementKind, name: String): String =
        editorFor(kind, name)?.stateOf(name) ?: DEFAULT

    internal fun selectModeForTest(mode: CaptureMode) =
        (if (mode == CaptureMode.ALL) allButton else selectedButton).doClick()

    internal fun setWindowForTest(start: Double, end: Double) {
        windowEnabled.isSelected = true
        windowStart.text = start.toString(); windowEnd.text = end.toString()
        onWindowChanged()
    }

    internal fun validationTextForTest(): String = validationLabel.text

    private companion object {
        const val DEFAULT = "Default"; const val INCLUDE = "Include"; const val EXCLUDE = "Exclude"
        val STATES = arrayOf(DEFAULT, INCLUDE, EXCLUDE)
    }
}
