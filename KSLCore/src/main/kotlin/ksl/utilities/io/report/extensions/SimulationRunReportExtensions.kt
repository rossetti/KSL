/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.report.extensions

import kotlinx.datetime.Instant
import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.statistic.Statistic

/**
 * DSL extension functions on [ReportBuilder] for rendering
 * [ExperimentRunParameters] and [SimulationRun] within the KSL reporting framework.
 *
 * **Separation of concerns — granular functions (composable building blocks):**
 * - [experimentRunParameters] — all 15 run-configuration fields in a single `DataTable`;
 *   usable before a simulation has been executed
 * - [simulationRun] — composite report: run identity, optional error notice, run
 *   parameters, inputs, optional model configuration, replication timing summary,
 *   and across-replication response statistics; gracefully handles un-executed runs
 *
 * **Zero-code entry points:**
 * - [ExperimentRunParameters.toReport] — standalone parameters document
 * - [SimulationRun.toReport] — full run report
 *
 * **Composability example — parameters and inputs only:**
 * ```kotlin
 * report("Pre-Run Review") {
 *     experimentRunParameters(run.experimentRunParameters)
 *     // inputs section without full statistics
 *     section("Inputs") {
 *         val rows = run.inputs.entries.sortedBy { it.key }
 *                       .map { (k, v) -> listOf(k, "%.6f".format(v)) }
 *         dataTable(listOf("Input Name", "Value"), rows)
 *     }
 * }
 * ```
 */

// ── DSL Function 1: Experiment Run Parameters ─────────────────────────────────

/**
 * Appends a single section containing a `DataTable` of all 15 run-configuration
 * fields from [params].
 *
 * This is a standalone building block. It does not require the simulation to have
 * been run — it reports the current values of the configuration properties only.
 *
 * **Produces (inside a section titled `caption` or `"Experiment Run Parameters"`):**
 * - `DataTable` (headers: Parameter | Value): experiment name, experiment ID,
 *   number of replications, run name, starting replication ID, number of chunks,
 *   length of replication, length of replication warm-up, maximum allowed execution
 *   time per replication, replication initialization option, reset start stream,
 *   advance next sub-stream, antithetic option, stream advances prior to running,
 *   garbage collect after replication flag
 *
 * @param params  the [ExperimentRunParameters] to report
 * @param caption optional section title; defaults to `"Experiment Run Parameters"`
 */
fun ReportBuilder.experimentRunParameters(
    params: ExperimentRunParameters,
    caption: String? = null
) {
    val myRows = listOf(
        listOf("Experiment Name",                      params.experimentName),
        listOf("Experiment ID",                        params.experimentId.toString()),
        listOf("Number of Replications",               params.numberOfReplications.toString()),
        listOf("Run Name (Chunk Label)",               params.runName),
        listOf("Starting Replication ID",              params.startingRepId.toString()),
        listOf("Number of Chunks",                     params.numChunks.toString()),
        listOf("Length of Replication",                fmtDouble(params.lengthOfReplication)),
        listOf("Length of Replication Warm-Up",        fmtDouble(params.lengthOfReplicationWarmUp)),
        listOf("Max Execution Time per Replication",   params.maximumAllowedExecutionTimePerReplication.toString()),
        listOf("Replication Initialization Option",    params.replicationInitializationOption.toString()),
        listOf("Reset Start Stream Option",            params.resetStartStreamOption.toString()),
        listOf("Advance Next Sub-Stream Option",       params.advanceNextSubStreamOption.toString()),
        listOf("Antithetic Option",                    params.antitheticOption.toString()),
        listOf("Stream Advances Prior to Running",     params.numberOfStreamAdvancesPriorToRunning.toString()),
        listOf("Garbage Collect After Replication",    params.garbageCollectAfterReplicationFlag.toString())
    )
    section(caption ?: "Experiment Run Parameters") {
        dataTable(
            headers = listOf("Parameter", "Value"),
            rows    = myRows,
            caption = caption ?: "Experiment Run Parameters"
        )
    }
}

// ── DSL Function 2: Simulation Run (composite) ───────────────────────────────

