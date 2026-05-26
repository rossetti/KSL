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
 * Step 1 of 6 — Model.
 *
 * **Phase O2 shell.**  Replaced in Phase O3 with the bundle picker,
 * model dropdown, run-parameter overrides, and the advanced baseline
 * controls disclosure.  See `.claude/plans/simopt-app-plan.md` §5.3.
 */
class ModelStepPanel(controller: SimoptAppController) : PlaceholderStepPanel(
    title = "Model",
    summary = "Pick a KSL bundle and model; configure run-parameter " +
        "overrides and the fixed baseline controls held constant " +
        "during the optimization.",
    pendingPhase = "Phase O3"
)
