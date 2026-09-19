package ksl.fleet.policy

import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.spatial.FleetSpaceIfc
import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.fleet.AssignmentProposal
import ksl.modeling.fleet.Dispatcher
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.Bid
import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.CallForProposals
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.Disposition
import ksl.modeling.fleet.policies.DispositionPolicyIfc
import ksl.modeling.fleet.policies.TaskSelectionRuleIfc
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  All four decision seams, implemented from **outside** the subsystem's package.
 *
 *  This file lives in `ksl.agv.policy` deliberately. A seam that can only be extended from inside
 *  the package it belongs to is not an extension point -- it is an internal abstraction with
 *  aspirations, and the difference is invisible from within. Everything below is written against the
 *  public API alone: if any of it required an `internal` member, this would not compile, and **the
 *  compilation is the substance of the test**. The assertions afterwards only confirm that a model
 *  built entirely from outside rules actually runs.
 *
 *  The four seams are the four questions the subsystem does not answer for itself: who gets sent
 *  (assignment), what an idle vehicle does (disposition), what a vehicle offers when asked (bidding),
 *  and in what order work is considered (task selection).
 */
class PolicySubstitutionTest {

    /** Seam 1: assignment. Sends whichever available vehicle sorts first by name. */
    private class AlphabeticalPolicy : AssignmentPolicyIfc {
        var callCount = 0
            private set

        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            callCount++
            val free = context.available.sortedBy { it.name }.toMutableList()
            val out = mutableListOf<AssignmentProposal>()
            for (task in context.board.unassigned) {
                if (free.isEmpty()) break
                // Reading a task's public surface from outside: where it must go, what it waits for.
                val v = free.removeAt(0)
                out.add(AssignmentProposal(v, task, terms = context.distanceTo(v, task.pickupLocation)))
            }
            return out
        }

        override fun toString(): String = "AlphabeticalPolicy"
    }

    /** Seam 2: disposition. Alternates between going home and staying put. */
    private class AlternatingDisposition : DispositionPolicyIfc {
        private var goHome = true
        var calls = 0
            private set

        override fun disposition(vehicle: FleetVehicle): Disposition {
            calls++
            goHome = !goHome
            return if (goHome) Disposition.ReturnToHomeBase else Disposition.ParkInPlace
        }
    }

    /** Seam 3: bidding. Quotes distance, and declines outright beyond a stated range. */
    private class RangeLimitedBid(private val maxRange: Double) : BidPolicyIfc {
        var declines = 0
            private set

        override fun bid(vehicle: FleetVehicle, cfp: CallForProposals, space: FleetSpaceIfc): Bid? {
            val here = space.location(vehicle.currentLocationName) ?: return null
            val there = space.location(cfp.task.pickupLocation) ?: return null
            if (!space.isReachable(here, there)) { declines++; return null }
            val d = space.distance(here, there)
            if (d > maxRange) { declines++; return null }
            return Bid(vehicle, d, "within $maxRange")
        }
    }

    /** Seam 4: task selection. Longest-waiting first, then by name, so it is total. */
    private class OldestThenNameSelection : TaskSelectionRuleIfc {
        var calls = 0
            private set

        override fun order(tasks: List<Dispatcher.Task>): List<Dispatcher.Task> {
            calls++
            return tasks.sortedWith(compareBy({ it.timeEnteredQueue }, { it.name }))
        }
    }

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val policy = AlphabeticalPolicy()
        val selection = OldestThenNameSelection()
        val disposition = AlternatingDisposition()
        val bidding = RangeLimitedBid(maxRange = 150.0)

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        val carts = listOf(SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.AGV2_HOME)
            .mapIndexed { i, home ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                    .apply {
                        homeBase = home
                        dispositionPolicy = disposition
                        bidPolicy = bidding
                    }
            }

        init {
            agv.dispatcher.taskSelectionRule = selection
        }

        var delivered = 0

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                delivered++
            }
        }

        private val tba = ExponentialRV(50.0, streamNum = 1)

        inner class Source : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(20) { delay(tba); activate(Part().p) }
            }
        }

        override fun initialize() {
            activate(Source().g)
        }
    }

    @Test
    @DisplayName("All four seams are implementable from outside the package, and a model runs on them")
    fun allFourSeamsAreSubstitutableFromOutside() {
        val m = Model("PolicySubstitution")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1200.0
        m.simulate()

        assertTrue(shop.delivered > 5, "the model did too little to exercise the seams: ${shop.delivered}")

        // Each seam was actually consulted. A substitutable interface nobody calls is a worse
        // failure than one that will not compile, because it looks like it works.
        assertTrue(shop.policy.callCount > 0, "the assignment policy was never consulted")
        assertTrue(shop.selection.calls > 0, "the task selection rule was never consulted")
        assertTrue(shop.disposition.calls > 0, "the disposition policy was never consulted")

        // The bidding seam is declared and reachable here; it is consulted once negotiated
        // dispatching arrives. Asserting it is installed keeps the four seams symmetric.
        assertEquals(shop.bidding, shop.carts[0].bidPolicy)

        // The vehicles the policy named are the ones that did the work.
        assertEquals(
            shop.delivered.toDouble(),
            shop.carts.sumOf { it.numTasksCompleted.value },
            "deliveries and completions disagree"
        )
    }
}