/**
 * Appends a self-contained section reporting the full contents of [run].
 *
 * **Produces (inside a section titled `caption` or `"Simulation Run"`):**
 * 1. **Run Identity** sub-section — `DataTable` (Parameter | Value): run ID, run name,
 *    model identifier, replications executed, execution start time, execution end time,
 *    total execution duration; timing fields show `"—"` when the run has not been executed
 * 2. **Run Error** sub-section — paragraph notice and verbatim error text; present only
 *    when [SimulationRun.runErrorMsg] is non-empty
 * 3. [experimentRunParameters] sub-section — all 15 configuration fields
 * 4. **Inputs** sub-section — `DataTable` (Input Name | Value) of all control and
 *    random-variable parameter inputs sorted alphabetically; a paragraph is shown
 *    instead when no inputs are present
 * 5. **Model Configuration** sub-section — `DataTable` (Key | Value) of model
 *    configuration entries; omitted when [SimulationRun.modelConfiguration] is null
 *    or empty
 * 6. **Replication Timing** sub-section — min, mean, max, and total replication
 *    wall-clock time in **milliseconds** (as recorded by [ksl.observers.SimulationTimer]),
 *    plus an annotation noting that the first replication typically measures higher
 *    than subsequent ones due to JVM JIT compilation warm-up and one-time model
 *    initialization overhead; present only when [showTimings] is `true` and the run
 *    has been executed
 * 7. **Response Statistics** sub-section — `StatTable` for all responses collected
 *    via [SimulationRun.statisticalReporter]; omitted when the run has not been executed
 *
 * @param run             the [SimulationRun] to report
 * @param confidenceLevel confidence level for the response statistics half-width and CI;
 *                        defaults to 0.95
 * @param showTimings     `true` includes the Replication Timing sub-section;
 *                        defaults to `false` because timing data is diagnostic rather
 *                        than part of the primary experimental output
 * @param caption         optional section title; defaults to `"Simulation Run"`
 */
fun ReportBuilder.simulationRun(
    run: SimulationRun,
    confidenceLevel: Double = 0.95,
    showTimings: Boolean = false,
    caption: String? = null
) {
    section(caption ?: "Simulation Run") {

        val myHasRun  = run.hasBeenExecuted
        val myHasErr  = run.hasError

        // ── 1. Run identity ───────────────────────────────────────────────────
        section("Run Identity") {
            val myDuration = if (myHasRun) {
                (run.endExecutionTime - run.beginExecutionTime).toString()
            } else "\u2014"
            val myStart = if (myHasRun) run.beginExecutionTime.toString() else "\u2014"
            val myEnd   = if (myHasRun && run.endExecutionTime != Instant.DISTANT_FUTURE)
                run.endExecutionTime.toString() else "\u2014"

            dataTable(
                headers = listOf("Property", "Value"),
                rows    = listOf(
                    listOf("Run ID",                    run.id),
                    listOf("Run Name",                  run.name),
                    listOf("Model Identifier",          run.modelIdentifier),
                    listOf("Replications Executed",     run.numberOfReplications.toString()),
                    listOf("Execution Start",           myStart),
                    listOf("Execution End",             myEnd),
                    listOf("Total Execution Duration",  myDuration),
                    listOf("Run Error",                 if (myHasErr) "Yes \u26a0" else "No")
                ),
                caption = "Run Identity"
            )
        }

        // ── 2. Error notice ───────────────────────────────────────────────────
        if (myHasErr) {
            section("Run Error") {
                paragraph("The simulation run terminated with an error. No response statistics were recorded.")
                text(run.runErrorMsg)
            }
        }

        // ── 3. Experiment run parameters ──────────────────────────────────────
        experimentRunParameters(run.experimentRunParameters, caption = "Run Parameters")

        // ── 4. Inputs ─────────────────────────────────────────────────────────
        section("Inputs") {
            if (run.inputs.isEmpty()) {
                paragraph("No inputs specified. The model ran with its default parameter settings.")
            } else {
                val myInputRows = run.inputs.entries
                    .sortedBy { it.key }
                    .map { (k, v) -> listOf(k, "%.6g".format(v)) }
                dataTable(
                    headers = listOf("Input Name", "Value"),
                    rows    = myInputRows,
                    caption = "Input Settings (${run.inputs.size} inputs)"
                )
            }
        }

        // ── 5. Model configuration ────────────────────────────────────────────
        val myConfig = run.modelConfiguration
        if (!myConfig.isNullOrEmpty()) {
            section("Model Configuration") {
                val myConfigRows = myConfig.entries
                    .sortedBy { it.key }
                    .map { (k, v) -> listOf(k, v) }
                dataTable(
                    headers = listOf("Configuration Key", "Value"),
                    rows    = myConfigRows,
                    caption = "Model Configuration"
                )
            }
        }

        // ── 6. Replication timing ─────────────────────────────────────────────
        if (showTimings && myHasRun && !myHasErr) {
            val myTimings = run.replicationTimings
            if (myTimings != null && myTimings.isNotEmpty()) {
                section("Replication Timing") {
                    val myTimingStat = Statistic("Replication Wall-Clock Time (ms)", myTimings)
                    dataTable(
                        headers = listOf("Measure", "Value"),
                        rows    = listOf(
                            listOf("Replications",              myTimingStat.count.toLong().toString()),
                            listOf("Min Time (ms)",             fmtDouble(myTimingStat.min)),
                            listOf("Mean Time (ms)",            fmtDouble(myTimingStat.average)),
                            listOf("Max Time (ms)",             fmtDouble(myTimingStat.max)),
                            listOf("Total Wall-Clock (ms)",     fmtDouble(myTimings.sum()))
                        ),
                        caption = "Per-Replication Execution Times (milliseconds)"
                    )
                    paragraph(
                        "Note: The first replication typically records a higher wall-clock time " +
                        "than subsequent replications. This reflects one-time model initialization " +
                        "overhead and JVM JIT compilation warm-up: the first replication executes " +
                        "interpreted bytecode before the JIT compiler has had the opportunity to " +
                        "optimise hot paths. Subsequent replications benefit from compiled native " +
                        "code and skip one-time setup work. The min/mean/max values above include " +
                        "the first replication."
                    )
                }
            }
        }

        // ── 7. Response statistics ────────────────────────────────────────────
        if (!myHasErr) {
            val myStats = run.acrossReplicationStatistics(confidenceLevel).values.toList()
            if (myStats.isEmpty()) {
                paragraph("No response statistics were recorded. The run may not have been executed.")
            } else {
                section("Response Statistics") {
                    statTable(
                        stats           = myStats,
                        caption         = "Across-Replication Statistics (${run.responseCount} responses)",
                        confidenceLevel = confidenceLevel
                    )
                }
            }
        }
    }
}

