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
import ksl.app.config.experiment.ControlBinding
import ksl.app.config.experiment.FactorSpec
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 *  *Factors* tab — author the factor list this experiment varies.
 *
 *  Master-detail layout:
 *  - Top: button toolbar (Add / Delete / Move Up / Move Down) + a
 *    [JTable] listing factors (Name, Binding summary, Levels
 *    summary).  Single-selection.
 *  - Bottom: editor for the selected factor — Name field, Binding
 *    picker (Control vs RV-parameter, with descriptor-driven
 *    dropdowns), Levels editor (comma-separated doubles).  Edits
 *    commit on Apply; Revert reloads from the controller.
 *
 *  Empty states (via `CardLayout`):
 *  - **No model selected** — instruct the user to pick a model first.
 *  - **Model selected but descriptor unavailable** — the document
 *    carries an unresolved or non-bundle ref.
 *  - **Populated** — the master-detail editor.
 *
 *  Validation:
 *  - Name uniqueness + non-blank: controller's `addFactor` /
 *    `updateFactor` `require(...)` calls.  Apply catches and shows
 *    the message inline.
 *  - ≥ 2 levels, no duplicates: substrate `FactorSpec.init`.  Apply
 *    catches and shows inline.
 *  - Levels parse: panel parser (comma-separated doubles).  Inline
 *    warning below the field.
 *  - Binding key resolves against [ModelDescriptor]: panel check.
 *    Inline warning below the binding row.
 *  - Control bounds (when both finite): soft warning when a level
 *    falls outside `lowerBound..upperBound`.  Non-blocking — some
 *    controls carry placeholder bounds; the analyst can override.
 *
 *  Phase E5 publishes [ExperimentAppController.currentModelDescriptor]
 *  — this panel collects it to drive the binding picker and the
 *  resolvability check.
 */
