package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.NetworkDistanceBid
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  Negotiating is not free, and the model says so.
 *
 *  An auction that awarded instantaneously would be a convenient fiction: real dispatching protocols
 *  take time to run, and a subsystem whose auctions cost nothing would let a modeller conclude that
 *  negotiation is strictly better than a rule, which it is not. The deadline is charged in simulated
 *  time and shows up where it belongs -- in what the loads waited.
 *
 *  The same model is run at several deadlines. The first load's wait is exactly the deadline, to the
 *  digit, because with an idle fleet the only thing between posting and award is the auction itself.
 *
 *  This also closes the zero-deadline question. Zero is not a degenerate case that collects no bids:
 *  a bid is computed by a non-suspending mailbox handler that answers inside the broadcast, so at
 *  zero every vehicle has still bid and the award is the same one a positive deadline would reach.
 *  What zero means is "negotiation is instantaneous", which is a modelling choice rather than a trap
 *  -- and it is safe because `BidPolicyIfc.bid` is not a suspending function, so a bidding rule that
 *  consumed time cannot be written in the first place.
 */
class AuctionCostsTimeTest {

    private class Shop(parent: ModelElement, deadline: Double) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network,
            assignmentPolicy = ContractNetAssignmentPolicy(deadline), name = "Agv"
        )

        val carts = listOf(SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.AGV2_HOME)
            .mapIndexed { i, home ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                    .apply { homeBase = home; bidPolicy = NetworkDistanceBid() }
            }

        val firstWait = Statistic("firstWait")
        val allWaits = Statistic("allWaits")
        var carrier: String? = null
        private var seen = 0

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val r = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                allWaits.collect(r.waitForAssignment)
                if (seen++ == 0) { firstWait.collect(r.waitForAssignment); carrier = r.vehicleName }
            }
        }

        private val tba = ExponentialRV(70.0, streamNum = 1)

        inner class Source : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(30) { delay(tba); activate(Part().p) }
            }
        }

        override fun initialize() {
            seen = 0
            // The first load arrives at time zero with the whole fleet idle, so nothing stands
            // between its posting and its award except the auction.
            activate(Part().p)
            activate(Source().g)
        }
    }

    private fun run(deadline: Double): Shop {
        val m = Model("AuctionCost")
        val shop = Shop(m, deadline)
        m.numberOfReplications = 5
        m.lengthOfReplication = 2500.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("The deadline is charged in simulated time and appears in what the loads waited")
    fun theDeadlineShowsUpInTheAnswer() {
        val results = listOf(0.0, 5.0, 20.0).map { it to run(it) }

        val report = buildString {
            append("\nAuction cost -- two carts, distance bidding\n")
            append("%-10s %14s %14s %14s\n".format("deadline", "first wait", "mean wait", "auctions"))
            for ((d, s) in results) {
                append("%-10.1f %14.4f %14.4f %14.1f\n".format(
                    d, s.firstWait.average, s.allWaits.average,
                    s.agv.dispatcher.numAuctionsRun.acrossReplicationStatistic.average))
            }
        }
        println(report)

        for ((deadline, shop) in results) {
            // Exactly the deadline: with an idle fleet the auction is the only thing that happens
            // between posting and award.
            assertEquals(deadline, shop.firstWait.average, 1e-9,
                "the first load's wait for assignment is not the auction deadline$report")
            assertTrue(shop.agv.dispatcher.numAuctionsRun.acrossReplicationStatistic.average > 10.0,
                "too few auctions for the comparison to mean anything$report")
        }

        // A longer deadline costs more waiting overall, which is the trade being made visible.
        val (_, atZero) = results[0]
        val (_, atTwenty) = results[2]
        assertTrue(atTwenty.allWaits.average > atZero.allWaits.average,
            "a 20-unit deadline cost nothing overall, so it cannot be being charged$report")

        // And a zero deadline still awards: it is instantaneous negotiation, not absent negotiation.
        assertEquals(0.0, atZero.agv.dispatcher.numAuctionsUnfilled.acrossReplicationStatistic.average, 1e-9,
            "a zero deadline left auctions unfilled, so it is collecting no bids$report")
        assertEquals("Cart2", atZero.carrier,
            "at a zero deadline the award differs from the one distance bidding should reach$report")
    }
}
