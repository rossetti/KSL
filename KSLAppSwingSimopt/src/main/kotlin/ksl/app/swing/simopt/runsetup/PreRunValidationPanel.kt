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

package ksl.app.swing.simopt.runsetup

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.validation.FieldError
import ksl.app.validation.OptimizationConfigurationValidator
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.stepper.Step
import java.awt.Color
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Live pre-run validation surface.
 *
 * **Document-only checks** (cheap) run on every state change —
 * [OptimizationConfigurationValidator.validate] doesn't build a
 * probe model and is safe to call live.  Results are merged with
 * any cached model-aware result.
 *
 * **Model-aware checks** (expensive) are gated behind a *Re-check
 * against model* button.  Clicking it calls
 * [OptimizationConfigurationValidator.validateForRun] with the
 * controller's bundle provider, builds a probe model, and caches
 * the result until the next document edit invalidates it.
 *
 * Each error / warning row carries a hyperlink-styled "Jump to fix"
 * link whose target step is derived from the error path:
 *
 *  - `model.*` → [Step.MODEL]
 *  - `problem.linearConstraints*` / `problem.responseConstraints*` → [Step.CONSTRAINTS]
 *  - `problem` / `problem.*` → [Step.PROBLEM]
 *  - `solver` / `solver.*` → [Step.ALGORITHM]
 *  - otherwise: no link rendered.
 *
 * The panel never gates the step's completion — gating stays on
 * `solverSpec != null` (set by Phase O2).  Phase O7b's Execute step
 * gates the Run button on validation.
 */