class FactorsTabPanel(
    private val controller: ExperimentAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(CardLayout()) {

    private val cards = CardLayout()
    private val noModelCard = makeMessageCard(
        "Select a model on the Model tab before authoring factors."
    )
    private val unresolvedCard = makeMessageCard(
        "The model reference is unresolved — load its bundle on the Model tab to enable " +
            "factor authoring."
    )
    /** Built in `init` because [makeNoFactorsCard] references
     *  [noFactorsAddButton], which is declared further down — field
     *  initializers run in declaration order and would NPE if this
     *  was a field initializer. */
    private lateinit var noFactorsCard: JPanel
    private val populatedCard = JPanel(BorderLayout())

    // ── Toolbar buttons ────────────────────────────────────────────────────

    private val addButton = JButton("Add Factor").apply {
        toolTipText = "Add a new factor with a default 2-level binding to the first " +
            "available control."
    }
    private val deleteButton = JButton("Delete").apply { isEnabled = false }
    private val moveUpButton = JButton("Move Up").apply { isEnabled = false }
    private val moveDownButton = JButton("Move Down").apply { isEnabled = false }
    private val noFactorsAddButton = JButton("Add First Factor")

    // ── Table ──────────────────────────────────────────────────────────────

    private val tableModel = FactorTableModel()
    private val table = JTable(tableModel).apply {
        rowHeight = 24
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        autoCreateRowSorter = false
        tableHeader.reorderingAllowed = false
        columnModel.getColumn(COL_NAME).preferredWidth = 160
        columnModel.getColumn(COL_BINDING).preferredWidth = 280
        columnModel.getColumn(COL_LEVELS).preferredWidth = 240
    }

    // ── Detail editor ──────────────────────────────────────────────────────

    private val nameField = JTextField(20)
    private val controlRadio = JRadioButton("Control", true)
    private val rvRadio = JRadioButton("RV parameter")
    private val controlKeyCombo: JComboBox<String> = JComboBox()
    private val rvNameCombo: JComboBox<String> = JComboBox()
    private val rvParamCombo: JComboBox<String> = JComboBox()
    private val levelsField = JTextField(28)
    private val levelsPreview = JLabel(" ").apply { foreground = Color(0x66, 0x66, 0x66) }
    private val bindingResolvabilityLabel = JLabel(" ").apply {
        foreground = Color(0x66, 0x66, 0x66)
    }
    private val applyButton = JButton("Apply changes").apply { isEnabled = false }
    private val revertButton = JButton("Revert").apply { isEnabled = false }

    private val bindingCards = CardLayout()
    private val bindingCardHost = JPanel(bindingCards)
    private val controlBindingPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JLabel("Control key:"))
        add(Box.createHorizontalStrut(8))
        add(controlKeyCombo)
        add(Box.createHorizontalGlue())
    }
    private val rvBindingPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JLabel("RV name:"))
        add(Box.createHorizontalStrut(8))
        add(rvNameCombo)
        add(Box.createHorizontalStrut(16))
        add(JLabel("Parameter:"))
        add(Box.createHorizontalStrut(8))
        add(rvParamCombo)
        add(Box.createHorizontalGlue())
    }

    /** Index of the factor currently loaded into the editor, or `-1`
     *  when no factor is selected.  Drives Apply's target index +
     *  Revert's source. */
    private var editorTargetIndex: Int = -1

    /** `true` while the editor's fields differ from the loaded
     *  factor.  Enables Apply / Revert, gates selection changes
     *  (prompt before discarding). */
    private var editsDirty: Boolean = false

    /** Guards collector-driven updates so they don't trigger the
     *  detail-editor's "dirty" listeners. */
    private var programmaticEditorUpdate: Boolean = false

    init {
        layout = cards

        noFactorsCard = makeNoFactorsCard()

        populatedCard.add(buildToolbar(), BorderLayout.NORTH)
        populatedCard.add(JScrollPane(table).apply {
            border = BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC))
            preferredSize = Dimension(700, 180)
        }, BorderLayout.CENTER)
        populatedCard.add(buildDetailEditor(), BorderLayout.SOUTH)

        add(noModelCard, CARD_NO_MODEL)
        add(unresolvedCard, CARD_UNRESOLVED)
        add(noFactorsCard, CARD_NO_FACTORS)
        add(populatedCard, CARD_POPULATED)

        wireToolbarListeners()
        wireSelectionListener()
        wireDetailEditorListeners()
        wireCollectors()

        refreshCardSelection()
        refreshButtonEnablement()
    }

    // ── Layout builders ────────────────────────────────────────────────────

    private fun buildToolbar(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
        add(addButton)
        add(Box.createHorizontalStrut(12))
        add(deleteButton)
        add(Box.createHorizontalStrut(6))
        add(moveUpButton)
        add(moveDownButton)
        add(Box.createHorizontalGlue())
    }

    private fun buildDetailEditor(): JComponent {
        ButtonGroup().apply { add(controlRadio); add(rvRadio) }
        bindingCardHost.add(controlBindingPanel, CARD_BINDING_CONTROL)
        bindingCardHost.add(rvBindingPanel, CARD_BINDING_RV)

        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )

            add(row("Name:", nameField))
            add(Box.createVerticalStrut(6))
            add(row("Binding:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(controlRadio); add(Box.createHorizontalStrut(8)); add(rvRadio)
                add(Box.createHorizontalGlue())
            }))
            add(Box.createVerticalStrut(4))
            bindingCardHost.alignmentX = Component.LEFT_ALIGNMENT
            add(bindingCardHost)
            add(Box.createVerticalStrut(2))
            bindingResolvabilityLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(bindingResolvabilityLabel)
            add(Box.createVerticalStrut(8))

            add(row("Levels:", JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                levelsField.alignmentX = Component.LEFT_ALIGNMENT
                add(levelsField)
                levelsPreview.alignmentX = Component.LEFT_ALIGNMENT
                add(levelsPreview)
                add(JLabel(
                    "Comma-separated raw values, e.g. 0.5, 1.0, 1.5 (≥ 2 distinct levels)."
                ).apply {
                    alignmentX = Component.LEFT_ALIGNMENT
                    foreground = Color(0x88, 0x88, 0x88)
                    font = font.deriveFont(font.size2D - 1f)
                })
            }))
            add(Box.createVerticalStrut(8))

            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
                add(Box.createHorizontalGlue())
                add(revertButton)
                add(Box.createHorizontalStrut(8))
                add(applyButton)
            })
        }
        return panel
    }

    private fun row(label: String, content: JComponent): JComponent = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT
        add(JLabel(label).apply {
            font = font.deriveFont(Font.BOLD)
            preferredSize = Dimension(80, preferredSize.height)
            maximumSize = Dimension(80, preferredSize.height)
        })
        add(Box.createHorizontalStrut(8))
        add(content)
        add(Box.createHorizontalGlue())
    }

    private fun makeMessageCard(message: String): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel(message, SwingConstants.CENTER).apply {
            foreground = Color(0x66, 0x66, 0x66)
            border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
        }, BorderLayout.CENTER)
    }

    private fun makeNoFactorsCard(): JPanel = JPanel(BorderLayout()).apply {
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.CENTER_ALIGNMENT
            add(JLabel("No factors yet.", SwingConstants.CENTER).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                foreground = Color(0x66, 0x66, 0x66)
            })
            add(Box.createVerticalStrut(8))
            noFactorsAddButton.alignmentX = Component.CENTER_ALIGNMENT
            add(noFactorsAddButton)
        }
        add(center, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
    }

    // ── Wiring: toolbar ────────────────────────────────────────────────────

    private fun wireToolbarListeners() {
        addButton.addActionListener { addNewFactor() }
        noFactorsAddButton.addActionListener { addNewFactor() }
        deleteButton.addActionListener {
            val index = currentRow()
            if (index < 0) return@addActionListener
            val name = controller.factors.value.getOrNull(index)?.name ?: return@addActionListener
            val choice = JOptionPane.showConfirmDialog(
                this, "Delete factor '$name'?", "Delete Factor",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
            )
            if (choice != JOptionPane.YES_OPTION) return@addActionListener
            controller.deleteFactor(index)
        }
        moveUpButton.addActionListener {
            val index = currentRow()
            if (index >= 1) controller.moveFactorUp(index)
        }
        moveDownButton.addActionListener {
            val index = currentRow()
            if (index in 0 until controller.factors.value.lastIndex) {
                controller.moveFactorDown(index)
            }
        }
    }

    private fun addNewFactor() {
        val descriptor = controller.currentModelDescriptor.value ?: run {
            onMessage(
                "Select a model on the Model tab before adding factors.",
                NotificationSeverity.WARNING
            )
            return
        }
        val newName = nextAvailableFactorName(controller.factors.value)
        val defaultBinding = defaultBindingFor(descriptor) ?: run {
            onMessage(
                "Selected model has no controls or RV parameters to bind to.",
                NotificationSeverity.ERROR
            )
            return
        }
        val spec = FactorSpec(
            name = newName,
            levels = listOf(0.0, 1.0),
            binding = defaultBinding
        )
        try {
            controller.addFactor(spec)
        } catch (t: IllegalArgumentException) {
            onMessage("Could not add factor: ${t.message}", NotificationSeverity.ERROR)
        }
    }

    private fun nextAvailableFactorName(existing: List<FactorSpec>): String {
        val taken = existing.map { it.name }.toSet()
        var i = 1
        while ("Factor$i" in taken) i++
        return "Factor$i"
    }

    private fun defaultBindingFor(descriptor: ModelDescriptor): ControlBinding? {
        val firstControl = descriptor.controls.numericControls.firstOrNull()?.keyName
        if (firstControl != null) return ControlBinding.Control(firstControl)
        val firstRv = descriptor.rvParameterMap.entries.firstOrNull()
        if (firstRv != null) {
            val firstParam = firstRv.value.keys.firstOrNull()
            if (firstParam != null) {
                return ControlBinding.RVParameter(rvName = firstRv.key, paramName = firstParam)
            }
        }
        return null
    }

    // ── Wiring: table selection ────────────────────────────────────────────

    private fun wireSelectionListener() {
        table.selectionModel.addListSelectionListener { e ->
            if (e.valueIsAdjusting) return@addListSelectionListener
            val newRow = table.selectedRow
            if (newRow == editorTargetIndex) return@addListSelectionListener
            if (editsDirty) {
                val choice = JOptionPane.showConfirmDialog(
                    this,
                    "Discard unsaved factor edits?",
                    "Unsaved Edits",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
                )
                if (choice != JOptionPane.YES_OPTION) {
                    // Restore the previous selection silently.
                    if (editorTargetIndex in 0 until table.rowCount) {
                        programmaticEditorUpdate = true
                        try {
                            table.selectionModel.setSelectionInterval(editorTargetIndex, editorTargetIndex)
                        } finally {
                            programmaticEditorUpdate = false
                        }
                    }
                    return@addListSelectionListener
                }
            }
            controller.setSelectedFactorIndex(newRow.coerceAtLeast(-1))
            loadEditorFromController(newRow)
        }
    }

    private fun currentRow(): Int = table.selectedRow

    // ── Wiring: detail editor ──────────────────────────────────────────────

    private fun wireDetailEditorListeners() {
        val markDirty: () -> Unit = {
            if (!programmaticEditorUpdate && editorTargetIndex >= 0 && !editsDirty) {
                editsDirty = true
                refreshButtonEnablement()
            }
        }
        nameField.document.addDocumentListener(SimpleDocumentListener { markDirty() })
        levelsField.document.addDocumentListener(SimpleDocumentListener {
            markDirty()
            refreshLevelsPreview()
        })
        controlRadio.addActionListener {
            if (programmaticEditorUpdate) return@addActionListener
            if (controlRadio.isSelected) {
                bindingCards.show(bindingCardHost, CARD_BINDING_CONTROL)
                refreshBindingResolvability()
                markDirty()
            }
        }
        rvRadio.addActionListener {
            if (programmaticEditorUpdate) return@addActionListener
            if (rvRadio.isSelected) {
                bindingCards.show(bindingCardHost, CARD_BINDING_RV)
                refreshBindingResolvability()
                markDirty()
            }
        }
        controlKeyCombo.addActionListener {
            if (programmaticEditorUpdate) return@addActionListener
            markDirty()
            refreshBindingResolvability()
        }
        rvNameCombo.addActionListener {
            if (programmaticEditorUpdate) return@addActionListener
            refreshRvParamComboForSelectedRv()
            markDirty()
            refreshBindingResolvability()
        }
        rvParamCombo.addActionListener {
            if (programmaticEditorUpdate) return@addActionListener
            markDirty()
            refreshBindingResolvability()
        }
        applyButton.addActionListener { applyEditor() }
        revertButton.addActionListener {
            loadEditorFromController(editorTargetIndex)
        }
    }

    // ── Wiring: controller collectors ──────────────────────────────────────

    private fun wireCollectors() {
        controller.edtScope.launch {
            controller.factors.collect { _ ->
                tableModel.fireTableDataChanged()
                syncSelection()
                refreshCardSelection()
                refreshButtonEnablement()
            }
        }
        controller.edtScope.launch {
            controller.selectedFactorIndex.collect { _ ->
                syncSelection()
            }
        }
        controller.edtScope.launch {
            controller.modelReference.collect { _ ->
                refreshCardSelection()
            }
        }
        controller.edtScope.launch {
            controller.currentModelDescriptor.collect { descriptor ->
                rebuildBindingDropdowns(descriptor)
                refreshCardSelection()
                refreshBindingResolvability()
            }
        }
    }

    // ── Card / state orchestration ─────────────────────────────────────────

    private fun refreshCardSelection() {
        val hasModel = controller.modelReference.value != null
        val hasDescriptor = controller.currentModelDescriptor.value != null
        val hasFactors = controller.factors.value.isNotEmpty()
        val card = when {
            !hasModel -> CARD_NO_MODEL
            !hasDescriptor -> CARD_UNRESOLVED
            !hasFactors -> CARD_NO_FACTORS
            else -> CARD_POPULATED
        }
        cards.show(this, card)
    }

    private fun refreshButtonEnablement() {
        val factors = controller.factors.value
        val row = currentRow()
        deleteButton.isEnabled = row in factors.indices
        moveUpButton.isEnabled = row >= 1
        moveDownButton.isEnabled = row in 0 until factors.lastIndex
        applyButton.isEnabled = editsDirty
        revertButton.isEnabled = editsDirty
    }

    private fun syncSelection() {
        val target = controller.selectedFactorIndex.value
        if (target !in 0 until table.rowCount) {
            if (table.selectedRow != -1) table.clearSelection()
            if (editorTargetIndex != -1) loadEditorFromController(-1)
            return
        }
        if (table.selectedRow != target) {
            programmaticEditorUpdate = true
            try { table.selectionModel.setSelectionInterval(target, target) }
            finally { programmaticEditorUpdate = false }
        }
        if (editorTargetIndex != target && !editsDirty) {
            loadEditorFromController(target)
        }
    }

    // ── Detail editor: load / apply / state ────────────────────────────────

    /** Reload the editor from controller state for the factor at
     *  [index].  Clears the dirty flag.  Pass -1 to clear the editor. */
    private fun loadEditorFromController(index: Int) {
        programmaticEditorUpdate = true
        try {
            editorTargetIndex = index
            editsDirty = false
            val spec = controller.factors.value.getOrNull(index)
            if (spec == null) {
                nameField.text = ""
                levelsField.text = ""
                controlRadio.isSelected = true
                bindingCards.show(bindingCardHost, CARD_BINDING_CONTROL)
            } else {
                nameField.text = spec.name
                levelsField.text = spec.levels.joinToString(", ")
                when (val b = spec.binding) {
                    is ControlBinding.Control -> {
                        controlRadio.isSelected = true
                        bindingCards.show(bindingCardHost, CARD_BINDING_CONTROL)
                        selectComboItem(controlKeyCombo, b.controlKey)
                    }
                    is ControlBinding.RVParameter -> {
                        rvRadio.isSelected = true
                        bindingCards.show(bindingCardHost, CARD_BINDING_RV)
                        selectComboItem(rvNameCombo, b.rvName)
                        refreshRvParamComboForSelectedRv()
                        selectComboItem(rvParamCombo, b.paramName)
                    }
                }
            }
            refreshLevelsPreview()
            refreshBindingResolvability()
            refreshButtonEnablement()
        } finally {
            programmaticEditorUpdate = false
        }
    }

    private fun applyEditor() {
        val index = editorTargetIndex
        if (index < 0 || index !in controller.factors.value.indices) return
        val parsed = parseLevels(levelsField.text)
        if (parsed == null) {
            onMessage(
                "Could not parse levels.  Use comma-separated numeric values, e.g. " +
                    "'0.5, 1.0, 1.5'.",
                NotificationSeverity.WARNING
            )
            return
        }
        val binding = buildBindingFromEditor() ?: run {
            onMessage(
                "Selected binding is incomplete.  Pick a control key or an RV parameter.",
                NotificationSeverity.WARNING
            )
            return
        }
        val newName = nameField.text.trim()
        if (newName.isBlank()) {
            onMessage("Factor name cannot be blank.", NotificationSeverity.WARNING)
            return
        }
        val newSpec = try {
            FactorSpec(name = newName, levels = parsed, binding = binding)
        } catch (t: IllegalArgumentException) {
            onMessage("Invalid factor: ${t.message}", NotificationSeverity.WARNING)
            return
        }
        try {
            controller.updateFactor(index, newSpec)
        } catch (t: IllegalArgumentException) {
            onMessage("Could not apply: ${t.message}", NotificationSeverity.ERROR)
            return
        }
        editsDirty = false
        refreshButtonEnablement()
    }

    private fun buildBindingFromEditor(): ControlBinding? {
        return if (controlRadio.isSelected) {
            val key = (controlKeyCombo.selectedItem as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            ControlBinding.Control(controlKey = key)
        } else {
            val rv = (rvNameCombo.selectedItem as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            val param = (rvParamCombo.selectedItem as? String)?.takeIf { it.isNotBlank() }
                ?: return null
            ControlBinding.RVParameter(rvName = rv, paramName = param)
        }
    }

    /** Parse a comma-separated list of doubles.  Returns null on
     *  empty input or any unparseable token. */
    private fun parseLevels(raw: String): List<Double>? {
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<Double>(parts.size)
        for (p in parts) {
            val d = p.toDoubleOrNull() ?: return null
            out.add(d)
        }
        return out
    }

    private fun refreshLevelsPreview() {
        val parsed = parseLevels(levelsField.text)
        when {
            parsed == null -> {
                levelsPreview.foreground = Color(0xCC, 0x33, 0x33)
                levelsPreview.text = "Could not parse levels — use comma-separated numbers."
            }
            parsed.size < 2 -> {
                levelsPreview.foreground = Color(0xCC, 0x33, 0x33)
                levelsPreview.text = "Need at least 2 levels."
            }
            parsed.toSet().size != parsed.size -> {
                levelsPreview.foreground = Color(0xCC, 0x33, 0x33)
                levelsPreview.text = "Duplicate level values detected."
            }
            else -> {
                val outOfRange = warningForBoundsViolation(parsed)
                if (outOfRange != null) {
                    levelsPreview.foreground = Color(0xCC, 0x77, 0x00)
                    levelsPreview.text = outOfRange
                } else {
                    levelsPreview.foreground = Color(0x33, 0x77, 0x33)
                    levelsPreview.text = "${parsed.size} levels parsed."
                }
            }
        }
    }

    /** Returns a soft warning when the current binding is a control
     *  with finite bounds and any level falls outside them.  Returns
     *  `null` otherwise (no bounds, no binding, or all in range). */
    private fun warningForBoundsViolation(levels: List<Double>): String? {
        if (!controlRadio.isSelected) return null
        val key = controlKeyCombo.selectedItem as? String ?: return null
        val descriptor = controller.currentModelDescriptor.value ?: return null
        val control = descriptor.controls.numericControls.firstOrNull { it.keyName == key }
            ?: return null
        if (!control.lowerBound.isFinite() || !control.upperBound.isFinite()) return null
        val violators = levels.filter { it < control.lowerBound || it > control.upperBound }
        if (violators.isEmpty()) return null
        return "Warning: level(s) ${violators.joinToString(", ")} outside control range " +
            "[${control.lowerBound}, ${control.upperBound}] — apply will still succeed."
    }

    private fun refreshBindingResolvability() {
        val descriptor = controller.currentModelDescriptor.value ?: run {
            bindingResolvabilityLabel.text = " "
            return
        }
        val (ok, msg) = if (controlRadio.isSelected) {
            val key = controlKeyCombo.selectedItem as? String
            if (key.isNullOrBlank()) {
                false to "Pick a control key."
            } else {
                val hit = descriptor.controls.numericControls.any { it.keyName == key }
                hit to if (hit) " " else "Control '$key' not found on the selected model."
            }
        } else {
            val rv = rvNameCombo.selectedItem as? String
            val param = rvParamCombo.selectedItem as? String
            when {
                rv.isNullOrBlank() -> false to "Pick an RV name."
                param.isNullOrBlank() -> false to "Pick a parameter name."
                else -> {
                    val hit = descriptor.rvParameterMap[rv]?.containsKey(param) == true
                    hit to if (hit) " " else "RV parameter '$rv.$param' not found on the selected model."
                }
            }
        }
        bindingResolvabilityLabel.text = msg
        bindingResolvabilityLabel.foreground = if (ok) {
            Color(0x66, 0x66, 0x66)
        } else {
            Color(0xCC, 0x33, 0x33)
        }
    }

    // ── Dropdown population ────────────────────────────────────────────────

    private fun rebuildBindingDropdowns(descriptor: ModelDescriptor?) {
        programmaticEditorUpdate = true
        try {
            val controlKeys = descriptor
                ?.controls?.numericControls
                ?.map { it.keyName }
                ?.sorted()
                ?: emptyList()
            controlKeyCombo.model = DefaultComboBoxModel(controlKeys.toTypedArray())

            val rvNames = descriptor?.rvParameterMap?.keys?.sorted() ?: emptyList()
            rvNameCombo.model = DefaultComboBoxModel(rvNames.toTypedArray())
            refreshRvParamComboForSelectedRv()
        } finally {
            programmaticEditorUpdate = false
        }
        // After rebuilding, re-load the editor from the current
        // factor so selections reflect the (possibly new) options.
        if (editorTargetIndex >= 0 && !editsDirty) {
            loadEditorFromController(editorTargetIndex)
        }
    }

    private fun refreshRvParamComboForSelectedRv() {
        val descriptor = controller.currentModelDescriptor.value
        val rv = rvNameCombo.selectedItem as? String
        val params = if (descriptor != null && !rv.isNullOrBlank()) {
            descriptor.rvParameterMap[rv]?.keys?.sorted() ?: emptyList()
        } else {
            emptyList()
        }
        // Preserve user selection where possible.
        val previous = rvParamCombo.selectedItem as? String
        programmaticEditorUpdate = true
        try {
            rvParamCombo.model = DefaultComboBoxModel(params.toTypedArray())
            if (previous in params) rvParamCombo.selectedItem = previous
        } finally {
            programmaticEditorUpdate = false
        }
    }

    private fun selectComboItem(combo: JComboBox<String>, value: String?) {
        if (value == null) {
            combo.selectedIndex = -1
            return
        }
        val items = (0 until combo.itemCount).map { combo.getItemAt(it) }
        if (value in items) combo.selectedItem = value
        // If value isn't in the dropdown, leave the existing
        // selection; refreshBindingResolvability will surface the
        // unresolvability.
    }

    // ── Table model ────────────────────────────────────────────────────────

    private inner class FactorTableModel : AbstractTableModel() {
        override fun getRowCount(): Int = controller.factors.value.size
        override fun getColumnCount(): Int = 3
        override fun getColumnName(c: Int): String = when (c) {
            COL_NAME -> "Name"
            COL_BINDING -> "Binding"
            COL_LEVELS -> "Levels"
            else -> ""
        }
        override fun isCellEditable(r: Int, c: Int): Boolean = false
        override fun getValueAt(r: Int, c: Int): Any {
            val spec = controller.factors.value[r]
            return when (c) {
                COL_NAME -> spec.name
                COL_BINDING -> describeBinding(spec.binding)
                COL_LEVELS -> "${spec.levels.size}: [${spec.levels.joinToString(", ")}]"
                else -> ""
            }
        }
    }

    private fun describeBinding(b: ControlBinding): String = when (b) {
        is ControlBinding.Control -> "Control: ${b.controlKey}"
        is ControlBinding.RVParameter -> "RV: ${b.rvName}.${b.paramName}"
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Tiny DocumentListener wrapper to keep call sites concise. */
    private class SimpleDocumentListener(
        private val onChange: () -> Unit
    ) : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    }

    companion object {
        private const val COL_NAME = 0
        private const val COL_BINDING = 1
        private const val COL_LEVELS = 2

        private const val CARD_NO_MODEL = "noModel"
        private const val CARD_UNRESOLVED = "unresolved"
        private const val CARD_NO_FACTORS = "noFactors"
        private const val CARD_POPULATED = "populated"

        private const val CARD_BINDING_CONTROL = "bindingControl"
        private const val CARD_BINDING_RV = "bindingRv"
    }
}
