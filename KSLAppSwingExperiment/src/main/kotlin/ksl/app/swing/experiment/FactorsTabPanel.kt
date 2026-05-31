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
import ksl.app.notification.NotificationSink
import ksl.controls.ControlData
import ksl.app.swing.common.editor.CatalogLabels
import ksl.simulation.ModelDescriptor
import ksl.simulation.NominatedInput
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
 *  Master-detail layout with explicit Add / Edit modes (E6.1
 *  redesign — see plan doc):
 *
 *  - **Idle**: no factor in the editor.  Prompts the user to select
 *    a row or click *Add Factor*.
 *  - **Add mode**: triggered by *Add Factor*.  Editor opens with
 *    default values; the factor is NOT in the controller's list
 *    yet.  Buttons: **Add** + **Cancel**.
 *  - **Edit mode**: triggered by selecting a row.  Editor shows the
 *    current factor's values.  Buttons: **Save changes** + **Cancel**
 *    (Cancel = reload from controller, the old Revert semantics).
 *
 *  Mode transitions:
 *  - Add + click *Add* → controller.addFactor; switches to Edit on
 *    the newly-added factor so the user can fine-tune levels
 *    without losing context.  Controller's `addFactor` selects the
 *    new index for us.
 *  - Add + click *Cancel* → returns to Idle.
 *  - Edit + click *Save changes* → controller.updateFactor; stays
 *    in Edit with refreshed values.
 *  - Edit + click *Cancel* → reload from controller (drops in-flight
 *    edits).
 *  - Selecting another row with dirty edits prompts before switching.
 *
 *  Binding picker improvements (E6.1):
 *  - Filter text field above each dropdown — substring, case-
 *    insensitive.
 *  - **Two-level Control picker** when the model's controls split
 *    naturally by `parentElementName`: parent dropdown → control
 *    dropdown.  Falls back to a flat single dropdown when grouping
 *    isn't meaningful (single parent, or all parents `null`).
 *  - RV picker is already two-level (rvName → paramName); each
 *    level now carries its own filter field.
 *
 *  Empty states unchanged: NO_MODEL, UNRESOLVED, NO_FACTORS,
 *  POPULATED — see the original Phase E6 commit for the no-model /
 *  unresolved-ref copy.
 */
