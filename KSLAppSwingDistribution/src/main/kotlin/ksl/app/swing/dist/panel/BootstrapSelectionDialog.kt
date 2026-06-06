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

package ksl.app.swing.dist.panel

import ksl.app.dist.config.BootstrapConfig
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/** Result of the parameter-bootstrap dialog: the config (null = disabled) and apply-to-all flag. */
data class BootstrapChoice(
    val config: BootstrapConfig?,
    val applyToAll: Boolean
)

/**
 * Modal dialog to configure the per-fit parameter-estimate bootstrap (Kind 1)
 * for one (continuous) dataset. Disabling it yields a null config — the
 * bootstrap is then neither computed nor displayed. On OK, [choice] holds the
 * selection; on Cancel it stays null. (The family-frequency bootstrap is a
 * separate analysis in the Bootstrap tab.)
 */
class BootstrapSelectionDialog(
    owner: Window?,
    datasetName: String,
    current: BootstrapConfig?
) : JDialog(owner, "Parameter bootstrap — $datasetName", ModalityType.APPLICATION_MODAL) {

    var choice: BootstrapChoice? = null
        private set

    private val enableCheck = JCheckBox("Bootstrap fitted parameters", current != null)
    private val samplesField = JTextField((current?.sampleSize ?: 399).toString(), 6)
    private val levelField = JTextField((current?.level ?: 0.95).toString(), 6)
    private val streamField = JTextField((current?.streamNumber ?: 0).toString(), 4)
    private val applyToAllCheck = JCheckBox("Apply to all continuous datasets")
    private val errorLabel = JLabel(" ")

    init {
        layout = BorderLayout()
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)
        enableCheck.addActionListener { updateEnabled() }
        updateEnabled()
        pack()
        setLocationRelativeTo(owner)
    }

    private fun buildCenter(): JPanel {
        val enableRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply { add(enableCheck) }
        val paramRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Samples:")); add(samplesField)
            add(JLabel("Level:")); add(levelField)
            add(JLabel("Stream:")); add(streamField)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(enableRow)
            add(paramRow)
        }
    }

    private fun buildSouth(): JPanel {
        val applyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { add(applyToAllCheck) }
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(JButton("Cancel").apply { addActionListener { choice = null; dispose() } })
            add(JButton("OK").apply { addActionListener { onOk() } })
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(errorLabel) })
            add(JPanel(BorderLayout()).apply {
                add(applyRow, BorderLayout.WEST)
                add(buttonRow, BorderLayout.EAST)
            })
        }
    }

    private fun updateEnabled() {
        val on = enableCheck.isSelected
        samplesField.isEnabled = on
        levelField.isEnabled = on
        streamField.isEnabled = on
    }

    private fun onOk() {
        if (!enableCheck.isSelected) {
            choice = BootstrapChoice(config = null, applyToAll = applyToAllCheck.isSelected)
            dispose()
            return
        }
        val samples = samplesField.text.trim().toIntOrNull()
        val level = levelField.text.trim().toDoubleOrNull()
        val stream = streamField.text.trim().toIntOrNull()
        when {
            samples == null || samples <= 0 -> errorLabel.text = "⚠ samples must be a positive integer"
            level == null || level <= 0.0 || level >= 1.0 -> errorLabel.text = "⚠ level must be in (0, 1)"
            stream == null || stream < 0 -> errorLabel.text = "⚠ stream must be a non-negative integer"
            else -> {
                choice = BootstrapChoice(
                    config = BootstrapConfig(sampleSize = samples, level = level, streamNumber = stream),
                    applyToAll = applyToAllCheck.isSelected
                )
                dispose()
            }
        }
    }
}
