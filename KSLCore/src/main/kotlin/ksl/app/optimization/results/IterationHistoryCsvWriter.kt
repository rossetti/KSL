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
 *  Wide-format CSV of the full iteration history.
 *
 *  Column order:
 *  1. Fixed metric columns: `iteration`, `oracle_calls`,
 *     `replications`, `est_obj`, `pen_obj`
 *  2. One column per decision variable, in
 *     [OptimizationProblemSpec.inputs] declaration order
 *  3. One column per `solverSpecificState` key, alphabetical
 *     (union of keys seen across all snapshots; missing values
 *     render as blank cells)
 *
 *  Suitable for loading directly into pandas, R, or Excel.  The
 *  format does not change across runs of the same problem so
 *  cross-run scripts can rely on a stable header shape.
 *
 *  Substrate-level API — usable by any UI shell.
 */
object IterationHistoryCsvWriter {

    private const val SEP = ","
    private const val NL = "\n"

    /** Build the CSV content as a String — pure function so callers
     *  can inspect rows without filesystem access. */
    fun encode(
        history: List<SolverStateSnapshot>,
        problem: OptimizationProblemSpec
    ): String {
        val inputCols = problem.inputs.map { it.name }
        val stateCols = history
            .flatMap { it.solverSpecificState?.keys.orEmpty() }
            .toSortedSet()
            .toList()

        val sb = StringBuilder()
        // Header
        sb.append("iteration").append(SEP)
        sb.append("oracle_calls").append(SEP)
        sb.append("replications").append(SEP)
        sb.append("est_obj").append(SEP)
        sb.append("pen_obj")
        for (name in inputCols) sb.append(SEP).append(quote(name))
        for (name in stateCols) sb.append(SEP).append(quote(name))
        sb.append(NL)

        // Rows
        for (snap in history) {
            sb.append(snap.iterationNumber).append(SEP)
            sb.append(snap.numOracleCalls).append(SEP)
            sb.append(snap.numReplicationsRequested).append(SEP)
            sb.append(fmt(snap.estimatedObjFncValue)).append(SEP)
            sb.append(fmt(snap.penalizedObjFncValue))
            val inputs = snap.bestSolutionSoFar.inputMap
            for (name in inputCols) {
                sb.append(SEP).append(inputs[name]?.let(::fmt) ?: "")
            }
            val state = snap.solverSpecificState
            for (name in stateCols) {
                sb.append(SEP).append(state?.get(name)?.let(::fmt) ?: "")
            }
            sb.append(NL)
        }
        return sb.toString()
    }

    /** Encode and write to [path].  Best-effort — returns `true` on
     *  success, `false` if the write threw. */
    fun write(
        history: List<SolverStateSnapshot>,
        problem: OptimizationProblemSpec,
        path: Path
    ): Boolean = try {
        Files.createDirectories(path.parent)
        Files.writeString(path, encode(history, problem))
        true
    } catch (_: Throwable) {
        false
    }

    /** Number formatting consistent across the column set.  We use
     *  the default `Double.toString` so round-trip parsing is
     *  exact; downstream consumers can reformat as desired. */
    private fun fmt(v: Double): String = v.toString()

    /** Quote a header cell when it contains a comma or quote;
     *  otherwise emit it bare.  Standard RFC 4180 escaping. */
    private fun quote(s: String): String {
        if (!s.contains(',') && !s.contains('"') && !s.contains('\n')) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
}
