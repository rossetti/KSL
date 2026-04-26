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

import ksl.controls.experiments.Scenario
import ksl.controls.experiments.ScenarioRunner
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.scenario
import ksl.utilities.io.report.extensions.scenarioRunner
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.showInBrowser

/**
 * Demonstrates use of the scenario-runner reporting framework extensions.
 *
 * Model: M/M/1 queue ([GIGcQueue]) with default parameters — mean inter-arrival
 * time 1.0 (λ = 1.0, stream 1), mean service time 0.5 (μ = 2.0, stream 2).
 * The control `"MM1Q.numServers"` is used to vary the server count between scenarios.
 *
 * Single-server traffic intensity: ρ = λ / μ = 0.50.
 * With c servers: ρ = λ / (c · μ).
 *
 * Three demos:
 * 1. [demoSingleScenario]   — zero-code [Scenario.toReport] for a single scenario;
 *                              shows the not-executed and executed paths
 * 2. [demoScenarioRunner]   — zero-code [ScenarioRunner.toReport] for a three-scenario
 *                              study (1, 2, 3 servers); all scenarios share the same model
 * 3. [demoScenarioCustomBlock] — custom `report {}` block composing a narrative
 *                              paragraph with [scenarioRunner]; illustrates composability
 */
fun main() {
//    demoSingleScenario()
    demoScenarioRunner()
//    demoScenarioCustomBlock()
}

// ── Shared model factory ──────────────────────────────────────────────────────

/**
 * Builds a fresh M/M/1 model with [GIGcQueue] named `"MM1Q"`.
 *
 * Default settings: 30 replications, run length = 1 000 time units,
 * warm-up = 100 time units. Uses [GIGcQueue] defaults: mean inter-arrival
 * time = 1.0 (λ = 1.0, stream 1), mean service time = 0.5 (μ = 2.0, stream 2).
 */
private fun buildQueueModel(name: String): Model {
    val myModel = Model(name, autoCSVReports = false)
    myModel.numberOfReplications = 30
    myModel.lengthOfReplication = 1_000.0
    myModel.lengthOfReplicationWarmUp = 100.0
    GIGcQueue(myModel, numServers = 1, name = "MM1Q")
    return myModel
}

// ── Demo 1: Single Scenario ───────────────────────────────────────────────────

/**
 * Demonstrates [Scenario.toReport] as a zero-code entry point for a single scenario.
 *
 * Two reports are produced:
 * - **Before execution** — shows the "not-executed" path with the scenario overview
 *   table and the notice paragraph; demonstrates that the report is safe to call
 *   at any time
 * - **After execution** — shows the full scenario report with run identity,
 *   inputs, and across-replication response statistics
 *
 * The scenario uses 2 servers (`"MM1Q.numServers" = 2.0`), giving
 * ρ = λ / (2 · μ) = 1.0 / (2 · 2.0) = 0.25.
 *
 * **Illustrates:**
 * - [Scenario.toReport] zero-code path
 * - Graceful handling of un-executed scenarios
 * - Scenario overview table (name, model, settings, status)
 * - Full simulation results embedded in the scenario section after execution
 */
fun demoSingleScenario() {
    println("=== Demo 1: Single Scenario ===")

    val myModel   = buildQueueModel("MM1_ScenarioDemo")
    val myInputs  = mapOf("MM1Q.numServers" to 2.0)
    val myScenario = Scenario(model = myModel, name = "MM1_2Servers", inputs = myInputs)

    println("Before execution:")
    println("  scenario.simulationRun = ${myScenario.simulationRun}")

    // Report before execution — shows not-executed notice
    myScenario.toReport("M/M/1 Queue — Scenario (Before Execution)").showInBrowser()

    println("Executing scenario (2 servers, \u03c1 = 0.25)...")
    myScenario.simulate()
    println("After execution:")
    println("  scenario.simulationRun = ${myScenario.simulationRun?.name}")

    // Report after execution — full results
    myScenario.toReport("M/M/1 Queue — Scenario (After Execution)").showInBrowser()
}

// ── Demo 2: Scenario Runner ───────────────────────────────────────────────────

