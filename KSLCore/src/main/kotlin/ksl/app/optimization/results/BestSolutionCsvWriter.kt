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

package ksl.app.optimization.results

import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.simopt.solvers.SolverStateSnapshot
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Single-row CSV of the best solution found by an optimization run.
 *
 *  Useful for stacking multiple runs into a single comparison sheet
 *  (e.g. one row per `(analysisName, algorithm)` combination).
 *
 *  Column order:
 *  1. One column per decision variable, in
 *     [OptimizationProblemSpec.inputs] declaration order
 *  2. `est_obj`
 *  3. `pen_obj`
 *
 *  Substrate-level API — usable by any UI shell.
 */
object BestSolutionCsvWriter {

    private const val SEP = ","
    private const val NL = "\n"

    fun encode(
        best: SolverStateSnapshot,
        problem: OptimizationProblemSpec
    ): String {
        val inputCols = problem.inputs.map { it.name }
        val sb = StringBuilder()
        // Header
        for ((i, name) in inputCols.withIndex()) {
            if (i > 0) sb.append(SEP)
            sb.append(quote(name))
        }
        if (inputCols.isNotEmpty()) sb.append(SEP)
        sb.append("est_obj").append(SEP).append("pen_obj").append(NL)
        // Row
        val inputs = best.bestSolutionSoFar.inputMap
        for ((i, name) in inputCols.withIndex()) {
            if (i > 0) sb.append(SEP)
            sb.append(inputs[name]?.toString() ?: "")
        }
        if (inputCols.isNotEmpty()) sb.append(SEP)
        sb.append(best.estimatedObjFncValue).append(SEP).append(best.penalizedObjFncValue).append(NL)
        return sb.toString()
    }

    fun write(
        best: SolverStateSnapshot,
        problem: OptimizationProblemSpec,
        path: Path
    ): Boolean = try {
        Files.createDirectories(path.parent)
        Files.writeString(path, encode(best, problem))
        true
    } catch (_: Throwable) {
        false
    }

    private fun quote(s: String): String {
        if (!s.contains(',') && !s.contains('"') && !s.contains('\n')) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
}