// ── toReport() — zero-code entry points ──────────────────────────────────────

/**
 * Builds a [ReportNode.Document] containing a standalone run-parameters report
 * via [experimentRunParameters].
 *
 * Zero-code path:
 * ```kotlin
 * val params = model.extractRunParameters()
 * params.toReport("Pre-Run Configuration").showInBrowser()
 * ```
 *
 * @param title  document title; defaults to the experiment name
 * @param block  optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun ExperimentRunParameters.toReport(
    title: String = "Experiment Run Parameters \u2014 $experimentName",
    block: ReportBuilder.() -> Unit = { experimentRunParameters(this@toReport) }
): ReportNode.Document = report(title, block)

/**
 * Builds a [ReportNode.Document] containing a full simulation run report
 * via [simulationRun].
 *
 * Zero-code path:
 * ```kotlin
 * val run = SimulationRunner(model).simulate()
 * run.toReport().showInBrowser()
 * ```
 *
 * Custom block:
 * ```kotlin
 * run.toReport("Pallet Work Center — Baseline") {
 *     simulationRun(this@toReport)
 *     paragraph("Utilization target of 85% was achieved.")
 * }
 * ```
 *
 * @param title           document title; defaults to the run name
 * @param confidenceLevel confidence level for response statistics; defaults to 0.95
 * @param showTimings     `true` includes the Replication Timing sub-section;
 *                        defaults to `false` because timing data is diagnostic rather
 *                        than part of the primary experimental output
 * @param block           optional DSL block; replaces the default when provided
 * @return the assembled [ReportNode.Document]
 */
fun SimulationRun.toReport(
    title: String = "Simulation Run \u2014 $name",
    confidenceLevel: Double = 0.95,
    showTimings: Boolean = false,
    block: ReportBuilder.() -> Unit = { simulationRun(this@toReport, confidenceLevel, showTimings) }
): ReportNode.Document = report(title, block)

