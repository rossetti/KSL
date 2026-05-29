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

package ksl.app.swing.simopt.steps

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.app.config.optimization.AlgorithmKind
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.algorithm.CeSamplerEditor
import ksl.app.swing.simopt.algorithm.CoolingScheduleEditor
import ksl.app.swing.simopt.algorithm.RandomRestartEditor
import ksl.app.swing.simopt.algorithm.TemperatureSpecEditor
import ksl.app.swing.simopt.runsetup.DisclosurePanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListCellRenderer
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 *  *Algorithm* step — Phase O6.
 *
 *  Layout (top → bottom inside a `JScrollPane`):
 *
 *  1. **Algorithm picker** — combo selecting one of the four
 *     [AlgorithmKind] variants.
 *  2. **Common parameters** — shared header (max iterations, stream
 *     number, solver name, replications-per-evaluation) used by every
 *     algorithm.  RSpline disables Replications/eval because it
 *     drives RPE via a growth schedule.
 *  3. **Algorithm-specific parameters** — `CardLayout` body that
 *     swaps to the matching variant's editor.  SHC has no extra
 *     fields; SA / CE / RSpline have nested editors.
 *  4. **Random restart wrapper** — checkbox + max-restarts.
 *
 *  Every algorithm-specific field has a concrete substrate-aligned
 *  default so the user always sees a populated form on first open.
 *  Switching algorithms preserves each algorithm's prior values
 *  (the controller stores them in dedicated per-algorithm flows).
 */
