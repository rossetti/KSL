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
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  An auction nobody bids on is counted, not raised, and the task stays on the board.
 *
 *  A fleet that is out of range, out of charge, or under a rule that makes it unwilling has nothing
 *  to offer, and that is ordinary operation of a negotiated system rather than a fault. Raising here
 *  would be badly wrong: it would turn a modelling situation the subsystem exists to represent --
 *  demand the fleet cannot currently serve -- into a crash, and it would do so in the models where
 *  it matters most.
 *
 *  So the task waits, accruing waiting time all the while, which is exactly what should happen to
 *  work nobody will take. The count exists because a rising unfilled rate is the earliest sign that
 *  a bidding rule has been set too strictly, and nothing else in the output would say so: the fleet
 *  looks idle, the loads look slow, and no single statistic points at the reason.
 *
 *  ## Who is responsible for asking again
 *
 *  The dispatcher wakes on the two things that can change a decision from inside this subsystem: a
 *  task being posted, and a vehicle declaring itself available. Between them those cover every
 *  internal change, because a vehicle that moves re-declares when it stops.
 *
 *  A fleet that refuses for reasons *outside* the subsystem -- a acceptance not yet begun, here -- is a
 *  different matter. The dispatcher cannot see a acceptance change and must not go looking for one: a
 *  dispatcher waking on a timer to re-ask a question whose answer had not changed would be polling,
 *  and polling in a discrete-event model means a state change has gone unmodelled. The acceptance change
 *  is an event the model already schedules, so the model tells the fleet about it with
 *  [Dispatcher.reconsider]. That is one line, at the place where the world actually changes.
 */
class UnfilledAuctionTest {

    /** Declines until the model says the fleet is accepting work. Silence is how a vehicle
     *  declines -- there is deliberately no "I decline" message for a dispatcher to interpret. */
    private class AcceptingWorkBid(private val acceptingWork: () -> Boolean) : BidPolicyIfc {
        override fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid? {
            if (!acceptingWork()) return null
            return NetworkDistanceBid().bid(vehicle, cfp, space)
        }
    }

    private class Shop(parent: ModelElement, val acceptsFrom: Double) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network, assignmentPolicy = ContractNetAssignmentPolicy(0.0), name = "Agv"
        )

        var acceptingWork = false
            private set

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME; bidPolicy = AcceptingWorkBid { acceptingWork } }

        var result: FleetTransportResult? = null

        /** Board size and refusals while the fleet was off acceptance, so "stayed on the board" is
         *  observed rather than inferred from the load eventually being delivered. */
        var boardWhileRefusing = -1
        var declinesWhileRefusing = 0
        var unfilledWhileRefusing = 0.0

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                result = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
            }
        }

        override fun initialize() {
            acceptingWork = false
            activate(Part().p)
            schedule(::sample, acceptsFrom / 2.0)
            schedule(::startsAccepting, acceptsFrom)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            boardWhileRefusing = agv.dispatcher.taskQ.size
            declinesWhileRefusing = cart.agent?.callsDeclined ?: 0
            unfilledWhileRefusing = agv.dispatcher.numAuctionsUnfilled.value
        }

        /** The world changes, and the model says so. Without the second line the fleet would never
         *  learn: nothing inside the subsystem changed, so nothing inside it would wake. */
        @Suppress("UNUSED_PARAMETER")
        private fun startsAccepting(event: KSLEvent<Nothing>) {
            acceptingWork = true
            agv.dispatcher.reconsider()
        }
    }

    private fun run(acceptsFrom: Double): Shop {
        val m = Model("UnfilledAuction")
        val shop = Shop(m, acceptsFrom)
        m.numberOfReplications = 1
        m.lengthOfReplication = 900.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("Every vehicle declining is counted, the task waits, and no exception is raised")
    fun anUnfilledAuctionIsCountedNotRaised() {
        // Notably: no exception. The whole point is that this runs.
        val shop = run(acceptsFrom = 50.0)

        // The task was on the board while the fleet was refusing it, and the vehicle was being asked
        // rather than passed over.
        assertEquals(1, shop.boardWhileRefusing,
            "the task did not stay on the board while every vehicle was declining")
        assertTrue(shop.declinesWhileRefusing > 0,
            "the vehicle was never asked while it was set to decline, so nothing was tested")
        assertTrue(shop.unfilledWhileRefusing > 0.0,
            "an auction that nobody bid on was not counted")

        // And once the acceptance began -- and the model said so -- the load was served, having waited
        // through the refusal.
        val r = requireNotNull(shop.result) { "the load was never delivered" }
        assertTrue(r.waitForAssignment >= 50.0,
            "the load did not wait through the period in which the fleet refused it: $r")
        assertEquals(1.0, shop.agv.dispatcher.numTasksCompleted.value)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCancelled.value,
            "an unfilled auction cancelled the task rather than leaving it on the board")
        assertTrue(shop.agv.dispatcher.numAuctionsRun.value > shop.agv.dispatcher.numAuctionsUnfilled.value,
            "every auction went unfilled, so the fleet never resumed bidding")
    }

    @Test
    @DisplayName("A change the model does not announce is a change the dispatcher cannot see")
    fun anUnannouncedChangeIsNotNoticed() {
        // The counterpart, and the reason `reconsider` exists rather than a timer. Here the acceptance
        // begins but nothing tells the fleet, and nothing inside the subsystem changes -- no task is
        // posted, no vehicle becomes available, no vehicle moves. So no pass happens and the load
        // waits out the run beside a cart that would now happily take it.
        //
        // This is the honest behaviour, not a defect. A dispatcher that polled would paper over it
        // and would go on polling in every model that never needed it; the alternative on offer is
        // one line at the point where the world actually changes. What the subsystem owes is that
        // the task is not *lost* -- it is on the board, counted, and reported at the horizon.
        val m = Model("UnannouncedAcceptance")
        val shop = object : ProcessModel(m, "Shop") {
            val network = SimpleAgvNetwork.create()

            init {
                spatialModel = network
            }

            val agv = AgvSystem(
                this, network, assignmentPolicy = ContractNetAssignmentPolicy(0.0), name = "Agv"
            )
            var acceptingWork = false
            val cart = AgvVehicle(
                agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
            ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME; bidPolicy = AcceptingWorkBid { acceptingWork } }
            var result: FleetTransportResult? = null

            inner class Part : Entity("Part") {
                val p = process(isDefaultProcess = true) {
                    currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                    result = transportByFleet(
                        agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                    )
                }
            }

            override fun initialize() {
                acceptingWork = false
                activate(Part().p)
                schedule(::startsAcceptingQuietly, 50.0)
            }

            @Suppress("UNUSED_PARAMETER")
            private fun startsAcceptingQuietly(event: KSLEvent<Nothing>) {
                acceptingWork = true      // and deliberately nothing else
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 900.0
        m.simulate()

        assertNull(shop.result,
            "the load was delivered without the model announcing the change, which means something " +
                    "is waking the dispatcher on its own -- check that no polling has crept back in")

        // Not lost, though: still on the board, counted, and reported at the horizon.
        assertEquals(1, shop.agv.unfinishedTasksAtHorizon,
            "the stranded task was not reported at the horizon")
        assertEquals(1, shop.agv.loadsAwaitingPickupAtHorizon)
        assertEquals(0.0, shop.agv.dispatcher.numTasksCompleted.value)
    }
}
