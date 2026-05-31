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

package ksl.app.swing.simopt.problem

import ksl.app.config.optimization.OptimizationInputSpec
import ksl.controls.ControlType
import ksl.simulation.ModelDescriptor
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Modal dialog for adding or editing one decision variable.
 *
 * Driven by the live [ModelDescriptor] (controls + RV parameters)
 * exposed on the controller.  The user picks a source — *From model
 * control* or *From RV parameter* — filters the candidate list by
 * substring, selects a candidate to pre-fill the bounds and
 * granularity, then commits with OK.
 *
 * Returns the new / updated [OptimizationInputSpec] when the user
 * clicks OK, or `null` when they cancel.
 *
 * In Edit mode the source toggle is disabled — changing the
 * underlying control / RV-parameter binding requires deleting and
 * re-adding the variable.  (The substrate stores no link from a
 * decision-variable name back to its source; the source is only
 * meaningful at GUI-authoring time.)
 *
 * The dialog blocks OK until every invariant the substrate's
 * `OptimizationInputSpec.init` will check passes:
 *
 *  - [name] is non-blank
 *  - [lowerBound] and [upperBound] are finite
 *  - [lowerBound] strictly less than [upperBound]
 *  - [granularity] >= 0 and finite
 *
 * Plus one app-level invariant — the name must not collide with any
 * other input in [existingNames] (in Edit mode, the original name is
 * removed from this set before the check).
 *
 * @param owner parent window for the modal dialog
 * @param descriptor live model descriptor; `null` is accepted (no
 *        candidates shown, all fields blank) so the dialog still
 *        instantiates cleanly during model-load races
 * @param mode [Mode.Add] for a fresh entry, [Mode.Edit] to pre-fill
 *        from an existing spec
 * @param existingNames names of all decision variables currently in
 *        the document (used for the uniqueness check)
 */