/**
 * Demonstrates [ScenarioRunner.toReport] as a zero-code entry point for a
 * multi-scenario study.
 *
 * Three scenarios are defined on separate model instances, varying server count:
 * - **Baseline** — 1 server; ρ = λ / μ = 0.50
 * - **2 Servers** — 2 servers; ρ = λ / (2 · μ) = 0.25
 * - **3 Servers** — 3 servers; ρ = λ / (3 · μ) = 0.17
 *
 * All three scenarios use different models with the same default GIGcQueue
 * settings. `ScenarioRunner.simulate()` is called once to run all three.
 *
 * **Illustrates:**
 * - [ScenarioRunner.toReport] zero-code path
 * - Runner overview (name, output directory, scenario counts)
 * - Scenario summary table (one row per scenario with configured settings)
 * - Per-scenario sections with full simulation results
 * - How scenario names differentiate experiments in the KSL database
 */
fun demoScenarioRunner() {
    println("\n=== Demo 2: Scenario Runner ===")

    // Each scenario uses its own model instance
    val myModel1 = buildQueueModel("MM1_Scenario1")
    val myModel2 = buildQueueModel("MM1_Scenario2")
    val myModel3 = buildQueueModel("MM1_Scenario3")

    val myScenario1 = Scenario(model = myModel1, name = "MM1_1Server",
        inputs = mapOf("MM1Q.numServers" to 1.0))
    val myScenario2 = Scenario(model = myModel2, name = "MM1_2Servers",
        inputs = mapOf("MM1Q.numServers" to 2.0))
    val myScenario3 = Scenario(model = myModel3, name = "MM1_3Servers",
        inputs = mapOf("MM1Q.numServers" to 3.0))

    val myRunner = ScenarioRunner(
        name         = "MM1_ServerCountStudy",
        scenarioList = listOf(myScenario1, myScenario2, myScenario3)
    )

    println("Running all scenarios...")
    myRunner.simulate()
    println("All scenarios complete.")
    myRunner.print()

    // Zero-code full runner report
    myRunner.toReport("M/M/1 Queue \u2014 Server Count Study").showInBrowser()
}

// ── Demo 3: Custom Block ──────────────────────────────────────────────────────

/**
 * Demonstrates composing [scenarioRunner] inside a custom `report {}` block
 * with a narrative preamble and analysis paragraph.
 *
 * The same three-scenario setup from [demoScenarioRunner] is used. The custom
 * block adds context paragraphs before and after the runner section, and
 * requests 99% confidence intervals.
 *
 * **Illustrates:**
 * - [scenarioRunner] called inside a custom `report {}` block
 * - Narrative paragraphs wrapping the scenario results
 * - Custom `confidenceLevel` (0.99) applied to all embedded scenario reports
 * - Composability of the scenario reporting framework
 */
fun demoScenarioCustomBlock() {
    println("\n=== Demo 3: Custom Block ===")

    val myModel1 = buildQueueModel("MM1_Custom1")
    val myModel2 = buildQueueModel("MM1_Custom2")
    val myModel3 = buildQueueModel("MM1_Custom3")

    val myScenario1 = Scenario(model = myModel1, name = "MM1_1Server_C",
        inputs = mapOf("MM1Q.numServers" to 1.0))
    val myScenario2 = Scenario(model = myModel2, name = "MM1_2Servers_C",
        inputs = mapOf("MM1Q.numServers" to 2.0))
    val myScenario3 = Scenario(model = myModel3, name = "MM1_3Servers_C",
        inputs = mapOf("MM1Q.numServers" to 3.0))

    val myRunner = ScenarioRunner(
        name         = "MM1_Custom_Study",
        scenarioList = listOf(myScenario1, myScenario2, myScenario3)
    )

    println("Running all scenarios...")
    myRunner.simulate()

    val myDoc = report("M/M/1 Queue \u2014 Server Count Analysis") {
        paragraph(
            "This study compares M/M/1 queue performance under three server-count " +
            "configurations. The model uses exponential inter-arrival times (mean 1.0, " +
            "\u03bb = 1.0) and exponential service times (mean 0.5, \u03bc = 2.0). " +
            "Traffic intensities: 1 server \u03c1 = 0.50, 2 servers \u03c1 = 0.25, " +
            "3 servers \u03c1 = 0.17."
        )
        scenarioRunner(myRunner, confidenceLevel = 0.99, caption = "Server Count Scenarios")
        paragraph(
            "All scenarios converged to stable estimates. Increasing server count " +
            "produces monotonically decreasing mean system time and queue length, " +
            "as expected from M/M/c queuing theory. " +
            "See the Response Statistics sections for 99% confidence interval comparisons."
        )
    }
    myDoc.showInBrowser()
}
