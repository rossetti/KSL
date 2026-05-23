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
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.ManualPointSpec
import ksl.app.config.experiment.StreamPolicy
import ksl.app.swing.common.notification.NotificationSeverity
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel

/**
 *  *Design* tab — choose the design type and stream policy for the
 *  experiment.  Phase E7 (per plan).
 *
 *  Layout (top → bottom):
 *
 *  - **Design type** radio row (Full factorial / Two-level fractional /
 *    Central composite / Manual).
 *  - **Variant sub-panel** swapped by `CardLayout` based on the radio
 *    selection.  Each card binds its widgets to the corresponding
 *    `DesignSpec` variant and pushes updates via
 *    `controller.setDesignSpec`.
 *  - **Stream policy** row at the bottom (Independent / Common Random
 *    Numbers).  When Independent is selected, an *Advanced…* checkbox
 *    exposes the `startingStreamAdvance` + `streamAdvanceSpacing`
 *    fields.
 *
 *  Synchronisation: each card observes both `controller.designSpec`
 *  and `controller.factors` so the live summary text (e.g. point
 *  counts) tracks edits made on the Factors tab.  All widget→
 *  controller writes use a `suppressEvents` guard to avoid feedback
 *  loops when the controller pushes back a fresh value on its flow.
 *
 *  Validation strategy for v1: variant-spec preconditions are enforced
 *  on submit; bad spinner / text-field values are silently clamped or
 *  dropped (no commit).  Deeper design-resolution checks (e.g.
 *  generator aliasing) are deferred to Phase E11 polish — only
 *  parse-level validation lives here, per E7 decision Q1.
 */
