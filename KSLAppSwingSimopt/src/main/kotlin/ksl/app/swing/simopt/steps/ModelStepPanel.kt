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
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.notification.NotificationSink
import ksl.app.swing.simopt.SimoptAppController
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants

/**
 *  *Model* step — Phase O3.
 *
 *  Three-card layout via [CardLayout]:
 *
 *  - **No bundles loaded** — instructs the user to use
 *    *Bundles → Load Bundle JAR…* from the menu, or run the packaged
 *    app where the example bundles ship on the classpath.
 *  - **Picker** — bundle dropdown (hidden when only one bundle is
 *    loaded) + model dropdown + introspection summary + run-parameter
 *    defaults + advanced baseline-controls disclosure.
 *  - **Unresolved** — shown when the document's model reference is a
 *    non-bundle variant or its bundle isn't loaded.  Tells the user
 *    which bundle to load.
 *
 *  Switching to a different model with non-empty downstream specs
 *  (problem / solver) prompts the user with **Switch and Clear**,
 *  **Switch and Keep**, or **Cancel**.  Cancel restores the dropdown
 *  selection.
 *
 *  Note on the Replications field: this is the *baseline*
 *  replication count saved on `ModelRunTemplate.runParameters`.  The
 *  algorithm step's `replicationsPerEvaluation` is independent;
 *  per the design discussion, a future phase may default the
 *  algorithm-level value from this baseline when the user has not
 *  explicitly set it.
 */
