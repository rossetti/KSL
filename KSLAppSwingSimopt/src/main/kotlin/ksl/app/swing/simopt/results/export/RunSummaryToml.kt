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

import kotlinx.serialization.Serializable
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.session.RunResult
import ksl.simopt.solvers.Solver
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlComment
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Status of a finished run, embedded in [RunSummary] so a partial
 *  artifact set (cancelled / failed runs) is still self-describing.
 */
@Serializable
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
 *  Field declaration order **is** the encoded file's key order —
 *  `tomlkt` emits fields in declaration order — so the leading
 *  fields are the human-friendly identifiers users want to see
 *  first, followed by termination metadata, the headline numeric
 *  outcomes, and the substrate's UUID-based cross-reference last.
 *  The two map-valued fields ([bestInputs], [solverConfiguration])
 *  are emitted as TOML tables after the scalar block.
 *
 *  Per-field `@TomlComment` annotations show up as `#`-prefixed
 *  lines above each key in the encoded file, so the artifact is
 *  self-documenting for anyone opening it cold in a text editor.
 *  This mirrors the convention `OptimizationRunConfiguration`
 *  already uses for the document format.
 *
 *  Optional fields ([bestEstimatedObjective], [bestPenalizedObjective],
 *  [bestFoundAtIteration], [bestInputs]) are null on partial / failed
 *  runs where no completed best snapshot is available.  Those keys
 *  are omitted from the encoded file via `explicitNulls = false`
 *  rather than written as `key = null`.
 */
@Serializable
data class RunSummary(
    @TomlComment(
        "Human-readable name of the analysis (set on the Run Setup\n" +
        "step — defaults to \"Untitled\")."
    )
    val analysisName: String,

    @TomlComment(
        "Leaf name of the run-output folder (e.g. \"run-001\").\n" +
        "Matches the path shown on the Results step's \"Output folder\"\n" +
        "line.  This is the human-friendly run identifier; the UUID\n" +
        "below (runId) exists for tooling cross-reference."
    )
    val runDirectory: String? = null,

    @TomlComment(
        "Algorithm class name (e.g. \"StochasticHillClimbing\",\n" +
        "\"SimulatedAnnealing\", \"CrossEntropySolver\", \"RSplineSolver\",\n" +
        "\"RandomRestartSolver\")."
    )
    val algorithm: String,

    @TomlComment(
        "Human-readable solver name.  Auto-derived from the algorithm\n" +
        "when the user leaves the Solver-name field blank on the\n" +
        "Algorithm step (e.g. \"Stochastic Hill Climbing\")."
    )
    val solverName: String? = null,

    @TomlComment(
        "Identifier of the simulation Model the run was built against.\n" +
        "Matches Model.modelIdentifier on the live engine instance."
    )
    val modelIdentifier: String? = null,

    @TomlComment("Run termination status: COMPLETED, CANCELLED, or FAILED.")
    val status: ResultsStatus,

    @TomlComment(
        "Cancellation reason or error message.  Populated only for\n" +
        "CANCELLED / FAILED runs."
    )
    val statusReason: String? = null,

    @TomlComment("Wall-clock instant the run began (ISO-8601 / RFC 3339).")
    val startTime: String,

    @TomlComment("Wall-clock instant the run terminated (ISO-8601 / RFC 3339).")
    val endTime: String,

    @TomlComment("Elapsed wall-clock duration, in milliseconds.")
    val elapsedMillis: Long,

    @TomlComment("Solver iterations completed.")
    val totalIterations: Int,

    @TomlComment(
        "Cumulative number of oracle (simulation) calls the solver\n" +
        "issued across all iterations.  Omitted on partial summaries\n" +
        "where the count isn't available."
    )
    val totalOracleCalls: Int? = null,

    @TomlComment(
        "Best estimated objective-function value found by the solver.\n" +
        "Maps to SolverStateSnapshot.estimatedObjFncValue on the best\n" +
        "snapshot."
    )
    val bestEstimatedObjective: Double? = null,

    @TomlComment(
        "Best penalized objective value (estimated objective +\n" +
        "constraint penalty terms).  Equals bestEstimatedObjective\n" +
        "when no penalty function fires."
    )
    val bestPenalizedObjective: Double? = null,

    @TomlComment(
        "Iteration number at which the best estimated objective was\n" +
        "first achieved.  Lets readers locate the convergence point\n" +
        "in the iteration history."
    )
    val bestFoundAtIteration: Int? = null,

    @TomlComment(
        "Substrate-assigned UUID for the run.  Kept for log correlation\n" +
        "and future \"compare runs\" tooling; not intended for human\n" +
        "eyes — the runDirectory field above is the human-friendly\n" +
        "identifier."
    )
    val runId: String,

    @TomlComment(
        "Best decision-variable assignment found by the solver — one\n" +
        "key per decision variable, value at the best snapshot."
    )
    val bestInputs: Map<String, Double> = emptyMap(),

    @TomlComment(
        "Configuration the solver was running with: every field that\n" +
        "Solver.configurationProperties exposes, in declaration order\n" +
        "(base-class fields first, then subclass-specific fields).\n" +
        "Nested values from RandomRestartSolver appear with an\n" +
        "\"innerSolver.\" prefix."
    )
    val solverConfiguration: Map<String, String> = emptyMap()
)