class AlgorithmStepPanel(
    private val controller: SimoptAppController,
    @Suppress("UNUSED_PARAMETER") onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(BorderLayout()) {

    // ── Picker ─────────────────────────────────────────────────────────────
    //
    // The combo model carries a leading `null` sentinel so the picker
    // opens to "— Choose algorithm —" on a fresh document rather than
    // auto-selecting the first real algorithm.  Without this, Swing's
    // default-selection behaviour displays the first entry but does
    // **not** fire the [java.awt.event.ActionListener] — leaving
    // `controller.algorithmKind` null even though the UI looked
    // populated, which would silently lock every step downstream of
    // ALGORITHM.

    private val algorithmCombo: JComboBox<AlgorithmKind?> = JComboBox<AlgorithmKind?>(
        DefaultComboBoxModel<AlgorithmKind?>(
            arrayOf<AlgorithmKind?>(null) + AlgorithmKind.entries.toTypedArray()
        )
    ).apply {
        renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val text = if (value == null) "— Choose algorithm —"
                    else (value as AlgorithmKind).displayName
                return super.getListCellRendererComponent(
                    list, text, index, isSelected, cellHasFocus
                )
            }
        }
    }

    // ── Common parameters ─────────────────────────────────────────────────

    private val maxIterationsField = JTextField(10)
    private val streamNumField = JTextField(10)
    private val solverNameField = JTextField(16).apply {
        toolTipText = "Optional.  When blank, the report and summary.toml use the " +
            "algorithm name (e.g. \"Stochastic Hill Climbing\")."
    }
    private val rpeField = JTextField(10)
    private val rpeNote = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = Color(0x77, 0x77, 0x77)
    }

    // ── Algorithm-specific cards ──────────────────────────────────────────

    private val cards = CardLayout()
    private val cardHost = JPanel(cards)

    private val shcCard = buildShcCard()
    private val saTemperatureEditor = TemperatureSpecEditor(
        initial = controller.saTemperature.value,
        onChanged = { spec -> spec?.let { controller.setSaTemperature(it) } }
    )
    private val saCoolingEditor = CoolingScheduleEditor(
        initial = controller.saCoolingSchedule.value,
        onChanged = { spec -> spec?.let { controller.setSaCoolingSchedule(it) } }
    )
    private val saStoppingTempField = JTextField(10)
    private val saCard = buildSaCard()
    private val ceSamplerEditor = CeSamplerEditor(
        initial = controller.ceSampler.value as? ksl.app.config.optimization.CESamplerSpec.Normal
            ?: ksl.app.config.optimization.CESamplerSpec.Normal(),
        onChanged = { spec -> spec?.let { controller.setCeSampler(it) } }
    )
    private val ceElitePctField = JTextField(10)
    private val ceSampleSizeField = JTextField(10)
    private val ceCard = buildCeCard()
    private val rsplineInitialField = JTextField(10)
    private val rsplineGrowthField = JTextField(10)
    private val rsplineMaxField = JTextField(10)
    private val rsplineCard = buildRsplineCard()

    // ── Random restart ────────────────────────────────────────────────────

    private val randomRestartEditor = RandomRestartEditor(
        initial = controller.randomRestart.value,
        onChanged = { spec -> controller.setRandomRestart(spec) }
    )

    // ── Disclosure wrappers ──────────────────────────────────────────────
    //
    // Algorithm-specific parameters and the random-restart wrapper each
    // sit inside a `DisclosurePanel` so their headers are always
    // visible in the rail-style stack — users discover what's there
    // without scrolling past the common-parameter block.  Default
    // states reflect editing frequency: algorithm-specific is expanded
    // (primary editing surface) and random restart is collapsed (an
    // optional wrapper most users won't touch).  Summary lines on
    // each header keep the current state visible when collapsed.

    private val algorithmSpecificDisclosure = DisclosurePanel(
        title = "Algorithm-specific parameters",
        body = buildAlgorithmSpecificBody(),
        initiallyExpanded = true
    )
    private val randomRestartDisclosure = DisclosurePanel(
        title = "Random restart",
        body = buildRandomRestartBody(),
        initiallyExpanded = false
    )

    @Volatile private var suppressEvents = false

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(buildPickerSection())
            add(Box.createVerticalStrut(8))
            add(buildCommonSection())
            add(Box.createVerticalStrut(8))
            add(algorithmSpecificDisclosure)
            add(Box.createVerticalStrut(8))
            add(randomRestartDisclosure)
            add(Box.createVerticalGlue())
        }

        add(JScrollPane(
            stack,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ), BorderLayout.CENTER)

        cardHost.add(shcCard, AlgorithmKind.STOCHASTIC_HILL_CLIMBING.name)
        cardHost.add(saCard, AlgorithmKind.SIMULATED_ANNEALING.name)
        cardHost.add(ceCard, AlgorithmKind.CROSS_ENTROPY.name)
        cardHost.add(rsplineCard, AlgorithmKind.R_SPLINE.name)

        wirePicker()
        wireCommonFields()
        wireSaCard()
        wireCeCard()
        wireRsplineCard()
        wireCollectors()

        refreshAll()
    }

    // ── Section builders ──────────────────────────────────────────────────

    private fun buildPickerSection(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Algorithm"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )
        add(JLabel("Algorithm:"), gbc(0, 0, anchor = GridBagConstraints.WEST))
        add(algorithmCombo, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun buildCommonSection(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Common parameters"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(JLabel("Max iterations:"), gbc(0, 0, anchor = GridBagConstraints.WEST))
        add(maxIterationsField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Stream number:"), gbc(0, 1, anchor = GridBagConstraints.WEST))
        add(streamNumField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("(0 = next available)").apply {
            foreground = Color(0x77, 0x77, 0x77)
            font = font.deriveFont(Font.PLAIN, 11f)
        }, gbc(2, 1, anchor = GridBagConstraints.WEST, insets = Insets(2, 8, 2, 4)))

        add(JLabel("Solver name:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(solverNameField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(JLabel("(optional)").apply {
            foreground = Color(0x77, 0x77, 0x77)
            font = font.deriveFont(Font.PLAIN, 11f)
        }, gbc(2, 2, anchor = GridBagConstraints.WEST, insets = Insets(2, 8, 2, 4)))

        add(JLabel("Replications/eval:"), gbc(0, 3, anchor = GridBagConstraints.WEST))
        add(rpeField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        add(rpeNote, gbc(2, 3, anchor = GridBagConstraints.WEST, insets = Insets(2, 8, 2, 4)))
    }

    /** Body panel for the algorithm-specific [DisclosurePanel].  Hosts
     *  the [cards]/[cardHost] CardLayout that swaps editors per
     *  selected [AlgorithmKind]. */
    private fun buildAlgorithmSpecificBody(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        add(cardHost, BorderLayout.CENTER)
    }

    /** Body panel for the random-restart [DisclosurePanel].  The
     *  what-does-this-do explainer lives inline since the disclosure
     *  collapses everything else by default. */
    private fun buildRandomRestartBody(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        val help = JLabel(
            "<html><i>When enabled, the solver factory wraps the chosen algorithm " +
                "in a RandomRestartSolver that runs the algorithm up to N times from " +
                "randomly-drawn starting points.</i></html>"
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
            border = BorderFactory.createEmptyBorder(0, 0, 6, 0)
        }
        add(help, BorderLayout.NORTH)
        add(randomRestartEditor, BorderLayout.CENTER)
    }

    // ── Card builders ─────────────────────────────────────────────────────

    private fun buildShcCard(): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel(
            "<html><i>Stochastic Hill Climbing uses only the common parameters above.</i></html>"
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        }, BorderLayout.NORTH)
    }

    private fun buildSaCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Initial temperature"),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
            add(saTemperatureEditor, BorderLayout.CENTER)
        }, gbc(0, 0, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Cooling schedule"),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
            add(saCoolingEditor, BorderLayout.CENTER)
        }, gbc(0, 1, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(8, 4, 2, 4)))

        add(JLabel("Stopping temperature:"), gbc(0, 2, anchor = GridBagConstraints.WEST,
            insets = Insets(8, 4, 2, 4)))
        add(saStoppingTempField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(8, 4, 2, 4)))
    }

    private fun buildCeCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Sampler (Multivariate Normal)"),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
            add(ceSamplerEditor, BorderLayout.CENTER)
        }, gbc(0, 0, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Elite %:"), gbc(0, 1, anchor = GridBagConstraints.WEST,
            insets = Insets(8, 4, 2, 4)))
        add(ceElitePctField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(8, 4, 2, 4)))

        add(JLabel("CE sample size:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(ceSampleSizeField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
    }

    private fun buildRsplineCard(): JPanel = JPanel(GridBagLayout()).apply {
        add(JLabel("Initial replications:"), gbc(0, 0, anchor = GridBagConstraints.WEST))
        add(rsplineInitialField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Growth rate:"), gbc(0, 1, anchor = GridBagConstraints.WEST))
        add(rsplineGrowthField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Max replications:"), gbc(0, 2, anchor = GridBagConstraints.WEST))
        add(rsplineMaxField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel(
            "<html><i>R-SPLINE drives replications-per-evaluation via this growth " +
                "schedule, so the common Replications/eval field above is disabled " +
                "when this algorithm is selected.</i></html>"
        ).apply {
            foreground = Color(0x55, 0x55, 0x55)
        }, gbc(0, 3, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(8, 4, 2, 4)))
    }

    // ── Wiring ───────────────────────────────────────────────────────────

    private fun wirePicker() {
        algorithmCombo.addActionListener {
            if (suppressEvents) return@addActionListener
            val kind = algorithmCombo.selectedItem as? AlgorithmKind
            controller.setAlgorithmKind(kind)
        }
    }

    private fun wireCommonFields() {
        maxIterationsField.addActionListener { commitMaxIterations() }
        maxIterationsField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitMaxIterations() }
        })
        streamNumField.addActionListener { commitStreamNum() }
        streamNumField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitStreamNum() }
        })
        solverNameField.addActionListener { commitSolverName() }
        solverNameField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitSolverName() }
        })
        rpeField.addActionListener { commitRpe() }
        rpeField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitRpe() }
        })
    }

    private fun commitMaxIterations() {
        if (suppressEvents) return
        val parsed = maxIterationsField.text.trim().toIntOrNull()?.takeIf { it > 0 }
        if (parsed != null) controller.setCommonMaxIterations(parsed)
        refreshCommonFields()
    }

    private fun commitStreamNum() {
        if (suppressEvents) return
        val parsed = streamNumField.text.trim().toIntOrNull()?.takeIf { it >= 0 }
        if (parsed != null) controller.setCommonStreamNum(parsed)
        refreshCommonFields()
    }

    private fun commitSolverName() {
        if (suppressEvents) return
        controller.setCommonSolverName(solverNameField.text)
    }

    private fun commitRpe() {
        if (suppressEvents) return
        val parsed = rpeField.text.trim().toIntOrNull()?.takeIf { it > 0 }
        if (parsed != null) controller.setCommonReplicationsPerEvaluation(parsed)
        refreshCommonFields()
    }

    private fun wireSaCard() {
        saStoppingTempField.addActionListener { commitSaStoppingTemp() }
        saStoppingTempField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitSaStoppingTemp() }
        })
    }

    private fun commitSaStoppingTemp() {
        if (suppressEvents) return
        val parsed = saStoppingTempField.text.trim().toDoubleOrNull()
            ?.takeIf { it > 0.0 && it.isFinite() }
        if (parsed != null) controller.setSaStoppingTemperature(parsed)
        refreshSaCard()
    }

    private fun wireCeCard() {
        ceElitePctField.addActionListener { commitCeElitePct() }
        ceElitePctField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitCeElitePct() }
        })
        ceSampleSizeField.addActionListener { commitCeSampleSize() }
        ceSampleSizeField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitCeSampleSize() }
        })
    }

    private fun commitCeElitePct() {
        if (suppressEvents) return
        val parsed = ceElitePctField.text.trim().toDoubleOrNull()
            ?.takeIf { it > 0.0 && it < 1.0 }
        if (parsed != null) controller.setCeElitePct(parsed)
        refreshCeCard()
    }

    private fun commitCeSampleSize() {
        if (suppressEvents) return
        val parsed = ceSampleSizeField.text.trim().toIntOrNull()?.takeIf { it >= 1 }
        if (parsed != null) controller.setCeSampleSize(parsed)
        refreshCeCard()
    }

    private fun wireRsplineCard() {
        rsplineInitialField.addActionListener { commitRsplineInitial() }
        rsplineInitialField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitRsplineInitial() }
        })
        rsplineGrowthField.addActionListener { commitRsplineGrowth() }
        rsplineGrowthField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitRsplineGrowth() }
        })
        rsplineMaxField.addActionListener { commitRsplineMax() }
        rsplineMaxField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitRsplineMax() }
        })
    }

    private fun commitRsplineInitial() {
        if (suppressEvents) return
        val parsed = rsplineInitialField.text.trim().toIntOrNull()?.takeIf { it > 0 }
        if (parsed != null) controller.setRsplineInitialNumReps(parsed)
        refreshRsplineCard()
    }

    private fun commitRsplineGrowth() {
        if (suppressEvents) return
        val parsed = rsplineGrowthField.text.trim().toDoubleOrNull()
            ?.takeIf { it > 0.0 && it.isFinite() }
        if (parsed != null) controller.setRsplineGrowthRate(parsed)
        refreshRsplineCard()
    }

    private fun commitRsplineMax() {
        if (suppressEvents) return
        val parsed = rsplineMaxField.text.trim().toIntOrNull()?.takeIf { it > 0 }
        if (parsed != null) controller.setRsplineMaxNumReplications(parsed)
        refreshRsplineCard()
    }

    private fun wireCollectors() {
        controller.algorithmKind.onEach { _ ->
            refreshPicker()
            refreshCardSelection()
            refreshRpeNote()
            refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)

        controller.commonMaxIterations.onEach { _ -> refreshCommonFields() }
            .launchIn(controller.edtScope)
        controller.commonStreamNum.onEach { _ -> refreshCommonFields() }
            .launchIn(controller.edtScope)
        controller.commonSolverName.onEach { _ -> refreshCommonFields() }
            .launchIn(controller.edtScope)
        controller.commonReplicationsPerEvaluation.onEach { _ -> refreshCommonFields() }
            .launchIn(controller.edtScope)
        controller.modelTemplate.onEach { _ -> refreshRpeNote() }
            .launchIn(controller.edtScope)

        controller.saTemperature.onEach { spec ->
            if (saTemperatureEditor.value != spec) saTemperatureEditor.setValue(spec)
        }.launchIn(controller.edtScope)
        controller.saCoolingSchedule.onEach { spec ->
            if (saCoolingEditor.value != spec) saCoolingEditor.setValue(spec)
        }.launchIn(controller.edtScope)
        controller.saStoppingTemperature.onEach { _ ->
            refreshSaCard(); refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)

        controller.ceSampler.onEach { spec ->
            if (ceSamplerEditor.value != spec) ceSamplerEditor.setValue(spec)
        }.launchIn(controller.edtScope)
        controller.ceElitePct.onEach { _ ->
            refreshCeCard(); refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)
        controller.ceSampleSize.onEach { _ ->
            refreshCeCard(); refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)

        controller.rsplineInitialNumReps.onEach { _ ->
            refreshRsplineCard(); refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)
        controller.rsplineGrowthRate.onEach { _ ->
            refreshRsplineCard(); refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)
        controller.rsplineMaxNumReplications.onEach { _ ->
            refreshRsplineCard(); refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)

        controller.randomRestart.onEach { spec ->
            if (randomRestartEditor.value != spec) randomRestartEditor.setValue(spec)
            refreshDisclosureSummaries()
        }.launchIn(controller.edtScope)
    }

    // ── Refresh ──────────────────────────────────────────────────────────

    private fun refreshAll() {
        refreshPicker()
        refreshCommonFields()
        refreshSaCard()
        refreshCeCard()
        refreshRsplineCard()
        refreshCardSelection()
        refreshRpeNote()
        refreshDisclosureSummaries()
    }

    /** Update the one-line summary lines shown on the two
     *  [DisclosurePanel]s when they're collapsed, so users can read
     *  the current state without expanding the section. */
    private fun refreshDisclosureSummaries() {
        algorithmSpecificDisclosure.setSummary(algorithmSpecificSummary())
        randomRestartDisclosure.setSummary(randomRestartSummary())
    }

    private fun algorithmSpecificSummary(): String =
        when (controller.algorithmKind.value) {
            null -> "pick an algorithm above"
            AlgorithmKind.STOCHASTIC_HILL_CLIMBING ->
                "Stochastic Hill Climbing — no extra parameters"
            AlgorithmKind.SIMULATED_ANNEALING -> {
                val stop = controller.saStoppingTemperature.value
                "Simulated Annealing — stopping T=$stop"
            }
            AlgorithmKind.CROSS_ENTROPY -> {
                val elite = controller.ceElitePct.value
                val n = controller.ceSampleSize.value
                "Cross-Entropy — elite=$elite, sample=$n"
            }
            AlgorithmKind.R_SPLINE -> {
                val init = controller.rsplineInitialNumReps.value
                val growth = controller.rsplineGrowthRate.value
                val max = controller.rsplineMaxNumReplications.value
                "R-SPLINE — init=$init, growth=$growth, max=$max"
            }
        }

    private fun randomRestartSummary(): String {
        val r = controller.randomRestart.value ?: return "off"
        return "on — max ${r.maxNumRestarts} restarts"
    }

    private fun refreshPicker() {
        suppressEvents = true
        try {
            // `null` is in the combo model as the leading sentinel
            // (see picker construction), so assigning a null
            // `algorithmKind` here selects the "— Choose algorithm —"
            // row rather than silently falling back to SHC.
            algorithmCombo.selectedItem = controller.algorithmKind.value
        } finally { suppressEvents = false }
    }

    private fun refreshCommonFields() {
        suppressEvents = true
        try {
            if (!maxIterationsField.hasFocus()) {
                maxIterationsField.text = controller.commonMaxIterations.value.toString()
            }
            if (!streamNumField.hasFocus()) {
                streamNumField.text = controller.commonStreamNum.value.toString()
            }
            if (!solverNameField.hasFocus()) {
                solverNameField.text = controller.commonSolverName.value.orEmpty()
            }
            if (!rpeField.hasFocus()) {
                rpeField.text = controller.commonReplicationsPerEvaluation.value.toString()
            }
            // RSpline disables RPE (uses growth schedule instead).
            val isRSpline = controller.algorithmKind.value == AlgorithmKind.R_SPLINE
            rpeField.isEnabled = !isRSpline
            rpeField.toolTipText = if (isRSpline) {
                "R-SPLINE drives replications-per-evaluation via the growth schedule; this field has no effect."
            } else {
                "Number of simulation replications requested per evaluation."
            }
        } finally { suppressEvents = false }
    }

    private fun refreshSaCard() {
        suppressEvents = true
        try {
            if (!saStoppingTempField.hasFocus()) {
                saStoppingTempField.text = controller.saStoppingTemperature.value.toString()
            }
        } finally { suppressEvents = false }
    }

    private fun refreshCeCard() {
        suppressEvents = true
        try {
            if (!ceElitePctField.hasFocus()) {
                ceElitePctField.text = controller.ceElitePct.value.toString()
            }
            if (!ceSampleSizeField.hasFocus()) {
                ceSampleSizeField.text = controller.ceSampleSize.value.toString()
            }
        } finally { suppressEvents = false }
    }

    private fun refreshRsplineCard() {
        suppressEvents = true
        try {
            if (!rsplineInitialField.hasFocus()) {
                rsplineInitialField.text = controller.rsplineInitialNumReps.value.toString()
            }
            if (!rsplineGrowthField.hasFocus()) {
                rsplineGrowthField.text = controller.rsplineGrowthRate.value.toString()
            }
            if (!rsplineMaxField.hasFocus()) {
                rsplineMaxField.text = controller.rsplineMaxNumReplications.value.toString()
            }
        } finally { suppressEvents = false }
    }

    private fun refreshCardSelection() {
        val kind = controller.algorithmKind.value ?: AlgorithmKind.STOCHASTIC_HILL_CLIMBING
        cards.show(cardHost, kind.name)
    }

    private fun refreshRpeNote() {
        val baseline = controller.modelTemplate.value?.runParameters?.numberOfReplications
        rpeNote.text = if (baseline != null) "(model baseline: $baseline)" else " "
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.CENTER,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }

    @Suppress("unused")
    private fun unusedSuppress() {
        // Document listeners may be needed for cross-field validation;
        // placeholder hook prevents "unused import" warnings on the
        // event interfaces above when wiring is intentionally minimal.
        val l: DocumentListener? = null
        val e: DocumentEvent? = null
        check(l == null && e == null)
    }
}
