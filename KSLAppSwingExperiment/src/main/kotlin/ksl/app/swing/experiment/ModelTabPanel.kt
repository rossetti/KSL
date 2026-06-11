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
import ksl.app.config.ModelReference
import ksl.app.notification.NotificationSink
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 *  *Model* tab — pick the single model this experiment binds to, and
 *  surface its introspection (controls, RV parameters, response
 *  names, run defaults) so the user knows what bindings are
 *  available to factors and what responses are available to
 *  regression fits.
 *
 *  The picker enumerates every `(bundleId, modelId)` pair across
 *  [ExperimentAppController.loadedBundles].  Selecting an entry sets
 *  `controller.modelReference` to the matching
 *  [ModelReference.ByBundleAndModelId] and triggers
 *  `currentModelDescriptor` to be populated.
 *
 *  The panel runs in three states via a CardLayout:
 *  - **No bundles loaded** — instructs the user to use
 *    *Bundles → Load Bundle JAR…*.
 *  - **Picker** — dropdown + summary card (default state when
 *    bundles are available).
 *  - **Unresolved reference** — shown when the document's
 *    [ModelReference] is a non-bundle variant (`ByProviderId`,
 *    `Embedded`, `ByJar`) or its bundle isn't loaded yet.  Lets the
 *    user know to load the matching JAR.
 *
 *  Phase E6 (Factors tab) reads
 *  [ExperimentAppController.currentModelDescriptor] to populate its
 *  binding picker; Phase E9 (Regression tab) reads `responseNames`.
 *  This panel just drives that state — it doesn't consume the
 *  descriptor's contents directly beyond surfacing the summary.
 */
