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
 * Step 5 of 6 — Execute.
 *
 * **Phase O2 shell.**  Replaced in Phase O7b with the Run / Cancel
 * controls, live progress (iteration counter, best-so-far objective,
 * lets-plot sparkline, best inputs, algorithm-specific state from
 * `solverSpecificState`).  See `.claude/plans/simopt-app-plan.md` §5.7.
 */
class ExecuteStepPanel(controller: SimoptAppController) : PlaceholderStepPanel(
    title = "Execute",
    summary = "Run / Cancel the configured optimization.  Live " +
        "progress display: iteration counter, best-so-far objective, " +
        "lets-plot convergence sparkline, current best inputs, and " +
        "algorithm-specific state when the snapshot carries it.",
    pendingPhase = "Phase O7b"
)
