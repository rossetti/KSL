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
import ksl.app.config.experiment.AxialSpacing
import ksl.app.config.experiment.DesignSpec
import ksl.app.config.experiment.FactorSpec
import ksl.app.config.experiment.Fraction
import ksl.app.config.experiment.ManualCsvImportResult
import ksl.app.config.experiment.ManualPointSpec
import ksl.app.config.experiment.ReplicationSpec
import ksl.app.config.experiment.parseManualCsv
import ksl.app.notification.NotificationSink
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JRadioButton
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SpinnerNumberModel
import javax.swing.table.AbstractTableModel

/** Outcome of parsing a single comma-separated word string into a
 *  list of 1-based factor indices.  File-scope so it can be nested
 *  through `inner class` boundaries (sealed classes can't live
 *  inside an `inner class`). */
private sealed class WordParseResult {
    data class Ok(val indices: List<Int>) : WordParseResult()
    data class Err(val message: String) : WordParseResult()
}

/**
 *  *Design* tab — choose the design family, configure its settings,
 *  and set the document-level replications + random-stream policy.
 *  Phase E7.1 redesign (per plan, re-aligned to the substrate's
 *  construction API in `ksl.controls.experiments`).
 *
 *  Layout (top to bottom, all sections visible at once):
 *
 *  1. **Design family** — radio row picking one of:
 *     `Full factorial`, `Two-level factorial`, `Central composite`,
 *     `Custom design points`.  Switching the radio swaps the
 *     family-specific strip below.
 *
 *  2. **Family settings** — a compact inline strip whose widgets
 *     match the substrate's construction surface for the chosen
 *     family:
 *
 *     - Full factorial: a one-line summary ("k = N factors -> P
 *       design points x R reps = T runs").  No inputs; reps come
 *       from the Replications row.
 *     - Two-level factorial: fraction picker (Full / Half / Custom)
 *       and, when Custom, an editable relations table; live summary.
 *     - Central composite: axial spacing (Rotatable / Explicit),
 *       three rep spinners (numFactorialReps / numAxialReps /
 *       numCenterReps), optional factorial fraction picker, live
 *       summary.  The CCD row hides the document Replications panel
 *       because the spec's three knobs already cover that ground —
 *       see DesignSpec KDoc.
 *     - Custom: edit-in-place table of design points with per-point
 *       reps; Add / Delete row.
 *
 *  3. **Replications** — Uniform / PerPoint radio + default-reps
 *     spinner.  PerPoint's index-keyed overrides will be edited
 *     on the Phase E8 *Design Points* tab when it lands.  Hidden
 *     when the family is CentralComposite (which carries its own
 *     three-way split).
 *
 *  4. **Random streams** — Independent / CRN radio with the
 *     Advanced disclosure exposing startingStreamAdvance +
 *     streamAdvanceSpacing.
 *
 *  Synchronisation: every section observes the relevant controller
 *  flow (`designSpec`, `replications`, `streamPolicy`, `factors`).
 *  Widget -> controller writes are guarded by a `suppressEvents`
 *  flag so flow callbacks don't trigger feedback loops.
 *
 *  Validation strategy for v1: variant-spec preconditions guard
 *  invalid commits (e.g. negative spinner values are clamped or
 *  ignored).  Deeper checks (fractional resolution, level-range
 *  validation) are the substrate's job at submit time and surface
 *  through the orchestrator's run-state messages.
 */
