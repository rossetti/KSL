/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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

package ksl.examples.general.agent

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.fail

/**
 *  Every agent example must survive a multi-replication run.
 *
 *  This guard exists because its absence hid a defect in five of the eleven
 *  examples. A `KSLProcess` is one-shot: once completed or terminated it cannot be
 *  activated again. An example that holds an agent as a *model field* and activates
 *  that agent's `script` from `initialize()` therefore re-activates one and the same
 *  process object every replication. It survives replication 1, is terminated by
 *  end-of-replication cleanup, and fails on replication 2 with
 *  "cannot be activated ... because the entity is already running a process".
 *
 *  Nothing caught it because **every example's `main()` sets
 *  `numberOfReplications = 1`**, which is precisely the configuration that hides it.
 *  Running one replication is not a test of a simulation model.
 *
 *  The correct shapes, both already used elsewhere in this package, are to create
 *  the agent fresh inside `initialize()` so each replication gets a new process, or
 *  to give a genuinely permanent agent a statechart rather than a long-lived
 *  process — statecharts are restarted per replication by `AgentModel.initialize()`.
 */
class AgentExampleReplicationTest {

    /**
     *  Builders for every agent example. Kept explicit rather than reflective so a
     *  newly added example has to be listed here, which is the point of the guard.
     */
    private val examples: List<Pair<String, (Model) -> ModelElement>> = listOf(
        "AgentArrivalsExample" to { m: Model -> AgentArrivalsExample(m) },
        "GridEpidemicExample" to { m: Model -> GridEpidemicExample(m) },
        "NetworkRumorExample" to { m: Model -> NetworkRumorExample(m) },
        "CorridorPedestrianExample" to { m: Model -> CorridorPedestrianExample(m) },
        "FlockingExample" to { m: Model -> FlockingExample(m) },
        "PedestrianCrowdExample" to { m: Model -> PedestrianCrowdExample(m) },
        "BuildingEvacuationExample" to { m: Model -> BuildingEvacuationExample(m) },
        "AutonomousForkliftExample" to { m: Model -> AutonomousForkliftExample(m) },
        "JobShopExample" to { m: Model -> JobShopExample(m) },
        "WarehouseAGVExample" to { m: Model -> WarehouseAGVExample(m) },
        "AutonomousDeliveryExample" to { m: Model -> AutonomousDeliveryExample(m) },
        "DroneDeliveryExample" to { m: Model -> DroneDeliveryExample(m) },
    )

    private fun runReplications(label: String, build: (Model) -> ModelElement, reps: Int): String? {
        val model = Model("replicationGuard-$label")
        build(model)
        model.numberOfReplications = reps
        model.lengthOfReplication = 15.0
        return try {
            model.simulate()
            null
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message?.replace('\n', ' ')?.trim()?.take(140)}"
        }
    }

    @Test
    @DisplayName("every agent example completes three replications")
    fun everyAgentExampleSurvivesMultipleReplications() {
        val failures = mutableListOf<String>()
        for ((label, build) in examples) {
            runReplications(label, build, reps = 3)?.let { failures.add("$label -> $it") }
        }
        if (failures.isNotEmpty()) {
            fail(
                "these agent examples cannot run more than one replication:\n" +
                    failures.joinToString("\n") { "  $it" },
            )
        }
    }

    /**
     *  Anti-vacuity: if a single replication also failed, the test above would be
     *  reporting a different problem than the one it is here to catch.
     */
    @Test
    @DisplayName("every agent example completes a single replication")
    fun everyAgentExampleRunsAtAll() {
        val failures = mutableListOf<String>()
        for ((label, build) in examples) {
            runReplications(label, build, reps = 1)?.let { failures.add("$label -> $it") }
        }
        if (failures.isNotEmpty()) {
            fail("these agent examples fail even at one replication:\n" + failures.joinToString("\n") { "  $it" })
        }
    }
}
