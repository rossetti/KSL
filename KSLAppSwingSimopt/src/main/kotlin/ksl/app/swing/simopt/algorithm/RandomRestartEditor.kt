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
    private val concurrentField = JTextField("1", 6)
    private val concurrentLabel = JLabel("Concurrent restarts:")

    @Volatile private var suppress = false

    init {
        add(enableCheckbox, gbc(0, 0, width = 2, anchor = GridBagConstraints.WEST))

        add(maxRestartsLabel, gbc(0, 1, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 24, 2, 4)))
        add(maxRestartsField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(concurrentLabel, gbc(0, 2, anchor = GridBagConstraints.WEST,
            insets = Insets(2, 24, 2, 4)))
        add(concurrentField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))
        val concurrentTip = "<html>Number of restarts allowed to run at the same time, each on its own<br>" +
                "worker with private evaluation resources.  1 = sequential (the classic<br>" +
                "behavior).  Results are reproducible and do not depend on the worker<br>" +
                "count.  Only supported for Stochastic Hill Climbing and Simulated<br>" +
                "Annealing, and mutually exclusive with parallel evaluation (Run Setup ><br>" +
                "Evaluation).  Live progress arrives in bursts: restarts are reported in<br>" +
                "order as each completes.</html>"
        concurrentLabel.toolTipText = concurrentTip
        concurrentField.toolTipText = concurrentTip

        setValue(initial)

        enableCheckbox.addActionListener {
            applyEnablement()
            if (!suppress) onChanged(value)
        }
        val documentListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun removeUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun changedUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
        }
        maxRestartsField.document.addDocumentListener(documentListener)
        concurrentField.document.addDocumentListener(documentListener)

        applyEnablement()
    }

    /** Parse the current panel into a `RandomRestartSpec?`.  Returns
     *  `null` when the checkbox is off OR either field doesn't parse to
     *  a valid integer in range. */
    val value: RandomRestartSpec?
        get() {
            if (!enableCheckbox.isSelected) return null
            val n = maxRestartsField.text.trim().toIntOrNull() ?: return null
            val c = concurrentField.text.trim().toIntOrNull() ?: return null
            return try {
                RandomRestartSpec(maxNumRestarts = n, concurrentRestarts = c)
            } catch (_: IllegalArgumentException) { null }
        }

    /** Replace the editor's contents.  Passing `null` unticks the
     *  checkbox; passing a non-null spec ticks it and loads
     *  `maxNumRestarts` and `concurrentRestarts`. */
    fun setValue(spec: RandomRestartSpec?) {
        suppress = true
        try {
            if (spec == null) {
                enableCheckbox.isSelected = false
            } else {
                enableCheckbox.isSelected = true
                maxRestartsField.text = spec.maxNumRestarts.toString()
                concurrentField.text = spec.concurrentRestarts.toString()
            }
            applyEnablement()
        } finally { suppress = false }
    }

    /** Validation message when the checkbox is on but a field is
     *  invalid; `null` otherwise (including when the panel is off,
     *  which is a valid "no restart" state). */
    fun validationMessage(): String? {
        if (!enableCheckbox.isSelected) return null
        val n = maxRestartsField.text.trim().toIntOrNull()
            ?: return "Max restarts must be an integer"
        if (n <= 0) return "Max restarts must be > 0"
        val c = concurrentField.text.trim().toIntOrNull()
            ?: return "Concurrent restarts must be an integer"
        if (c < 1) return "Concurrent restarts must be >= 1"
        return null
    }

    private fun applyEnablement() {
        val on = enableCheckbox.isSelected
        maxRestartsLabel.isEnabled = on
        maxRestartsField.isEnabled = on
        concurrentLabel.isEnabled = on
        concurrentField.isEnabled = on
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