class PreRunValidationPanel(
    private val controller: SimoptAppController
) : JPanel() {

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 13f)
    }
    private val recheckButton = JButton("Re-check against model").apply {
        toolTipText = "Builds a probe model and runs model-aware validation. " +
            "Cached until the next document edit."
    }
    private val cachedModelStatusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = Color(0x77, 0x77, 0x77)
    }

    private val rowsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    /** Cached model-aware validation result.  Cleared on any document
     *  edit; reset when the user clicks "Re-check against model". */
    @Volatile private var modelAwareCache: ValidationResult? = null
    @Volatile private var modelAwareStale: Boolean = true

    init {
        layout = GridBagLayout()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createTitledBorder("Pre-run validation"),
            BorderFactory.createEmptyBorder(2, 6, 6, 6)
        )

        add(statusLabel, gbc(0, 0, width = 2, weightx = 1.0,
            anchor = GridBagConstraints.WEST, fill = GridBagConstraints.HORIZONTAL))

        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(recheckButton)
            add(cachedModelStatusLabel)
        }, gbc(0, 1, width = 2, weightx = 1.0, fill = GridBagConstraints.HORIZONTAL,
            insets = Insets(4, 4, 4, 4)))

        val rowScroll = JScrollPane(
            rowsContainer,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = java.awt.Dimension(640, 160)
        }
        add(rowScroll, gbc(0, 2, width = 2, weightx = 1.0, weighty = 1.0,
            fill = GridBagConstraints.BOTH, insets = Insets(2, 4, 2, 4)))

        wireButton()
        wireCollectors()
        refresh()
    }

    private fun wireButton() {
        recheckButton.addActionListener {
            val config = controller.currentConfiguration() ?: run {
                modelAwareCache = ValidationResult(
                    errors = listOf(FieldError(
                        path = "model", message = "Select a model on the Model step first.",
                        severity = ValidationSeverity.ERROR, code = "MISSING_MODEL"
                    ))
                )
                modelAwareStale = false
                refresh()
                return@addActionListener
            }
            val provider = controller.bundleProvider.value
            val result = try {
                OptimizationConfigurationValidator.validateForRun(config, provider)
            } catch (t: Throwable) {
                ValidationResult(
                    errors = listOf(FieldError(
                        path = "model", message = "Model-aware validation threw: ${t.message}",
                        severity = ValidationSeverity.ERROR, code = "VALIDATOR_EXCEPTION"
                    ))
                )
            }
            modelAwareCache = result
            modelAwareStale = false
            refresh()
        }
    }

    private fun wireCollectors() {
        val invalidate: () -> Unit = {
            modelAwareStale = true
            refresh()
        }
        controller.output.onEach { invalidate() }.launchIn(controller.edtScope)
        controller.modelTemplate.onEach { invalidate() }.launchIn(controller.edtScope)
        controller.problemSpec.onEach { invalidate() }.launchIn(controller.edtScope)
        controller.solverSpec.onEach { invalidate() }.launchIn(controller.edtScope)
        controller.evaluationSpec.onEach { invalidate() }.launchIn(controller.edtScope)
        controller.trackingSpec.onEach { invalidate() }.launchIn(controller.edtScope)
    }

    // ── Refresh ───────────────────────────────────────────────────────────

    private fun refresh() {
        val docResult = runDocumentChecks()
        val modelCache = modelAwareCache?.takeIf { !modelAwareStale }
        val merged = merge(docResult, modelCache)

        // Status line + cache-state label
        statusLabel.text = if (merged.isValid) {
            val warnCount = merged.warnings.size
            if (warnCount == 0) "<html><b>Status:</b> ✓ No issues found</html>"
            else "<html><b>Status:</b> ✓ No errors  (${warnCount} warning(s))</html>"
        } else {
            "<html><b>Status:</b> ✗ ${merged.errors.size} error(s)  (${merged.warnings.size} warning(s))</html>"
        }
        statusLabel.foreground = when {
            !merged.isValid -> Color(0xB5, 0x40, 0x40)
            merged.warnings.isNotEmpty() -> Color(0xB5, 0x80, 0x00)
            else -> Color(0x2D, 0x6D, 0x40)
        }
        cachedModelStatusLabel.text = when {
            modelAwareCache == null -> "Model-aware checks not yet run."
            modelAwareStale -> "Model-aware result is stale — click Re-check to refresh."
            else -> "Model-aware checks last passed at this state."
        }

        // Rows
        rowsContainer.removeAll()
        if (merged.errors.isEmpty() && merged.warnings.isEmpty()) {
            rowsContainer.add(JLabel("<html><i>No issues to report.</i></html>").apply {
                foreground = Color(0x55, 0x55, 0x55)
                border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
            })
        } else {
            for (error in merged.errors) rowsContainer.add(buildRow(error, isError = true))
            for (warning in merged.warnings) rowsContainer.add(buildRow(warning, isError = false))
            rowsContainer.add(Box.createVerticalGlue())
        }
        rowsContainer.revalidate()
        rowsContainer.repaint()
    }

    private fun runDocumentChecks(): ValidationResult {
        val config = controller.currentConfiguration() ?: return ValidationResult(
            errors = listOf(FieldError(
                path = "model",
                message = "Select a model on the Model step before checking.",
                severity = ValidationSeverity.ERROR,
                code = "MISSING_MODEL"
            ))
        )
        return OptimizationConfigurationValidator.validate(config)
    }

    private fun merge(doc: ValidationResult, modelAware: ValidationResult?): ValidationResult {
        if (modelAware == null) return doc
        // Concatenate; preserve doc-first ordering.
        return ValidationResult(
            errors = doc.errors + modelAware.errors,
            warnings = doc.warnings + modelAware.warnings
        )
    }

    private fun buildRow(error: FieldError, isError: Boolean): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
        isOpaque = false
        val icon = JLabel(if (isError) "✗" else "⚠").apply {
            foreground = if (isError) Color(0xB5, 0x40, 0x40) else Color(0xB5, 0x80, 0x00)
            font = font.deriveFont(Font.BOLD, 13f)
            preferredSize = java.awt.Dimension(16, 16)
        }
        val msg = JLabel(buildString {
            append("<html>")
            append(error.message)
            append(" <span style='color:#888;'>[")
            append(error.code)
            append("]</span></html>")
        })
        msg.toolTipText = "Path: ${error.path}"

        add(icon)
        add(msg)

        val targetStep = jumpTarget(error.path)
        if (targetStep != null) {
            val link = JLabel("<html><a href='#'>Jump to ${targetStep.title}</a></html>").apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        controller.jumpToStep(targetStep)
                    }
                })
            }
            add(link)
        }
    }

    private fun jumpTarget(path: String): Step? = when {
        path.startsWith("model") -> Step.MODEL
        path.startsWith("problem.linearConstraints") ||
            path.startsWith("problem.responseConstraints") -> Step.CONSTRAINTS
        path.startsWith("problem") -> Step.PROBLEM
        path.startsWith("solver") -> Step.ALGORITHM
        else -> null
    }

    private fun gbc(
        col: Int,
        row: Int,
        weightx: Double = 0.0,
        weighty: Double = 0.0,
        width: Int = 1,
        anchor: Int = GridBagConstraints.CENTER,
        fill: Int = GridBagConstraints.NONE,
        insets: Insets = Insets(2, 4, 2, 4)
    ): GridBagConstraints = GridBagConstraints().apply {
        this.gridx = col
        this.gridy = row
        this.gridwidth = width
        this.weightx = weightx
        this.weighty = weighty
        this.anchor = anchor
        this.fill = fill
        this.insets = insets
    }
}
