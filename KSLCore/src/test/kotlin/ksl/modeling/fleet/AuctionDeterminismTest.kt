package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.fleet.policies.Bid
import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.CallForProposals
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.NetworkDistanceBid
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 *  An auction gives the same award however the fleet was declared -- exact ties included.
 *
 *  Everywhere else in this subsystem declaration order is the tiebreaker, and deliberately so: it is
 *  stable, stated, and reproducible. Inside an auction it would be wrong. A negotiation treats its
 *  bidders as symmetric except for what they offer, so two vehicles quoting the same number must get
 *  the same answer whichever was declared first; a rule that let the fleet's order settle it would
 *  mean a modeller could change a result by reordering two lines that say nothing about behaviour.
 *
 *  Exact ties are not a curiosity here. The layout below is symmetric on purpose -- two spurs
 *  equidistant from the pickup -- so distance bidding produces a genuine tie on every call, and the
 *  test would have a 50/50 chance of passing by luck if the tiebreak were not total.
 */
class AuctionDeterminismTest {

    companion object {
        const val PICKUP = "Pickup"
        const val DROP = "DropStation"
        const val WEST_SPUR = "WestSpur"
        const val EAST_SPUR = "EastSpur"

        /**
         *  A symmetric layout: `West` and `East` sit on spurs the same distance from the pickup, so
         *  a distance bid from each is exactly equal.
         */
        fun create(): GuidedPathNetwork = GuidedPathNetwork.builder("Symmetric")
            .intersection("Hub", x = 0.0, y = 0.0)
            .intersection("Pick", x = 0.0, y = 40.0)
            .intersection("Drop", x = 0.0, y = -40.0)
            .intersection("W", x = -30.0, y = 0.0)
            .intersection("E", x = 30.0, y = 0.0)
            .link("ToPick", "Hub", "Pick", length = 40.0, zoneLength = 10.0, beginDirection = 90.0)
            .link("PickToDrop", "Pick", "Drop", length = 80.0, zoneLength = 10.0, beginDirection = 270.0)
            .link("DropToHub", "Drop", "Hub", length = 40.0, zoneLength = 10.0, beginDirection = 90.0)
            .link("WSpur", "Hub", "W", length = 30.0, zoneLength = 30.0,
                type = LinkType.SPUR, beginDirection = 180.0)
            .link("ESpur", "Hub", "E", length = 30.0, zoneLength = 30.0,
                type = LinkType.SPUR, beginDirection = 0.0)
            .station(PICKUP, "Pick")
            .station(DROP, "Drop")
            .station(WEST_SPUR, "W")
            .station(EAST_SPUR, "E")
            .build()
    }

    private class Shop(
        parent: ModelElement,
        reversed: Boolean,
        bid: () -> BidPolicyIfc
    ) : ProcessModel(parent, "Shop") {

        val network = create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, assignmentPolicy = ContractNetAssignmentPolicy(0.0), name = "Agv"
        )

        // Name and home follow the vehicle, so reversing changes declaration order and nothing else.
        private val spec = listOf("Alpha" to WEST_SPUR, "Beta" to EAST_SPUR)
            .let { if (reversed) it.reversed() else it }

        val carts = spec.map { (nm, home) ->
            AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = nm)
                .apply { homeBase = home; bidPolicy = bid() }
        }

        var carrier: String? = null
        var tieObserved = false

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(PICKUP)
                carrier = transportByFleet(agv, DROP, origin = PICKUP).vehicleName
            }
        }

        override fun initialize() {
            // Confirm the layout really does produce a tie, so a passing test is not passing because
            // the two bids happened to differ.
            val hub = network.requireLocation(PICKUP)
            val w = network.requireLocation(WEST_SPUR)
            val e = network.requireLocation(EAST_SPUR)
            tieObserved = network.distance(w, hub) == network.distance(e, hub)
            activate(Part().p)
        }
    }

    private fun run(reversed: Boolean, bid: () -> BidPolicyIfc): Shop {
        val m = Model("AuctionDeterminism")
        val shop = Shop(m, reversed, bid)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("Reversing the fleet does not change the award, including on an exact tie")
    fun theAwardIsIndependentOfDeclarationOrder() {
        val forward = run(false) { NetworkDistanceBid() }
        val backward = run(true) { NetworkDistanceBid() }

        // The premise: the layout is symmetric, so both vehicles bid exactly the same number. If a
        // later edit broke that symmetry this test would still pass while proving nothing.
        assertEquals(true, forward.tieObserved,
            "the layout no longer produces an exact tie, so the tiebreak is not being exercised")

        assertEquals(
            assertNotNull(forward.carrier), assertNotNull(backward.carrier),
            "reversing the fleet's declaration order changed who won an exactly tied auction"
        )
        // And the winner is the one the stated rule names -- lowest bid, ties by name -- rather than
        // whichever happened to be asked first.
        assertEquals("Alpha", forward.carrier,
            "the tie was not broken by name, so the rule is not the one documented")
    }

    @Test
    @DisplayName("A supplied selectBest takes over responsibility for its own tiebreak")
    fun aSuppliedRuleOwnsItsOwnTiebreak() {
        // Highest name wins: the opposite of the default, and total, so still order-independent.
        val byLastName = { bids: List<Bid> ->
            bids.maxWithOrNull(compareBy({ -it.value }, { it.vehicle.name }))
        }
        fun runWith(reversed: Boolean): String? {
            val m = Model("AuctionCustom")
            val shop = object : ProcessModel(m, "Shop") {
                val network = create()

                init {
                    spatialModel = network
                }

                val agv = AgvSystem(
                    this, network,
                    assignmentPolicy = ContractNetAssignmentPolicy(0.0, byLastName), name = "Agv"
                )
                val spec = listOf("Alpha" to WEST_SPUR, "Beta" to EAST_SPUR)
                    .let { if (reversed) it.reversed() else it }
                val carts = spec.map { (nm, home) ->
                    AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = nm)
                        .apply { homeBase = home; bidPolicy = NetworkDistanceBid() }
                }
                var carrier: String? = null

                inner class Part : Entity("Part") {
                    val p = process(isDefaultProcess = true) {
                        currentLocation = network.requireLocation(PICKUP)
                        carrier = transportByFleet(agv, DROP, origin = PICKUP).vehicleName
                    }
                }

                override fun initialize() {
                    activate(Part().p)
                }
            }
            m.numberOfReplications = 1
            m.lengthOfReplication = 600.0
            m.simulate()
            return shop.carrier
        }

        assertEquals("Beta", runWith(false), "the supplied rule did not decide the award")
        assertEquals(runWith(false), runWith(true),
            "a total supplied rule still gave an order-dependent answer")
    }
}
