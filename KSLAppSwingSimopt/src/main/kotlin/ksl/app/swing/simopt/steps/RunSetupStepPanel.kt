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
 * Step 4 of 6 — Run Setup.
 *
 * **Phase O2 shell.**  Replaced in Phase O7a with the evaluation
 * settings panel, tracking & trace panel, pre-run validation
 * checklist, and the run preview (estimated total simulation runs +
 * resolved output directory + trace path).  See
 * `.claude/plans/simopt-app-plan.md` §5.6.
 */
class RunSetupStepPanel(controller: SimoptAppController) : PlaceholderStepPanel(
    title = "Run Setup",
    summary = "Configure evaluation settings (caches, snapshot " +
        "frequency, feasibility) and optional CSV / console trace " +
        "tracking.  Pre-run validation against the live model with " +
        "jump-to-fix links back to upstream steps.",
    pendingPhase = "Phase O7a"
)
