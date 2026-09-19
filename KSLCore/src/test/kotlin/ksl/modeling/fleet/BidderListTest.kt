package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.NetworkDistanceBid
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Where the bidder list comes from, and why it cannot come from the agent registry.
 *
 *  `contractNet` takes a list of `AgentModel.Agent`, and there is an obvious-looking place to get
 *  one: `AgentModel.agents`. It compiles, it returns a list, and for this subsystem it is **always
 *  empty** -- vehicle agents are constructed inside `initialize()`, which makes them runtime agents,
 *  and the registry holds only agents created before the run began.
 *
 *  An auction over an empty bidder list does not fail. It runs, collects nothing, awards nothing,
 *  and is counted as unfilled -- so a fleet wired that way would sit idle beside a full board while
 *  the model ran happily to completion. The symptom is a subsystem that delivers nothing, and the
 *  cause is one plausible-looking accessor.
 *
 *  This test pins both halves: the registry really is empty, and the auction really does reach every
 *  vehicle anyway, because the list is built from `AgvSystem.vehicles` mapped to their live agents.
 */
class BidderListTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, assignmentPolicy = ContractNetAssignmentPolicy(2.0), name = "Agv"
        )

        val carts = listOf(SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.AGV2_HOME)
            .mapIndexed { i, home ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                    .apply { homeBase = home; bidPolicy = NetworkDistanceBid() }
            }

        var registrySizeDuringRun = -1
        val bidsByCart = mutableMapOf<String, Int>()
        var delivered = 0

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                delivered++
            }
        }

        override fun initialize() {
            repeat(4) { i -> activate(Part().p, timeUntilActivation = i * 60.0) }
            schedule(::sample, 1.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            registrySizeDuringRun = agv.agents.size
        }

        override fun replicationEnded() {
            super.replicationEnded()
            for (v in agv.vehicles) bidsByCart[v.name] = v.agent?.bidsSubmitted ?: 0
        }
    }

    @Test
    @DisplayName("Bidders come from the fleet, not the registry -- which is empty and would fail silently")
    fun theBidderListComesFromTheFleet() {
        val m = Model("BidderList")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 900.0
        m.simulate()

        // The trap, demonstrated: the registry a contributor would reach for holds nothing.
        assertEquals(0, shop.registrySizeDuringRun,
            "AgentModel.agents is no longer empty, so the reasoning in this test needs revisiting: " +
                    "if runtime agents began appearing there, the bidder list could legitimately " +
                    "be drawn from it")

        // And the auctions nevertheless reached every vehicle, repeatedly.
        assertTrue(shop.bidsByCart.values.all { it > 0 },
            "a vehicle never bid, so the bidder list is not reaching the whole fleet: ${shop.bidsByCart}")
        assertEquals(2, shop.bidsByCart.size)

        // The work got done, which is what a fleet auctioning over an empty bidder list would not
        // have managed -- it would have run to completion delivering nothing.
        assertEquals(4, shop.delivered, "not every load was delivered")
        assertTrue(shop.agv.dispatcher.numAuctionsRun.value > 0.0, "no auction ran at all")
        assertEquals(0.0, shop.agv.dispatcher.numAuctionsUnfilled.value,
            "an auction went unfilled with a willing fleet, which is what an empty bidder list looks like")
    }
}
