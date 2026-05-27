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

import ksl.app.config.optimization.CESamplerSpec
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Reusable sub-panel that edits a [CESamplerSpec.Normal].
 *
 * The substrate's [CESamplerSpec] is a sealed type designed for
 * future sampler variants, but only [CESamplerSpec.Normal] is
 * implemented today.  Per the Phase O6 design decision, this editor
 * commits to the Normal variant — no type picker, just the four
 * Normal-specific fields.  When a new sampler lands in the substrate
 * we can re-introduce a picker and a CardLayout body.
 *
 * Fields and defaults match the substrate's [CESamplerSpec.Normal]
 * default-argument values.
 */
class CeSamplerEditor(
    initial: CESamplerSpec.Normal = CESamplerSpec.Normal(),
    private val onChanged: (CESamplerSpec?) -> Unit = {}
) : JPanel(GridBagLayout()) {

    private val meanSmootherField = JTextField(initial.meanSmoother.toString(), 10)
    private val sdSmootherField = JTextField(initial.sdSmoother.toString(), 10)
    private val cvThresholdField = JTextField(initial.coefficientOfVariationThreshold.toString(), 10)
    private val streamNumField = JTextField(initial.streamNum.toString(), 10)

    @Volatile private var suppress = false

    init {
        add(JLabel("Mean smoother (α_μ):"), gbc(0, 0))
        add(meanSmootherField, gbc(1, 0, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("SD smoother (α_σ):"), gbc(0, 1))
        add(sdSmootherField, gbc(1, 1, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("CV threshold:"), gbc(0, 2))
        add(cvThresholdField, gbc(1, 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        add(JLabel("Sampler stream number:"), gbc(0, 3))
        add(streamNumField, gbc(1, 3, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL))

        val docListener = object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun removeUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
            override fun changedUpdate(e: DocumentEvent?) { if (!suppress) onChanged(value) }
        }
        meanSmootherField.document.addDocumentListener(docListener)
        sdSmootherField.document.addDocumentListener(docListener)
        cvThresholdField.document.addDocumentListener(docListener)
        streamNumField.document.addDocumentListener(docListener)
    }

    val value: CESamplerSpec?
        get() {
            val m = meanSmootherField.text.trim().toDoubleOrNull() ?: return null
            val s = sdSmootherField.text.trim().toDoubleOrNull() ?: return null
            val cv = cvThresholdField.text.trim().toDoubleOrNull() ?: return null
            val sn = streamNumField.text.trim().toIntOrNull() ?: return null
            return try {
                CESamplerSpec.Normal(m, s, cv, sn)
            } catch (_: IllegalArgumentException) { null }
        }

    fun setValue(spec: CESamplerSpec) {
        if (spec !is CESamplerSpec.Normal) return  // future variants: extend here
        suppress = true
        try {
            meanSmootherField.text = spec.meanSmoother.toString()
            sdSmootherField.text = spec.sdSmoother.toString()
            cvThresholdField.text = spec.coefficientOfVariationThreshold.toString()
            streamNumField.text = spec.streamNum.toString()
        } finally { suppress = false }
    }

    fun validationMessage(): String? {
        val m = meanSmootherField.text.trim().toDoubleOrNull()
            ?: return "Mean smoother must be a number"
        if (m <= 0.0 || m > 1.0) return "Mean smoother must be in (0, 1]"
        val s = sdSmootherField.text.trim().toDoubleOrNull()
            ?: return "SD smoother must be a number"
        if (s <= 0.0 || s > 1.0) return "SD smoother must be in (0, 1]"
        val cv = cvThresholdField.text.trim().toDoubleOrNull()
            ?: return "CV threshold must be a number"
        if (!cv.isFinite() || cv <= 0.0) return "CV threshold must be > 0 and finite"
        val sn = streamNumField.text.trim().toIntOrNull()
            ?: return "Stream number must be an integer"
        if (sn < 0) return "Stream number must be >= 0"
        return null
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        meanSmootherField.isEnabled = enabled
        sdSmootherField.isEnabled = enabled
        cvThresholdField.isEnabled = enabled
        streamNumField.isEnabled = enabled
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        fill: Int = GridBagConstraints.NONE
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.weightx = weightx
        this.anchor = if (col == 0) GridBagConstraints.WEST else GridBagConstraints.CENTER
        this.fill = fill
        this.insets = Insets(2, 4, 2, 4)
    }
}