class FactorsTabPanel(
    private val controller: ExperimentAppController,
    private val notifier: NotificationSink = NotificationSink.NOOP
) : JPanel(BorderLayout()) {

    // Outer layout = BorderLayout (added in E7.9 to host the
    // Edited / Saved badge in SOUTH); the card-switching moved into
    // [cardsPanel] (BorderLayout.CENTER) so the badge sits below
    // regardless of which card is showing.
    private val cards = CardLayout()
    private val cardsPanel = JPanel(cards)
    private val noModelCard = makeMessageCard(
        "Select a model on the Model tab before authoring factors."
    )
    private val unresolvedCard = makeMessageCard(
        "The model reference is unresolved — load its bundle on the Model tab to enable " +
            "factor authoring."
    )
    private lateinit var noFactorsCard: JPanel
    private val populatedCard = JPanel(BorderLayout())

    // ── Toolbar buttons ────────────────────────────────────────────────────

    private val addButton = JButton("Add Factor").apply {
        toolTipText = "Open the editor for a new factor.  Click Add inside the editor to commit."
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

    // ── Detail editor: state ───────────────────────────────────────────────

    /** Editor state.  Three variants: [Idle], [Add], [Edit].  Add
     *  carries no controller-side index — the factor doesn't exist
     *  yet.  Edit pins the controller index it's authoring. */
    private sealed class EditorMode {
        object Idle : EditorMode()
        object Add : EditorMode()
        data class Edit(val index: Int) : EditorMode()
    }

    private var editorMode: EditorMode = EditorMode.Idle

    /** `true` when the editor fields differ from what was last
     *  loaded / committed.  Drives the primary-action button's
     *  enablement and the discard-on-switch prompt. */
    private var editsDirty: Boolean = false

    /** Guards collector-driven updates so they don't mark the
     *  editor dirty. */
    private var programmaticEditorUpdate: Boolean = false

    // ── Detail editor: widgets ─────────────────────────────────────────────

    private val editorHeaderLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.BOLD)
        foreground = Color(0x44, 0x44, 0x44)
    }
    private val nameField = JTextField(20)

    private val controlRadio = JRadioButton("Control", true)
    private val rvRadio = JRadioButton("RV parameter")

    // Control binding widgets.  Two-level: parent ▾  control ▾ ,
    // with a filter text field above each.  When grouping isn't
    // meaningful the parent combo + filter are hidden.
    private val controlParentFilter = JTextField(10)
    private val controlParentCombo: JComboBox<String> = JComboBox()
    private val controlKeyFilter = JTextField(14)
    private val controlKeyCombo: JComboBox<String> = JComboBox<String>().apply {
        // Label a nominated control with its catalog display name + unit (if any).
        renderer = CatalogLabels.listRenderer(
            displayNameFor = { v -> nominatedInputFor(v)?.displayName },
            tooltipFor = { v -> CatalogLabels.tooltip(nominatedInputFor(v)) }
        )
    }

    // RV binding widgets.  Always two-level (rvName → paramName).
    private val rvNameFilter = JTextField(12)
    private val rvNameCombo: JComboBox<String> = JComboBox()
    private val rvParamFilter = JTextField(12)
    private val rvParamCombo: JComboBox<String> = JComboBox<String>().apply {
        // Label a nominated RV parameter (rvName + selected param) with its display name.
        renderer = CatalogLabels.listRenderer(
            displayNameFor = { v -> nominatedRvInputFor(v)?.displayName },
            tooltipFor = { v -> CatalogLabels.tooltip(nominatedRvInputFor(v)) }
        )
    }

    /** The nominated input for a full control key combo item, or null. */
    private fun nominatedInputFor(item: Any?): NominatedInput? {
        val key = item as? String ?: return null
        return controller.currentModelDescriptor.value?.catalog
            ?.nominatedInputs?.firstOrNull { it.key == key }
    }

    /** The nominated input for an RV-parameter combo item (joined with the selected RV name), or null. */
    private fun nominatedRvInputFor(item: Any?): NominatedInput? {
        val param = item as? String ?: return null
        val rv = rvNameCombo.selectedItem as? String ?: return null
        val key = "$rv${ksl.utilities.random.rvariable.parameters.RVParameterSetter.rvParamConCatChar}$param"
        return controller.currentModelDescriptor.value?.catalog
            ?.nominatedInputs?.firstOrNull { it.key == key }
    }

    private val levelsField = JTextField(28)
    private val levelsPreview = JLabel(" ").apply { foreground = Color(0x66, 0x66, 0x66) }
    private val bindingResolvabilityLabel = JLabel(" ").apply {
        foreground = Color(0x66, 0x66, 0x66)
    }

    private val primaryActionButton = JButton("Save changes").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel").apply { isEnabled = false }

    private val bindingCards = CardLayout()
    private val bindingCardHost = JPanel(bindingCards)

    /** `true` when the current model's controls split naturally
     *  across multiple non-null parent elements.  When false the
     *  parent dropdown + filter are hidden in the Control binding
     *  pane.  Recomputed in [rebuildBindingDropdowns]. */
    private var useControlParentLevel: Boolean = false

    /** Master lists — all candidates, before filtering.  The combo
     *  models are derived from these. */
    private var allControlParents: List<String> = emptyList()
    private var allControlKeys: List<String> = emptyList()
    private var allRvNames: List<String> = emptyList()

    /** Per-parent map of control keys.  Empty when grouping isn't
     *  used.  Indexed by parent name. */
    private var controlKeysByParent: Map<String, List<String>> = emptyMap()

    init {
        noFactorsCard = makeNoFactorsCard()

        populatedCard.add(buildToolbar(), BorderLayout.NORTH)
        populatedCard.add(JScrollPane(table).apply {
            border = BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC))
            preferredSize = Dimension(700, 180)
        }, BorderLayout.CENTER)
        populatedCard.add(buildDetailEditor(), BorderLayout.SOUTH)

        cardsPanel.add(noModelCard, CARD_NO_MODEL)
        cardsPanel.add(unresolvedCard, CARD_UNRESOLVED)
        cardsPanel.add(noFactorsCard, CARD_NO_FACTORS)
        cardsPanel.add(populatedCard, CARD_POPULATED)
        add(cardsPanel, BorderLayout.CENTER)

        // Edited / Saved badge in the footer.
        val footer = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0))
        footer.add(DocumentStateLabel(controller.isDirty, controller.edtScope))
        add(footer, BorderLayout.SOUTH)

        wireToolbarListeners()
        wireSelectionListener()
        wireDetailEditorListeners()
        wireCollectors()

        refreshCardSelection()
        applyMode(EditorMode.Idle)
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
        bindingCardHost.add(buildControlBindingPane(), CARD_BINDING_CONTROL)
        bindingCardHost.add(buildRvBindingPane(), CARD_BINDING_RV)

        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )

            editorHeaderLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(editorHeaderLabel)
            add(Box.createVerticalStrut(6))

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
                add(cancelButton)
                add(Box.createHorizontalStrut(8))
                add(primaryActionButton)
            })
        }
    }

    /** Control binding pane: two-level parent ▾ control ▾ inline
     *  with filter text fields above each.  Parent combo + filter
     *  are hidden when grouping isn't useful. */
    private fun buildControlBindingPane(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT

        // Filter row above the dropdowns.
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            controlParentFilter.toolTipText = "Filter parent elements"
            controlParentFilter.maximumSize = Dimension(160, controlParentFilter.preferredSize.height)
            controlKeyFilter.toolTipText = "Filter control keys within the chosen parent"
            controlKeyFilter.maximumSize = Dimension(180, controlKeyFilter.preferredSize.height)
            add(JLabel("Filter parent:"))
            add(Box.createHorizontalStrut(4))
            add(controlParentFilter)
            add(Box.createHorizontalStrut(16))
            add(JLabel("Filter control:"))
            add(Box.createHorizontalStrut(4))
            add(controlKeyFilter)
            add(Box.createHorizontalGlue())
        })
        add(Box.createVerticalStrut(4))

        // Dropdown row.
        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Control:"))
            add(Box.createHorizontalStrut(8))
            add(controlParentCombo)
            add(Box.createHorizontalStrut(8))
            add(controlKeyCombo)
            add(Box.createHorizontalGlue())
        })
    }

    /** RV binding pane: rvName ▾ paramName ▾ inline with filters. */
    private fun buildRvBindingPane(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        alignmentX = Component.LEFT_ALIGNMENT

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            rvNameFilter.toolTipText = "Filter RV names"
            rvNameFilter.maximumSize = Dimension(160, rvNameFilter.preferredSize.height)
            rvParamFilter.toolTipText = "Filter parameter names for the chosen RV"
            rvParamFilter.maximumSize = Dimension(160, rvParamFilter.preferredSize.height)
            add(JLabel("Filter RV:"))
            add(Box.createHorizontalStrut(4))
            add(rvNameFilter)
            add(Box.createHorizontalStrut(16))
            add(JLabel("Filter param:"))
            add(Box.createHorizontalStrut(4))
            add(rvParamFilter)
            add(Box.createHorizontalGlue())
        })
        add(Box.createVerticalStrut(4))

        add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("RV:"))
            add(Box.createHorizontalStrut(8))
            add(rvNameCombo)
            add(Box.createHorizontalStrut(16))
            add(JLabel("Parameter:"))
            add(Box.createHorizontalStrut(8))
            add(rvParamCombo)
            add(Box.createHorizontalGlue())
        })
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
        addButton.addActionListener { enterAddMode() }
        noFactorsAddButton.addActionListener { enterAddMode() }
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
            applyMode(EditorMode.Idle)
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

    private fun enterAddMode() {
        val descriptor = controller.currentModelDescriptor.value ?: run {
            notifier.warn(
                "Select a model on the Model tab before adding factors."
            )
            return
        }
        if (!confirmDiscardIfDirty("Discard the in-progress factor edits?")) return
        if (defaultBindingFor(descriptor) == null) {
            notifier.error(
                "Selected model has no controls or RV parameters to bind to."
            )
            return
        }
        loadDefaultsForNewFactor()
        applyMode(EditorMode.Add)
    }

    private fun loadDefaultsForNewFactor() {
        val descriptor = controller.currentModelDescriptor.value ?: return
        val newName = nextAvailableFactorName(controller.factors.value)
        val defaultBinding = defaultBindingFor(descriptor) ?: return
        programmaticEditorUpdate = true
        try {
            nameField.text = newName
            levelsField.text = "0.0, 1.0"
            when (defaultBinding) {
                is ControlBinding.Control -> {
                    controlRadio.isSelected = true
                    bindingCards.show(bindingCardHost, CARD_BINDING_CONTROL)
                    selectControlBindingInUI(defaultBinding.controlKey)
                }
                is ControlBinding.RVParameter -> {
                    rvRadio.isSelected = true
                    bindingCards.show(bindingCardHost, CARD_BINDING_RV)
                    selectRvBindingInUI(defaultBinding.rvName, defaultBinding.paramName)
                }
            }
        } finally {
            programmaticEditorUpdate = false
        }
        refreshLevelsPreview()
        refreshBindingResolvability()
        editsDirty = false
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
            val currentIndex = (editorMode as? EditorMode.Edit)?.index ?: -1
            if (newRow == currentIndex && editorMode !is EditorMode.Add) return@addListSelectionListener
            if (!confirmDiscardIfDirty("Discard unsaved factor edits?")) {
                // Restore previous selection silently.
                if (currentIndex in 0 until table.rowCount) {
                    programmaticEditorUpdate = true
                    try {
                        table.selectionModel.setSelectionInterval(currentIndex, currentIndex)
                    } finally {
                        programmaticEditorUpdate = false
                    }
                }
                return@addListSelectionListener
            }
            if (newRow < 0) {
                applyMode(EditorMode.Idle)
            } else {
                controller.setSelectedFactorIndex(newRow)
                loadEditorFromController(newRow)
                applyMode(EditorMode.Edit(newRow))
            }
        }
    }

    private fun currentRow(): Int = table.selectedRow

    // ── Wiring: detail editor ──────────────────────────────────────────────

    private fun wireDetailEditorListeners() {
        val markDirty: () -> Unit = {
            if (!programmaticEditorUpdate &&
                editorMode !is EditorMode.Idle &&
                !editsDirty
            ) {
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
        controlParentCombo.addActionListener {
            if (programmaticEditorUpdate) return@addActionListener
            refreshControlKeyComboForSelectedParent()
            markDirty()
            refreshBindingResolvability()
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

        // Filter text-field listeners — re-filter the source list,
        // rebuild the combo model, preserve selection where possible.
        controlParentFilter.document.addDocumentListener(SimpleDocumentListener {
            applyControlParentFilter()
        })
        controlKeyFilter.document.addDocumentListener(SimpleDocumentListener {
            applyControlKeyFilter()
        })
        rvNameFilter.document.addDocumentListener(SimpleDocumentListener {
            applyRvNameFilter()
        })
        rvParamFilter.document.addDocumentListener(SimpleDocumentListener {
            applyRvParamFilter()
        })

        primaryActionButton.addActionListener { commitEditor() }
        cancelButton.addActionListener { cancelEditor() }
    }

    // ── Wiring: controller collectors ──────────────────────────────────────

    private fun wireCollectors() {
        controller.edtScope.launch {
            controller.factors.collect { _ ->
                tableModel.fireTableDataChanged()
                syncSelectionFromController()
                refreshCardSelection()
                refreshButtonEnablement()
            }
        }
        controller.edtScope.launch {
            controller.selectedFactorIndex.collect { _ ->
                syncSelectionFromController()
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
            !hasFactors && editorMode !is EditorMode.Add -> CARD_NO_FACTORS
            else -> CARD_POPULATED
        }
        cards.show(cardsPanel, card)
    }

    private fun refreshButtonEnablement() {
        val factors = controller.factors.value
        val row = currentRow()
        // Don't allow Delete / Move while in Add mode (the row
        // doesn't exist in the list yet); these act on table rows.
        val inAddMode = editorMode is EditorMode.Add
        deleteButton.isEnabled = !inAddMode && row in factors.indices
        moveUpButton.isEnabled = !inAddMode && row >= 1
        moveDownButton.isEnabled = !inAddMode && row in 0 until factors.lastIndex

        primaryActionButton.isEnabled = when (editorMode) {
            is EditorMode.Idle -> false
            is EditorMode.Add -> true                    // always allow Add attempt — validation surfaces if it fails
            is EditorMode.Edit -> editsDirty
        }
        cancelButton.isEnabled = editorMode !is EditorMode.Idle
    }

    private fun syncSelectionFromController() {
        if (editorMode is EditorMode.Add) return  // don't disturb Add mode
        val target = controller.selectedFactorIndex.value
        if (target !in 0 until table.rowCount) {
            if (table.selectedRow != -1) {
                programmaticEditorUpdate = true
                try { table.clearSelection() }
                finally { programmaticEditorUpdate = false }
            }
            if (editorMode !is EditorMode.Idle) applyMode(EditorMode.Idle)
            return
        }
        if (table.selectedRow != target) {
            programmaticEditorUpdate = true
            try { table.selectionModel.setSelectionInterval(target, target) }
            finally { programmaticEditorUpdate = false }
        }
        val currentIndex = (editorMode as? EditorMode.Edit)?.index ?: -1
        if (currentIndex != target && !editsDirty) {
            loadEditorFromController(target)
            applyMode(EditorMode.Edit(target))
        }
    }

    // ── Mode application ───────────────────────────────────────────────────

    private fun applyMode(newMode: EditorMode) {
        editorMode = newMode
        editsDirty = false
        when (newMode) {
            is EditorMode.Idle -> {
                editorHeaderLabel.text = "Select a factor from the table, or click Add Factor."
                clearEditorFields()
                primaryActionButton.text = "Save changes"
                cancelButton.text = "Cancel"
            }
            is EditorMode.Add -> {
                editorHeaderLabel.text = "New factor — click Add to commit, Cancel to discard."
                primaryActionButton.text = "Add"
                cancelButton.text = "Cancel"
            }
            is EditorMode.Edit -> {
                val name = controller.factors.value.getOrNull(newMode.index)?.name ?: "?"
                editorHeaderLabel.text = "Editing factor '$name'."
                primaryActionButton.text = "Save changes"
                cancelButton.text = "Cancel"
            }
        }
        refreshCardSelection()
        refreshButtonEnablement()
    }

    private fun clearEditorFields() {
        programmaticEditorUpdate = true
        try {
            nameField.text = ""
            levelsField.text = ""
            controlRadio.isSelected = true
            bindingCards.show(bindingCardHost, CARD_BINDING_CONTROL)
        } finally {
            programmaticEditorUpdate = false
        }
        refreshLevelsPreview()
        refreshBindingResolvability()
    }

    private fun confirmDiscardIfDirty(question: String): Boolean {
        if (!editsDirty && editorMode !is EditorMode.Add) return true
        if (editorMode is EditorMode.Add && !editsDirty) return true
        val choice = JOptionPane.showConfirmDialog(
            this, question, "Unsaved Edits",
            JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.YES_OPTION
    }

    // ── Editor: load / commit / cancel ─────────────────────────────────────

    /** Reload the editor from controller state for the factor at [index].
     *  Clears the dirty flag. */
    private fun loadEditorFromController(index: Int) {
        val spec = controller.factors.value.getOrNull(index) ?: run {
            clearEditorFields()
            return
        }
        programmaticEditorUpdate = true
        try {
            nameField.text = spec.name
            levelsField.text = spec.levels.joinToString(", ")
            when (val b = spec.binding) {
                is ControlBinding.Control -> {
                    controlRadio.isSelected = true
                    bindingCards.show(bindingCardHost, CARD_BINDING_CONTROL)
                    selectControlBindingInUI(b.controlKey)
                }
                is ControlBinding.RVParameter -> {
                    rvRadio.isSelected = true
                    bindingCards.show(bindingCardHost, CARD_BINDING_RV)
                    selectRvBindingInUI(b.rvName, b.paramName)
                }
            }
        } finally {
            programmaticEditorUpdate = false
        }
        refreshLevelsPreview()
        refreshBindingResolvability()
        editsDirty = false
    }

    private fun commitEditor() {
        val parsed = parseLevels(levelsField.text)
        if (parsed == null) {
            notifier.warn(
                "Could not parse levels.  Use comma-separated numeric values, e.g. " +
                    "'0.5, 1.0, 1.5'."
            )
            return
        }
        val binding = buildBindingFromEditor() ?: run {
            notifier.warn(
                "Selected binding is incomplete.  Pick a control key or an RV parameter."
            )
            return
        }
        val newName = nameField.text.trim()
        if (newName.isBlank()) {
            notifier.warn("Factor name cannot be blank.")
            return
        }
        val newSpec = try {
            FactorSpec(name = newName, levels = parsed, binding = binding)
        } catch (t: IllegalArgumentException) {
            notifier.warn("Invalid factor: ${t.message}")
            return
        }
        when (val mode = editorMode) {
            is EditorMode.Add -> {
                try {
                    controller.addFactor(newSpec)
                } catch (t: IllegalArgumentException) {
                    notifier.error("Could not add: ${t.message}")
                    return
                }
                // Controller selected the new last index; the
                // factors collector will sync the table.  Switch to
                // Edit mode pointing at the new factor so the user
                // can fine-tune without losing context.
                val newIndex = controller.factors.value.lastIndex
                loadEditorFromController(newIndex)
                applyMode(EditorMode.Edit(newIndex))
            }
            is EditorMode.Edit -> {
                try {
                    controller.updateFactor(mode.index, newSpec)
                } catch (t: IllegalArgumentException) {
                    notifier.error("Could not save: ${t.message}")
                    return
                }
                editsDirty = false
                editorHeaderLabel.text = "Editing factor '${newSpec.name}'."
                refreshButtonEnablement()
            }
            EditorMode.Idle -> { /* primary action disabled in idle */ }
        }
    }

    private fun cancelEditor() {
        when (val mode = editorMode) {
            is EditorMode.Add -> applyMode(EditorMode.Idle)
            is EditorMode.Edit -> {
                loadEditorFromController(mode.index)
                refreshButtonEnablement()
            }
            EditorMode.Idle -> { /* disabled */ }
        }
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

    /** Parse comma-separated doubles.  null on empty or any
     *  unparseable token. */
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
            "[${control.lowerBound}, ${control.upperBound}] — commit will still succeed."
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

    /** Rebuild the master candidate lists + initial combo models
     *  from the descriptor.  Called from the descriptor collector. */
    private fun rebuildBindingDropdowns(descriptor: ModelDescriptor?) {
        programmaticEditorUpdate = true
        try {
            allControlKeys = descriptor?.controls?.numericControls
                ?.map { it.keyName }
                ?.sorted()
                ?: emptyList()

            // Two-level Control grouping: meaningful when there are
            // multiple distinct non-null parent names.
            val parentMap: Map<String, List<String>> = descriptor?.controls?.numericControls
                ?.groupBy(
                    keySelector = { c: ControlData -> c.parentElementName ?: "" },
                    valueTransform = { c: ControlData -> c.keyName }
                )
                ?.filterKeys { it.isNotBlank() }
                ?.mapValues { (_, v) -> v.sorted() }
                ?: emptyMap()
            useControlParentLevel = parentMap.size > 1
            controlKeysByParent = parentMap
            allControlParents = parentMap.keys.sorted()

            controlParentCombo.isVisible = useControlParentLevel
            controlParentFilter.isVisible = useControlParentLevel

            controlParentCombo.model = DefaultComboBoxModel(allControlParents.toTypedArray())
            // Initially populate the key combo with every key (flat
            // mode) or with the first parent's keys (grouped mode).
            val initialKeys = if (useControlParentLevel && allControlParents.isNotEmpty()) {
                controlKeysByParent[allControlParents[0]] ?: emptyList()
            } else {
                allControlKeys
            }
            controlKeyCombo.model = DefaultComboBoxModel(initialKeys.toTypedArray())

            allRvNames = descriptor?.rvParameterMap?.keys?.sorted() ?: emptyList()
            rvNameCombo.model = DefaultComboBoxModel(allRvNames.toTypedArray())
            refreshRvParamComboForSelectedRv()
        } finally {
            programmaticEditorUpdate = false
        }
        // Re-load the editor for the currently-selected factor (if
        // any) so its binding selection lands in the new combo
        // models.  Skip in Add mode (the user is mid-creation).
        val mode = editorMode
        if (mode is EditorMode.Edit && !editsDirty) {
            loadEditorFromController(mode.index)
        }
    }

    private fun refreshControlKeyComboForSelectedParent() {
        if (!useControlParentLevel) return
        val parent = controlParentCombo.selectedItem as? String ?: return
        val keys = controlKeysByParent[parent] ?: emptyList()
        val previous = controlKeyCombo.selectedItem as? String
        programmaticEditorUpdate = true
        try {
            controlKeyCombo.model = DefaultComboBoxModel(
                applyFilter(keys, controlKeyFilter.text).toTypedArray()
            )
            if (previous in keys) controlKeyCombo.selectedItem = previous
        } finally {
            programmaticEditorUpdate = false
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
        val previous = rvParamCombo.selectedItem as? String
        programmaticEditorUpdate = true
        try {
            rvParamCombo.model = DefaultComboBoxModel(
                applyFilter(params, rvParamFilter.text).toTypedArray()
            )
            if (previous in params) rvParamCombo.selectedItem = previous
        } finally {
            programmaticEditorUpdate = false
        }
    }

    // ── Filter handlers ────────────────────────────────────────────────────

    private fun applyControlParentFilter() {
        val filtered = applyFilter(allControlParents, controlParentFilter.text)
        val previous = controlParentCombo.selectedItem as? String
        programmaticEditorUpdate = true
        try {
            controlParentCombo.model = DefaultComboBoxModel(filtered.toTypedArray())
            if (previous in filtered) controlParentCombo.selectedItem = previous
        } finally {
            programmaticEditorUpdate = false
        }
        refreshControlKeyComboForSelectedParent()
    }

    private fun applyControlKeyFilter() {
        val pool = if (useControlParentLevel) {
            val parent = controlParentCombo.selectedItem as? String
            controlKeysByParent[parent] ?: emptyList()
        } else {
            allControlKeys
        }
        val filtered = applyFilter(pool, controlKeyFilter.text)
        val previous = controlKeyCombo.selectedItem as? String
        programmaticEditorUpdate = true
        try {
            controlKeyCombo.model = DefaultComboBoxModel(filtered.toTypedArray())
            if (previous in filtered) controlKeyCombo.selectedItem = previous
        } finally {
            programmaticEditorUpdate = false
        }
        refreshBindingResolvability()
    }

    private fun applyRvNameFilter() {
        val filtered = applyFilter(allRvNames, rvNameFilter.text)
        val previous = rvNameCombo.selectedItem as? String
        programmaticEditorUpdate = true
        try {
            rvNameCombo.model = DefaultComboBoxModel(filtered.toTypedArray())
            if (previous in filtered) rvNameCombo.selectedItem = previous
        } finally {
            programmaticEditorUpdate = false
        }
        refreshRvParamComboForSelectedRv()
    }

    private fun applyRvParamFilter() {
        refreshRvParamComboForSelectedRv()
    }

    /** Case-insensitive substring filter.  Empty filter returns the
     *  source unchanged. */
    private fun applyFilter(source: List<String>, filter: String): List<String> {
        val needle = filter.trim().lowercase()
        if (needle.isEmpty()) return source
        return source.filter { it.lowercase().contains(needle) }
    }

    // ── Selection helpers ──────────────────────────────────────────────────

    private fun selectControlBindingInUI(controlKey: String) {
        if (useControlParentLevel) {
            val parent = controlKeysByParent.entries
                .firstOrNull { controlKey in it.value }
                ?.key
            if (parent != null) {
                controlParentCombo.selectedItem = parent
                refreshControlKeyComboForSelectedParent()
            }
        }
        selectComboItem(controlKeyCombo, controlKey)
    }

    private fun selectRvBindingInUI(rvName: String, paramName: String) {
        selectComboItem(rvNameCombo, rvName)
        refreshRvParamComboForSelectedRv()
        selectComboItem(rvParamCombo, paramName)
    }

    private fun selectComboItem(combo: JComboBox<String>, value: String?) {
        if (value == null) {
            combo.selectedIndex = -1
            return
        }
        val items = (0 until combo.itemCount).map { combo.getItemAt(it) }
        if (value in items) combo.selectedItem = value
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
