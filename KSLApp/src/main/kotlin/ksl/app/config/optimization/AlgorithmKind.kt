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

package ksl.app.config.optimization

/**
 *  Human-friendly discriminator for the four algorithms exposed by
 *  the [SolverSpec] sealed type.  Each constant maps 1-to-1 to a
 *  [SolverSpec] variant; the [displayName] is the string a UI
 *  shell shows in pickers, reports, and summaries.
 *
 *  Substrate-level API — any UI shell (Swing pickers, web dropdowns,
 *  CLI menus) uses the same enum so the algorithm vocabulary stays
 *  consistent across host applications.
 */
enum class AlgorithmKind(val displayName: String) {
    STOCHASTIC_HILL_CLIMBING("Stochastic Hill Climbing"),
    SIMULATED_ANNEALING("Simulated Annealing"),
    CROSS_ENTROPY("Cross-Entropy"),
    R_SPLINE("R-SPLINE");

    override fun toString(): String = displayName

    companion object {
        /** Map a live [SolverSpec] instance to its discriminator. */
        fun of(spec: SolverSpec): AlgorithmKind = when (spec) {
            is SolverSpec.StochasticHillClimbing -> STOCHASTIC_HILL_CLIMBING
            is SolverSpec.SimulatedAnnealing -> SIMULATED_ANNEALING
            is SolverSpec.CrossEntropy -> CROSS_ENTROPY
            is SolverSpec.RSpline -> R_SPLINE
        }
    }
}
