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

package ksl.app.swing.scenario

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import ksl.app.swing.common.bundle.BundleModelPickerPanel
import ksl.simulation.ModelDescriptor
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 *  Modal *Add Scenario* dialog built around the shared
 *  [BundleModelPickerPanel] — the same bundle → model two-step + model-info
 *  table the Experiment and Simopt apps use — plus a scenario *Name* field.
 *
 *  Remembers the last-selected bundle across Adds: the caller passes the
 *  previously-chosen `bundleId` so the picker opens on that bundle, instead of
 *  forcing a re-select for each additional model.
 *
 *  When no bundles are loaded, [prompt] shows a short hint and returns `null`.
 */
object AddScenarioDialog {

    /**
     *  Show the dialog over [parent], offering models from [bundles] and
     *  rejecting names in [existingNames].  [rememberedBundleId], when it names
     *  a loaded bundle, pre-selects that bundle.  Returns the new [ScenarioSpec]
     *  on Add, or `null` if the user cancelled.
     */
    fun prompt(
        parent: Component,
        bundles: List<LoadedBundle>,
        existingNames: Set<String>,
        rememberedBundleId: String? = null
    ): ScenarioSpec? {
        if (bundles.isEmpty()) {
            JOptionPane.showMessageDialog(
                parent,
                "No bundles are loaded.\nUse Bundles → Load Bundle JAR… first.",
                "Add Scenario",
                JOptionPane.INFORMATION_MESSAGE
            )
            return null
        }
        val owner: Window = when (parent) {
            is Window -> parent
            else -> SwingUtilities.getWindowAncestor(parent) ?: JOptionPane.getRootFrame() ?: Frame()
        }
        val dialog = AddDialog(owner, bundles, existingNames, rememberedBundleId)
        dialog.isVisible = true
        return dialog.result
    }

    private class AddDialog(
        owner: Window,
        private val bundles: List<LoadedBundle>,
        private val existingNames: Set<String>,
        rememberedBundleId: String?
    ) : JDialog(owner, "Add Scenario", ModalityType.APPLICATION_MODAL) {

        var result: ScenarioSpec? = null
            private set

        /** `true` once the user has typed a name themselves; suppresses
         *  auto-reseed when switching models. */
        private var userEditedName: Boolean = false

        /** Set while updating [nameField] programmatically so the document
         *  listener doesn't mark it as user-edited. */
        private var settingNameProgrammatically: Boolean = false

        /** Dialog-lifetime scope for the picker's flow collectors and the
         *  off-EDT descriptor resolution; cancelled on close.  The default
         *  dispatcher is fine — the picker re-dispatches its own UI work to the
         *  EDT, and the descriptor build wants to be off the EDT anyway. */
        private val scope = CoroutineScope(SupervisorJob())

        private val bundlesFlow = MutableStateFlow(bundles)
        private val referenceFlow = MutableStateFlow<ModelReference?>(seedReference(rememberedBundleId))
        private val descriptorFlow = MutableStateFlow<ModelDescriptor?>(null)

        private val picker = BundleModelPickerPanel(
            loadedBundles = bundlesFlow,
            currentReference = referenceFlow,
            currentDescriptor = descriptorFlow,
            scope = scope,
            onSelect = ::onModelSelected
        )

        private val nameField = JTextField(24)
        private val addButton = JButton("Add")
        private val cancelButton = JButton("Cancel")

        init {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            addWindowListener(object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent?) = scope.cancel()
            })
            contentPane.layout = BorderLayout()
            contentPane.add(buildForm(), BorderLayout.CENTER)
            contentPane.add(buildButtons(), BorderLayout.SOUTH)
            rootPane.defaultButton = addButton

