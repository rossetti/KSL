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

package ksl.app.swing.simopt.stepper

import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import ksl.app.swing.simopt.SimoptAppController
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Footer strip beneath the active-step body.
 *
 * Layout (left → middle → right):
 *
 * - **Back**  — moves the active step one earlier in [Step.entries].
 *   Disabled when the active step is [Step.MODEL].
 * - **Edited / Saved badge** — small text indicator of
 *   [SimoptAppController.isDirty] state.  Centered.
 * - **Next** — moves the active step one later in [Step.entries].
 *   Label reads "Next: <step title>" when reachable; disabled when
 *   the next step is locked or when the active step is [Step.RESULTS].
 */
class StepFooterPanel(
    private val controller: SimoptAppController
) : JPanel(BorderLayout()) {

    private val backButton = JButton("◂ Back").apply {
        isFocusable = false
        addActionListener {
            val current = controller.activeStep.value
            val idx = Step.entries.indexOf(current)
            if (idx > 0) controller.jumpToStep(Step.entries[idx - 1])
        }
    }

    private val nextButton = JButton("Next ▸").apply {
        isFocusable = false
        addActionListener {
            val current = controller.activeStep.value
            val idx = Step.entries.indexOf(current)
            if (idx < Step.entries.size - 1) {
                val target = Step.entries[idx + 1]
                if (controller.canAdvanceTo(target)) controller.jumpToStep(target)
            }
        }
    }

    private val editedBadge = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, 12f)
    }

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(0xE6, 0xE6, 0xE6)),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        )

        add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(backButton)
        }, BorderLayout.WEST)

        add(JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            isOpaque = false
            add(editedBadge)
        }, BorderLayout.CENTER)

        add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
            isOpaque = false
            add(nextButton)
        }, BorderLayout.EAST)

        // Live wiring — every relevant flow tick refreshes the
        // enable-state and the button labels.
        combine(
            controller.activeStep,
            controller.stepCompletion
        ) { active, _ ->
            refreshButtons(active)
        }.launchIn(controller.edtScope)
        controller.isDirty.onEach { dirty -> refreshBadge(dirty) }.launchIn(controller.edtScope)

        // Initial render.
        refreshButtons(controller.activeStep.value)
        refreshBadge(controller.isDirty.value)
    }

    private fun refreshButtons(active: Step) {
        val idx = Step.entries.indexOf(active)
        backButton.isEnabled = idx > 0
        if (idx < Step.entries.size - 1) {
            val next = Step.entries[idx + 1]
            nextButton.isEnabled = controller.canAdvanceTo(next)
            nextButton.text = "Next: ${next.title} ▸"
            nextButton.toolTipText = if (controller.canAdvanceTo(next)) {
                "Advance to ${next.title}"
            } else {
                "Complete the current step before advancing to ${next.title}"
            }
        } else {
            nextButton.isEnabled = false
            nextButton.text = "Next ▸"
            nextButton.toolTipText = "Already at the last step"
        }
    }

    private fun refreshBadge(dirty: Boolean) {
        if (dirty) {
            editedBadge.text = "● Edited"
            editedBadge.foreground = Color(0xB5, 0x80, 0x00)
            editedBadge.toolTipText = "The document has unsaved changes.  Use File → Save."
        } else {
            editedBadge.text = "✓ Saved"
            editedBadge.foreground = Color(0x70, 0x70, 0x70)
            editedBadge.toolTipText = "The document matches its file on disk."
        }
    }
}
