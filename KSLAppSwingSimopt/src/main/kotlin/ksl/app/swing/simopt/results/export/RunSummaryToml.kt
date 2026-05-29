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

package ksl.app.swing.simopt.results.export

import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.session.RunResult
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Status of a finished run, embedded in [RunSummary] so a partial
 *  artifact set (cancelled / failed runs) is still self-describing.
 */
enum class ResultsStatus {
    COMPLETED,
    CANCELLED,
    FAILED
}

/**
 *  Machine-readable summary of a finished optimization run.
 *
 *  Written as `summary.toml` alongside the other run-output
 *  artifacts.  Lets a future "compare runs" feature ingest the
 *  metadata without parsing HTML or scraping CSV headers.
 *
 *  Optional fields ([bestEstimatedObjective], [bestPenalizedObjective],
 *  [bestFoundAtIteration], [bestInputs]) are null on partial / failed
 *  runs where no completed best snapshot is available.
 *
 *  Encoded by hand (no `kotlinx-serialization` plugin in this
 *  module) — the structure is shallow and the writer escapes
 *  user-supplied strings.
 */
data class RunSummary(
    val runId: String,
    val status: ResultsStatus,
    val algorithm: String,
    val solverName: String?,
    val analysisName: String,
    val modelIdentifier: String?,
    val startTime: String,
    val endTime: String,
    val elapsedMillis: Long,
    val totalIterations: Int,
    val totalOracleCalls: Int?,
    val bestEstimatedObjective: Double?,
    val bestPenalizedObjective: Double?,
    val bestFoundAtIteration: Int?,
    val bestInputs: Map<String, Double> = emptyMap(),
    /** Populated only for [ResultsStatus.CANCELLED] / [ResultsStatus.FAILED];
     *  carries the user-facing cancel reason or the error message. */
    val statusReason: String? = null
)

/**
 *  Writer + builder for [RunSummary].
 *
 *  All builders are pure functions over the inputs so the tests can
 *  exercise the projection without filesystem access; [write] is the
 *  only filesystem-touching call.
 */
internal object RunSummaryWriter {

    /** Build a [RunSummary] from a successful run. */
    fun forCompleted(
        config: OptimizationRunConfiguration,
        result: RunResult.OptimizationCompleted
    ): RunSummary {
        val problem = config.problem
        val solver = config.solver
        val summary = result.summary
        val bestObj = result.bestSolution.estimatedObjFncValue
        val bestIter = result.iterationHistory
            .firstOrNull { it.estimatedObjFncValue == bestObj }
            ?.iterationNumber
        return RunSummary(
            runId = summary.runId,
            status = ResultsStatus.COMPLETED,
            algorithm = solver?.let { it::class.simpleName ?: "Solver" } ?: "Solver",
            solverName = solver?.name,
            analysisName = config.output.analysisName,
            modelIdentifier = problem?.modelIdentifier,
            startTime = summary.beginTime.toString(),
            endTime = summary.endTime.toString(),
            elapsedMillis = summary.wallClockDuration.inWholeMilliseconds,
            totalIterations = summary.completedItems,
            totalOracleCalls = result.bestSolution.numOracleCalls,
            bestEstimatedObjective = bestObj,
            bestPenalizedObjective = result.bestSolution.penalizedObjFncValue,
            bestFoundAtIteration = bestIter,
            bestInputs = HashMap(result.bestSolution.bestSolutionSoFar.inputMap)
        )
    }

    /** Build a partial [RunSummary] for a run that did not complete
     *  successfully.  [latestBest] is the best-so-far data captured
     *  by the controller from the last `IterationCompleted` event;
     *  it may be `null` if the run aborted before any iteration
     *  fired. */
    fun forIncomplete(
        config: OptimizationRunConfiguration,
        status: ResultsStatus,
        runId: String,
        startTimeIso: String,
        endTimeIso: String,
        elapsedMillis: Long,
        latestBest: LatestBestSnapshot?,
        statusReason: String?
    ): RunSummary {
        require(status != ResultsStatus.COMPLETED) {
            "forIncomplete must not be called with COMPLETED; use forCompleted"
        }
        val problem = config.problem
        val solver = config.solver
        return RunSummary(
            runId = runId,
            status = status,
            algorithm = solver?.let { it::class.simpleName ?: "Solver" } ?: "Solver",
            solverName = solver?.name,
            analysisName = config.output.analysisName,
            modelIdentifier = problem?.modelIdentifier,
            startTime = startTimeIso,
            endTime = endTimeIso,
            elapsedMillis = elapsedMillis,
            totalIterations = latestBest?.iteration ?: 0,
            totalOracleCalls = null,
            bestEstimatedObjective = latestBest?.estimatedObjective,
            bestPenalizedObjective = null,
            bestFoundAtIteration = latestBest?.iteration,
            bestInputs = latestBest?.bestInputs ?: emptyMap(),
            statusReason = statusReason
        )
    }

