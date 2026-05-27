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

/**
 * The six steps of the SimOpt App's gated linear stepper.
 *
 * Iteration order is the natural user-flow order:
 * [MODEL] → [PROBLEM] → [ALGORITHM] → [RUN_SETUP] → [EXECUTE] → [RESULTS].
 *
 * `SimoptAppController` exposes per-step locked / unlocked / complete
 * state; the `StepperPanel` widget renders the rail and emits clicks
 * for unlocked steps.
 *
 * @property title human-readable label shown on the step rail pill
 */
enum class Step(val title: String) {
    /** Pick a KSL bundle + model; configure run-parameter overrides
     *  and the fixed baseline controls / RV overrides held constant
     *  during optimization. */
    MODEL("Model"),

    /** Define the objective response and decision variables.  Required.
     *  Constraints + penalty function defaults moved to the optional
     *  Constraints step (see [CONSTRAINTS]). */
    PROBLEM("Problem"),

    /** **Optional.**  Declared response names, response constraints,
     *  linear constraints, and problem-level default penalty functions.
     *  Auto-completes the moment [PROBLEM] is complete — the user can
     *  walk straight through to [ALGORITHM] without authoring anything
     *  here.  Visiting only matters when the problem has constraints. */
    CONSTRAINTS("Constraints"),

    /** Choose one of the four algorithms (SHC / SA / CE / RSpline)
     *  and configure its parameters.  Optional random-restart wrapper
     *  lives on the same step. */
    ALGORITHM("Algorithm"),

    /** Configure evaluation settings (caches, snapshot frequency,
     *  feasibility) and optional CSV / console trace.  Pre-run
     *  validation runs against the live model. */
    RUN_SETUP("Run Setup"),

    /** Execute the configured run.  Live progress (iteration counter,
     *  best-so-far objective, lets-plot sparkline, algorithm-specific
     *  state) is shown until terminal. */
    EXECUTE("Execute"),

    /** Post-run analysis surface: best solution, full convergence
     *  plot, iteration-history CSV export, HTML report, and a
     *  one-click open of any tracker CSV the run produced. */
    RESULTS("Results");

    companion object {
        /** Returns [MODEL] — the step the app opens to. */
        val initial: Step get() = MODEL
    }
}
