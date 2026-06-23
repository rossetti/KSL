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

package ksl.modeling.agent

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Phase B of the station-integration API asks: [AgentModel.AgentRegistryObserver]
 *  hands external/integration code a reference to every agent as it is
 *  created — crucially including transient agents created during a
 *  replication, which are not in the [AgentModel.agents] registry and
 *  could not otherwise be referenced.
 */
class AgentRegistryObserverTest {

    private class RegistryModel(parent: ModelElement, name: String? = null) :
        AgentModel(parent, name) {

        val seen = mutableListOf<String>()

        // Attached *before* the agent fields below so it also catches the
        // setup-time agents (init blocks / field initializers run in order).
        val recorder = object : AgentRegistryObserver {
            override fun onAgentRegistered(agent: AgentLike) { seen.add(agent.name) }
        }

        init {
            attachRegistryObserver(recorder)
        }

        val supervisor = PermanentAgent("supervisor")
        val server = AgentResource(this, "server", capacity = 1)

        inner class Walker(n: String) : Agent(n)

        override fun initialize() {
            super.initialize()
            // A transient agent created during the run — not in `agents`.
            Walker("walker")
        }
    }

    @Test
    fun observerSeesSetupTimeAndTransientAgents() {
        val model = Model("RegistryObserverTest")
        val m = RegistryModel(model, "reg")
        model.lengthOfReplication = 5.0
        model.numberOfReplications = 1
        model.simulate()

        assertTrue("supervisor" in m.seen, "setup-time PermanentAgent should be reported")
        assertTrue("server" in m.seen, "setup-time AgentResource should be reported")
        assertTrue("walker" in m.seen, "transient runtime Agent should be reported")

        // Division of labor: the `agents` registry holds only setup-time
        // agents that drive the per-replication restart (Agent /
        // PermanentAgent). The AgentResource and the transient Walker are
        // not in it — the observer is how you learn about those.
        val registered = m.agents.map { it.name }
        assertTrue("supervisor" in registered)
        assertTrue("server" !in registered, "AgentResource is not in the agents registry")
        assertTrue("walker" !in registered, "transient agents are not in the agents registry")
    }

    @Test
    fun registryObserversAttachAndDetach() {
        val model = Model("RegistryObserverCountTest")
        val m = RegistryModel(model, "regc")
        assertEquals(1, m.registryObserverCount)
        m.detachRegistryObserver(m.recorder)
        assertEquals(0, m.registryObserverCount)
    }
}
