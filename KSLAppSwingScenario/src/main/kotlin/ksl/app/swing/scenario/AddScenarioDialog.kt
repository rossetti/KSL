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

import ksl.app.bundle.KSLBundledModel
import ksl.app.bundle.LoadedBundle
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/**
 *  Modal *Add Scenario* dialog with cascading bundle / model picker.
 *
 *  Layout:
 *
 *  ```
 *  Bundle:  [ combo of loaded bundles ▾ ]
 *  Model:   ┌─ list of models in the chosen bundle ─┐
 *           └────────────────────────────────────────┘
 *  Description: <selected model's description, read-only>
 *  Name:    [ text field, seeded from displayName ]
 *           [ Cancel ] [ Add ]
 *  ```
 *
 *  When no bundles are loaded, the dialog body is replaced with a
 *  short hint and an OK-only button; the caller surfaces this state
 *  to the user.
 */
object AddScenarioDialog {

    /**
     *  Show the dialog over [parent], offering models from [bundles]
     *  and rejecting names in [existingNames].  Returns the new
     *  [ScenarioSpec] on Add, or `null` if the user cancelled.
     */
    fun prompt(
        parent: Component,
        bundles: List<LoadedBundle>,
        existingNames: Set<String>
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
        val owner: Window = SwingUtilities.getWindowAncestor(parent as? JComponent ?: return null)
            ?: JOptionPane.getRootFrame()
        val dialog = AddDialog(owner, bundles, existingNames)
        dialog.isVisible = true
        return dialog.result
    }

    private class AddDialog(
        owner: Window,
        private val bundles: List<LoadedBundle>,
        private val existingNames: Set<String>
    ) : JDialog(owner, "Add Scenario", ModalityType.APPLICATION_MODAL) {

        var result: ScenarioSpec? = null
            private set

        private val bundleCombo = JComboBox(DefaultComboBoxModel(bundles.toTypedArray())).apply {
            renderer = BundleRenderer()
        }
        private val modelListModel = DefaultListModel<KSLBundledModel>()
        private val modelList = JList(modelListModel).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = ModelRenderer()
            visibleRowCount = 6
        }
        private val descriptionArea = JTextArea(3, 40).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }
        private val nameField = JTextField(24)
        private val addButton = JButton("Add")
        private val cancelButton = JButton("Cancel")

        init {
            defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
            contentPane.layout = BorderLayout()
            contentPane.add(buildForm(), BorderLayout.CENTER)
            contentPane.add(buildButtons(), BorderLayout.SOUTH)
            rootPane.defaultButton = addButton

            bundleCombo.addActionListener { refreshModelsForBundle() }
            modelList.addListSelectionListener { e ->
                if (!e.valueIsAdjusting) refreshDescriptionAndName()
            }
            addButton.addActionListener { tryAccept() }
            cancelButton.addActionListener { dispose() }

            refreshModelsForBundle()
            pack()
            setLocationRelativeTo(owner)
        }

        private fun buildForm(): JPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createEmptyBorder(12, 12, 8, 12)
            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.LINE_START
                insets = Insets(2, 2, 2, 8)
            }
            add(JLabel("Bundle:"), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
            add(bundleCombo, gbc)

            gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
            gbc.anchor = GridBagConstraints.FIRST_LINE_START
            add(JLabel("Model:"), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.BOTH; gbc.weightx = 1.0; gbc.weighty = 1.0
            add(JScrollPane(modelList).apply { preferredSize = Dimension(360, 140) }, gbc)

            gbc.gridx = 0; gbc.gridy = 2; gbc.fill = GridBagConstraints.NONE; gbc.weighty = 0.0
            add(JLabel("Description:"), gbc)
            gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL
            add(JScrollPane(descriptionArea).apply { preferredSize = Dimension(360, 60) }, gbc)

            gbc.gridx = 0; gbc.gridy = 3
            add(JLabel("Name:"), gbc)
            gbc.gridx = 1
            add(nameField, gbc)
        }

        private fun buildButtons(): JPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(8))
            add(addButton)
        }

        private fun refreshModelsForBundle() {
            val bundle = bundleCombo.selectedItem as? LoadedBundle ?: return
            modelListModel.clear()
            bundle.bundle.models.forEach { modelListModel.addElement(it) }
            if (modelListModel.size > 0) modelList.selectedIndex = 0
        }

        private fun refreshDescriptionAndName() {
            val model = modelList.selectedValue
            if (model == null) {
                descriptionArea.text = ""
                return
            }
            descriptionArea.text = model.description
            descriptionArea.caretPosition = 0
            // Seed the name only when the user hasn't typed something custom yet.
            if (nameField.text.isBlank() ||
                bundles.any { b -> b.bundle.models.any { it.displayName == nameField.text } }
            ) {
                nameField.text = uniqueName(model.displayName)
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
            val bundle = bundleCombo.selectedItem as? LoadedBundle ?: return
            val model = modelList.selectedValue ?: run {
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
                    bundleId = bundle.bundle.bundleId,
                    modelId = model.modelId
                )
            )
            dispose()
        }
    }

    private class BundleRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val text = (value as? LoadedBundle)?.let { "${it.bundle.bundleId}  (${it.bundle.models.size} model${if (it.bundle.models.size == 1) "" else "s"})" }
                ?: value?.toString().orEmpty()
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }

    private class ModelRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?, value: Any?, index: Int,
            isSelected: Boolean, cellHasFocus: Boolean
        ): Component {
            val text = (value as? KSLBundledModel)?.let { "${it.displayName}  —  ${it.modelId}" }
                ?: value?.toString().orEmpty()
            return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
        }
    }
}
