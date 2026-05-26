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
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Horizontal step-rail widget rendering the six SimOpt App steps as
 * clickable pills separated by `➝` glyphs.
 *
 * Each pill reflects one of four visual states:
 *
 * - **Locked** — earlier step incomplete; greyed out; click disabled.
 * - **Unlocked** — reachable; default colour; click jumps to that step.
 * - **Active** — the step whose body is currently visible; blue
 *   highlight + bold label.
 * - **Complete** — already satisfied; a leading ✓ glyph is prepended.
 *   A complete step can also be active or unlocked; the badge stacks
 *   with whichever visual state applies.
 *
 * Subscribes to [SimoptAppController.activeStep] +
 * [SimoptAppController.stepCompletion] and re-renders on every change.
 */
class StepperPanel(
    private val controller: SimoptAppController
) : JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)) {

    private val pillsByStep: Map<Step, StepPill>

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, SEPARATOR_COLOR),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)
        )

        val builders = mutableMapOf<Step, StepPill>()
        Step.entries.forEachIndexed { idx, step ->
            if (idx > 0) {
                add(JLabel("  ➝  ").apply {
                    foreground = SEPARATOR_COLOR_DARK
                    font = font.deriveFont(Font.PLAIN, 14f)
                })
            }
            val pill = StepPill(step).also { it.addActionListener { controller.jumpToStep(step) } }
            builders[step] = pill
            add(pill)
        }
        pillsByStep = builders.toMap()

        // Live state — recompute every pill whenever either flow changes.
        combine(controller.activeStep, controller.stepCompletion) { active, completion ->
            applyState(active, completion)
        }.launchIn(controller.edtScope)

        // Initial render before any flow tick — keeps the UI sane if
        // the widget is added to the frame before the coroutine starts.
        applyState(controller.activeStep.value, controller.stepCompletion.value)
    }

    private fun applyState(active: Step, completion: Map<Step, Boolean>) {
        for (step in Step.entries) {
            val pill = pillsByStep[step] ?: continue
            val isActive = step == active
            val isComplete = completion[step] == true
            val isReachable = controller.canAdvanceTo(step)
            pill.apply(isActive = isActive, isComplete = isComplete, isUnlocked = isReachable)
        }
    }

    /** A single pill in the rail.  Custom paint via a styled JButton
     *  so click is straightforward; visual state driven by [apply]. */
    private class StepPill(step: Step) : JButton() {
        private val labelText: String = " ${step.title} "
        init {
            isFocusable = false
            isOpaque = true
            isBorderPainted = true
            isContentAreaFilled = true
            margin = java.awt.Insets(4, 12, 4, 12)
            font = font.deriveFont(Font.PLAIN, 13f)
            text = labelText
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC)),
                BorderFactory.createEmptyBorder(2, 6, 2, 6)
            )
        }

        fun apply(isActive: Boolean, isComplete: Boolean, isUnlocked: Boolean) {
            val checkmark = if (isComplete) "✓ " else ""
            text = "$checkmark${labelText.trim()}"

            isEnabled = isUnlocked || isActive
            when {
                isActive -> {
                    foreground = Color.WHITE
                    background = ACTIVE_BG
                    font = font.deriveFont(Font.BOLD, 13f)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(ACTIVE_BG.darker()),
                        BorderFactory.createEmptyBorder(2, 6, 2, 6)
                    )
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                isUnlocked -> {
                    foreground = Color(0x33, 0x33, 0x33)
                    background = UNLOCKED_BG
                    font = font.deriveFont(Font.PLAIN, 13f)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0xCC, 0xCC, 0xCC)),
                        BorderFactory.createEmptyBorder(2, 6, 2, 6)
                    )
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
                else -> {
                    foreground = Color(0xAA, 0xAA, 0xAA)
                    background = LOCKED_BG
                    font = font.deriveFont(Font.PLAIN, 13f)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(Color(0xDD, 0xDD, 0xDD)),
                        BorderFactory.createEmptyBorder(2, 6, 2, 6)
                    )
                    cursor = Cursor.getDefaultCursor()
                }
            }
        }
    }

    private companion object {
        private val ACTIVE_BG = Color(0x2D, 0x6D, 0xB4)
        private val UNLOCKED_BG = Color(0xF5, 0xF5, 0xF5)
        private val LOCKED_BG = Color(0xF0, 0xF0, 0xF0)
        private val SEPARATOR_COLOR = Color(0xE6, 0xE6, 0xE6)
        private val SEPARATOR_COLOR_DARK = Color(0x99, 0x99, 0x99)
    }
}

/** Inner panel parent for [StepperPanel] when it lives inside a
 *  surrounding `BorderLayout` and needs to fill the full width.
 *  Convenience wrapper so the frame doesn't have to know the rail's
 *  preferred-size quirks. */
class StepperRow(controller: SimoptAppController) : JPanel(BorderLayout()) {
    init {
        add(StepperPanel(controller), BorderLayout.WEST)
    }
}
