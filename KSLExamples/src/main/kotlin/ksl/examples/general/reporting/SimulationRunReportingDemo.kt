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

package ksl.examples.general.reporting

import ksl.controls.experiments.ExperimentRunParameters
import ksl.controls.experiments.SimulationRun
import ksl.controls.experiments.SimulationRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.experimentRunParameters
import ksl.utilities.io.report.extensions.simulationRun
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser

/**
 * Demonstrates use of the simulation-run reporting framework extensions.
 *
 * Model: M/M/c queue ([GIGcQueue]) — an M/M/1 system with exponential inter-arrival
 * and service times — with responses: NumBusy, Num in System, System Time, Num Served.
 * The control `"MM1Q.numServers"` adjusts the server count between scenarios.
 *
 * Four demos:
 * 1. [demoRunParametersOnly] — zero-code [ExperimentRunParameters.toReport] on
 *    parameters extracted before running; shows configuration without any results
 * 2. [demoSimulationRunBasic] — zero-code [SimulationRun.toReport] after a default
 *    run; all seven report sections rendered
 * 3. [demoSimulationRunWithInputs] — run with explicit inputs (2 servers via
 *    `"MM1Q.numServers"`); inputs section shows the applied control value
 * 4. [demoSimulationRunGranularBlock] — custom DSL block that embeds
 *    `experimentRunParameters()` and `simulationRun()` with a custom caption and
 *    an appended narrative `paragraph`; illustrates composability
 */
fun main() {
    demoRunParametersOnly()
//    demoSimulationRunBasic()
//    demoSimulationRunWithInputs()
//    demoSimulationRunGranularBlock()
}

// ── Shared model factory ──────────────────────────────────────────────────────

/**
 * Builds a fresh M/M/1 model with [GIGcQueue] named `"MM1Q"`.
 *
 * Default settings: 30 replications, run length = 1 000 time units,
 * warm-up = 100 time units.  Inter-arrival rate = 1.0 (stream 1),
 * service rate = 1.25 (stream 2) giving ρ ≈ 0.80.
 */
private fun buildModel(name: String = "MM1_Model"): Model {
    val myModel = Model(name, autoCSVReports = false)
    myModel.numberOfReplications  = 30
    myModel.lengthOfReplication   = 1_000.0
    myModel.lengthOfReplicationWarmUp = 100.0
    // GIGcQueue default streams: arrivals on stream 1, service on stream 2
    GIGcQueue(myModel, numServers = 1, name = "MM1Q")
    return myModel
}

// ── Demo 1: Run Parameters Only ───────────────────────────────────────────────

/**
 * Demonstrates [ExperimentRunParameters.toReport] as a zero-code way to document
 * a simulation's configuration before execution.
 *
 * The parameters are extracted from the model via `model.extractRunParameters()`
 * and reported without executing any replications. This is useful for reviewing
 * and sharing experimental setup documentation ahead of a production run.
 *
 * **Illustrates:**
 * - [ExperimentRunParameters.toReport] zero-code path
 * - `experimentRunParameters()` embedded inside a custom `report {}` block
 *   with a pre-run commentary paragraph
 */
fun demoRunParametersOnly() {
    println("=== Demo 1: Run Parameters Only ===")

    val myModel  = buildModel("MM1_ParamsOnly")
    val myParams = myModel.extractRunParameters()

    println("Experiment name : ${myParams.experimentName}")
    println("Replications    : ${myParams.numberOfReplications}")
    println("Run length      : ${myParams.lengthOfReplication}")
    println("Warm-up         : ${myParams.lengthOfReplicationWarmUp}")

    // Zero-code path
    myParams.toReport("M/M/1 Queue — Pre-Run Configuration").showInBrowser()

    // Granular: parameters + commentary in one document
    val myDoc = report("M/M/1 Queue — Planned Experiment") {
        experimentRunParameters(myParams, caption = "Planned Run Configuration")
        paragraph(
            "Planned study: M/M/1 queue with arrival rate \u03bb = 1.0, " +
            "service rate \u03bc = 1.25, giving utilisation \u03c1 \u2248 0.80. " +
            "${myParams.numberOfReplications} replications of length " +
            "${myParams.lengthOfReplication.toInt()} with warm-up " +
            "${myParams.lengthOfReplicationWarmUp.toInt()}."
        )
    }
    myDoc.showInBrowser()
}

// ── Demo 2: Basic Simulation Run ──────────────────────────────────────────────

/**
 * Demonstrates [SimulationRun.toReport] as a zero-code entry point after a
 * completed simulation run with default inputs.
 *
 * The model runs with 1 server at ρ ≈ 0.80. The report shows all seven sections:
 * run identity (with real execution timestamps and duration), run parameters,
 * empty inputs notice, replication timing summary, and across-replication
 * response statistics for NumBusy, Num in System, System Time, and Num Served.
 *
 * **Illustrates:**
 * - [SimulationRun.toReport] zero-code path
 * - Run identity section with real `beginExecutionTime`/`endExecutionTime`
 * - Response statistics `StatTable` for all model responses
 * - Replication timing section (opt-in via `showTimings = true`):
 *   wall-clock times in **milliseconds**, JVM warm-up annotation
 */
