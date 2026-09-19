package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.spatial.FleetSpaceIfc
import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.Bid
import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.CallForProposals
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.NetworkDistanceBid
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  A real auction: the fleet's bidding rule decides the award, not the dispatcher's arithmetic.
 *
 *  This is the test that separates a negotiation from a distance rule wearing a negotiation's
 *  clothes. A dispatcher that computed each vehicle's distance itself and called the result an
 *  auction would pass any test that only checked "the nearest vehicle won" -- so the same layout,
 *  the same dispatcher and the same tasks are run twice, changing **only the fleet's bid policy**,
 *  and a different vehicle must win.
 *
 *  Where the knowledge lives is the whole substance of the paradigm. Under the passive one there is
 *  nothing a resource could hold an opinion with; here the vehicle answers, and the dispatcher does
 *  not know how it arrived at its number.
 */
class AuctionTest {

    /** Bids the vehicle's own name length: nothing to do with geography, everything to do with the
     *  fact that the vehicle decides. Rigged so `Cart1` -- the network-*further* one -- wins. */
    private class PreferByNameBid(private val favoured: String) : BidPolicyIfc {
        override fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid =
            Bid(vehicle, if (vehicle.name == favoured) 0.0 else 1000.0, "favours $favoured")
    }

    private class Shop(
        parent: ModelElement,
        bidFor: (String) -> BidPolicyIfc,
        deadline: Double = 0.0
    ) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, assignmentPolicy = ContractNetAssignmentPolicy(deadline), name = "Agv"
        )

        val carts = listOf("Cart1" to SimpleAgvNetwork.AGV1_HOME, "Cart2" to SimpleAgvNetwork.AGV2_HOME)
            .map { (nm, home) ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = nm)
                    .apply { homeBase = home; bidPolicy = bidFor(nm) }
            }

        var carrier: String? = null

        /** Captured at the horizon. An agent is a per-replication object and its handle is dropped
         *  in `afterReplication`, so asking a vehicle about its agent once the run has finished
         *  gets null -- which reads as "never bid" and is not the same thing at all. */
        val bidsByCart = mutableMapOf<String, Int>()
        val declinesByCart = mutableMapOf<String, Int>()

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                carrier = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                ).vehicleName
            }
        }

        override fun initialize() {
            activate(Part().p)
        }

        override fun replicationEnded() {
            super.replicationEnded()
            for (v in agv.vehicles) {
                bidsByCart[v.name] = v.agent?.bidsSubmitted ?: 0
                declinesByCart[v.name] = v.agent?.callsDeclined ?: 0
            }
        }
    }

    private fun run(bidFor: (String) -> BidPolicyIfc): Shop {
        val m = Model("Auction")
        val shop = Shop(m, bidFor)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("The fleet's bid policy decides the winner, not the dispatcher")
    fun theBidPolicyDecidesTheAward() {
        // Under distance bidding, the vehicle nearest along the guide path wins. On this one-way
        // loop that is Cart2 at I7 (126 to the entry station) rather than Cart1 at I6 (198).
        val byDistance = run { NetworkDistanceBid() }
        assertEquals("Cart2", assertNotNull(byDistance.carrier),
            "distance bidding did not award to the vehicle nearest along the path")

        // Same layout, same dispatcher, same task. Only the fleet's bidding rule changes -- and the
        // award changes with it. A dispatcher computing distances itself could not produce this.
        val byName = run { PreferByNameBid("Cart1") }
        assertEquals("Cart1", assertNotNull(byName.carrier),
            "changing only the fleet's bid policy did not change the award, so the dispatcher is " +
                    "deciding rather than the vehicles")

        // Both fleets actually bid, so neither result is an accident of nobody answering.
        for (shop in listOf(byDistance, byName)) {
            assertEquals(1.0, shop.agv.dispatcher.numAuctionsRun.value, "no auction was run")
            assertEquals(0.0, shop.agv.dispatcher.numAuctionsUnfilled.value, "the auction went unfilled")
        }
    }

    @Test
    @DisplayName("Every bidder is asked, and the bids reach the dispatcher")
    fun everyAvailableVehicleIsAsked() {
        val shop = run { NetworkDistanceBid() }
        // Both carts are idle when the task is posted, so both should have been called and both
        // should have answered. One bid would still have produced a winner and a passing model,
        // which is why the count is asserted rather than the outcome.
        assertEquals(mapOf("Cart1" to 1, "Cart2" to 1), shop.bidsByCart,
            "not every available vehicle bid")
        assertEquals(mapOf("Cart1" to 0, "Cart2" to 0), shop.declinesByCart,
            "a vehicle declined a call it should have answered")
    }
}
