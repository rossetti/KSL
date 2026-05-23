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
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.swing.common.notification.NotificationSeverity
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
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
    private val onMessage: (String, NotificationSeverity) -> Unit = { _, _ -> }
) : JPanel(CardLayout()) {

    private val cards = CardLayout()
    private val emptyCard = JPanel(BorderLayout())
    private val pickerCard = JPanel(BorderLayout())
    private val unresolvedCard = JPanel(BorderLayout())

    /** One row in the model dropdown.  `toString()` is what the
     *  combo renders to the user. */
    private data class ModelChoice(
        val bundleId: String,
        val modelId: String,
        val displayName: String,
        val bundleDisplayName: String
    ) {
        override fun toString(): String =
            if (bundleDisplayName.isBlank()) "$displayName ($bundleId/$modelId)"
            else "$displayName · $bundleDisplayName"
    }

    private val modelCombo: JComboBox<ModelChoice> = JComboBox()
    private val summaryArea = SummaryPanel()
    private val unresolvedLabel: JLabel = JLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
        foreground = Color(0xCC, 0x77, 0x00)
    }

    /** Guards the dropdown's ActionListener so the controller's
     *  collector-driven `setSelectedItem(...)` doesn't fire a no-op
     *  setModelReference call. */
    private var programmaticComboUpdate: Boolean = false

    init {
        layout = cards

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
        unresolvedCard.add(buildUnresolvedCard(), BorderLayout.CENTER)

        add(emptyCard, CARD_EMPTY)
        add(pickerCard, CARD_PICKER)
        add(unresolvedCard, CARD_UNRESOLVED)

        wireDropdownListener()
        wireCollectors()
        refreshCardSelection()
    }

    // ── Layout builders ────────────────────────────────────────────────────

    private fun buildPickerCard(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)

        // Picker row
        val pickerRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            add(JLabel("Model: ").apply { font = font.deriveFont(Font.BOLD) })
            add(Box.createHorizontalStrut(8))
            add(modelCombo)
            add(Box.createHorizontalGlue())
        }
        add(pickerRow)
        add(Box.createVerticalStrut(16))

        // Summary card
        summaryArea.alignmentX = Component.LEFT_ALIGNMENT
        add(summaryArea)
        add(Box.createVerticalGlue())
    }

    private fun buildUnresolvedCard(): JPanel = JPanel(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder(48, 16, 48, 16)
        add(unresolvedLabel, BorderLayout.CENTER)
    }

    // ── Wiring ─────────────────────────────────────────────────────────────

    private fun wireDropdownListener() {
        modelCombo.addActionListener {
            if (programmaticComboUpdate) return@addActionListener
            val choice = modelCombo.selectedItem as? ModelChoice ?: return@addActionListener
            controller.setModelReference(
                ModelReference.ByBundleAndModelId(
                    bundleId = choice.bundleId,
                    modelId = choice.modelId
                )
            )
        }
    }

    private fun wireCollectors() {
        controller.edtScope.launch {
            controller.loadedBundles.collect { _ -> rebuildDropdown() }
        }
        controller.edtScope.launch {
            controller.modelReference.collect { _ ->
                syncDropdownSelection()
                refreshCardSelection()
            }
        }
        controller.edtScope.launch {
            controller.currentModelDescriptor.collect { descriptor ->
                summaryArea.render(descriptor, controller.modelReference.value)
                refreshCardSelection()
            }
        }
    }

    // ── State synchronisation ──────────────────────────────────────────────

    private fun rebuildDropdown() {
        val choices = controller.loadedBundles.value
            .flatMap { lb -> lb.bundle.models.map { m -> choiceFor(lb, m.modelId) } }
            .filterNotNull()
        programmaticComboUpdate = true
        try {
            modelCombo.model = DefaultComboBoxModel(choices.toTypedArray())
        } finally {
            programmaticComboUpdate = false
        }
        syncDropdownSelection()
        refreshCardSelection()
    }

    private fun syncDropdownSelection() {
        val ref = controller.modelReference.value as? ModelReference.ByBundleAndModelId
            ?: return run {
                programmaticComboUpdate = true
                try { modelCombo.selectedItem = null } finally { programmaticComboUpdate = false }
            }
        val target = (0 until modelCombo.itemCount)
            .map { modelCombo.getItemAt(it) }
            .firstOrNull { it.bundleId == ref.bundleId && it.modelId == ref.modelId }
            ?: return
        if (modelCombo.selectedItem != target) {
            programmaticComboUpdate = true
            try { modelCombo.selectedItem = target } finally { programmaticComboUpdate = false }
        }
    }

    /** Decide which card to show based on the controller's current
     *  state.  Called from any state-changing collector. */
    private fun refreshCardSelection() {
        val bundlesEmpty = controller.loadedBundles.value.isEmpty()
        val ref = controller.modelReference.value
        val descriptor = controller.currentModelDescriptor.value
        when {
            bundlesEmpty && ref == null -> cards.show(this, CARD_EMPTY)
            ref == null -> cards.show(this, CARD_PICKER)
            descriptor != null -> cards.show(this, CARD_PICKER)
            else -> {
                unresolvedLabel.text = buildUnresolvedMessage(ref)
                cards.show(this, CARD_UNRESOLVED)
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

    /** Build the dropdown choice for a (bundle, modelId) pair, or
     *  `null` when the model isn't found (defensive — shouldn't
     *  happen in practice). */
    private fun choiceFor(bundle: LoadedBundle, modelId: String): ModelChoice? {
        val model = bundle.bundle.models.firstOrNull { it.modelId == modelId } ?: return null
        return ModelChoice(
            bundleId = bundle.bundle.bundleId,
            modelId = model.modelId,
            displayName = model.displayName,
            bundleDisplayName = bundle.bundle.displayName
        )
    }

    // ── Summary card ───────────────────────────────────────────────────────

    /** Read-only summary of the current model descriptor.  Lays out
     *  bundle identity + a four-row stats table; falls back to a
     *  greyed-out empty-state when no descriptor is available. */
    private class SummaryPanel : JPanel(BorderLayout()) {

        private val emptyState: JLabel = JLabel(
            "Pick a model above to see its controls, RV parameters, and responses."
        ).apply {
            foreground = Color(0x66, 0x66, 0x66)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }

        private val content: JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Selected model"),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
            )
        }

        init {
            border = BorderFactory.createEmptyBorder(0, 0, 0, 0)
            add(emptyState, BorderLayout.CENTER)
        }

        fun render(descriptor: ModelDescriptor?, ref: ModelReference?) {
            removeAll()
            if (descriptor == null) {
                add(emptyState, BorderLayout.CENTER)
                revalidate(); repaint()
                return
            }
            content.removeAll()

            val title = (ref as? ModelReference.ByBundleAndModelId)?.let {
                "${it.bundleId} / ${it.modelId}"
            } ?: descriptor.modelName

            content.add(row("Model:", title))
            val nc = descriptor.controls.numericControls.size
            val sc = descriptor.controls.stringControls.size
            val jc = descriptor.controls.jsonControls.size
            content.add(row(
                "Controls:",
                "${nc + sc + jc} total · numeric: $nc · string: $sc · JSON: $jc"
            ))
            val rvCount = descriptor.rvParameterMap.size
            val paramCount = descriptor.rvParameterMap.values.sumOf { it.size }
            content.add(row(
                "RV parameters:",
                "$rvCount random variable${if (rvCount == 1) "" else "s"} · " +
                    "$paramCount tunable parameter${if (paramCount == 1) "" else "s"}"
            ))
            content.add(row(
                "Responses:",
                "${descriptor.responseNames.size} response${if (descriptor.responseNames.size == 1) "" else "s"}"
            ))
            val rd = descriptor.experimentRunDefaults
            content.add(row(
                "Run defaults:",
                "replications=${rd.numberOfReplications}, " +
                    "length=${rd.lengthOfReplication}, " +
                    "warmUp=${rd.lengthOfReplicationWarmUp}"
            ))

            add(content, BorderLayout.NORTH)
            revalidate(); repaint()
        }

        private fun row(label: String, value: String): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
            add(JLabel(label).apply {
                font = font.deriveFont(Font.BOLD)
                preferredSize = java.awt.Dimension(110, preferredSize.height)
                maximumSize = java.awt.Dimension(110, preferredSize.height)
            })
            add(Box.createHorizontalStrut(8))
            add(JLabel(value).apply { foreground = Color(0x33, 0x33, 0x33) })
            add(Box.createHorizontalGlue())
        }
    }

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_PICKER = "picker"
        private const val CARD_UNRESOLVED = "unresolved"
    }
}
