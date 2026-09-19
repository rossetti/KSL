package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 *  Where the fleet is enumerated from, and why it cannot be the agent registry.
 *
 *  `AgentModel` registers an agent in its restart registry only when the agent is constructed
 *  before the run starts. Vehicle agents are constructed inside `initialize()`, because a
 *  `KSLProcess` is a coroutine that runs once and a vehicle must run in every replication -- so
 *  they are runtime agents, and the registry is empty of them by design.
 *
 *  A later contributor who wants "all the agents" will find `AgentModel.agents` and reach for it.
 *  It will compile, it will return an empty list, and a fleet enumerated from it will simply never
 *  be given any work -- silently, in a model that still runs. This test is here to make that a
 *  failing test rather than a puzzling afternoon.
 *
 *  It also pins the constraint the Phase 0 verification turned up: the agent handle is **replaced**
 *  each replication, never reused. Nothing resets a runtime agent's mailbox, so a retained agent
 *  would read the previous replication's traffic.
 */
class RegistryIndependenceTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cartA = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "CartA"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }
        val cartB = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME), ConstantRV(10.0), name = "CartB"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        val registrySizes = mutableListOf<Int>()
        val agentNames = mutableListOf<String>()
        val agentIdentities = mutableListOf<Any>()

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            activate(Part().p)
            schedule(::sample, 1.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            registrySizes.add(agv.agents.size)
            agentNames.addAll(agv.agents.map { it.name })
            agentIdentities.add(assertNotNull(cartA.agent))
        }
    }

    @Test
    @DisplayName("Vehicle agents are absent from the registry; the fleet comes from the system")
    fun theFleetIsNotTheRegistry() {
        val m = Model("RegistryIndependence")
        val shop = Shop(m)
        m.numberOfReplications = 3
        m.lengthOfReplication = 200.0
        m.simulate()

        // The registry never sees them, in any replication.
        assertEquals(listOf(0, 0, 0), shop.registrySizes,
            "a vehicle or dispatcher agent reached AgentModel.agents: ${shop.agentNames}")

        // The fleet is enumerated from the system, in declaration order, which is how ties break.
        assertEquals(listOf("CartA", "CartB"), shop.agv.vehicles.map { it.name })

        // A fresh agent object per replication. Nothing resets a runtime agent's mailbox, so
        // reusing the handle would carry the previous replication's traffic into this one.
        assertEquals(3, shop.agentIdentities.size)
        assertNotSame(shop.agentIdentities[0], shop.agentIdentities[1],
            "the same agent object was reused across replications")
        assertNotSame(shop.agentIdentities[1], shop.agentIdentities[2],
            "the same agent object was reused across replications")

        // And the handles are dropped at the end, so nothing holds a dead agent between runs.
        assertTrue(shop.agv.vehicles.all { it.agent == null },
            "a vehicle still holds last replication's agent after the run")

        // The work actually got done, so the above is not describing an inert model. A counter is
        // per-replication, so this is the last replication's one delivery; the across-replication
        // statistic is what confirms all three did the same.
        assertEquals(1.0, shop.agv.dispatcher.numTasksCompleted.value)
        val across = shop.agv.dispatcher.numTasksCompleted.acrossReplicationStatistic
        assertEquals(3.0, across.count, "each of the three replications should have contributed")
        assertEquals(1.0, across.average, 1e-9, "every replication should have delivered exactly one load")
    }
}
