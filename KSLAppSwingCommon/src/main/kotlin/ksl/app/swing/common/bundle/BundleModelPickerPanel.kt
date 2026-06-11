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

package ksl.app.swing.common.bundle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import ksl.app.bundle.LoadedBundle
import ksl.app.bundle.bundleSourceLabel
import ksl.app.config.ModelReference
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
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
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 *  The shared two-step bundle → model picker used by every bundle-driven app
 *  (Experiment / Simopt / Scenario / Single): a **Bundle** dropdown (hidden
 *  when only one bundle is loaded) above a **Model** dropdown scoped to the
 *  selected bundle, with a read-only **model-info table** below.  Replaces the
 *  near-identical hand-rolled pickers each app used to carry.
 *
 *  It is controller-agnostic — driven by three flows and one callback:
 *
 *  - [loadedBundles] populates the bundle dropdown (disambiguated by version +
 *    source via [bundlePickerLabel]); copies of one `bundleId` from different
 *    JARs are individually pickable.
 *  - [currentReference] drives which bundle/model is selected; the panel
 *    re-selects to match it (keeping the user's chosen source when the
 *    referenced `bundleId` already matches — the reference isn't source-aware).
 *  - [currentDescriptor] feeds the info table (controls / RV parameters /
 *    responses / run defaults of the currently-resolved model).
 *  - [onSelect] fires with `(bundleId, modelId)` whenever the user changes the
 *    selection.  The host decides what to do — commit a reference, run a
 *    switch-prompt, stash a pending choice — this panel never mutates state
 *    itself.  After a host *rejects* a selection (e.g. a cancelled
 *    switch-prompt that leaves [currentReference] unchanged), call
 *    [syncToReference] to revert the combos.
 *
 *  The host supplies its own surrounding chrome (empty / unresolved states,
 *  run-parameter overrides, the switch-prompt); this panel is just the picker
 *  plus the info table.
 *
 *  Threading: construct and use on the Swing EDT.  Flow collectors run on
 *  [Dispatchers.Swing].
 */
class BundleModelPickerPanel(
    private val loadedBundles: StateFlow<List<LoadedBundle>>,
    private val currentReference: StateFlow<ModelReference?>,
    currentDescriptor: StateFlow<ModelDescriptor?>,
    scope: CoroutineScope,
    private val onSelect: (bundleId: String, modelId: String) -> Unit
) : JPanel(BorderLayout(0, 12)) {

    /** One row in the bundle dropdown.  Carries version + source so copies of
     *  the same `bundleId` loaded from different JARs render distinctly. */
    private data class BundleChoice(
        val bundleId: String,
        val bundleDisplayName: String,
        val version: String,
        val sourceLabel: String
    ) {
        override fun toString(): String =
            bundlePickerLabel(bundleDisplayName.ifBlank { bundleId }, version, sourceLabel)
    }

    /** One row in the model dropdown, scoped to the selected bundle. */
    private data class ModelChoice(
        val bundleId: String,
        val modelId: String,
        val displayName: String
    ) {
        override fun toString(): String =
            if (displayName.isBlank() || displayName.equals(modelId, ignoreCase = true)) modelId
            else "$displayName ($modelId)"
    }

    private val bundleCombo: JComboBox<BundleChoice> = JComboBox()
    private val modelCombo: JComboBox<ModelChoice> = JComboBox()
    private val summaryPanel = SummaryPanel()

    /** Bundle row (label + combo); hidden when ≤ 1 bundle is loaded. */
    private val bundleRow: JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        add(JLabel("Bundle: ").apply { font = font.deriveFont(Font.BOLD) })
        add(Box.createHorizontalStrut(8))
        add(bundleCombo)
        add(Box.createHorizontalGlue())
    }

    /** Guards combo ActionListeners against programmatic `setSelectedItem`
     *  calls so a sync/rebuild doesn't fire [onSelect]. */
    private var programmaticComboUpdate: Boolean = false

    init {
        val modelRow = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(JLabel("Model: ").apply { font = font.deriveFont(Font.BOLD) })
            add(Box.createHorizontalStrut(8))
            add(modelCombo)
            add(Box.createHorizontalGlue())
        }
        val pickerRows = JPanel(GridBagLayout()).apply {
            val gbc = GridBagConstraints().apply {
                gridx = 0; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL
                anchor = GridBagConstraints.NORTHWEST
                insets = Insets(0, 0, 6, 0)
            }
            gbc.gridy = 0; add(bundleRow, gbc)
            gbc.gridy = 1; add(modelRow, gbc)
        }
        add(pickerRows, BorderLayout.NORTH)
        add(
            JScrollPane(
                summaryPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            ).apply {
                border = BorderFactory.createEmptyBorder()
                verticalScrollBar.unitIncrement = 16
            },
            BorderLayout.CENTER
        )

        bundleCombo.addActionListener {
            if (programmaticComboUpdate) return@addActionListener
            rebuildModelDropdownForSelectedBundle()
            // Picking a new bundle auto-selects its first model; report it.
            (modelCombo.selectedItem as? ModelChoice)?.let { onSelect(it.bundleId, it.modelId) }
        }
        modelCombo.addActionListener {
            if (programmaticComboUpdate) return@addActionListener
            (modelCombo.selectedItem as? ModelChoice)?.let { onSelect(it.bundleId, it.modelId) }
        }

        scope.launch(Dispatchers.Swing) {
            loadedBundles.collect { rebuildBundleDropdown() }
        }
        scope.launch(Dispatchers.Swing) {
            currentReference.collect { syncDropdownsToController() }
        }
        scope.launch(Dispatchers.Swing) {
            currentDescriptor.collect { summaryPanel.render(it, currentReference.value) }
        }
    }

    /**
     *  Re-select the combos to match [currentReference].  Call after the host
     *  rejects an [onSelect] (e.g. a cancelled switch-prompt) so the visible
     *  selection reverts to the unchanged reference.
     */
    fun syncToReference() = syncDropdownsToController()

    /**
     *  The currently selected `(bundleId, modelId)`, or `null` when no model is
     *  selected.  Lets a one-shot host (e.g. a modal dialog) read the choice
     *  directly instead of tracking every [onSelect].
     */
    fun selectedModel(): Pair<String, String>? =
        (modelCombo.selectedItem as? ModelChoice)?.let { it.bundleId to it.modelId }

    // ── State synchronisation ──────────────────────────────────────────────

    private fun rebuildBundleDropdown() {
        val bundles = loadedBundles.value
        val choices = bundles.map { lb ->
            BundleChoice(
                bundleId = lb.bundle.bundleId,
                bundleDisplayName = lb.bundle.displayName,
                version = lb.bundle.version,
                sourceLabel = bundleSourceLabel(lb)
            )
        }
        programmaticComboUpdate = true
        try {
            bundleCombo.model = DefaultComboBoxModel(choices.toTypedArray())
            // A bundle picker only earns its space when > 1 is loaded.
            bundleRow.isVisible = choices.size > 1
        } finally {
            programmaticComboUpdate = false
        }
        rebuildModelDropdownForSelectedBundle()
        syncDropdownsToController()
    }

    private fun rebuildModelDropdownForSelectedBundle() {
        val bundles = loadedBundles.value
        val selected = bundleCombo.selectedItem as? BundleChoice
        val targetBundle: LoadedBundle? = when {
            selected != null ->
                // Match the specific source so the model list follows the exact
                // bundle copy the user picked, not just the bundleId.
                bundles.firstOrNull {
                    it.bundle.bundleId == selected.bundleId &&
                        bundleSourceLabel(it) == selected.sourceLabel
                }
            bundles.size == 1 -> bundles.first()
            else -> null
        }
        val modelChoices = targetBundle?.bundle?.models?.map { m ->
            ModelChoice(
                bundleId = targetBundle.bundle.bundleId,
                modelId = m.modelId,
                displayName = m.displayName
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
        val ref = currentReference.value as? ModelReference.ByBundleAndModelId
            ?: return run {
                programmaticComboUpdate = true
                try { modelCombo.selectedItem = null } finally { programmaticComboUpdate = false }
            }
        // Bundle selection.  The reference isn't source-aware, so if the
        // current selection already matches the referenced bundleId keep the
        // user's chosen source rather than snapping to the first same-id entry.
        val currentBundle = bundleCombo.selectedItem as? BundleChoice
        val targetBundle = (0 until bundleCombo.itemCount)
            .map { bundleCombo.getItemAt(it) }
            .firstOrNull { it.bundleId == ref.bundleId }
        if (targetBundle != null &&
            currentBundle?.bundleId != ref.bundleId &&
            bundleCombo.selectedItem != targetBundle
        ) {
            programmaticComboUpdate = true
            try {
                bundleCombo.selectedItem = targetBundle
                rebuildModelDropdownForSelectedBundle()
            } finally { programmaticComboUpdate = false }
        }
        // Model selection.
        val targetModel = (0 until modelCombo.itemCount)
            .map { modelCombo.getItemAt(it) }
            .firstOrNull { it.bundleId == ref.bundleId && it.modelId == ref.modelId }
            ?: return
        if (modelCombo.selectedItem != targetModel) {
            programmaticComboUpdate = true
            try { modelCombo.selectedItem = targetModel } finally { programmaticComboUpdate = false }
        }
    }

    // ── Model-info table ───────────────────────────────────────────────────

    /** Read-only summary of the current model descriptor: bundle identity +
     *  a controls / RV-parameter / response / run-default table; falls back to
     *  a greyed-out empty state when no descriptor is available. */
    private class SummaryPanel : JPanel(BorderLayout()) {

        private val emptyState: JLabel = JLabel(
            "Pick a model above to see its controls, RV parameters, and responses."
        ).apply {
            foreground = Color(0x66, 0x66, 0x66)
            border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        }

        private val content: JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
        }

        init {
            border = BorderFactory.createTitledBorder("Selected model")
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
            content.add(row("Controls:", "${nc + sc + jc} total · numeric: $nc · string: $sc · JSON: $jc"))
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
                preferredSize = Dimension(110, preferredSize.height)
                maximumSize = Dimension(110, preferredSize.height)
            })
            add(Box.createHorizontalStrut(8))
            add(JLabel(value).apply { foreground = Color(0x33, 0x33, 0x33) })
            add(Box.createHorizontalGlue())
        }
    }
}