    /** Encode [summary] as TOML text by hand.  Skips fields whose
     *  values are `null` (TOML 1.0 doesn't support an explicit
     *  null), so consumers should treat missing keys as "not
     *  applicable for this status."  Strings are quoted and
     *  escaped per TOML 1.0 basic-string rules. */
    fun encode(summary: RunSummary): String = buildString {
        appendLine("# SimOpt run summary — machine-readable.  Written automatically")
        appendLine("# at run completion; safe to ingest with any TOML 1.0 parser.")
        appendLine()
        appendKv("runId", summary.runId)
        appendKv("status", summary.status.name)
        appendKv("algorithm", summary.algorithm)
        summary.solverName?.let { appendKv("solverName", it) }
        appendKv("analysisName", summary.analysisName)
        summary.modelIdentifier?.let { appendKv("modelIdentifier", it) }
        appendKv("startTime", summary.startTime)
        appendKv("endTime", summary.endTime)
        appendKvNum("elapsedMillis", summary.elapsedMillis)
        appendKvNum("totalIterations", summary.totalIterations.toLong())
        summary.totalOracleCalls?.let { appendKvNum("totalOracleCalls", it.toLong()) }
        summary.bestEstimatedObjective?.let { appendKvDouble("bestEstimatedObjective", it) }
        summary.bestPenalizedObjective?.let { appendKvDouble("bestPenalizedObjective", it) }
        summary.bestFoundAtIteration?.let { appendKvNum("bestFoundAtIteration", it.toLong()) }
        summary.statusReason?.let { appendKv("statusReason", it) }
        if (summary.bestInputs.isNotEmpty()) {
            appendLine()
            appendLine("[bestInputs]")
            for ((name, value) in summary.bestInputs.entries.sortedBy { it.key }) {
                appendKvDouble(name, value)
            }
        }
    }

    /** Encode [summary] and write to [path] (creating parent
     *  directories as needed).  Best-effort — returns `true` on
     *  success, `false` if the write threw. */
    fun write(summary: RunSummary, path: Path): Boolean = try {
        Files.createDirectories(path.parent)
        Files.writeString(path, encode(summary))
        true
    } catch (_: Throwable) {
        false
    }

    private fun StringBuilder.appendKv(key: String, value: String) {
        append(key).append(" = ").append(tomlQuote(value)).appendLine()
    }

    private fun StringBuilder.appendKvNum(key: String, value: Long) {
        append(key).append(" = ").append(value).appendLine()
    }

    private fun StringBuilder.appendKvDouble(key: String, value: Double) {
        append(key).append(" = ").append(tomlDouble(value)).appendLine()
    }

    /** TOML basic-string quoting per the spec — escape `"` and
     *  `\` and control characters.  Sufficient for the strings we
     *  emit (status enums, ISO timestamps, user-provided names). */
    private fun tomlQuote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch.code < 0x20 -> sb.append("\\u%04X".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append("\"")
        return sb.toString()
    }

    /** TOML floats require an explicit decimal point or
     *  exponent; `inf` / `nan` are valid TOML 1.0 literals. */
    private fun tomlDouble(v: Double): String = when {
        v.isNaN() -> "nan"
        v == Double.POSITIVE_INFINITY || v == Double.MAX_VALUE -> "inf"
        v == Double.NEGATIVE_INFINITY || v == -Double.MAX_VALUE -> "-inf"
        else -> {
            val raw = v.toString()
            if ('.' in raw || 'e' in raw || 'E' in raw) raw else "$raw.0"
        }
    }
}

/**
 *  Minimal projection of a `RunEvent.IterationCompleted` used when
 *  building a partial [RunSummary].  Decoupled from the substrate
 *  type so test fixtures don't need to construct a real `RunEvent`.
 */
data class LatestBestSnapshot(
    val iteration: Int,
    val estimatedObjective: Double,
    val bestInputs: Map<String, Double>
)