class ModelStepPanel(
    private val controller: SimoptAppController,
    private val notifier: NotificationSink = NotificationSink.NOOP
) : JPanel(BorderLayout()) {

    private val cards = CardLayout()
    private val cardsPanel = JPanel(cards)
    private val emptyCard = buildEmptyCard()
    private val pickerCard = JPanel(BorderLayout())
    private val unresolvedCard = JPanel(BorderLayout())

    /** One row in the model dropdown.  `toString()` renders the
     *  combo-box label. */
    private data class ModelChoice(
        val bundleId: String,
        val modelId: String,
        val modelDisplayName: String,
        val bundleDisplayName: String
    ) {
        override fun toString(): String =
            if (modelDisplayName.equals(modelId, ignoreCase = true)) modelId
            else "$modelDisplayName ($modelId)"
    }

    /** One row in the bundle dropdown.  Carries the source label so copies
     *  of the same `bundleId` loaded from different JARs render as distinct,
     *  pickable rows. */
    private data class BundleChoice(
        val bundleId: String,
        val bundleDisplayName: String,
        val version: String,
        val sourceLabel: String
    ) {
        override fun toString(): String =
            ksl.app.swing.common.bundle.bundlePickerLabel(
                bundleDisplayName.ifBlank { bundleId }, version, sourceLabel
            )
    }

    private val bundleCombo: JComboBox<BundleChoice> = JComboBox()
    private val modelCombo: JComboBox<ModelChoice> = JComboBox()

    private val summaryPanel = SummaryPanel()
    private val unresolvedLabel: JLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = Color(0xCC, 0x77, 0x00)
    }

    // Run-defaults widgets — must be initialized before init {} reads them.
    private val repsField = JTextField(10)
    private val lengthField = JTextField(10)
    private val warmUpField = JTextField(10)
    private val repsModelDefaultLabel = mutedLabel()
    private val lengthModelDefaultLabel = mutedLabel()
    private val warmUpModelDefaultLabel = mutedLabel()

    // Advanced disclosure (read-only summary in O3; full editor lands later).
    private val advancedToggle = JLabel("▸ Fixed baseline controls (advanced) — 0 overrides").apply {
        foreground = Color(0x33, 0x55, 0x88)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        font = font.deriveFont(Font.PLAIN, 12f)
    }
    private val advancedBody = JTextArea(6, 40).apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = Color(0xFA, 0xFA, 0xFA)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(0xDD, 0xDD, 0xDD)),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)
        )
    }
    private val advancedBodyScroll = JScrollPane(
        advancedBody,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
    ).apply { isVisible = false }

    /** Guards the combo ActionListeners so collector-driven
     *  `setSelectedItem(...)` doesn't loop back into the controller. */
    @Volatile private var programmaticComboUpdate: Boolean = false
    @Volatile private var suppressRunDefaultsEvents: Boolean = false

    init {
        pickerCard.add(buildPickerCard(), BorderLayout.CENTER)
        unresolvedCard.add(buildUnresolvedCard(), BorderLayout.CENTER)

        cardsPanel.add(emptyCard, CARD_EMPTY)
        cardsPanel.add(pickerCard, CARD_PICKER)
        cardsPanel.add(unresolvedCard, CARD_UNRESOLVED)
        add(cardsPanel, BorderLayout.CENTER)

        wireBundleAndModelCombos()
        wireRunDefaultsCommit()
        wireCollectors()
        wireAdvancedDisclosure()

        rebuildBundleDropdown()
        refreshCardSelection()
        refreshModelDefaultLabels()
        refreshAdvancedSummary()
    }

    // ── Card builders ──────────────────────────────────────────────────────

    private fun buildEmptyCard(): JPanel = JPanel(BorderLayout()).apply {
        add(JLabel(
            "<html><div style='text-align:center;'>" +
                "No model bundles loaded.<br>" +
                "Use <b>Bundles → Load Bundle JAR…</b> to load one,<br>" +
                "or start the app with a bundle on the classpath." +
                "</div></html>",
            SwingConstants.CENTER
        ).apply {
            foreground = Color(0x66, 0x66, 0x66)
            border = BorderFactory.createEmptyBorder(48, 24, 48, 24)
        }, BorderLayout.CENTER)
    }

    private fun buildPickerCard(): JPanel = JPanel(BorderLayout(0, 12)).apply {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(buildPickerRow(), BorderLayout.NORTH)

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(summaryScroll())
            add(Box.createVerticalStrut(12))
            add(buildRunDefaultsPanel())
            add(Box.createVerticalStrut(12))
            add(buildAdvancedDisclosurePanel())
            add(Box.createVerticalGlue())
        }
        add(center, BorderLayout.CENTER)
    }

    private fun summaryScroll(): JScrollPane = JScrollPane(
        summaryPanel,
        JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    ).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Selected model"),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )
        preferredSize = java.awt.Dimension(640, 140)
    }

    private fun buildPickerRow(): JPanel = JPanel(GridBagLayout()).apply {
        // Bundle row — hidden when only one bundle is loaded.
        val bundleLabel = JLabel("Bundle: ").apply { font = font.deriveFont(Font.BOLD) }
        val modelLabel = JLabel("Model: ").apply { font = font.deriveFont(Font.BOLD) }

        // Row 0: bundle (toggled visible later).
        add(bundleLabel, gbc(0, 0, anchor = GridBagConstraints.WEST))
        add(bundleCombo, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Row 1: model.
        add(modelLabel, gbc(0, 1, anchor = GridBagConstraints.WEST))
        add(modelCombo, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        // Hide bundle row until we know whether ≥ 2 bundles are loaded.
        bundleLabel.isVisible = false
        bundleCombo.isVisible = false
    }

    private fun buildUnresolvedCard(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
        add(unresolvedLabel, BorderLayout.CENTER)
    }

    // ── Run-defaults panel ─────────────────────────────────────────────────

    private fun buildRunDefaultsPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Run parameter baseline"),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )

        repsField.toolTipText = "Baseline number of replications for this model.  " +
            "Used as the default replications-per-evaluation on the Algorithm step " +
            "when no explicit value is set there."

        add(JLabel("Replications:"), gbc(0, 0, anchor = GridBagConstraints.WEST, insets = Insets(2, 4, 2, 8)))
        add(repsField, gbc(1, 0, weightx = 0.6, fill = GridBagConstraints.HORIZONTAL))
        add(repsModelDefaultLabel, gbc(2, 0, weightx = 0.4))

        add(JLabel("Length of replication:"), gbc(0, 1, anchor = GridBagConstraints.WEST, insets = Insets(2, 4, 2, 8)))
        add(lengthField, gbc(1, 1, weightx = 0.6, fill = GridBagConstraints.HORIZONTAL))
        add(lengthModelDefaultLabel, gbc(2, 1, weightx = 0.4))

        add(JLabel("Length of warm-up:"), gbc(0, 2, anchor = GridBagConstraints.WEST, insets = Insets(2, 4, 2, 8)))
        add(warmUpField, gbc(1, 2, weightx = 0.6, fill = GridBagConstraints.HORIZONTAL))
        add(warmUpModelDefaultLabel, gbc(2, 2, weightx = 0.4))

        val help = JLabel(
            "<html><i>Length and warm-up set the simulation horizon for each " +
                "replication.  Replications is the baseline count; the Algorithm step " +
                "may override it with its own replications-per-evaluation setting.</i></html>"
        ).apply { foreground = Color(0x55, 0x55, 0x55) }
        add(help, gbc(0, 3, width = 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(6, 4, 2, 4)))
    }

    private fun buildAdvancedDisclosurePanel(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(2, 4, 2, 4)
        val top = JPanel(BorderLayout()).apply { add(advancedToggle, BorderLayout.WEST) }
        add(top, BorderLayout.NORTH)
        add(advancedBodyScroll, BorderLayout.CENTER)
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private fun wireBundleAndModelCombos() {
        bundleCombo.addActionListener {
            if (programmaticComboUpdate) return@addActionListener
            rebuildModelDropdownForSelectedBundle()
            // Auto-select the first model when the user picks a new bundle.
            (modelCombo.selectedItem as? ModelChoice)?.let { handleUserModelSelection(it) }
        }
        modelCombo.addActionListener {
            if (programmaticComboUpdate) return@addActionListener
            (modelCombo.selectedItem as? ModelChoice)?.let { handleUserModelSelection(it) }
        }
    }

    private fun handleUserModelSelection(choice: ModelChoice) {
        val newRef = ModelReference.ByBundleAndModelId(
            bundleId = choice.bundleId,
            modelId = choice.modelId
        )
        val currentRef = controller.modelTemplate.value?.modelReference
        val hasDownstream = controller.problemSpec.value != null ||
            controller.solverSpec.value != null
        if (currentRef != null && currentRef != newRef && hasDownstream) {
            handleModelSwitchPrompt(newRef)
        } else {
            controller.setModelReference(newRef)
        }
    }

    /** Prompt the user when they pick a different model and there
     *  is downstream content that probably references the old model's
     *  controls / responses. */
    private fun handleModelSwitchPrompt(newRef: ModelReference) {
        val options = arrayOf("Switch and Clear", "Switch and Keep", "Cancel")
        val choice = JOptionPane.showOptionDialog(
            this,
            "<html>Switching to a different model will likely invalidate the current " +
                "problem and solver specs (they reference the previous model's controls / " +
                "responses).<br><br>" +
                "<b>Switch and Clear</b> — reset the problem, solver, evaluation, and " +
                "tracking specs back to defaults.<br>" +
                "<b>Switch and Keep</b> — keep the existing specs; fix them manually on " +
                "the Problem / Algorithm steps.<br>" +
                "<b>Cancel</b> — keep the previously selected model.</html>",
            "Switch model?",
            JOptionPane.YES_NO_CANCEL_OPTION,
            JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        )
        when (choice) {
            0 -> controller.setModelReferenceAndClear(newRef)
            1 -> controller.setModelReference(newRef)
            else -> {
                // Cancel — revert the dropdowns to the prior selection.
                syncDropdownsToController()
            }
        }
    }

    private fun wireRunDefaultsCommit() {
        repsField.addActionListener { commitReps() }
        repsField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitReps() }
        })
        lengthField.addActionListener { commitLength() }
        lengthField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitLength() }
        })
        warmUpField.addActionListener { commitWarmUp() }
        warmUpField.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { commitWarmUp() }
        })
    }

    private fun commitReps() {
        if (suppressRunDefaultsEvents) return
        val parsed = repsField.text.trim().toIntOrNull()?.takeIf { it >= 1 }
        if (parsed != null) {
            try {
                controller.setNumberOfReplications(parsed)
            } catch (ex: IllegalArgumentException) {
                notifier.warn(ex.message ?: "Invalid replications")
            }
        }
        refreshRepsField()
    }

    private fun commitLength() {
        if (suppressRunDefaultsEvents) return
        val parsed = lengthField.text.trim().toDoubleOrNull()?.takeIf { it > 0.0 && it.isFinite() }
        if (parsed != null) {
            try {
                controller.setLengthOfReplication(parsed)
            } catch (ex: IllegalArgumentException) {
                notifier.warn(ex.message ?: "Invalid length of replication")
            }
        }
        refreshLengthField()
    }

    private fun commitWarmUp() {
        if (suppressRunDefaultsEvents) return
        val parsed = warmUpField.text.trim().toDoubleOrNull()?.takeIf { it >= 0.0 && it.isFinite() }
        if (parsed != null) {
            try {
                controller.setLengthOfReplicationWarmUp(parsed)
            } catch (ex: IllegalArgumentException) {
                notifier.warn(ex.message ?: "Invalid warm-up length")
            }
        }
        refreshWarmUpField()
    }

    private fun wireCollectors() {
        controller.loadedBundles.onEach { _ ->
            rebuildBundleDropdown()
            refreshCardSelection()
        }.launchIn(controller.edtScope)

        controller.modelTemplate.onEach { _ ->
            syncDropdownsToController()
            refreshRunDefaultsFromController()
            refreshAdvancedSummary()
            refreshCardSelection()
        }.launchIn(controller.edtScope)

        controller.currentModelDescriptor.onEach { descriptor ->
            summaryPanel.render(descriptor, controller.modelTemplate.value?.modelReference)
            refreshModelDefaultLabels()
            refreshCardSelection()
        }.launchIn(controller.edtScope)
    }

    private fun wireAdvancedDisclosure() {
        advancedToggle.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                advancedBodyScroll.isVisible = !advancedBodyScroll.isVisible
                refreshAdvancedSummary()
                revalidate()
                repaint()
            }
        })
    }

    // ── State synchronisation ──────────────────────────────────────────────

    private fun rebuildBundleDropdown() {
        val bundles = controller.loadedBundles.value
        val choices = bundles.map { lb ->
            BundleChoice(
                bundleId = lb.bundle.bundleId,
                bundleDisplayName = lb.bundle.displayName,
                version = lb.bundle.version,
                sourceLabel = ksl.app.bundle.bundleSourceLabel(lb)
            )
        }
        programmaticComboUpdate = true
        try {
            bundleCombo.model = DefaultComboBoxModel(choices.toTypedArray())
            // Show bundle dropdown only when > 1 bundle is loaded.
            val showBundle = choices.size > 1
            bundleCombo.isVisible = showBundle
            // The bundle's *label* is in the same panel; find it via parent.
            (bundleCombo.parent as? JPanel)?.components?.forEach { comp ->
                if (comp is JLabel && comp.text == "Bundle: ") comp.isVisible = showBundle
            }
        } finally {
            programmaticComboUpdate = false
        }
        rebuildModelDropdownForSelectedBundle()
        syncDropdownsToController()
    }

    private fun rebuildModelDropdownForSelectedBundle() {
        val bundles = controller.loadedBundles.value
        val selectedBundleChoice = bundleCombo.selectedItem as? BundleChoice
        val targetBundle: LoadedBundle? = when {
            selectedBundleChoice != null ->
                // Match the specific source so the model list follows the
                // exact bundle copy the user picked, not just the bundleId.
                bundles.firstOrNull {
                    it.bundle.bundleId == selectedBundleChoice.bundleId &&
                        ksl.app.bundle.bundleSourceLabel(it) == selectedBundleChoice.sourceLabel
                }
            bundles.size == 1 -> bundles.first()
            else -> null
        }
        val modelChoices = targetBundle?.bundle?.models?.map { m ->
            ModelChoice(
                bundleId = targetBundle.bundle.bundleId,
                modelId = m.modelId,
                modelDisplayName = m.displayName,
                bundleDisplayName = targetBundle.bundle.displayName
            )
        }.orEmpty()
        programmaticComboUpdate = true
        try {
            modelCombo.model = DefaultComboBoxModel(modelChoices.toTypedArray())
        } finally {
            programmaticComboUpdate = false
        }
    }

    private fun syncDropdownsToController() {
        val ref = controller.modelTemplate.value?.modelReference as? ModelReference.ByBundleAndModelId
            ?: return run {
                programmaticComboUpdate = true
                try { modelCombo.selectedItem = null } finally { programmaticComboUpdate = false }
            }
        // Bundle dropdown selection.  The reference isn't source-aware yet
        // (Part 3), so if the current selection already matches the referenced
        // bundleId, keep the user's chosen source rather than snapping to the
        // first same-id entry.
        val currentBundleChoice = bundleCombo.selectedItem as? BundleChoice
        val targetBundle = (0 until bundleCombo.itemCount)
            .map { bundleCombo.getItemAt(it) }
            .firstOrNull { it.bundleId == ref.bundleId }
        if (targetBundle != null &&
            currentBundleChoice?.bundleId != ref.bundleId &&
            bundleCombo.selectedItem != targetBundle
        ) {
            programmaticComboUpdate = true
            try {
                bundleCombo.selectedItem = targetBundle
                rebuildModelDropdownForSelectedBundle()
            } finally { programmaticComboUpdate = false }
        }
        // Model dropdown selection.
        val targetModel = (0 until modelCombo.itemCount)
            .map { modelCombo.getItemAt(it) }
            .firstOrNull { it.bundleId == ref.bundleId && it.modelId == ref.modelId }
        if (targetModel != null && modelCombo.selectedItem != targetModel) {
            programmaticComboUpdate = true
            try { modelCombo.selectedItem = targetModel } finally { programmaticComboUpdate = false }
        }
    }

    private fun refreshCardSelection() {
        val bundles = controller.loadedBundles.value
        if (bundles.isEmpty()) {
            cards.show(cardsPanel, CARD_EMPTY)
            return
        }
        val ref = controller.modelTemplate.value?.modelReference
        when (ref) {
            null -> cards.show(cardsPanel, CARD_PICKER) // pre-selection state
            is ModelReference.ByBundleAndModelId -> {
                val descriptor = controller.currentModelDescriptor.value
                if (descriptor != null) cards.show(cardsPanel, CARD_PICKER)
                else {
                    unresolvedLabel.text = "<html><div style='text-align:center;'>" +
                        "Document references model <b>${ref.modelId}</b> from bundle " +
                        "<b>${ref.bundleId}</b>, but that bundle isn't loaded.<br>" +
                        "Use <b>Bundles → Load Bundle JAR…</b> to load it." +
                        "</div></html>"
                    cards.show(cardsPanel, CARD_UNRESOLVED)
                }
            }
            else -> {
                unresolvedLabel.text = "<html><div style='text-align:center;'>" +
                    "Document references a model via <b>${ref::class.simpleName}</b>.<br>" +
                    "This Phase O3 UI only supports bundle-based model references.<br>" +
                    "Use File → New to start a fresh document, or hand-edit the TOML." +
                    "</div></html>"
                cards.show(cardsPanel, CARD_UNRESOLVED)
            }
        }
    }

    private fun refreshRunDefaultsFromController() {
        refreshRepsField()
        refreshLengthField()
        refreshWarmUpField()
    }

    private fun refreshRepsField() {
        if (repsField.hasFocus()) return
        suppressRunDefaultsEvents = true
        try {
            val v = controller.modelTemplate.value?.runParameters?.numberOfReplications
            repsField.text = v?.toString().orEmpty()
            repsField.isEnabled = controller.modelTemplate.value != null
        } finally { suppressRunDefaultsEvents = false }
    }

    private fun refreshLengthField() {
        if (lengthField.hasFocus()) return
        suppressRunDefaultsEvents = true
        try {
            val v = controller.modelTemplate.value?.runParameters?.lengthOfReplication
            lengthField.text = v?.takeIf { it.isFinite() }?.toString().orEmpty()
            lengthField.isEnabled = controller.modelTemplate.value != null
        } finally { suppressRunDefaultsEvents = false }
    }

    private fun refreshWarmUpField() {
        if (warmUpField.hasFocus()) return
        suppressRunDefaultsEvents = true
        try {
            val v = controller.modelTemplate.value?.runParameters?.lengthOfReplicationWarmUp
            warmUpField.text = v?.toString().orEmpty()
            warmUpField.isEnabled = controller.modelTemplate.value != null
        } finally { suppressRunDefaultsEvents = false }
    }

    private fun refreshModelDefaultLabels() {
        val desc = controller.currentModelDescriptor.value
        val defaults = desc?.experimentRunDefaults
        repsModelDefaultLabel.text =
            defaults?.let { "(model default: ${it.numberOfReplications})" } ?: " "
        lengthModelDefaultLabel.text =
            defaults?.let { "(model default: ${it.lengthOfReplication})" } ?: " "
        warmUpModelDefaultLabel.text =
            defaults?.let { "(model default: ${it.lengthOfReplicationWarmUp})" } ?: " "
    }

    private fun refreshAdvancedSummary() {
        val template = controller.modelTemplate.value
        val controlsCount = template?.controls?.numericControls?.size ?: 0
        val rvOverridesCount = template?.rvOverrides?.size ?: 0
        val totalOverrides = controlsCount + rvOverridesCount
        val arrow = if (advancedBodyScroll.isVisible) "▾" else "▸"
        advancedToggle.text =
            "$arrow Fixed baseline controls (advanced) — $totalOverrides override" +
                (if (totalOverrides == 1) "" else "s")

        val sb = StringBuilder()
        if (template == null) sb.appendLine("(no model selected)")
        else {
            if (controlsCount > 0) {
                sb.appendLine("# Controls (${controlsCount}):")
                template.controls.numericControls.forEach { c ->
                    sb.appendLine("  ${c.keyName} = ${c.value}")
                }
            }
            if (rvOverridesCount > 0) {
                if (controlsCount > 0) sb.appendLine()
                sb.appendLine("# RV parameter overrides (${rvOverridesCount}):")
                template.rvOverrides.forEach { o ->
                    sb.appendLine("  ${o.rvName}.${o.paramName} = ${o.value}")
                }
            }
            if (totalOverrides == 0) {
                sb.appendLine("(no baseline overrides; model author's defaults apply)")
            }
        }
        advancedBody.text = sb.toString().trimEnd()
        advancedBody.caretPosition = 0
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun mutedLabel(): JLabel = JLabel(" ").apply { foreground = Color(0x77, 0x77, 0x77) }

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

    /** Summary card for the selected model.  Lightweight — just
     *  control count / RV count / response count / time unit. */
    private class SummaryPanel : JPanel(BorderLayout()) {
        private val text = JTextArea().apply {
            isEditable = false
            lineWrap = false
            background = this@SummaryPanel.background
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

        init {
            add(text, BorderLayout.CENTER)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }

        fun render(descriptor: ModelDescriptor?, ref: ModelReference?) {
            if (descriptor == null) {
                text.text = if (ref == null) "Select a model from the dropdown above."
                else "Descriptor not available for this reference."
                return
            }
            val sb = StringBuilder()
            sb.append("Model name        : ").appendLine(descriptor.modelName)
            sb.append("Model identifier  : ").appendLine(descriptor.modelIdentifier)
            sb.append("Controls          : ").appendLine(descriptor.controls.numericControls.size)
            sb.append("RV parameters     : ").appendLine(descriptor.rvParameterData.size)
            sb.append("Numeric inputs    : ").appendLine(descriptor.inputNames.size)
            sb.append("Responses         : ").appendLine(descriptor.responseNames.size)
            sb.append("Base time unit    : ").appendLine(descriptor.baseTimeUnit)
            text.text = sb.toString().trimEnd()
        }
    }

    private companion object {
        const val CARD_EMPTY: String = "empty"
        const val CARD_PICKER: String = "picker"
        const val CARD_UNRESOLVED: String = "unresolved"
    }
}