class DesignTabPanel(
    private val controller: ExperimentAppController,
    private val notifier: NotificationSink
) : JPanel(BorderLayout(0, 8)) {

    // -- Design family — sub-tabs (E7.10).  Each sub-tab IS the
    //    settings for one design family; switching tabs IS switching
    //    the family.  Replaces the prior radio bar + CardLayout +
    //    "Family settings" titled border.

    private val ffCard = FullFactorialCard()
    private val tlfCard = TwoLevelFactorialCard()
    private val ccdCard = CentralCompositeCard()
    private val manualCard = ManualCard()
    private val familyTabs = javax.swing.JTabbedPane().apply {
        addTab("Full factorial", ffCard)
        addTab("Two-level factorial", tlfCard)
        addTab("Central composite", ccdCard)
        addTab("Custom design points", manualCard)
    }

    // -- Replications panel --

    private val repsUniformRadio = JRadioButton("Uniform", true)
    private val repsPerPointRadio = JRadioButton("Per-point")
    private val repsGroup = ButtonGroup().apply { add(repsUniformRadio); add(repsPerPointRadio) }
    private val repsSpinner = JSpinner(SpinnerNumberModel(10, 1, 1_000_000, 1))
    private val repsHelp = JLabel(" ").apply { foreground = Color(0x55, 0x55, 0x55) }
    private val replicationsPanel: JPanel = buildReplicationsPanel()

    // -- Stream-policy panel --

    // Random streams widgets moved to SimulateTabPanel in E7.10 —
    // stream policy is a runtime execution choice (variance-
    // reduction technique), not a design specification.

    // perPointSubdirsCheckbox moved to SimulateTabPanel's Run options
    // box in E7.10 — per-point output layout is a runtime/output
    // preference, not a design specification.

    @Volatile private var suppressEvents: Boolean = false

    init {
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        layout = BorderLayout(0, 8)

        // Single vertical stack inside a scroll pane so the tab
        // remains fully reachable when the window is short (console
        // open, user resized smaller, etc.).  The previous BorderLayout
        // NORTH+SOUTH split silently clipped SOUTH when NORTH consumed
        // the available height.
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            familyTabs.alignmentX = LEFT_ALIGNMENT
            add(familyTabs)
            add(Box.createVerticalStrut(8))
            replicationsPanel.alignmentX = LEFT_ALIGNMENT
            add(replicationsPanel)
            add(Box.createVerticalStrut(6))
            // Materialize button moved here in E7.10 — one button at
            // the bottom of the Design tab, OUTSIDE the family sub-
            // tabs (per-tab placement would have been awkward).
            buildPreviewBar().also { it.alignmentX = LEFT_ALIGNMENT }.let { add(it) }
            // A glue at the bottom keeps the stack top-anchored when
            // the scroll pane has more vertical space than the content
            // needs (avoids vertical centering, which looks wrong).
            add(Box.createVerticalGlue())
        }
        val scroll = JScrollPane(
            content,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = BorderFactory.createEmptyBorder()
            // Smoother mouse-wheel feel than the default 1-unit step.
            verticalScrollBar.unitIncrement = 16
        }
        add(scroll, BorderLayout.CENTER)

        // Edited / Saved badge (shared widget across authoring tabs).
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        footer.add(DocumentStateLabel(controller.isDirty, controller.edtScope))
        add(footer, BorderLayout.SOUTH)

        wireFamilyTabs()
        wireReplications()
        // wireStreamRadios() / wireAdvancedToggle() moved to SimulateTabPanel
        observeControllerFlows()
    }

    // ───────────────────────────────────────────────────────────────
    // Family radio bar + cards container
    // ───────────────────────────────────────────────────────────────

    private fun wireFamilyTabs() {
        familyTabs.addChangeListener {
            if (!suppressEvents) commitFamilySelection()
        }
    }

    /** User picked a new design family by clicking a sub-tab.  Push
     *  a default-valued spec of that family to the controller; the
     *  per-card observer will populate the widgets on the next flow
     *  tick.  When a family has prerequisites (e.g. two-level
     *  factors), validate first and revert the tab with a
     *  notification if they're not met. */
    private fun commitFamilySelection() {
        val factors = controller.factors.value
        val next: DesignSpec = when (familyTabs.selectedIndex) {
            TAB_FF -> DesignSpec.FullFactorial
            TAB_TLF -> {
                if (!factorsAreTwoLevel(factors)) {
                    notifier.warn(
                        "Two-level factorial requires every factor to have exactly 2 levels."
                    )
                    applyFamilySelectionFor(controller.designSpec.value)
                    return
                }
                DesignSpec.TwoLevelFactorial(fraction = Fraction.Full)
            }
            TAB_CCD -> {
                if (!factorsAreTwoLevel(factors) || factors.size < 2) {
                    notifier.warn(
                        "Central composite requires at least 2 factors with exactly 2 levels each."
                    )
                    applyFamilySelectionFor(controller.designSpec.value)
                    return
                }
                DesignSpec.CentralComposite(axialSpacing = AxialSpacing.Rotatable)
            }
            TAB_MN -> {
                if (factors.isEmpty()) {
                    notifier.warn(
                        "Custom design points require at least 1 factor."
                    )
                    applyFamilySelectionFor(controller.designSpec.value)
                    return
                }
                DesignSpec.Manual(points = listOf(firstLevelPoint(factors)))
            }
            else -> DesignSpec.FullFactorial
        }
        controller.setDesignSpec(next)
        updateReplicationsVisibility(next)
    }

    private fun factorsAreTwoLevel(factors: List<FactorSpec>): Boolean =
        factors.isNotEmpty() && factors.all { it.levels.size == 2 }

    /** Seed point when the user switches to Custom design points
     *  from another family — pinned at each factor's first declared
     *  level so the seed is always a valid level (never triggers the
     *  "not one of the declared levels" warning).  The user can edit
     *  in-place. */
    private fun firstLevelPoint(factors: List<FactorSpec>): ManualPointSpec {
        val values = factors.associate { f -> f.name to f.levels.first() }
        return ManualPointSpec(values)
    }

    // ───────────────────────────────────────────────────────────────
    // Replications panel
    // ───────────────────────────────────────────────────────────────

    private fun buildReplicationsPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.border = BorderFactory.createTitledBorder("Replications")

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.add(repsUniformRadio)
        row.add(repsPerPointRadio)
        row.add(JLabel("    default reps:"))
        row.add(repsSpinner)
        panel.add(row)
        panel.add(repsHelp)
        return panel
    }

    private fun wireReplications() {
        val push = {
            if (!suppressEvents) commitReplications()
        }
        repsUniformRadio.addActionListener { push() }
        repsPerPointRadio.addActionListener { push() }
        repsSpinner.addChangeListener { push() }
    }

    private fun commitReplications() {
        val n = (repsSpinner.value as? Int)?.coerceAtLeast(1) ?: 10
        val next: ReplicationSpec = if (repsPerPointRadio.isSelected) {
            // Keep any existing overrides intact when only the
            // default changed.  When switching from Uniform, start
            // with an empty overrides map (the Design Points tab
            // will let the user populate it once E8 lands).
            val cur = controller.replications.value
            val overrides = (cur as? ReplicationSpec.PerPoint)?.overrides ?: emptyMap()
            ReplicationSpec.PerPoint(default = n, overrides = overrides)
        } else {
            ReplicationSpec.Uniform(replications = n)
        }
        controller.setReplications(next)
    }

    private fun updateReplicationsVisibility(spec: DesignSpec) {
        // CCD's three rep knobs are authoritative for its points;
        // hide the document-level Replications panel to make that
        // explicit (the panel still exists in state and round-trips
        // through TOML).
        replicationsPanel.isVisible = spec !is DesignSpec.CentralComposite
        if (spec is DesignSpec.CentralComposite) {
            repsHelp.text = ""
        }
        revalidate()
        repaint()
    }

    // ───────────────────────────────────────────────────────────────
    // Stream-policy panel
    // ───────────────────────────────────────────────────────────────

    private fun buildPreviewBar(): JPanel {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        row.border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        val previewBtn = JButton("View design points...")
        previewBtn.toolTipText = "Enumerate the design points implied by the current " +
            "factors + design spec.  Lets you review the points and (when " +
            "ReplicationSpec.PerPoint is the policy) edit per-row reps overrides."
        previewBtn.addActionListener { openPreviewDialog() }
        row.add(previewBtn)
        return row
    }

    private fun openPreviewDialog() {
        val factors = controller.factors.value
        if (factors.isEmpty()) {
            notifier.warn(
                "Add at least one factor on the Factors tab before previewing the design."
            )
            return
        }
        try {
            val owner = javax.swing.SwingUtilities.getWindowAncestor(this)
            val dlg = DesignPointsPreviewDialog(owner, controller, notifier)
            dlg.isVisible = true
        } catch (ex: Exception) {
            notifier.error(
                "Could not materialize design: ${ex.message ?: ex::class.simpleName}"
            )
        }
    }

    // ───────────────────────────────────────────────────────────────
    // Controller observation
    // ───────────────────────────────────────────────────────────────

    private fun observeControllerFlows() {
        controller.edtScope.launch {
            controller.designSpec.collect { spec -> applySpecToUI(spec) }
        }
        controller.edtScope.launch {
            controller.replications.collect { rep -> applyRepsToUI(rep) }
        }
        controller.edtScope.launch {
            controller.factors.collect { factors ->
                ffCard.onFactorsChanged(factors)
                tlfCard.onFactorsChanged(factors)
                ccdCard.onFactorsChanged(factors)
                manualCard.onFactorsChanged(factors)
                refreshFamilyEnablement(factors)
            }
        }
        // experimentOutput collector moved to SimulateTabPanel in
        // E7.10 along with the perPointSubdirsCheckbox widget.
    }

    private fun refreshFamilyEnablement(factors: List<FactorSpec>) {
        val twoLevel = factorsAreTwoLevel(factors)
        familyTabs.setEnabledAt(TAB_TLF, twoLevel)
        familyTabs.setEnabledAt(TAB_CCD, twoLevel && factors.size >= 2)
        familyTabs.setEnabledAt(TAB_MN, factors.isNotEmpty())
        // Per-tab tooltips explain why a disabled tab is disabled.
        familyTabs.setToolTipTextAt(TAB_TLF,
            if (twoLevel) null
            else "Requires every factor to have exactly 2 levels.")
        familyTabs.setToolTipTextAt(TAB_CCD,
            if (twoLevel && factors.size >= 2) null
            else "Requires at least 2 factors with exactly 2 levels each.")
        familyTabs.setToolTipTextAt(TAB_MN,
            if (factors.isNotEmpty()) null
            else "Requires at least 1 factor.")
    }

    private fun applySpecToUI(spec: DesignSpec) {
        suppressEvents = true
        try {
            applyFamilySelectionFor(spec)
            when (spec) {
                is DesignSpec.FullFactorial      -> ffCard.load()
                is DesignSpec.TwoLevelFactorial  -> tlfCard.load(spec)
                is DesignSpec.CentralComposite   -> ccdCard.load(spec)
                is DesignSpec.Manual             -> manualCard.load(spec)
            }
            updateReplicationsVisibility(spec)
        } finally {
            suppressEvents = false
        }
    }

    private fun applyFamilySelectionFor(spec: DesignSpec) {
        val idx = when (spec) {
            is DesignSpec.FullFactorial     -> TAB_FF
            is DesignSpec.TwoLevelFactorial -> TAB_TLF
            is DesignSpec.CentralComposite  -> TAB_CCD
            is DesignSpec.Manual            -> TAB_MN
        }
        if (familyTabs.selectedIndex != idx) {
            familyTabs.selectedIndex = idx
        }
    }

    private fun applyRepsToUI(rep: ReplicationSpec) {
        suppressEvents = true
        try {
            when (rep) {
                is ReplicationSpec.Uniform -> {
                    repsUniformRadio.isSelected = true
                    repsSpinner.value = rep.replications
                    repsHelp.text = ""
                }
                is ReplicationSpec.PerPoint -> {
                    repsPerPointRadio.isSelected = true
                    repsSpinner.value = rep.default
                    val n = rep.overrides.size
                    repsHelp.text = if (n == 0) {
                        "<html><i>No per-point overrides yet — edit them on the " +
                            "<b>Design Points</b> tab once it lands (Phase E8).</i></html>"
                    } else {
                        "<html><i>$n per-point override${if (n == 1) "" else "s"} active.</i></html>"
                    }
                }
            }
        } finally {
            suppressEvents = false
        }
    }

    // applyStreamPolicyToUI moved to SimulateTabPanel (E7.10).

    // ===============================================================
    // Family cards
    // ===============================================================

    /** Full factorial: no inputs, just a live summary line. */
    private inner class FullFactorialCard : JPanel(BorderLayout()) {
        private val summary = JLabel(" ")
        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(summary, BorderLayout.CENTER)
        }
        fun load() { refreshSummary(controller.factors.value) }
        fun onFactorsChanged(factors: List<FactorSpec>) { refreshSummary(factors) }

        private fun refreshSummary(factors: List<FactorSpec>) {
            if (factors.isEmpty()) {
                summary.text = "<html><i>No factors defined " +
                    "&mdash; add factors on the Factors tab.</i></html>"
                return
            }
            val product = factors.fold(1L) { acc, f -> acc * f.levels.size.toLong() }
            val levelsCsv = factors.joinToString(" x ") { it.levels.size.toString() }
            summary.text = "<html><b>$product</b> design points " +
                "(${factors.size} factors: $levelsCsv).  Per-point replications " +
                "come from the <b>Replications</b> panel below.</html>"
        }
    }

    /** Two-level factorial: fraction picker + (when Custom) words table + summary. */
    private inner class TwoLevelFactorialCard : JPanel(BorderLayout(0, 6)) {
        private val fullRadio = JRadioButton("Full 2^k", true)
        private val halfRadio = JRadioButton("Half-fraction")
        private val customRadio = JRadioButton("Custom defining relation")
        private val fracGroup = ButtonGroup().apply {
            add(fullRadio); add(halfRadio); add(customRadio)
        }
        private val halfSignRadio = JRadioButton("+I (principal)", true)
        private val halfSignMinusRadio = JRadioButton("-I (alternate)")
        private val halfSignGroup = ButtonGroup().apply {
            add(halfSignRadio); add(halfSignMinusRadio)
        }
        private val customSignRadio = JRadioButton("+I", true)
        private val customSignMinusRadio = JRadioButton("-I")
        private val customSignGroup = ButtonGroup().apply {
            add(customSignRadio); add(customSignMinusRadio)
        }
        private val relationsModel = RelationsTableModel()
        private val relationsTable = JTable(relationsModel)
        private val addRelBtn = JButton("Add word")
        private val delRelBtn = JButton("Delete word")
        private val summary = JLabel(" ")

        private val halfRow: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JLabel("sign:")); add(halfSignRadio); add(halfSignMinusRadio)
        }
        private val customPanel: JPanel = buildCustomPanel()

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val north = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
                row.add(fullRadio); row.add(halfRadio); row.add(customRadio)
                add(row)
                add(halfRow)
                add(customPanel)
            }
            add(north, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)

            halfRow.isVisible = false
            customPanel.isVisible = false

            val push = { if (!suppressEvents) commitFromUI() }
            fullRadio.addActionListener { syncSubPanels(); push() }
            halfRadio.addActionListener { syncSubPanels(); push() }
            customRadio.addActionListener { syncSubPanels(); push() }
            halfSignRadio.addActionListener { push() }
            halfSignMinusRadio.addActionListener { push() }
            customSignRadio.addActionListener { push() }
            customSignMinusRadio.addActionListener { push() }
            relationsModel.addTableModelListener { if (!suppressEvents) commitFromUI() }
            addRelBtn.addActionListener { relationsModel.appendBlank() }
            delRelBtn.addActionListener {
                val row = relationsTable.selectedRow
                if (row >= 0) relationsModel.removeRow(row)
            }
        }

        private fun buildCustomPanel(): JPanel {
            val panel = JPanel(BorderLayout(0, 4))
            val north = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            north.add(JLabel("Defining relation (one word per row, e.g. ABCD):"))
            north.add(JLabel("    sign:"))
            north.add(customSignRadio)
            north.add(customSignMinusRadio)
            panel.add(north, BorderLayout.NORTH)
            relationsTable.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            relationsTable.fillsViewportHeight = true
            val scroll = JScrollPane(relationsTable)
            scroll.preferredSize = Dimension(280, 96)
            panel.add(scroll, BorderLayout.CENTER)
            val south = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            south.add(addRelBtn); south.add(delRelBtn)
            panel.add(south, BorderLayout.SOUTH)
            return panel
        }

        private fun syncSubPanels() {
            halfRow.isVisible = halfRadio.isSelected
            customPanel.isVisible = customRadio.isSelected
            revalidate()
            repaint()
        }

        fun load(spec: DesignSpec.TwoLevelFactorial) {
            when (val f = spec.fraction) {
                Fraction.Full -> { fullRadio.isSelected = true }
                is Fraction.HalfFraction -> {
                    halfRadio.isSelected = true
                    if (f.sign == 1) halfSignRadio.isSelected = true
                    else halfSignMinusRadio.isSelected = true
                }
                is Fraction.Custom -> {
                    customRadio.isSelected = true
                    relationsModel.setWords(f.words.map { it.joinToString(", ") })
                    if (f.sign == 1) customSignRadio.isSelected = true
                    else customSignMinusRadio.isSelected = true
                }
            }
            syncSubPanels()
            refreshSummary(controller.factors.value, spec.fraction)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            val cur = controller.designSpec.value
            if (cur is DesignSpec.TwoLevelFactorial) refreshSummary(factors, cur.fraction)
        }

        private fun commitFromUI() {
            val factors = controller.factors.value
            if (!factorsAreTwoLevel(factors)) {
                summary.text = "<html><font color='#a00'>Every factor must have exactly 2 levels.</font></html>"
                return
            }
            val fraction: Fraction = when {
                fullRadio.isSelected -> Fraction.Full
                halfRadio.isSelected -> Fraction.HalfFraction(
                    sign = if (halfSignRadio.isSelected) +1 else -1
                )
                customRadio.isSelected -> {
                    val rawRows = relationsModel.words()
                    if (rawRows.isEmpty()) {
                        summary.text = "<html><font color='#a00'>" +
                            "Add at least one word to the defining relation.</font></html>"
                        return
                    }
                    val parsed = mutableListOf<List<Int>>()
                    for ((i, raw) in rawRows.withIndex()) {
                        val parseResult = parseWord(raw, i + 1, factors.size)
                        when (parseResult) {
                            is WordParseResult.Ok -> parsed += parseResult.indices
                            is WordParseResult.Err -> {
                                summary.text = "<html><font color='#a00'>" +
                                    parseResult.message + "</font></html>"
                                return
                            }
                        }
                    }
                    if (parsed.size >= factors.size) {
                        summary.text = "<html><font color='#a00'>" +
                            "Number of words (${parsed.size}) must be < k = ${factors.size}." +
                            "</font></html>"
                        return
                    }
                    Fraction.Custom(
                        words = parsed,
                        sign = if (customSignRadio.isSelected) +1 else -1
                    )
                }
                else -> Fraction.Full
            }
            try {
                val spec = DesignSpec.TwoLevelFactorial(fraction = fraction)
                controller.setDesignSpec(spec)
                refreshSummary(factors, fraction)
            } catch (ex: IllegalArgumentException) {
                summary.text = "<html><font color='#a00'>${ex.message}</font></html>"
            }
        }

        private fun parseWord(raw: String, ordinal: Int, k: Int): WordParseResult {
            val tokens = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (tokens.isEmpty()) return WordParseResult.Err("Word #$ordinal is empty.")
            val parsed = mutableListOf<Int>()
            val seen = mutableSetOf<Int>()
            for (t in tokens) {
                val n = t.toIntOrNull()
                if (n == null) {
                    return WordParseResult.Err(
                        "Word #$ordinal token '$t' is not an integer."
                    )
                }
                if (n < 1 || n > k) {
                    return WordParseResult.Err(
                        "Word #$ordinal index $n is outside the legal range 1..$k."
                    )
                }
                if (!seen.add(n)) {
                    return WordParseResult.Err(
                        "Word #$ordinal repeats index $n."
                    )
                }
                parsed += n
            }
            return WordParseResult.Ok(parsed.sorted())
        }

        private fun refreshSummary(factors: List<FactorSpec>, fraction: Fraction) {
            val k = factors.size
            if (k == 0) {
                summary.text = "<html><i>No factors defined.</i></html>"
                return
            }
            val p = if (fraction is Fraction.Custom) fraction.words.size else 0
            val nominal: Long = when (fraction) {
                Fraction.Full -> 1L shl k
                is Fraction.HalfFraction -> (1L shl k) / 2
                is Fraction.Custom -> 1L shl (k - p).coerceAtLeast(0)
            }
            val tag = when (fraction) {
                Fraction.Full -> "full 2^$k"
                is Fraction.HalfFraction -> "half-fraction"
                is Fraction.Custom -> "2^($k-$p) fraction"
            }
            summary.text = "<html><b>$nominal</b> design points ($tag).  " +
                "Per-point replications come from the <b>Replications</b> panel below.</html>"
        }
    }

    /** Central composite: axial spacing + three rep spinners + summary.
     *  Always uses a full 2^k factorial core; fractional cores are a
     *  substrate capability deferred to Phase E11 polish. */
    private inner class CentralCompositeCard : JPanel(GridBagLayout()) {
        private val rotatableRadio = JRadioButton("Rotatable", true)
        private val explicitRadio = JRadioButton("Explicit")
        private val spacingGroup = ButtonGroup().apply { add(rotatableRadio); add(explicitRadio) }
        private val explicitField = JTextField("1.682", 6)
        private val numFactorialSpinner = JSpinner(SpinnerNumberModel(1, 1, 100, 1))
        private val numAxialSpinner     = JSpinner(SpinnerNumberModel(1, 1, 100, 1))
        private val numCenterSpinner    = JSpinner(SpinnerNumberModel(1, 1, 100, 1))
        private val summary = JLabel(" ")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 4, 2, 8)
            }
            var row = 0
            gbc.gridx = 0; gbc.gridy = row
            add(JLabel("Axial spacing (α):"), gbc)
            gbc.gridx = 1
            val spacingRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            spacingRow.add(rotatableRadio); spacingRow.add(explicitRadio); spacingRow.add(explicitField)
            add(spacingRow, gbc)
            row++

            gbc.gridx = 0; gbc.gridy = row
            add(JLabel("Replications:"), gbc)
            gbc.gridx = 1
            val repsRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            repsRow.add(JLabel("factorial"))
            repsRow.add(numFactorialSpinner)
            repsRow.add(JLabel("  axial"))
            repsRow.add(numAxialSpinner)
            repsRow.add(JLabel("  centre"))
            repsRow.add(numCenterSpinner)
            add(repsRow, gbc)
            row++

            gbc.gridx = 0; gbc.gridy = row; gbc.gridwidth = 2
            add(summary, gbc)

            // Listeners
            val push = { if (!suppressEvents) commitFromUI() }
            rotatableRadio.addActionListener  { push() }
            explicitRadio.addActionListener   { push() }
            explicitField.addFocusListener(object : FocusAdapter() {
                override fun focusLost(e: FocusEvent) { push() }
            })
            explicitField.addActionListener   { push() }
            numFactorialSpinner.addChangeListener { push() }
            numAxialSpinner.addChangeListener     { push() }
            numCenterSpinner.addChangeListener    { push() }
        }

        fun load(spec: DesignSpec.CentralComposite) {
            when (val s = spec.axialSpacing) {
                AxialSpacing.Rotatable -> rotatableRadio.isSelected = true
                is AxialSpacing.Explicit -> {
                    explicitRadio.isSelected = true
                    explicitField.text = s.value.toString()
                }
            }
            numFactorialSpinner.value = spec.numFactorialReps
            numAxialSpinner.value     = spec.numAxialReps
            numCenterSpinner.value    = spec.numCenterReps
            refreshSummary(controller.factors.value, spec)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            val cur = controller.designSpec.value as? DesignSpec.CentralComposite ?: return
            refreshSummary(factors, cur)
        }

        private fun commitFromUI() {
            val factors = controller.factors.value
            if (!factorsAreTwoLevel(factors) || factors.size < 2) {
                summary.text = "<html><font color='#a00'>" +
                    "CCD requires at least 2 factors with exactly 2 levels each.</font></html>"
                return
            }
            val axial: AxialSpacing = if (explicitRadio.isSelected) {
                val v = explicitField.text.trim().toDoubleOrNull()
                if (v == null || v <= 0.0) {
                    summary.text = "<html><font color='#a00'>" +
                        "Explicit axial spacing must be a positive number.</font></html>"
                    return
                }
                AxialSpacing.Explicit(v)
            } else AxialSpacing.Rotatable

            try {
                val spec = DesignSpec.CentralComposite(
                    axialSpacing = axial,
                    numFactorialReps = numFactorialSpinner.value as Int,
                    numAxialReps     = numAxialSpinner.value as Int,
                    numCenterReps    = numCenterSpinner.value as Int
                )
                controller.setDesignSpec(spec)
                refreshSummary(factors, spec)
            } catch (ex: IllegalArgumentException) {
                summary.text = "<html><font color='#a00'>${ex.message}</font></html>"
            }
        }

        private fun refreshSummary(factors: List<FactorSpec>, spec: DesignSpec.CentralComposite) {
            val k = factors.size
            if (k == 0) {
                summary.text = "<html><i>No factors defined.</i></html>"
                return
            }
            val factorialPoints: Long = 1L shl k
            val axials = 2L * k
            val totalRuns = factorialPoints * spec.numFactorialReps +
                axials * spec.numAxialReps + spec.numCenterReps
            summary.text = "<html><b>${factorialPoints + axials + 1}</b> design points " +
                "($factorialPoints factorial + $axials axial + 1 centre).  " +
                "<b>$totalRuns</b> total runs " +
                "(${spec.numFactorialReps}+${spec.numAxialReps}+${spec.numCenterReps} reps).</html>"
        }
    }

    /** Custom design points: edit-in-place JTable. */
    private inner class ManualCard : JPanel(BorderLayout(0, 6)) {
        private val tableModel = ManualPointsTableModel(notifier = notifier)
        private val table = JTable(tableModel)
        private val addRowBtn = JButton("Add point")
        private val delRowBtn = JButton("Delete point")
        // E7.11 #4 — moved from the Materialize preview dialog.
        // Import is naturally a Custom-design-editor concern.
        private val importCsvBtn = JButton("Import CSV...").apply {
            toolTipText = "Replace the manual point list with values from a CSV file.  " +
                "Header row must contain a column for every factor name; '#' and 'reps' " +
                "columns are optional."
        }
        private val summary = JLabel(" ")

        init {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            table.selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            table.fillsViewportHeight = true
            table.putClientProperty("terminateEditOnFocusLost", true)
            val scroll = JScrollPane(table)
            scroll.preferredSize = Dimension(420, 160)
            add(scroll, BorderLayout.CENTER)

            val btns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
            btns.add(addRowBtn); btns.add(delRowBtn); btns.add(importCsvBtn)
            add(btns, BorderLayout.NORTH)
            add(summary, BorderLayout.SOUTH)

            tableModel.addTableModelListener {
                if (!suppressEvents) commitFromUI()
            }
            addRowBtn.addActionListener {
                tableModel.appendFirstLevelRow(controller.factors.value)
            }
            delRowBtn.addActionListener {
                val row = table.selectedRow
                if (row >= 0 && tableModel.rowCount > 1) {
                    tableModel.removeRow(row)
                } else if (tableModel.rowCount <= 1) {
                    notifier.warn(
                        "Custom design must keep at least one point."
                    )
                }
            }
            importCsvBtn.addActionListener { importCsv() }
        }

        private fun importCsv() {
            val factors = controller.factors.value
            if (factors.isEmpty()) {
                notifier.warn(
                    "Add at least one factor on the Factors tab before importing."
                )
                return
            }
            val chooser = javax.swing.JFileChooser().apply {
                dialogTitle = "Import design points from CSV"
                fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
                    "CSV files (*.csv)", "csv"
                )
            }
            if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
            val file = chooser.selectedFile
            when (val result = parseManualCsv(file, factors)) {
                is ManualCsvImportResult.Failure -> {
                    javax.swing.JOptionPane.showMessageDialog(
                        this,
                        buildString {
                            append("Import failed (${result.errors.size} error")
                            append(if (result.errors.size == 1) "" else "s")
                            append("):\n\n")
                            for ((i, msg) in result.errors.withIndex()) {
                                append("  • $msg")
                                if (i < result.errors.lastIndex) append("\n")
                            }
                        },
                        "CSV import failed",
                        javax.swing.JOptionPane.ERROR_MESSAGE
                    )
                }
                is ManualCsvImportResult.Ok -> {
                    controller.setDesignSpec(DesignSpec.Manual(result.points))
                    notifier.info(
                        "Imported ${result.points.size} design point" +
                            (if (result.points.size == 1) "" else "s") +
                            " from ${file.name}."
                    )
                    // load() will fire via the designSpec collector
                    // and rebuild the table.
                }
            }
        }

        fun load(spec: DesignSpec.Manual) {
            tableModel.setFactorsAndPoints(controller.factors.value, spec.points)
            refreshSummary(spec.points.size)
        }

        fun onFactorsChanged(factors: List<FactorSpec>) {
            val cur = controller.designSpec.value as? DesignSpec.Manual ?: return
            val rebuilt = cur.points.map { p ->
                val reshaped = factors.associate { f ->
                    // Backfill missing factors with the first
                    // declared level (always a valid level).
                    val v = p.factorValues[f.name] ?: f.levels.first()
                    f.name to v
                }
                if (reshaped.isEmpty()) p else p.copy(factorValues = reshaped)
            }.filter { it.factorValues.isNotEmpty() }
            tableModel.setFactorsAndPoints(factors, rebuilt)
            if (rebuilt.isNotEmpty() && factors.isNotEmpty()) {
                val next = DesignSpec.Manual(rebuilt)
                if (next != cur) controller.setDesignSpec(next)
            }
        }

        private fun commitFromUI() {
            val factors = controller.factors.value
            if (factors.isEmpty()) return
            val points = tableModel.toPoints()
            if (points.isEmpty()) return
            try {
                controller.setDesignSpec(DesignSpec.Manual(points))
                refreshSummary(points.size)
            } catch (ex: IllegalArgumentException) {
                summary.text = "<html><font color='#a00'>${ex.message}</font></html>"
            }
        }

        private fun refreshSummary(rows: Int) {
            summary.text = "<html><b>$rows</b> custom design point${if (rows == 1) "" else "s"}.  " +
                "Per-row <i>reps</i> override the document-level Replications value " +
                "(blank = inherit).</html>"
        }
    }

    // ===============================================================
    // Table models
    // ===============================================================

    /** Table model for the custom-fraction words editor.  Each row
     *  is a comma-separated string of 1-based factor indices that
     *  represents one word in the single defining relation; the
     *  surrounding card parses each row into List<Int> on commit. */
    private class RelationsTableModel : AbstractTableModel() {
        private val rows: MutableList<String> = mutableListOf()
        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = 1
        override fun getColumnName(column: Int): String =
            "Word (comma-separated factor indices, e.g. 1, 2, 4)"
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any = rows[rowIndex]
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            rows[rowIndex] = (aValue as? String ?: "").trim()
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
        fun words(): List<String> = rows.filter { it.isNotBlank() }
        fun setWords(next: List<String>) {
            rows.clear()
            rows.addAll(next)
            fireTableDataChanged()
        }
    }

    /** Table model for the Manual design points editor.
     *
     *  Per-cell validation (Phase E7.3 follow-up):
     *  - **Hard reject** if the value is outside the factor's
     *    [min, max] interval — silently reverts the edit and surfaces
     *    a notification.  Mirrors substrate's `enforceRange = true`.
     *  - **Soft warning** if the value is within [min, max] but is
     *    not one of the factor's declared discrete levels — commits
     *    the value but surfaces an informational notification.
     *    Lets the user augment CCD-style with mid-range points.
     */
    private class ManualPointsTableModel(
        private val notifier: NotificationSink
    ) : AbstractTableModel() {
        private var factors: List<FactorSpec> = emptyList()
        private val factorNames: List<String> get() = factors.map { it.name }
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
                if (parsed == null) return  // unparseable: silently revert
                val factor = factors.getOrNull(columnIndex) ?: return
                val minLvl = factor.levels.min()
                val maxLvl = factor.levels.max()
                // Hard reject: outside [min, max].
                if (parsed < minLvl || parsed > maxLvl) {
                    notifier.warn(
                        "Value $parsed for '${factor.name}' is outside its range " +
                            "[$minLvl, $maxLvl].  Edit reverted."
                    )
                    fireTableCellUpdated(rowIndex, columnIndex)  // forces UI to re-read old value
                    return
                }
                // Soft warning: within range but not a declared level.
                if (parsed !in factor.levels) {
                    notifier.info(
                        "Value $parsed for '${factor.name}' is within range but " +
                            "not one of the declared levels ${factor.levels} " +
                            "(allowed; treated as a within-range augmentation)."
                    )
                }
                rows[rowIndex][factor.name] = parsed
                fireTableCellUpdated(rowIndex, columnIndex)
            } else {
                val s = (aValue as? String ?: "").trim()
                val newReps = if (s.isEmpty()) null else s.toIntOrNull()?.coerceAtLeast(1)
                reps[rowIndex] = newReps
                fireTableCellUpdated(rowIndex, columnIndex)
            }
        }

        fun setFactorsAndPoints(factors: List<FactorSpec>, points: List<ManualPointSpec>) {
            this.factors = factors
            rows.clear()
            reps.clear()
            for (p in points) {
                val row = mutableMapOf<String, Double>()
                // Missing values (e.g. a factor was added after this
                // point was authored) backfill at the factor's first
                // declared level — always a valid level, never trips
                // the in-range-but-not-a-level soft warning.
                for (f in factors) row[f.name] = p.factorValues[f.name] ?: f.levels.first()
                rows += row
                reps += p.replications
            }
            fireTableStructureChanged()
        }

        fun appendFirstLevelRow(factors: List<FactorSpec>) {
            if (factors.isEmpty()) return
            val row = mutableMapOf<String, Double>()
            for (f in factors) row[f.name] = f.levels.first()
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
        // Sub-tab indices on familyTabs.  Matches insertion order
        // in the JTabbedPane constructor block.
        private const val TAB_FF  = 0
        private const val TAB_TLF = 1
        private const val TAB_CCD = 2
        private const val TAB_MN  = 3
    }

    // Suppress unused-import warnings for utility types referenced
    // only through nested classes.
    @Suppress("unused")
    private val sealedAnchor: JComponent? = null
}
