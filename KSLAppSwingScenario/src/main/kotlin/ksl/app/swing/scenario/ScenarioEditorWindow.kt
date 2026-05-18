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

import kotlinx.coroutines.launch
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import ksl.app.swing.common.editor.ControlOverridesPanel
import ksl.app.swing.common.editor.ParameterPanel
import ksl.app.swing.common.editor.RVOverridesPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextField
import javax.swing.WindowConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 *  Modeless per-scenario editor.  Hosts the three reusable editor
 *  panels (Run Parameters, Control Overrides, RV Overrides) from
 *  [ksl.app.swing.common.editor] plus a per-scenario *Output* tab
 *  with the CSV toggles ([ScenarioSpec.enableReplicationCSV] /
 *  [ScenarioSpec.enableExperimentCSV]).
 *
 *  Edits are buffered in a [ScenarioEditBuffer]; *Commit* writes the
 *  buffer back to the parent controller via [ScenarioAppController.updateScenario]
 *  and disposes the window.  *Cancel* discards.  Closing the window
 *  with unsaved changes prompts.
 */
class ScenarioEditorWindow(
    private val controller: ScenarioAppController,
    private val scenarioIndex: Int,
    private val buffer: ScenarioEditBuffer,
    private val existingNames: Set<String>
) : JFrame("Edit Scenario — ${buffer.name.value}") {

    private val nameField = JTextField(buffer.name.value, 24)
    private val replicationCsv = JCheckBox("Per-replication CSV", buffer.enableReplicationCSV.value)
    private val experimentCsv = JCheckBox("Across-replication CSV", buffer.enableExperimentCSV.value)

    private val commitButton = JButton("Commit").apply { isEnabled = false }
    private val cancelButton = JButton("Cancel")

    init {
        defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
        preferredSize = Dimension(900, 600)
        contentPane.layout = BorderLayout()
        contentPane.add(buildHeader(), BorderLayout.NORTH)
        contentPane.add(buildTabs(), BorderLayout.CENTER)
        contentPane.add(buildButtons(), BorderLayout.SOUTH)

        wireFields()
        wireDirtyToCommit()
        wireTitleSync()

        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent?) {
                if (confirmDiscard()) close()
            }
        })
    }

    private fun buildHeader(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
        val gbc = GridBagConstraints().apply {
            gridx = 0; gridy = 0
            insets = Insets(2, 2, 2, 8)
            anchor = GridBagConstraints.LINE_START
        }
        add(JLabel("Model:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        add(JLabel(describeRef(buffer.modelReference)).apply {
            foreground = java.awt.Color(0x33, 0x33, 0x33)
        }, gbc)

        gbc.gridx = 0; gbc.gridy = 1; gbc.fill = GridBagConstraints.NONE; gbc.weightx = 0.0
        add(JLabel("Name:"), gbc)
        gbc.gridx = 1; gbc.fill = GridBagConstraints.HORIZONTAL; gbc.weightx = 1.0
        add(nameField, gbc)
    }

    private fun buildTabs(): JTabbedPane {
        val tabs = JTabbedPane()
        val parameterPanel = ParameterPanel(buffer)
        tabs.addTab("Run Parameters", JScrollPane(parameterPanel).apply {
            border = BorderFactory.createEmptyBorder()
        })
        val controlPanel = ControlOverridesPanel(buffer)
        if (controlPanel.isVisible) tabs.addTab("Control Overrides", controlPanel)
        val rvPanel = RVOverridesPanel(buffer)
        if (rvPanel.isVisible) tabs.addTab("RV Overrides", rvPanel)
        tabs.addTab("Output", buildOutputTab())
        return tabs
    }

    private fun buildOutputTab(): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(JLabel("Per-scenario CSV writes (in addition to the document-level KSL database):"))
        add(Box.createVerticalStrut(8))
        replicationCsv.alignmentX = LEFT_ALIGNMENT
        experimentCsv.alignmentX = LEFT_ALIGNMENT
        add(replicationCsv)
        add(experimentCsv)
        add(Box.createVerticalGlue())
    }

    private fun buildButtons(): JPanel {
        cancelButton.addActionListener { onCancel() }
        commitButton.addActionListener { onCommit() }
        // Use the *frame's* root pane (this@ScenarioEditorWindow.rootPane),
        // not the panel's — JPanel.getRootPane() is null until the panel is
        // added to a window.
        this@ScenarioEditorWindow.rootPane.defaultButton = commitButton
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = BorderFactory.createEmptyBorder(0, 12, 12, 12)
            add(Box.createHorizontalGlue())
            add(cancelButton)
            add(Box.createHorizontalStrut(8))
            add(commitButton)
        }
    }

    private fun wireFields() {
        nameField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = sync()
            override fun removeUpdate(e: DocumentEvent) = sync()
            override fun changedUpdate(e: DocumentEvent) = sync()
            private fun sync() { buffer.setName(nameField.text) }
        })
        replicationCsv.addActionListener { buffer.setEnableReplicationCSV(replicationCsv.isSelected) }
        experimentCsv.addActionListener { buffer.setEnableExperimentCSV(experimentCsv.isSelected) }
    }

    private fun wireDirtyToCommit() {
        buffer.edtScope.launch {
            buffer.isDirty.collect { dirty ->
                commitButton.isEnabled = dirty
            }
        }
    }

    private fun wireTitleSync() {
        buffer.edtScope.launch {
            buffer.name.collect { n ->
                title = "Edit Scenario — ${n.ifBlank { "<unnamed>" }}"
            }
        }
    }

    private fun onCommit() {
        val name = buffer.name.value.trim()
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(
                this, "Name must be non-blank.", "Commit", JOptionPane.WARNING_MESSAGE
            )
            return
        }
        // Name collision against any OTHER scenario.  existingNames was
        // captured at open time and excludes this scenario's own original
        // name, so a no-rename commit doesn't false-positive here.
        if (name in existingNames) {
            JOptionPane.showMessageDialog(
                this,
                "A scenario named '$name' already exists.\nPick a different name.",
                "Commit", JOptionPane.WARNING_MESSAGE
            )
            return
        }
        val updated = buffer.toSpec().copy(name = name)
        // Preserve the original skipOnRun — the editor doesn't expose it.
        val preserved = updated.copy(
            skipOnRun = controller.scenarios.value.getOrNull(scenarioIndex)?.skipOnRun ?: false
        )
        controller.updateScenario(scenarioIndex, preserved)
        close()
    }

    private fun onCancel() {
        if (confirmDiscard()) close()
    }

    private fun confirmDiscard(): Boolean {
        if (!buffer.isDirty.value) return true
        val choice = JOptionPane.showConfirmDialog(
            this,
            "Discard unsaved edits to this scenario?",
            "Unsaved Changes",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE
        )
        return choice == JOptionPane.YES_OPTION
    }

    private fun close() {
        buffer.close()
        dispose()
    }

    private fun describeRef(ref: ModelReference): String = when (ref) {
        is ModelReference.ByBundleAndModelId -> "${ref.bundleId} / ${ref.modelId}"
        is ModelReference.ByProviderId -> ref.providerId
        is ModelReference.ByJar -> ref.jarPath
        is ModelReference.Embedded -> "embedded: ${ref.modelName}"
    }
}
