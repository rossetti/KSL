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

package ksl.app.swing.simopt.steps

import ksl.app.swing.simopt.SimoptAppController

/**
 * Step 2 of 6 — Problem.
 *
 * **Phase O2 shell.**  Replaced in Phases O4 + O5 with the objective
 * form, decision-variable table + add-input dialog, declared responses
 * row, constraint dialogs, and the advanced penalty defaults
 * disclosure.  See `.claude/plans/simopt-app-plan.md` §5.4.
 */
class ProblemStepPanel(controller: SimoptAppController) : PlaceholderStepPanel(
    title = "Problem",
    summary = "Define the objective response, decision variables " +
        "(each bound to a control or RV parameter with finite " +
        "lower < upper bounds), declared responses, linear + " +
        "response constraints, and problem-level penalty defaults.",
    pendingPhase = "Phases O4 + O5"
)
