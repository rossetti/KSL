/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.examples.general.controls.experiments

import kotlinx.coroutines.runBlocking
import ksl.controls.experiments.*
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import java.io.PrintWriter

/**
 * Demonstrates the three ways to run [Scenario]s: sequentially through
 * [ScenarioRunner] on one shared model, concurrently through
 * [ConcurrentScenarioRunner] with a fresh model per scenario, and a single
 * [Scenario] run standalone with no runner at all. For how [ScenarioRunner]
 * and [ConcurrentScenarioRunner] use random-number streams, see
 * `ScenarioRandomStreamExamples.kt` in `ksl.examples.general.running`.
 *
 * Model: M/M/c queue ([GIGcQueue]); the control `"MM1Q.numServers"` varies
 * the server count across scenarios named "1 Server", "2 Servers", "3 Servers".
 *
 * Run `main` to execute all three demonstrations in turn.
 */
fun main() = runBlocking {
    demoSequentialScenarios()
    demoConcurrentScenarios()
    demoStandaloneScenario()
}

/**
 * Runs a handful of named, hand-picked scenarios sequentially against one
 * shared [Model] instance. Scenario names must be unique — they become the
 * experiment names in the runner's database.
 */
fun demoSequentialScenarios() {
    val model = Model("MM1_Test")
    model.numberOfReplications = 20
    model.lengthOfReplication = 1000.0
    model.lengthOfReplicationWarmUp = 200.0
    GIGcQueue(model, numServers = 1, name = "MM1Q")

    val runner = ScenarioRunner("Server Study")
    runner.addScenario(model, name = "1 Server", inputs = mapOf("MM1Q.numServers" to 1.0))
    runner.addScenario(model, name = "2 Servers", inputs = mapOf("MM1Q.numServers" to 2.0))
    runner.addScenario(model, name = "3 Servers", inputs = mapOf("MM1Q.numServers" to 3.0))
    runner.simulate()
    // runner.print() builds an unflushed PrintWriter internally, so its output
    // can be lost in a short-lived process; write() with an autoFlush writer avoids that.
    runner.write(PrintWriter(System.out, true))
}

/**
 * Runs the same three-scenario comparison concurrently. Every scenario needs
 * its own fresh model, so scenarios are built from a `ModelBuilderIfc` rather
 * than a shared instance — `addScenario` on [ConcurrentScenarioRunner] rejects
 * a [Scenario] that wraps a pre-built model.
 */
suspend fun demoConcurrentScenarios() {
    val runner = ConcurrentScenarioRunner("Server Study (parallel)")
    for (c in 1..3) {
        val scenario = Scenario(
            modelBuilder = mm1ModelBuilder("MM1_Test"),
            name = "$c Servers",
            inputs = mapOf("MM1Q.numServers" to c.toDouble()),
            numberReplications = 20,
            lengthOfReplication = 1000.0,
            lengthOfReplicationWarmUp = 200.0
        )
        runner.addScenario(scenario)
    }
    runner.simulate()
    // See the comment in demoSequentialScenarios() — write() with an autoFlush
    // writer is used in place of print() for the same reason.
    runner.write(PrintWriter(System.out, true))
}

/**
 * Runs a single [Scenario] standalone, with no [ScenarioRunner] at all —
 * useful for a one-off comparison point or a quick check before wiring up a
 * full study.
 */
fun demoStandaloneScenario() {
    val scenario = Scenario(
        modelBuilder = mm1ModelBuilder("MM1_Test"),
        name = "Baseline",
        numberReplications = 20,
        lengthOfReplication = 1000.0,
        lengthOfReplicationWarmUp = 200.0
    )
    val run: SimulationRun = scenario.simulate()
    println(run.acrossReplicationStatistic("System Time")?.average)
}
