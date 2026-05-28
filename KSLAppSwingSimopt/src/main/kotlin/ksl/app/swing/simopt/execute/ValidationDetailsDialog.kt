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

package ksl.app.swing.simopt.execute

import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationResult
import ksl.app.swing.simopt.SimoptAppController
import ksl.app.swing.simopt.stepper.Step
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Modal dialog listing every document and model-aware validation
 * issue with a jump-to-step link per row.
 *
 * Replaces the inline `PreRunValidationPanel` that lived directly in
 * the Execute step's body — opened on-demand from the
 * `OptimizationPanel` validation pill so an issue-free run doesn't
 * dedicate vertical space to an empty list.
 *
 * The dialog stays open while the user fixes things; collectors on
 * the controller's validation flows re-render rows whenever the
 * document changes.  Close-only — no OK / Cancel semantics.
 */
class ValidationDetailsDialog(
    owner: Window?,
    private val controller: SimoptAppController
) : JDialog(owner, "Pre-run validation", ModalityType.APPLICATION_MODAL) {

    private val statusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 13f)
    }
    private val cachedModelStatusLabel = JLabel(" ").apply {
        font = font.deriveFont(Font.PLAIN, 11f)
        foreground = Color(0x77, 0x77, 0x77)
    }
    private val recheckButton = JButton("Re-check against model").apply {
        toolTipText = "Builds a probe model and runs model-aware validation. " +
            "Cached until the next document edit."
        addActionListener { controller.runModelAwareValidationNow() }
    }
    private val closeButton = JButton("Close").apply {
        addActionListener { isVisible = false }
    }

    private val rowsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        layout = BorderLayout(0, 6)
        rootPane.border = BorderFactory.createEmptyBorder(10, 12, 10, 12)

        // Header — status line + cache-state label.
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(statusLabel)
            add(cachedModelStatusLabel)
        }
        add(header, BorderLayout.NORTH)

        // Body — scrollable rows.
        val rowScroll = JScrollPane(
            rowsContainer,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        ).apply {
            border = BorderFactory.createEmptyBorder()
            preferredSize = Dimension(640, 240)
        }
        add(rowScroll, BorderLayout.CENTER)

        // Footer — Re-check + Close.
        val footer = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(recheckButton)
            add(closeButton)
        }
        add(footer, BorderLayout.SOUTH)

        wireCollectors()
        refresh()
        pack()
        setLocationRelativeTo(owner)
    }

    private fun wireCollectors() {
        controller.documentValidation.onEach { refresh() }.launchIn(controller.edtScope)
        controller.modelAwareValidation.onEach { refresh() }.launchIn(controller.edtScope)
        controller.modelAwareStale.onEach { refresh() }.launchIn(controller.edtScope)
    }

    private fun refresh() {
        val doc = controller.documentValidation.value
        val cached = controller.modelAwareValidation.value
        val stale = controller.modelAwareStale.value
        val modelCache = cached?.takeIf { !stale }
        val merged = merge(doc, modelCache)

        statusLabel.text = if (merged.isValid) {
            val w = merged.warnings.size
            if (w == 0) "<html><b>Status:</b> ✓ No issues found</html>"
            else "<html><b>Status:</b> ✓ No errors  ($w warning(s))</html>"
        } else {
            "<html><b>Status:</b> ✗ ${merged.errors.size} error(s)  (${merged.warnings.size} warning(s))</html>"
        }
        statusLabel.foreground = when {
            !merged.isValid -> Color(0xB5, 0x40, 0x40)
            merged.warnings.isNotEmpty() -> Color(0xB5, 0x80, 0x00)
            else -> Color(0x2D, 0x6D, 0x40)
        }
        cachedModelStatusLabel.text = when {
            cached == null -> "Model-aware checks not yet run."
            stale -> "Model-aware result is stale — click Re-check to refresh."
            else -> "Model-aware checks last passed at this state."
        }

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

    private fun merge(doc: ValidationResult, modelAware: ValidationResult?): ValidationResult {
        if (modelAware == null) return doc
        return ValidationResult(
            errors = doc.errors + modelAware.errors,
            warnings = doc.warnings + modelAware.warnings
        )
    }

    private fun buildRow(error: FieldError, isError: Boolean): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            val icon = JLabel(if (isError) "✗" else "⚠").apply {
                foreground = if (isError) Color(0xB5, 0x40, 0x40) else Color(0xB5, 0x80, 0x00)
                font = font.deriveFont(Font.BOLD, 13f)
                preferredSize = Dimension(16, 16)
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
                            isVisible = false
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
}