class InputEditorDialog(
    owner: Window?,
    private val descriptor: ModelDescriptor?,
    private val mode: Mode,
    private val existingNames: Set<String>
) : JDialog(owner, dialogTitleFor(mode), ModalityType.APPLICATION_MODAL) {

    /** Result of the dialog — the user's input on OK, `null` on
     *  Cancel or window-close.  Read via [showDialog] only after the
     *  dialog returns. */
    private var result: OptimizationInputSpec? = null

    // ── Source toggle ─────────────────────────────────────────────────────

    private val controlRadio = JRadioButton("From model control").apply { isSelected = true }
    private val rvRadio = JRadioButton("From RV parameter")

    init {
        ButtonGroup().apply {
            add(controlRadio); add(rvRadio)
        }
    }

    // ── Filter + candidate list ────────────────────────────────────────────

    private val filterField = JTextField(20).apply {
        toolTipText = "Case-insensitive substring filter — type to narrow the list below."
    }
    private val filterCountLabel = JLabel(" ").apply {
        foreground = Color(0x77, 0x77, 0x77)
        font = font.deriveFont(Font.PLAIN, 11f)
        toolTipText = "Number of matches currently shown out of all candidates."
    }
    private val listModel = DefaultListModel<Candidate>()
    private val candidateList = JList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 8
        cellRenderer = CandidateCellRenderer()
    }

    // ── Editable fields ────────────────────────────────────────────────────

    private val nameField = JTextField(24)
    private val lowerField = JTextField(10)
    private val upperField = JTextField(10)
    private val granularityField = JTextField(10)

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val okButton = JButton("OK").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel")

    init {
        layout = BorderLayout()
        contentPane.add(buildSourceRow(), BorderLayout.NORTH)
        contentPane.add(buildCenterPanel(), BorderLayout.CENTER)
        contentPane.add(buildButtonRow(), BorderLayout.SOUTH)

        wireSourceToggle()
        wireFilter()
        wireCandidateSelection()
        wireFieldValidators()
        wireButtons()

        // Initial population.
        when (mode) {
            is Mode.Add -> {
                rebuildCandidates()
            }
            is Mode.Edit -> {
                // Edit mode: source toggle disabled; pre-fill fields
                // from the existing spec; pre-select the candidate
                // (if it still exists in the descriptor).
                controlRadio.isEnabled = false
                rvRadio.isEnabled = false
                rebuildCandidates()
                preselectCandidate(mode.spec.name)
                nameField.text = mode.spec.name
                lowerField.text = mode.spec.lowerBound.toString()
                upperField.text = mode.spec.upperBound.toString()
                granularityField.text = mode.spec.granularity.toString()
                refreshOkEnablement()
            }
        }

        // Focus the filter field once the dialog is shown — users
        // can start typing immediately to narrow long candidate lists
        // (e.g. dozens of RV parameters or model controls with long
        // model-element-hierarchy prefixes).
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent?) {
                filterField.requestFocusInWindow()
            }
        })

        pack()
        minimumSize = Dimension(540, 460)
        setLocationRelativeTo(owner)
    }

    /** Show the dialog and block until the user dismisses it.
     *  Returns the user's OK result, or `null` on Cancel. */
    fun showDialog(): OptimizationInputSpec? {
        isVisible = true
        return result
    }

    // ── Layout builders ────────────────────────────────────────────────────

    private fun buildSourceRow(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(2, 8, 2, 8)
        )
        add(JLabel("Source:").apply { font = font.deriveFont(Font.BOLD) })
        add(controlRadio)
        add(rvRadio)
    }

    private fun buildCenterPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        // Filter row — label + field + match counter, all in one row
        add(JLabel("Filter:"), gbc(0, 0, anchor = GridBagConstraints.WEST))
        add(JPanel(java.awt.BorderLayout(6, 0)).apply {
            isOpaque = false
            add(filterField, java.awt.BorderLayout.CENTER)
            add(filterCountLabel, java.awt.BorderLayout.EAST)
        }, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Candidate list
        val listScroll = JScrollPane(
            candidateList,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        ).apply { preferredSize = Dimension(420, 180) }
        add(listScroll, gbc(0, 1, weightx = 1.0, weighty = 1.0, width = 2,
            fill = GridBagConstraints.BOTH, insets = Insets(6, 4, 8, 4)))

        // Field grid
        add(JLabel("Name:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(nameField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Lower bound:"), gbc(0, 3, anchor = GridBagConstraints.WEST))
        add(lowerField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Upper bound:"), gbc(0, 4, anchor = GridBagConstraints.WEST))
        add(upperField, gbc(1, 4, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Granularity:"), gbc(0, 5, anchor = GridBagConstraints.WEST))
        add(granularityField, gbc(1, 5, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        val help = JLabel(
            "<html><i>Granularity: 0.0 = continuous; 1.0 = integer-ordered; other " +
                "positive values round to the nearest multiple.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55) }
        add(help, gbc(0, 6, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))
    }

    private fun buildButtonRow(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(8, 14, 8, 14)
        )
        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(statusLabel) }, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            add(cancelButton)
            add(okButton)
        }, BorderLayout.EAST)
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private fun wireSourceToggle() {
        val onToggle = { _: Any? ->
            rebuildCandidates()
        }
        controlRadio.addActionListener(onToggle)
        rvRadio.addActionListener(onToggle)
    }

    private fun wireFilter() {
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { rebuildCandidates() }
            override fun removeUpdate(e: DocumentEvent?) { rebuildCandidates() }
            override fun changedUpdate(e: DocumentEvent?) { rebuildCandidates() }
        })
    }

    private fun wireCandidateSelection() {
        candidateList.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            val sel = candidateList.selectedValue ?: return@addListSelectionListener
            // Auto-populate fields from the candidate.  Existing field
            // text is overwritten — in Add mode the user expects this.
            nameField.text = sel.name
            lowerField.text = sel.lowerHint?.toString().orEmpty()
            upperField.text = sel.upperHint?.toString().orEmpty()
            granularityField.text = sel.granularityHint.toString()
            refreshOkEnablement()
        }
    }

    private fun wireFieldValidators() {
        val refresh = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { refreshOkEnablement() }
            override fun removeUpdate(e: DocumentEvent?) { refreshOkEnablement() }
            override fun changedUpdate(e: DocumentEvent?) { refreshOkEnablement() }
        }
        nameField.document.addDocumentListener(refresh)
        lowerField.document.addDocumentListener(refresh)
        upperField.document.addDocumentListener(refresh)
        granularityField.document.addDocumentListener(refresh)
    }

    private fun wireButtons() {
        okButton.addActionListener {
            val spec = buildSpecOrNull() ?: return@addActionListener
            result = spec
            isVisible = false
            dispose()
        }
        cancelButton.addActionListener {
            result = null
            isVisible = false
            dispose()
        }
    }

    // ── Candidate population ──────────────────────────────────────────────

    private fun rebuildCandidates() {
        // Float author-nominated inputs to the top (in catalog priority order).
        val all = ksl.app.swing.common.editor.CatalogLabels.featuredFirst(
            if (controlRadio.isSelected) controlCandidates() else rvCandidates(),
            descriptor?.catalog?.nominatedInputs?.map { it.key } ?: emptyList()
        ) { it.name }
        val filter = filterField.text.trim().lowercase()
        val filtered = if (filter.isEmpty()) all else all.filter { c ->
            c.name.lowercase().contains(filter) || c.label.lowercase().contains(filter)
        }
        listModel.clear()
        filtered.forEach { listModel.addElement(it) }
        filterCountLabel.text = "Showing ${filtered.size} of ${all.size}"
    }

    /** Catalog display name for an input key (suffixed onto a candidate label), or null. */
    private fun displaySuffix(key: String): String {
        val display = descriptor?.catalog?.nominatedInputs
            ?.firstOrNull { it.key == key }?.displayName?.takeIf { it.isNotBlank() }
        return if (display == null) "" else "   ·   $display"
    }

    private fun controlCandidates(): List<Candidate> {
        val d = descriptor ?: return emptyList()
        return d.controls.numericControls.map { c ->
            Candidate(
                name = c.keyName,
                label = "${c.keyName} — type=${c.controlType}" +
                    (if (c.lowerBound.isFinite() || c.upperBound.isFinite())
                        " [${formatBound(c.lowerBound)}, ${formatBound(c.upperBound)}]"
                    else "") + displaySuffix(c.keyName),
                lowerHint = if (c.lowerBound.isFinite()) c.lowerBound else null,
                upperHint = if (c.upperBound.isFinite()) c.upperBound else null,
                granularityHint = if (c.controlType == ControlType.INTEGER) 1.0 else 0.0
            )
        }
    }

    private fun rvCandidates(): List<Candidate> {
        val d = descriptor ?: return emptyList()
        return d.rvParameterData.map { r ->
            val name = "${r.rvName}${RVParameterSetter.rvParamConCatChar}${r.paramName}"
            Candidate(
                name = name,
                label = "$name — current=${r.paramValue}" + displaySuffix(name),
                lowerHint = null,
                upperHint = null,
                granularityHint = 0.0
            )
        }
    }

    private fun preselectCandidate(name: String) {
        for (i in 0 until listModel.size()) {
            if (listModel.get(i).name == name) {
                candidateList.selectedIndex = i
                candidateList.ensureIndexIsVisible(i)
                return
            }
        }
    }

    private fun formatBound(v: Double): String = when {
        v == Double.NEGATIVE_INFINITY -> "-∞"
        v == Double.POSITIVE_INFINITY -> "∞"
        else -> v.toString()
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private fun buildSpecOrNull(): OptimizationInputSpec? {
        val name = nameField.text.trim()
        if (name.isBlank()) return null
        val lower = lowerField.text.trim().toDoubleOrNull() ?: return null
        val upper = upperField.text.trim().toDoubleOrNull() ?: return null
        val granularity = granularityField.text.trim().toDoubleOrNull() ?: return null
        if (!lower.isFinite() || !upper.isFinite() || !granularity.isFinite()) return null
        if (lower >= upper) return null
        if (granularity < 0.0) return null
        if (name in effectiveExistingNames()) return null
        return try {
            OptimizationInputSpec(
                name = name,
                lowerBound = lower,
                upperBound = upper,
                granularity = granularity
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun effectiveExistingNames(): Set<String> = when (mode) {
        is Mode.Add -> existingNames
        is Mode.Edit -> existingNames - mode.spec.name
    }

    private fun refreshOkEnablement() {
        val msg = validationMessage()
        if (msg == null) {
            okButton.isEnabled = true
            statusLabel.text = "Ready"
            statusLabel.foreground = Color(0x2D, 0x6D, 0x40)
        } else {
            okButton.isEnabled = false
            statusLabel.text = msg
            statusLabel.foreground = Color(0xB5, 0x40, 0x40)
        }
    }

    private fun validationMessage(): String? {
        val name = nameField.text.trim()
        if (name.isBlank()) return "Name must be non-blank"
        if (name in effectiveExistingNames()) return "Name '$name' already used"
        val lower = lowerField.text.trim().toDoubleOrNull()
            ?: return "Lower bound must be a number"
        val upper = upperField.text.trim().toDoubleOrNull()
            ?: return "Upper bound must be a number"
        val granularity = granularityField.text.trim().toDoubleOrNull()
            ?: return "Granularity must be a number"
        if (!lower.isFinite()) return "Lower bound must be finite"
        if (!upper.isFinite()) return "Upper bound must be finite"
        if (!granularity.isFinite()) return "Granularity must be finite"
        if (lower >= upper) return "Lower must be strictly less than upper"
        if (granularity < 0.0) return "Granularity must be >= 0"
        return null
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        weighty: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.CENTER,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.weighty = weighty
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }

    /** Mode discriminator — Add vs Edit. */
    sealed class Mode {
        /** Add a new decision variable. */
        object Add : Mode()

        /** Edit the existing variable at this index.  The dialog
         *  pre-populates from [spec] and excludes its name from the
         *  uniqueness check. */
        data class Edit(val index: Int, val spec: OptimizationInputSpec) : Mode()
    }

    /** One row in the candidate list. */
    private data class Candidate(
        val name: String,
        val label: String,
        val lowerHint: Double?,
        val upperHint: Double?,
        val granularityHint: Double
    ) {
        override fun toString(): String = label
    }

    /** Two-line cell renderer.  Top line: the candidate's name (the
     *  leaf segment after the last `:` or `.`, when the full key is
     *  a hierarchy-prefixed path) in normal weight.  Bottom line: the
     *  full label (type + bounds) in a dimmer, smaller font.  Keeps
     *  long model-element-hierarchy names readable. */
    private class CandidateCellRenderer : javax.swing.JPanel(java.awt.BorderLayout()),
        javax.swing.ListCellRenderer<Candidate> {

        private val titleLabel = JLabel().apply {
            font = font.deriveFont(Font.PLAIN, 13f)
        }
        private val detailLabel = JLabel().apply {
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = Color(0x77, 0x77, 0x77)
        }

        init {
            border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
            add(titleLabel, java.awt.BorderLayout.NORTH)
            add(detailLabel, java.awt.BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: javax.swing.JList<out Candidate>,
            value: Candidate,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            titleLabel.text = value.name
            // detail: drop the leading "<name> — " (it's already the
            // title) so the second line only carries type / bounds.
            val detail = value.label.removePrefix("${value.name} — ").trim()
            detailLabel.text = detail.ifEmpty { " " }
            background = if (isSelected) list.selectionBackground else list.background
            titleLabel.foreground = if (isSelected) list.selectionForeground else list.foreground
            isOpaque = true
            return this
        }
    }

    private companion object {
        private fun dialogTitleFor(mode: Mode): String = when (mode) {
            is Mode.Add -> "Add decision variable"
            is Mode.Edit -> "Edit decision variable"
        }
    }
}