class ModelTabPanel(
    private val controller: ExperimentAppController,
    private val notifier: NotificationSink = NotificationSink.NOOP
) : JPanel(BorderLayout()) {

    // The outer layout is BorderLayout (added in E7.9 to host the
    // Edited / Saved badge in SOUTH).  The original card switching
    // moved into [cardsPanel] (BorderLayout.CENTER) so the badge
    // and other persistent footer content sit below regardless of
    // which card is showing.
    private val cards = CardLayout()
    private val cardsPanel = JPanel(cards)
    private val emptyCard = JPanel(BorderLayout())
    private val pickerCard = JPanel(BorderLayout())
    private val unresolvedCard = JPanel(BorderLayout())

    /** The shared two-step bundle → model picker: a bundle combo + a model
     *  combo scoped to it + a model-info table.  This panel owns the
     *  surrounding empty/unresolved cards, the run-parameter overrides, and the
     *  switch-prompt; the picker just reports the user's choice through
     *  [handleUserModelSelection]. */
    private val pickerPanel = ksl.app.swing.common.bundle.BundleModelPickerPanel(
        loadedBundles = controller.loadedBundles,
        currentReference = controller.modelReference,
        currentDescriptor = controller.currentModelDescriptor,
        scope = controller.edtScope,
        onSelect = ::handleUserModelSelection
    )
    private val unresolvedLabel: JLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = Color(0xCC, 0x77, 0x00)
    }

    // Run parameter defaults widgets — declared BEFORE init {}
    // because Kotlin executes property initializers and init blocks
    // in source order; init's buildRunDefaultsPanel() would NPE on
    // any of these if they were declared below.
    private val repsField = javax.swing.JTextField(12)
    private val lengthField = javax.swing.JTextField(12)
    private val warmUpField = javax.swing.JTextField(12)
    private val repsModelDefaultLabel = JLabel(" ").apply {
        foreground = Color(0x77, 0x77, 0x77)
    }
    private val lengthModelDefaultLabel = JLabel(" ").apply {
        foreground = Color(0x77, 0x77, 0x77)
    }
    private val warmUpModelDefaultLabel = JLabel(" ").apply {
        foreground = Color(0x77, 0x77, 0x77)
    }

    @Volatile private var suppressRunDefaultsEvents: Boolean = false

    init {
        emptyCard.add(JLabel(
            "<html><div style='text-align:center;'>" +
                "No model bundles loaded.<br>" +
                "Use <b>Bundles → Load Bundle JAR…</b> to load one,<br>" +
                "or start the app with a bundle on the classpath." +
                "</div></html>",
            SwingConstants.CENTER
        ).apply {
            foreground = Color(0x66, 0x66, 0x66)
        }, BorderLayout.CENTER)

        pickerCard.add(buildPickerCard(), BorderLayout.CENTER)
        pickerCard.add(buildRunDefaultsPanel(), BorderLayout.SOUTH)
        unresolvedCard.add(buildUnresolvedCard(), BorderLayout.CENTER)

        cardsPanel.add(emptyCard, CARD_EMPTY)
        cardsPanel.add(pickerCard, CARD_PICKER)
        cardsPanel.add(unresolvedCard, CARD_UNRESOLVED)
        add(cardsPanel, BorderLayout.CENTER)

        // Edited / Saved badge in the footer; the same shared
        // widget appears at the bottom of Factors / Design /
        // Simulate tabs as well.
        val footer = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0))
        footer.add(DocumentStateLabel(controller.isDirty, controller.edtScope))
        add(footer, BorderLayout.SOUTH)

        wireCollectors()
        wireRunDefaultsCollectors()
        refreshCardSelection()
    }

    // ── Layout builders ────────────────────────────────────────────────────

    private fun buildPickerCard(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        // The shared picker provides the bundle/model rows + the model-info
        // table; run-parameter defaults go in pickerCard's SOUTH (see init).
        add(pickerPanel, BorderLayout.CENTER)
    }

    private fun buildUnresolvedCard(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
        add(unresolvedLabel, BorderLayout.CENTER)
    }

    // ── Run parameter defaults ─────────────────────────────────────────────
    //
    // Three fields under the model picker, all optional overrides
    // for the model's baked-in run parameters.  Replications is
    // cross-linked to ReplicationSpec.Uniform.replications (so the
    // user sees + edits replications here as well as on the Design
    // tab's Replications panel).  When the document's policy is
    // ReplicationSpec.PerPoint, the field is disabled with an
    // explanatory tooltip; per-point overrides are edited on the
    // Design tab.

    // (Run-defaults widget declarations moved up to before init {}
    // — see the block above the init {} block.)

    private fun buildRunDefaultsPanel(): JPanel {
        val panel = JPanel(java.awt.GridBagLayout())
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Run parameter defaults"),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)
        )

        // Column weights: label column rigid (weightx 0), field
        // column takes 60% of slack, default-label column takes
        // 40%.  Fields fill horizontally so resizing the window
        // gives them more room.
        fun label(text: String) = JLabel(text)
        fun labelGbc(rowIdx: Int) = java.awt.GridBagConstraints().apply {
            gridx = 0; gridy = rowIdx
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(2, 4, 2, 8)
        }
        fun fieldGbc(rowIdx: Int) = java.awt.GridBagConstraints().apply {
            gridx = 1; gridy = rowIdx
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(2, 4, 2, 8)
            fill = java.awt.GridBagConstraints.HORIZONTAL
            weightx = 0.6
        }
        fun defaultGbc(rowIdx: Int) = java.awt.GridBagConstraints().apply {
            gridx = 2; gridy = rowIdx
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(2, 4, 2, 4)
            weightx = 0.4
        }

        panel.add(label("Replications:"), labelGbc(0))
        panel.add(repsField, fieldGbc(0))
        panel.add(repsModelDefaultLabel, defaultGbc(0))

        panel.add(label("Length of replication:"), labelGbc(1))
        panel.add(lengthField, fieldGbc(1))
        panel.add(lengthModelDefaultLabel, defaultGbc(1))

        panel.add(label("Length of warm-up:"), labelGbc(2))
        panel.add(warmUpField, fieldGbc(2))
        panel.add(warmUpModelDefaultLabel, defaultGbc(2))

        val helpRowGbc = java.awt.GridBagConstraints().apply {
            gridx = 0; gridy = 3; gridwidth = 3
            anchor = java.awt.GridBagConstraints.WEST
            insets = java.awt.Insets(6, 4, 2, 4)
            fill = java.awt.GridBagConstraints.HORIZONTAL
            weightx = 1.0
        }
        val helpText = JLabel(
            "<html><i>Leave blank to inherit the model author's defaults.  " +
                "Replications is the document-level uniform value; switch to " +
                "Per-point on the Design tab to author per-row overrides.</i></html>"
        )
        helpText.foreground = Color(0x55, 0x55, 0x55)
        panel.add(helpText, helpRowGbc)

        // Commit on Enter / focus-lost.
        wireRunDefaultsCommit()

        return panel
    }

    private fun wireRunDefaultsCommit() {
        val commitReps: () -> Unit = {
            if (!suppressRunDefaultsEvents) {
                val t = repsField.text.trim()
                val parsed = t.toIntOrNull()
                if (parsed != null && parsed >= 1) {
                    val rep = controller.replications.value
                    val next = when (rep) {
                        is ksl.app.config.experiment.ReplicationSpec.Uniform ->
                            ksl.app.config.experiment.ReplicationSpec.Uniform(parsed)
                        is ksl.app.config.experiment.ReplicationSpec.PerPoint ->
                            rep.copy(default = parsed)
                    }
                    controller.setReplications(next)
                }
                // Re-read from controller in case parse failed (snaps back to current).
                refreshRepsFieldFromController()
            }
        }
        val commitLength: () -> Unit = {
            if (!suppressRunDefaultsEvents) {
                val t = lengthField.text.trim()
                val parsed = if (t.isEmpty()) null else t.toDoubleOrNull()?.takeIf { it > 0.0 }
                val cur = controller.runParameterOverrides.value
                controller.setRunParameterOverrides(cur.copy(lengthOfReplication = parsed))
                refreshLengthFieldFromController()
            }
        }
        val commitWarmUp: () -> Unit = {
            if (!suppressRunDefaultsEvents) {
                val t = warmUpField.text.trim()
                val parsed = if (t.isEmpty()) null else t.toDoubleOrNull()?.takeIf { it >= 0.0 }
                val cur = controller.runParameterOverrides.value
                try {
                    controller.setRunParameterOverrides(cur.copy(lengthOfReplicationWarmUp = parsed))
                } catch (ex: IllegalArgumentException) {
                    notifier.warn(ex.message ?: "Invalid warm-up")
                }
                refreshWarmUpFieldFromController()
            }
        }
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

    private fun wireRunDefaultsCollectors() {
        controller.edtScope.launch {
            controller.replications.collect { refreshRepsFieldFromController() }
        }
        controller.edtScope.launch {
            controller.runParameterOverrides.collect {
                refreshLengthFieldFromController()
                refreshWarmUpFieldFromController()
            }
        }
        controller.edtScope.launch {
            controller.currentModelDescriptor.collect { refreshModelDefaultLabels() }
        }
        refreshModelDefaultLabels()
    }

    private fun refreshRepsFieldFromController() {
        // E7.11 #2 — don't trample the user's in-progress edit.
        // Unrelated state changes (dirty-flag flip, summary refresh,
        // …) used to fire the replications collector and rewrite the
        // field text mid-keystroke, which reset the caret and made
        // it look like only one character was being accepted.
        if (repsField.hasFocus()) return
        suppressRunDefaultsEvents = true
        try {
            val rep = controller.replications.value
            when (rep) {
                is ksl.app.config.experiment.ReplicationSpec.Uniform -> {
                    repsField.text = rep.replications.toString()
                    repsField.isEnabled = true
                    repsField.toolTipText =
                        "Document-level uniform replications per design point."
                }
                is ksl.app.config.experiment.ReplicationSpec.PerPoint -> {
                    repsField.text = rep.default.toString()
                    repsField.isEnabled = false
                    repsField.toolTipText =
                        "Policy is Per-point.  Edit the default + overrides on the " +
                            "Design tab → Replications panel."
                }
            }
        } finally { suppressRunDefaultsEvents = false }
    }

    private fun refreshLengthFieldFromController() {
        // E7.11 #2 — see refreshRepsFieldFromController KDoc above.
        if (lengthField.hasFocus()) return
        suppressRunDefaultsEvents = true
        try {
            val ov = controller.runParameterOverrides.value.lengthOfReplication
            lengthField.text = ov?.toString().orEmpty()
        } finally { suppressRunDefaultsEvents = false }
    }

    private fun refreshWarmUpFieldFromController() {
        // E7.11 #2 — see refreshRepsFieldFromController KDoc above.
        if (warmUpField.hasFocus()) return
        suppressRunDefaultsEvents = true
        try {
            val ov = controller.runParameterOverrides.value.lengthOfReplicationWarmUp
            warmUpField.text = ov?.toString().orEmpty()
        } finally { suppressRunDefaultsEvents = false }
    }

    private fun refreshModelDefaultLabels() {
        val desc = controller.currentModelDescriptor.value
        val defaults = desc?.experimentRunDefaults
        repsModelDefaultLabel.text = defaults?.let { "(model default: ${it.numberOfReplications})" } ?: " "
        lengthModelDefaultLabel.text = defaults?.let { "(model default: ${it.lengthOfReplication})" } ?: " "
        warmUpModelDefaultLabel.text = defaults?.let { "(model default: ${it.lengthOfReplicationWarmUp})" } ?: " "
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private fun handleUserModelSelection(bundleId: String, modelId: String) {
        val newRef = ModelReference.ByBundleAndModelId(bundleId = bundleId, modelId = modelId)
        val currentRef = controller.modelReference.value
        // Switching to a different model: prompt the user.  Same-model
        // re-selection is a no-op and skips the prompt.
        if (currentRef != null && currentRef != newRef && controller.factors.value.isNotEmpty()) {
            handleModelSwitchPrompt(newRef)
        } else {
            controller.setModelReference(newRef)
        }
    }

    /**
     *  Prompt the user when they pick a different model and there
     *  are existing factors that probably reference the old model's
     *  controls.  Three outcomes:
     *  - Switch and Clear → reset factors / design / overrides to
     *    defaults
     *  - Switch and Keep  → keep existing values (user will fix
     *    bindings manually; Factors tab shows red-X markers)
     *  - Cancel           → revert the dropdown to the prior model
     */
    private fun handleModelSwitchPrompt(newRef: ModelReference) {
        val options = arrayOf("Switch and Clear", "Switch and Keep", "Cancel")
        val choice = javax.swing.JOptionPane.showOptionDialog(
            this,
            "<html>Switching to a different model will likely invalidate the current " +
                "factors (they reference the previous model's controls / RV parameters).<br><br>" +
                "<b>Switch and Clear</b> — reset factors, design spec, and run-parameter " +
                "overrides back to defaults.<br>" +
                "<b>Switch and Keep</b> — keep the existing values; fix the bindings " +
                "manually on the Factors tab.<br>" +
                "<b>Cancel</b> — keep the previously selected model.</html>",
            "Switch model?",
            javax.swing.JOptionPane.YES_NO_CANCEL_OPTION,
            javax.swing.JOptionPane.QUESTION_MESSAGE,
            null,
            options,
            options[0]
        )
        when (choice) {
            0 -> controller.setModelReferenceAndClear(newRef)
            1 -> controller.setModelReference(newRef)
            else -> {
                // Cancel — revert the picker to the unchanged controller
                // reference (re-selects the prior bundle + model).
                pickerPanel.syncToReference()
            }
        }
    }

    private fun wireCollectors() {
        // The shared picker rebuilds / syncs its own dropdowns and renders its
        // own summary off loadedBundles, modelReference, and
        // currentModelDescriptor.  This panel only needs those signals to pick
        // the empty / picker / unresolved card.
        controller.edtScope.launch {
            controller.loadedBundles.collect { _ -> refreshCardSelection() }
        }
        controller.edtScope.launch {
            controller.modelReference.collect { _ -> refreshCardSelection() }
        }
        controller.edtScope.launch {
            controller.currentModelDescriptor.collect { _ -> refreshCardSelection() }
        }
    }

    /** Decide which card to show based on the controller's current
     *  state.  Called from any state-changing collector. */
    private fun refreshCardSelection() {
        val bundlesEmpty = controller.loadedBundles.value.isEmpty()
        val ref = controller.modelReference.value
        val descriptor = controller.currentModelDescriptor.value
        when {
            bundlesEmpty && ref == null -> cards.show(cardsPanel, CARD_EMPTY)
            ref == null -> cards.show(cardsPanel, CARD_PICKER)
            descriptor != null -> cards.show(cardsPanel, CARD_PICKER)
            else -> {
                unresolvedLabel.text = buildUnresolvedMessage(ref)
                cards.show(cardsPanel, CARD_UNRESOLVED)
            }
        }
    }

    private fun buildUnresolvedMessage(ref: ModelReference): String = when (ref) {
        is ModelReference.ByBundleAndModelId ->
            "<html><div style='text-align:center;'>" +
                "Model reference: <b>${ref.bundleId}</b> / <b>${ref.modelId}</b><br>" +
                "Its bundle is not loaded.  Use <b>Bundles → Load Bundle JAR…</b> " +
                "to load the matching JAR; the picker will then resolve and the " +
                "Factors / Design / Run tabs become usable." +
                "</div></html>"
        is ModelReference.ByProviderId ->
            "<html><div style='text-align:center;'>" +
                "Model reference is a non-bundle form: <b>providerId = ${ref.providerId}</b>.<br>" +
                "This document was authored programmatically.  The Experiment app's<br>" +
                "Model picker only edits bundle-backed references; switch to one via<br>" +
                "<b>File → New Experiment</b>." +
                "</div></html>"
        is ModelReference.Embedded ->
            "<html><div style='text-align:center;'>" +
                "Model reference is an in-process embedded form: <b>${ref.modelName}</b>.<br>" +
                "This document was authored programmatically and is not editable here.<br>" +
                "Use <b>File → New Experiment</b> to start from a bundle-backed model." +
                "</div></html>"
        is ModelReference.ByJar ->
            "<html><div style='text-align:center;'>" +
                "Model reference is a JAR form: <b>${ref.jarPath}</b>.<br>" +
                "ByJar references are not supported by the Experiment app.<br>" +
                "Use <b>File → New Experiment</b> to start fresh." +
                "</div></html>"
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_PICKER = "picker"
        private const val CARD_UNRESOLVED = "unresolved"
    }
}