class DesignTabPanel(
    private val controller: ExperimentAppController,
    private val onMessage: (String, NotificationSeverity) -> Unit
) : JPanel(BorderLayout(0, 8)) {

    // ---------------------------------------------------------------
    // Design-type radio bar
    // ---------------------------------------------------------------

    private val ffRadio = JRadioButton("Full factorial", true)
    private val frRadio = JRadioButton("Two-level fractional (2^(k-p))")
    private val ccRadio = JRadioButton("Central composite")
    private val manualRadio = JRadioButton("Manual point list")
    private val typeGroup = ButtonGroup().apply {
        add(ffRadio); add(frRadio); add(ccRadio); add(manualRadio)
    }

    // ---------------------------------------------------------------
    // Cards
    // ---------------------------------------------------------------

    private val cards = JPanel(CardLayout())
    private val ffCard = FullFactorialCard()
    private val frCard = FractionalCard()
    private val ccCard = CentralCompositeCard()
    private val manualCard = ManualCard()

    // ---------------------------------------------------------------
    // Stream-policy sub-panel
    // ---------------------------------------------------------------

    private val indepRadio = JRadioButton("Independent (default)", true)
    private val crnRadio = JRadioButton("Common Random Numbers (CRN)")
    private val streamGroup = ButtonGroup().apply { add(indepRadio); add(crnRadio) }
    private val advancedToggle = JCheckBox("Advanced…")
    private val startingAdvanceField = JTextField("0", 6)
    private val spacingField = JTextField("", 6)  // blank ⇒ null ⇒ cumulative
    private val advancedRow: JPanel = buildAdvancedRow()

    // ---------------------------------------------------------------
    // Re-entrancy guard
    // ---------------------------------------------------------------

    @Volatile private var suppressEvents: Boolean = false

    init {
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        layout = BorderLayout(0, 8)

        add(buildTypeBar(), BorderLayout.NORTH)
        add(buildCardsContainer(), BorderLayout.CENTER)
        add(buildStreamPanel(), BorderLayout.SOUTH)

        wireTypeRadios()
        wireStreamRadios()
        wireAdvancedToggle()

        observeControllerFlows()
    }

    // ---------------------------------------------------------------
    // Top bar
    // ---------------------------------------------------------------

    private fun buildTypeBar(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.border = BorderFactory.createTitledBorder("Design type")
        row.add(ffRadio)
        row.add(frRadio)
        row.add(ccRadio)
        row.add(manualRadio)
        return row
    }

    private fun buildCardsContainer(): JPanel {
        cards.add(ffCard, KEY_FF)
        cards.add(frCard, KEY_FR)
        cards.add(ccCard, KEY_CC)
        cards.add(manualCard, KEY_MN)
        val wrapper = JPanel(BorderLayout())
        wrapper.border = BorderFactory.createTitledBorder("Variant settings")
        wrapper.add(cards, BorderLayout.CENTER)
        return wrapper
    }

    private fun wireTypeRadios() {
        ffRadio.addActionListener { if (!suppressEvents) commitTypeSelection() }
        frRadio.addActionListener { if (!suppressEvents) commitTypeSelection() }
        ccRadio.addActionListener { if (!suppressEvents) commitTypeSelection() }
        manualRadio.addActionListener { if (!suppressEvents) commitTypeSelection() }
    }

    /** User picked a new design type from the radio bar.  Push a
     *  default-valued spec of that variant to the controller.  The
     *  per-card observers will populate the widgets from the fresh
     *  spec on the next flow tick. */
    private fun commitTypeSelection() {
        val k = controller.factors.value.size
        val next: DesignSpec = when {
            ffRadio.isSelected -> DesignSpec.FullFactorial()
            frRadio.isSelected -> defaultFractional(k) ?: run {
                onMessage(
                    "Two-level fractional requires at least 2 factors.",
                    NotificationSeverity.WARNING
                )
                applyTypeRadiosFor(controller.designSpec.value)
                return
            }
            ccRadio.isSelected -> DesignSpec.CentralComposite()
            manualRadio.isSelected -> defaultManual(controller.factors.value) ?: run {
                onMessage(
                    "Manual design requires at least 1 factor.",
                    NotificationSeverity.WARNING
                )
                applyTypeRadiosFor(controller.designSpec.value)
                return
            }
            else -> DesignSpec.FullFactorial()
        }
        controller.setDesignSpec(next)
        showCardFor(next)
    }

    private fun defaultFractional(k: Int): DesignSpec.TwoLevelFractional? {
        if (k < 2) return null
        // Pick the smallest meaningful fraction: p = 1, generator =
        // first k letters in order (e.g. k=4 ⇒ "ABCD").  The user can
        // tweak in-place.
        val relation = (0 until k).joinToString("") { ('A' + it).toString() }
        return DesignSpec.TwoLevelFractional(
            numFactors = k,
            fractionExponent = 1,
            definingRelations = listOf(relation)
        )
    }

    private fun defaultManual(factors: List<FactorSpec>): DesignSpec.Manual? {
        if (factors.isEmpty()) return null
        // One seed point at the midpoint of each factor's level range.
        val seed = factors.associate { f ->
            val lo = f.levels.min()
            val hi = f.levels.max()
            f.name to ((lo + hi) / 2.0)
        }
        return DesignSpec.Manual(points = listOf(ManualPointSpec(seed)))
    }

    // ---------------------------------------------------------------
    // Stream-policy panel
    // ---------------------------------------------------------------

    private fun buildStreamPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Random streams")

        val radios = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        radios.add(indepRadio)
        radios.add(crnRadio)
        radios.add(advancedToggle)
        panel.add(radios)

        val help = JLabel(
            "<html><i>CRN reuses the same random-stream block at every design point — " +
                "reduces variance for cross-point comparisons but biases per-point " +
                "standard errors.  Independent (default) gives each point a fresh " +
                "non-overlapping block.</i></html>"
        ).apply {
            border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
            foreground = Color(0x55, 0x55, 0x55)
        }
        panel.add(help)
        panel.add(advancedRow)
        advancedRow.isVisible = false
        return panel
    }

    private fun buildAdvancedRow(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        row.add(JLabel("startingStreamAdvance:"))
        row.add(startingAdvanceField)
        row.add(JLabel("  streamAdvanceSpacing (blank = cumulative):"))
        row.add(spacingField)
        startingAdvanceField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { if (!suppressEvents) commitStreamAdvanced() }
        })
        startingAdvanceField.addActionListener { if (!suppressEvents) commitStreamAdvanced() }
        spacingField.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) { if (!suppressEvents) commitStreamAdvanced() }
        })
        spacingField.addActionListener { if (!suppressEvents) commitStreamAdvanced() }
        return row
    }

    private fun wireStreamRadios() {
        val push = {
            if (!suppressEvents) {
                val next: StreamPolicy = if (crnRadio.isSelected) {
                    StreamPolicy.CommonRandomNumbers
                } else {
                    parseAdvancedOrCurrent()
                }
                controller.setStreamPolicy(next)
                advancedToggle.isEnabled = indepRadio.isSelected
                if (!indepRadio.isSelected) advancedRow.isVisible = false
            }
        }
        indepRadio.addActionListener { push() }
        crnRadio.addActionListener { push() }
    }

    private fun wireAdvancedToggle() {
        advancedToggle.addActionListener {
            advancedRow.isVisible = advancedToggle.isSelected && indepRadio.isSelected
            revalidate()
            repaint()
        }
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

    // ---------------------------------------------------------------
    // Controller observation
    // ---------------------------------------------------------------

    private fun observeControllerFlows() {
        controller.edtScope.launch {
            controller.designSpec.collect { spec ->
                applySpecToUI(spec)
            }
        }
        controller.edtScope.launch {
            controller.streamPolicy.collect { policy ->
                applyStreamPolicyToUI(policy)
            }
        }
        controller.edtScope.launch {
            controller.factors.collect { factors ->
                // Factor-count changes ripple into every card's summary
                // text (and into the manual table's column count).
                ffCard.onFactorsChanged(factors)
                frCard.onFactorsChanged(factors)
                ccCard.onFactorsChanged(factors)
                manualCard.onFactorsChanged(factors)
            }
        }
    }

    private fun applySpecToUI(spec: DesignSpec) {
        suppressEvents = true
        try {
            applyTypeRadiosFor(spec)
            showCardFor(spec)
            when (spec) {
                is DesignSpec.FullFactorial -> ffCard.load(spec)
                is DesignSpec.TwoLevelFractional -> frCard.load(spec)
                is DesignSpec.CentralComposite -> ccCard.load(spec)
                is DesignSpec.Manual -> manualCard.load(spec)
            }
        } finally {
            suppressEvents = false
        }
    }

    private fun applyTypeRadiosFor(spec: DesignSpec) {
        when (spec) {
            is DesignSpec.FullFactorial -> ffRadio.isSelected = true
            is DesignSpec.TwoLevelFractional -> frRadio.isSelected = true
            is DesignSpec.CentralComposite -> ccRadio.isSelected = true
            is DesignSpec.Manual -> manualRadio.isSelected = true
        }
    }

    private fun showCardFor(spec: DesignSpec) {
        val key = when (spec) {
            is DesignSpec.FullFactorial -> KEY_FF
            is DesignSpec.TwoLevelFractional -> KEY_FR
            is DesignSpec.CentralComposite -> KEY_CC
            is DesignSpec.Manual -> KEY_MN
        }
        (cards.layout as CardLayout).show(cards, key)
    }

    private fun applyStreamPolicyToUI(policy: StreamPolicy) {
        suppressEvents = true
        try {
            when (policy) {
                is StreamPolicy.Independent -> {
                    indepRadio.isSelected = true
                    startingAdvanceField.text = policy.startingStreamAdvance.toString()
                    spacingField.text = policy.streamAdvanceSpacing?.toString() ?: ""
                    advancedToggle.isEnabled = true
                }
                is StreamPolicy.CommonRandomNumbers -> {
                    crnRadio.isSelected = true
                    advancedToggle.isEnabled = false
                    advancedRow.isVisible = false
                }
            }
        } finally {
            suppressEvents = false
        }
    }

    // ===============================================================
    // Variant cards (inner classes share the suppressEvents guard
    // and onMessage callback).
    // ===============================================================

    /** Full-factorial card: just the center-point spinner + summary. */
    private inner class FullFactorialCard : JPanel(GridBagLayout()) {
        private val centerSpinner = JSpinner(SpinnerNumberModel(0, 0, 1_000, 1))
        private val summary = JLabel(" ")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 4, 2, 8)
            }
            add(JLabel("Center points:"), gbc)
            gbc.gridx = 1
            add(centerSpinner, gbc)
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2
            add(summary, gbc)

            centerSpinner.addChangeListener {
                if (!suppressEvents) {
                    controller.setDesignSpec(
                        DesignSpec.FullFactorial(centerPoints = centerSpinner.value as Int)
                    )
                }
            }
        }

        fun load(spec: DesignSpec.FullFactorial) {
            centerSpinner.value = spec.centerPoints
            refreshSummary(controller.factors.value, spec.centerPoints)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            val cp = (centerSpinner.value as? Int) ?: 0
            refreshSummary(factors, cp)
        }

        private fun refreshSummary(factors: List<FactorSpec>, centerPoints: Int) {
            if (factors.isEmpty()) {
                summary.text = "<html><i>No factors defined — add factors on the Factors tab.</i></html>"
                return
            }
            val product = factors.fold(1L) { acc, f -> acc * f.levels.size.toLong() }
            val total = product + centerPoints
            summary.text = "<html><b>$total</b> design points " +
                "(${factors.size} factors, ${factors.joinToString(" × ") { it.levels.size.toString() }}" +
                "${if (centerPoints > 0) " + $centerPoints center" else ""})</html>"
        }
    }

    /** Two-level fractional card: k (read-only), p spinner, relations table. */
    private inner class FractionalCard : JPanel(BorderLayout(0, 6)) {
        private val kLabel = JLabel("k = 0")
        private val pSpinner = JSpinner(SpinnerNumberModel(1, 1, 1, 1))
        private val relationsModel = RelationsTableModel()
        private val relationsTable = JTable(relationsModel)
        private val addRowBtn = JButton("Add relation")
        private val delRowBtn = JButton("Delete relation")
        private val summary = JLabel(" ")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

            val top = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
            top.add(kLabel)
            top.add(JLabel("    p (fraction exponent):"))
            top.add(pSpinner)
            add(top, BorderLayout.NORTH)

            relationsTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            relationsTable.fillsViewportHeight = true
            val scroll = JScrollPane(relationsTable)
            scroll.preferredSize = Dimension(360, 120)
            add(scroll, BorderLayout.CENTER)

            val south = JPanel(BorderLayout())
            val btns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            btns.add(addRowBtn)
            btns.add(delRowBtn)
            south.add(btns, BorderLayout.NORTH)
            south.add(summary, BorderLayout.SOUTH)
            add(south, BorderLayout.SOUTH)

            pSpinner.addChangeListener {
                if (!suppressEvents) commitFromUI()
            }
            relationsModel.addTableModelListener {
                if (!suppressEvents) commitFromUI()
            }
            addRowBtn.addActionListener {
                relationsModel.appendBlank()
            }
            delRowBtn.addActionListener {
                val row = relationsTable.selectedRow
                if (row >= 0) relationsModel.removeRow(row)
            }
        }

        fun load(spec: DesignSpec.TwoLevelFractional) {
            kLabel.text = "k = ${spec.numFactors}"
            val pMax = (spec.numFactors - 1).coerceAtLeast(1)
            (pSpinner.model as SpinnerNumberModel).apply {
                maximum = pMax
                value = spec.fractionExponent.coerceIn(1, pMax)
            }
            relationsModel.setRelations(spec.definingRelations)
            refreshSummary(spec)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            kLabel.text = "k = ${factors.size}"
            val curSpec = controller.designSpec.value
            if (curSpec is DesignSpec.TwoLevelFractional && factors.size != curSpec.numFactors) {
                // Factor count drifted — controller already drops the
                // spec via dropRuntimeArtefacts (no spec change) but the
                // spec itself is now stale.  Push a fresh default so
                // we stay consistent.
                if (factors.size >= 2) {
                    val fresh = defaultFractional(factors.size)
                    if (fresh != null && fresh != curSpec) {
                        controller.setDesignSpec(fresh)
                    }
                }
            }
        }

        private fun commitFromUI() {
            val k = controller.factors.value.size
            if (k < 2) return
            val pMax = (k - 1).coerceAtLeast(1)
            val p = ((pSpinner.value as Int).coerceIn(1, pMax))
            val rels = relationsModel.relations()
            // Pad or trim to match p — the spec precondition requires
            // size == p.  We pad with blank generators which will fail
            // validateAndReport below but keep the user moving.
            val padded = when {
                rels.size == p -> rels
                rels.size < p -> rels + List(p - rels.size) { "" }
                else -> rels.take(p)
            }
            // Soft validate first: skip the commit if any relation is
            // empty or contains invalid letters, but flag in summary.
            val invalidReason = validateRelations(padded, k)
            if (invalidReason != null) {
                summary.text = "<html><font color='#a00'>$invalidReason</font></html>"
                return
            }
            try {
                controller.setDesignSpec(
                    DesignSpec.TwoLevelFractional(k, p, padded)
                )
                refreshSummary(
                    DesignSpec.TwoLevelFractional(k, p, padded)
                )
            } catch (ex: IllegalArgumentException) {
                summary.text = "<html><font color='#a00'>${ex.message}</font></html>"
            }
        }

        private fun validateRelations(rels: List<String>, k: Int): String? {
            for ((i, r) in rels.withIndex()) {
                val trimmed = r.trim()
                if (trimmed.isEmpty()) return "Relation #${i + 1} is empty."
                if (!trimmed.all { it in 'A'..'Z' }) {
                    return "Relation #${i + 1} ('$trimmed') must be uppercase letters only."
                }
                if (trimmed.toSet().size != trimmed.length) {
                    return "Relation #${i + 1} ('$trimmed') has duplicate letters."
                }
                val maxLetter = 'A' + (k - 1)
                trimmed.firstOrNull { it > maxLetter }?.let {
                    return "Relation #${i + 1} ('$trimmed') uses letter '$it' beyond k=$k."
                }
            }
            return null
        }

        private fun refreshSummary(spec: DesignSpec.TwoLevelFractional) {
            val nominal = 1L shl (spec.numFactors - spec.fractionExponent)
            summary.text = "<html><b>$nominal</b> design points " +
                "(2^(${spec.numFactors}-${spec.fractionExponent}) fraction)</html>"
        }
    }

    /** Central composite card: axial spacing + center spinner. */
    private inner class CentralCompositeCard : JPanel(GridBagLayout()) {
        private val axialField = JTextField("1.682", 8)
        private val centerSpinner = JSpinner(SpinnerNumberModel(5, 0, 100, 1))
        private val summary = JLabel(" ")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val gbc = GridBagConstraints().apply {
                gridx = 0; gridy = 0
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 4, 2, 8)
            }
            add(JLabel("Axial spacing (α):"), gbc)
            gbc.gridx = 1; add(axialField, gbc)
            gbc.gridx = 0; gbc.gridy = 1
            add(JLabel("Center points:"), gbc)
            gbc.gridx = 1; add(centerSpinner, gbc)
            gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2
            add(summary, gbc)

            axialField.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) { if (!suppressEvents) commitFromUI() }
            })
            axialField.addActionListener { if (!suppressEvents) commitFromUI() }
            centerSpinner.addChangeListener { if (!suppressEvents) commitFromUI() }
        }

        fun load(spec: DesignSpec.CentralComposite) {
            axialField.text = spec.axialSpacing.toString()
            centerSpinner.value = spec.centerPoints
            refreshSummary(controller.factors.value, spec)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            val curSpec = controller.designSpec.value as? DesignSpec.CentralComposite
            if (curSpec != null) refreshSummary(factors, curSpec)
        }

        private fun commitFromUI() {
            val axial = axialField.text.trim().toDoubleOrNull()
            if (axial == null || axial <= 0.0) {
                summary.text = "<html><font color='#a00'>Axial spacing must be a positive number.</font></html>"
                return
            }
            try {
                val spec = DesignSpec.CentralComposite(
                    axialSpacing = axial,
                    centerPoints = centerSpinner.value as Int
                )
                controller.setDesignSpec(spec)
                refreshSummary(controller.factors.value, spec)
            } catch (ex: IllegalArgumentException) {
                summary.text = "<html><font color='#a00'>${ex.message}</font></html>"
            }
        }

        private fun refreshSummary(factors: List<FactorSpec>, spec: DesignSpec.CentralComposite) {
            val k = factors.size
            if (k == 0) {
                summary.text = "<html><i>No factors defined — add factors on the Factors tab.</i></html>"
                return
            }
            val factorial = 1L shl k
            val axials = 2L * k
            val total = factorial + axials + spec.centerPoints
            summary.text = "<html><b>$total</b> design points (2^$k + 2·$k + ${spec.centerPoints} center)</html>"
        }
    }

    /** Manual card: edit-in-place JTable of design points. */
    private inner class ManualCard : JPanel(BorderLayout(0, 6)) {
        private val tableModel = ManualPointsTableModel()
        private val table = JTable(tableModel)
        private val addRowBtn = JButton("Add point")
        private val delRowBtn = JButton("Delete point")
        private val summary = JLabel(" ")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.fillsViewportHeight = true
            table.putClientProperty("terminateEditOnFocusLost", true)
            val scroll = JScrollPane(table)
            scroll.preferredSize = Dimension(420, 180)
            add(scroll, BorderLayout.CENTER)

            val btns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            btns.add(addRowBtn)
            btns.add(delRowBtn)
            add(btns, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)

            tableModel.addTableModelListener {
                if (!suppressEvents) commitFromUI()
            }
            addRowBtn.addActionListener {
                tableModel.appendMidpointRow(controller.factors.value)
            }
            delRowBtn.addActionListener {
                val row = table.selectedRow
                if (row >= 0 && tableModel.rowCount > 1) {
                    tableModel.removeRow(row)
                } else if (tableModel.rowCount <= 1) {
                    onMessage(
                        "Manual design must keep at least one point.",
                        NotificationSeverity.WARNING
                    )
                }
            }
        }

        fun load(spec: DesignSpec.Manual) {
            tableModel.setFactorsAndPoints(controller.factors.value, spec.points)
            refreshSummary(spec.points.size)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            val curSpec = controller.designSpec.value as? DesignSpec.Manual ?: return
            // Re-shape table columns to match the new factor set.
            // Existing rows keep matching factor values; missing
            // factors get midpoint defaults; removed factors drop out.
            val rebuilt = curSpec.points.map { point ->
                val reshaped = factors.associate { f ->
                    val existing = point.factorValues[f.name]
                    val fallback = (f.levels.min() + f.levels.max()) / 2.0
                    f.name to (existing ?: fallback)
                }
                if (reshaped.isEmpty()) point else point.copy(factorValues = reshaped)
            }.filter { it.factorValues.isNotEmpty() }
            tableModel.setFactorsAndPoints(factors, rebuilt)
            if (rebuilt.isNotEmpty() && factors.isNotEmpty()) {
                // Push the reshape back through the controller so the
                // persisted spec matches the table.  Skip if nothing
                // changed (avoids a feedback loop).
                val next = DesignSpec.Manual(rebuilt)
                if (next != curSpec) controller.setDesignSpec(next)
            }
        }

        private fun commitFromUI() {
            val factors = controller.factors.value
            if (factors.isEmpty()) return
            val points = tableModel.toPoints()
            if (points.isEmpty()) return  // Manual requires >= 1 point
            try {
                controller.setDesignSpec(DesignSpec.Manual(points))
                refreshSummary(points.size)
            } catch (ex: IllegalArgumentException) {
                summary.text = "<html><font color='#a00'>${ex.message}</font></html>"
            }
        }

        private fun refreshSummary(rows: Int) {
            summary.text = "<html><b>$rows</b> manual design point${if (rows == 1) "" else "s"}</html>"
        }
    }

    // ---------------------------------------------------------------
    // Table model for the fractional-design relations.
    // ---------------------------------------------------------------

    private class RelationsTableModel : AbstractTableModel() {
        private val rows: MutableList<String> = mutableListOf()

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 1
        override fun getColumnName(column: Int): String = "Generator (e.g. ABCD)"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            rows[rowIndex] = (aValue as? String ?: "").trim().uppercase()
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        fun appendBlank() {
            rows += ""
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun removeRow(index: Int) {
            if (index in rows.indices) {
                rows.removeAt(index)
                fireTableRowsDeleted(index, index)
            }
        }

        fun relations(): List<String> = rows.toList()

        fun setRelations(next: List<String>) {
            rows.clear()
            rows.addAll(next)
            fireTableDataChanged()
        }
    }

    // ---------------------------------------------------------------
    // Table model for the Manual design points.
    // ---------------------------------------------------------------

    private class ManualPointsTableModel : AbstractTableModel() {
        private var factorNames: List<String> = emptyList()
        private val rows: MutableList<MutableMap<String, Double>> = mutableListOf()
        private val reps: MutableList<Int?> = mutableListOf()

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = factorNames.size + 1
        override fun getColumnName(column: Int): String =
            if (column < factorNames.size) factorNames[column] else "reps (blank = default)"
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex < factorNames.size) java.lang.Double::class.java else String::class.java
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            return if (columnIndex < factorNames.size) {
                rows[rowIndex][factorNames[columnIndex]] ?: 0.0
            } else {
                reps[rowIndex]?.toString() ?: ""
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex < factorNames.size) {
                val parsed = when (aValue) {
                    is Number -> aValue.toDouble()
                    is String -> aValue.trim().toDoubleOrNull()
                    else -> null
                }
                if (parsed != null) {
                    rows[rowIndex][factorNames[columnIndex]] = parsed
                    fireTableCellUpdated(rowIndex, columnIndex)
                }
            } else {
                val s = (aValue as? String ?: "").trim()
                val newReps = if (s.isEmpty()) null else s.toIntOrNull()?.coerceAtLeast(1)
                reps[rowIndex] = newReps
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        fun setFactorsAndPoints(factors: List<FactorSpec>, points: List<ManualPointSpec>) {
            factorNames = factors.map { it.name }
            rows.clear()
            reps.clear()
            for (p in points) {
                val row = mutableMapOf<String, Double>()
                for (f in factors) row[f.name] = p.factorValues[f.name]
                    ?: ((f.levels.min() + f.levels.max()) / 2.0)
                rows += row
                reps += p.replications
            }
            fireTableStructureChanged()
        }

        fun appendMidpointRow(factors: List<FactorSpec>) {
            if (factors.isEmpty()) return
            val row = mutableMapOf<String, Double>()
            for (f in factors) row[f.name] = (f.levels.min() + f.levels.max()) / 2.0
            rows += row
            reps += null
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun removeRow(index: Int) {
            if (index in rows.indices) {
                rows.removeAt(index)
                reps.removeAt(index)
                fireTableRowsDeleted(index, index)
            }
        }

        fun toPoints(): List<ManualPointSpec> {
            return rows.indices.mapNotNull { i ->
                val values = rows[i].toMap()
                if (values.isEmpty()) null
                else ManualPointSpec(factorValues = values, replications = reps[i])
            }
        }
    }

    companion object {
        private const val KEY_FF = "fullFactorial"
        private const val KEY_FR = "twoLevelFractional"
        private const val KEY_CC = "centralComposite"
        private const val KEY_MN = "manual"
    }

    // Keep a handle so tests / debug code can dump the layout without
    // reflection.  Currently unused by production code; the alignment
    // utility below silences IDE warnings about unused imports.
    @Suppress("unused")
    private val alignmentAnchor: Int = SwingConstants.WEST

    @Suppress("unused")
    private val componentAlignmentAnchor: Component? = null
}
