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

package ksl.app.swing.simopt.algorithm

import ksl.app.config.optimization.RandomRestartSpec
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Reusable sub-panel that edits an optional [RandomRestartSpec].
 *
 * Layout: an enable-checkbox + a `Max restarts:` field that's
 * enabled only when the checkbox is ticked.  When the checkbox is
 * off, the panel's [value] is `null` (no random restart); when on,
 * [value] is a `RandomRestartSpec(maxNumRestarts = field's value)`
 * or `null` when the field doesn't parse.
 */
class RandomRestartEditor(
    initial: RandomRestartSpec? = null,
    private val onChanged: (RandomRestartSpec?) -> Unit = {}
) : JPanel(GridBagLayout()) {

    private val enableCheckbox = JCheckBox("Enable random restart")
    private val maxRestartsField = JTextField("5", 6)
    private val maxRestartsLabel = JLabel("Max restarts:")

    @Volatile private var suppress = false

    init {
        add(enableCheckbox, gbc(0, 0, width = 2, anchor = GridBagConstraints.WEST))

        add(maxRestartsLabel, gbc(0, 1, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 24, 2, 4)))
        add(maxRestartsField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        setValue(initial)

        enableCheckbox.addActionListener {
            applyEnablement()
            if (!suppress) onChanged(value)
        }
        maxRestartsField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun removeUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun changedUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
        })

        applyEnablement()
    }

    /** Parse the current panel into a `RandomRestartSpec?`.  Returns
     *  `null` when the checkbox is off OR the field doesn't parse to
     *  a valid positive integer. */
    val value: RandomRestartSpec?
        get() {
            if (!enableCheckbox.isSelected) return null
            val n = maxRestartsField.text.trim().toIntOrNull() ?: return null
            return try {
                RandomRestartSpec(n)
            } catch (_: IllegalArgumentException) { null }
        }

    /** Replace the editor's contents.  Passing `null` unticks the
     *  checkbox; passing a non-null spec ticks it and loads
     *  `maxNumRestarts`. */
    fun setValue(spec: RandomRestartSpec?) {
        suppress = true
        try {
            if (spec == null) {
                enableCheckbox.isSelected = false
            } else {
                enableCheckbox.isSelected = true
                maxRestartsField.text = spec.maxNumRestarts.toString()
            }
            applyEnablement()
        } finally { suppress = false }
    }

    /** Validation message when the checkbox is on but the field is
     *  invalid; `null` otherwise (including when the panel is off,
     *  which is a valid "no restart" state). */
    fun validationMessage(): String? {
        if (!enableCheckbox.isSelected) return null
        val n = maxRestartsField.text.trim().toIntOrNull()
            ?: return "Max restarts must be an integer"
        if (n <= 0) return "Max restarts must be > 0"
        return null
    }

    private fun applyEnablement() {
        val on = enableCheckbox.isSelected
        maxRestartsLabel.isEnabled = on
        maxRestartsField.isEnabled = on
    }

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
}