/**
 *  Writer + builder for [RunSummary].
 *
 *  All builders are pure functions over the inputs so tests can
 *  exercise the projection without filesystem access; [write] is
 *  the only filesystem-touching call.
 *
 *  The TOML encoder delegates to `tomlkt` with `explicitNulls = false`,
 *  so optional fields with `null` values are omitted from the
 *  encoded file rather than rendered as `key = null` lines.
 *  Round-trip decoding through [decode] is symmetric — a missing
 *  key takes the property's declared default.
 */
internal object RunSummaryWriter {

    /** Two-line banner prepended to every encoded file.  Mirrors
     *  the `DOCUMENT_HEADER` convention used by
     *  `OptimizationRunConfigurationToml` for the document format. */
    private const val DOCUMENT_HEADER =
        "# SimOpt run summary — machine-readable.  Written automatically\n" +
        "# at run completion; safe to ingest with any TOML 1.0 parser.\n\n"

    private val toml = Toml {
        explicitNulls = false
    }

    /** Build a [RunSummary] from a successful run.  [solverInstance]
     *  is the live [Solver] reference captured before the controller
     *  cleared its handle — its
     *  [Solver.configurationProperties] are projected into the
     *  summary's `solverConfiguration` field for the
     *  `[solverConfiguration]` TOML block.  [runDir] is the run-
     *  output directory path; its leaf name becomes the human-
     *  friendly [RunSummary.runDirectory] identifier. */
    fun forCompleted(
        config: OptimizationRunConfiguration,
        result: RunResult.OptimizationCompleted,
        solverInstance: Solver? = null,
        runDir: Path? = null
    ): RunSummary {
        val problem = config.problem
        val solver = config.solver
        val summary = result.summary
        val bestObj = result.bestSolution.estimatedObjFncValue
        val bestIter = result.iterationHistory
            .firstOrNull { it.estimatedObjFncValue == bestObj }
            ?.iterationNumber
        return RunSummary(
            analysisName = config.output.analysisName,
            runDirectory = runDir?.fileName?.toString(),
            algorithm = solver?.let { it::class.simpleName ?: "Solver" } ?: "Solver",
            solverName = solver?.name,
            modelIdentifier = problem?.modelIdentifier,
            status = ResultsStatus.COMPLETED,
            startTime = summary.beginTime.toString(),
            endTime = summary.endTime.toString(),
            elapsedMillis = summary.wallClockDuration.inWholeMilliseconds,
            totalIterations = summary.completedItems,
            totalOracleCalls = result.bestSolution.numOracleCalls,
            bestEstimatedObjective = bestObj,
            bestPenalizedObjective = result.bestSolution.penalizedObjFncValue,
            bestFoundAtIteration = bestIter,
            runId = summary.runId,
            bestInputs = HashMap(result.bestSolution.bestSolutionSoFar.inputMap),
            solverConfiguration = solverInstance?.configurationProperties ?: emptyMap()
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
        statusReason: String?,
        solverInstance: Solver? = null,
        runDir: Path? = null
    ): RunSummary {
        require(status != ResultsStatus.COMPLETED) {
            "forIncomplete must not be called with COMPLETED; use forCompleted"
        }
        val problem = config.problem
        val solver = config.solver
        return RunSummary(
            analysisName = config.output.analysisName,
            runDirectory = runDir?.fileName?.toString(),
            algorithm = solver?.let { it::class.simpleName ?: "Solver" } ?: "Solver",
            solverName = solver?.name,
            modelIdentifier = problem?.modelIdentifier,
            status = status,
            statusReason = statusReason,
            startTime = startTimeIso,
            endTime = endTimeIso,
            elapsedMillis = elapsedMillis,
            totalIterations = latestBest?.iteration ?: 0,
            totalOracleCalls = null,
            bestEstimatedObjective = latestBest?.estimatedObjective,
            bestPenalizedObjective = null,
            bestFoundAtIteration = latestBest?.iteration,
            runId = runId,
            bestInputs = latestBest?.bestInputs ?: emptyMap(),
            solverConfiguration = solverInstance?.configurationProperties ?: emptyMap()
        )
    }

    /** Serialise [summary] to a TOML string, prefixed with the
     *  [DOCUMENT_HEADER] banner.  Field order matches the
     *  declaration order on [RunSummary]; per-field
     *  `@TomlComment` annotations are emitted as `#` lines above
     *  each key, making the file self-documenting for anyone
     *  cracking it open in a text editor. */
    fun encode(summary: RunSummary): String =
        DOCUMENT_HEADER + toml.encodeToString(RunSummary.serializer(), summary)

    /** Round-trip decoder.  Lets downstream tooling (a future
     *  "compare runs" view, batch result inspection, etc.) load
     *  a `summary.toml` back into the typed model without writing
     *  its own parser. */
    fun decode(text: String): RunSummary =
        toml.decodeFromString(RunSummary.serializer(), text)

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