fun demoSimulationRunBasic() {
    println("\n=== Demo 2: Basic Simulation Run ===")

    val myModel  = buildModel("MM1_Basic")
    val myRunner = SimulationRunner(myModel)

    println("Running simulation (1 server, \u03c1 \u2248 0.80)...")
    val myRun = myRunner.simulate()
    println(myRun)

    // Default report: no timing section
    myRun.toReport("M/M/1 Queue — Baseline Run").showInBrowser()

    // With timing section (diagnostic): note millisecond units and first-rep anomaly
    myRun.toReport("M/M/1 Queue — Baseline Run (with Timings)", showTimings = true)
        .showInBrowser()
}

// ── Demo 3: Simulation Run With Inputs ────────────────────────────────────────

/**
 * Demonstrates the inputs section of [simulationRun] when explicit control values
 * are supplied to the runner.
 *
 * Two runs are executed and compared:
 * - **Baseline** — 1 server (`"MM1Q.numServers" = 1.0`)
 * - **2-Server** — 2 servers (`"MM1Q.numServers" = 2.0`)
 *
 * Both runs use the same model and runner instance. Each run is reported individually
 * and then both are composed into a single comparison document.
 *
 * **Illustrates:**
 * - Inputs section showing the applied `"MM1Q.numServers"` control value
 * - Two `simulationRun()` sections inside one `report {}` block for comparison
 * - How run identity differentiates the two runs via their run names
 */
fun demoSimulationRunWithInputs() {
    println("\n=== Demo 3: Simulation Run With Inputs ===")

    val myModel   = buildModel("MM1_InputsDemo")
    val myRunner  = SimulationRunner(myModel)
    val myParams1 = myModel.extractRunParameters().apply { experimentName = "MM1_1Server" }
    val myParams2 = myModel.extractRunParameters().apply { experimentName = "MM1_2Servers" }

    val myInputs1 = mapOf("MM1Q.numServers" to 1.0)
    val myInputs2 = mapOf("MM1Q.numServers" to 2.0)

    println("Running 1-server scenario...")
    val myRun1 = myRunner.simulate(
        modelIdentifier        = myModel.simulationName,
        inputs                 = myInputs1,
        experimentRunParameters = myParams1
    )

    println("Running 2-server scenario...")
    val myRun2 = myRunner.simulate(
        modelIdentifier        = myModel.simulationName,
        inputs                 = myInputs2,
        experimentRunParameters = myParams2
    )

    // Individual zero-code reports
    myRun1.toReport("M/M/1 Queue — 1 Server").showInBrowser()
    myRun2.toReport("M/M/1 Queue — 2 Servers").showInBrowser()

    // Comparison document
    val myDoc = report("M/M/1 Queue — Server Count Comparison") {
        simulationRun(myRun1, caption = "Scenario: 1 Server (\u03c1 \u2248 0.80)")
        simulationRun(myRun2, caption = "Scenario: 2 Servers (\u03c1 \u2248 0.40)")
        paragraph(
            "Adding a second server reduces mean system time and queue length " +
            "substantially. See the Response Statistics sections above for confidence " +
            "interval comparisons."
        )
    }
    myDoc.showInBrowser()
}

// ── Demo 4: Granular Custom Block ─────────────────────────────────────────────

/**
 * Demonstrates composing [experimentRunParameters] and [simulationRun] inside a
 * custom `report {}` block with a narrative paragraph.
 *
 * The run parameters are extracted and reported before execution (pre-run review),
 * and then the executed run is embedded in the same document alongside a brief
 * analysis note.
 *
 * **Illustrates:**
 * - `experimentRunParameters()` called standalone at the document level
 * - `simulationRun()` called with a custom caption
 * - Narrative `paragraph` appended after the run section
 * - Using `section {}` to group the pre-run and post-run content
 */
fun demoSimulationRunGranularBlock() {
    println("\n=== Demo 4: Granular Custom Block ===")

    val myModel  = buildModel("MM1_Granular")
    val myParams = myModel.extractRunParameters()
    val myRunner = SimulationRunner(myModel)

    println("Running simulation...")
    val myRun = myRunner.simulate()

    val myDoc = report("M/M/1 Queue — Study Report") {
        section("Pre-Run Configuration") {
            experimentRunParameters(myParams, caption = "Planned Parameters")
            paragraph(
                "The experiment was designed for ${myParams.numberOfReplications} " +
                "independent replications of length ${myParams.lengthOfReplication.toInt()} " +
                "with a ${myParams.lengthOfReplicationWarmUp.toInt()}-unit warm-up period."
            )
        }
        simulationRun(myRun, confidenceLevel = 0.99, showTimings = true, caption = "Execution Results")
        paragraph(
            "All 95% confidence intervals are contained within \u00b10.05 of the " +
            "point estimates, indicating sufficient replication count. " +
            "Estimated server utilisation: see NumBusy in the Response Statistics section."
        )
    }
    myDoc.showInBrowser()
}