            nameField.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = mark()
                override fun removeUpdate(e: DocumentEvent) = mark()
                override fun changedUpdate(e: DocumentEvent) = mark()
                private fun mark() {
                    if (settingNameProgrammatically) return
                    // Clearing the field back to blank resumes auto-tracking.
                    userEditedName = nameField.text.isNotBlank()
                }
            })
            addButton.addActionListener { tryAccept() }
            cancelButton.addActionListener { dispose() }

            // Seed the name + model-info for the initial (remembered / first)
            // selection — the picker's programmatic sync to it doesn't fire
            // onSelect, so prime it here.
            (referenceFlow.value as? ModelReference.ByBundleAndModelId)?.let {
                onModelSelected(it.bundleId, it.modelId)
            }

            pack()
            size = Dimension(560, 480)
            setLocationRelativeTo(owner)
        }

        /** First model of the remembered bundle (or the first loaded bundle),
         *  so the picker opens on a sensible default. */
        private fun seedReference(rememberedBundleId: String?): ModelReference? {
            val bundle = rememberedBundleId?.let { id -> bundles.firstOrNull { it.bundle.bundleId == id } }
                ?: bundles.firstOrNull()
            val model = bundle?.bundle?.models?.firstOrNull() ?: return null
            return ModelReference.ByBundleAndModelId(bundle.bundle.bundleId, model.modelId)
        }

        private fun buildForm(): JPanel = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
            add(picker, BorderLayout.CENTER)
            val nameRow = JPanel(GridBagLayout()).apply {
                val gbc = GridBagConstraints().apply {
                    gridx = 0; gridy = 0
                    anchor = GridBagConstraints.LINE_START
                    insets = Insets(2, 2, 2, 8)
                }
                add(JLabel("Name:"), gbc)
                gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
                add(nameField, gbc)
            }
            add(nameRow, BorderLayout.SOUTH)
        }

        private fun buildButtons(): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(8))
            add(addButton)
        }

        /** A model was picked (by the user or the initial seed): reseed the
         *  name and resolve the model-info descriptor off the EDT. */
        private fun onModelSelected(bundleId: String, modelId: String) {
            if (!userEditedName) {
                setNameFromModel(displayNameFor(bundleId, modelId))
            }
            descriptorFlow.value = null
            scope.launch {
                descriptorFlow.value =
                    runCatching { bundleFor(bundleId)?.descriptorFor(modelId) }.getOrNull()
            }
        }

        private fun bundleFor(bundleId: String): LoadedBundle? =
            bundles.firstOrNull { it.bundle.bundleId == bundleId }

        private fun displayNameFor(bundleId: String, modelId: String): String =
            bundleFor(bundleId)?.bundle?.models?.firstOrNull { it.modelId == modelId }?.displayName ?: modelId

        private fun setNameFromModel(displayName: String) {
            settingNameProgrammatically = true
            try {
                nameField.text = uniqueName(displayName)
            } finally {
                settingNameProgrammatically = false
            }
        }

        private fun uniqueName(seed: String): String {
            if (seed !in existingNames) return seed
            var n = 2
            while (true) {
                val candidate = "$seed ($n)"
                if (candidate !in existingNames) return candidate
                n++
            }
        }

        private fun tryAccept() {
            val selection = picker.selectedModel() ?: run {
                JOptionPane.showMessageDialog(
                    this, "Pick a model.", "Add Scenario", JOptionPane.WARNING_MESSAGE
                )
                return
            }
            val name = nameField.text.trim()
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(
                    this, "Name must be non-blank.", "Add Scenario", JOptionPane.WARNING_MESSAGE
                )
                return
            }
            if (name in existingNames) {
                JOptionPane.showMessageDialog(
                    this,
                    "A scenario named '$name' already exists.\nPick a different name.",
                    "Add Scenario", JOptionPane.WARNING_MESSAGE
                )
                return
            }
            result = ScenarioSpec(
                name = name,
                modelReference = ModelReference.ByBundleAndModelId(
                    bundleId = selection.first,
                    modelId = selection.second
                )
            )
            dispose()
        }
    }
}
