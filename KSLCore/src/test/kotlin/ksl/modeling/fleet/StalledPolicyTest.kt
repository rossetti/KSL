package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A policy that never decides is the subsystem's most dangerous failure, and the diagnostics catch it.
 *
 *  Every seam in this design hands a modeller the ability to write a rule that simply never assigns
 *  anything: a batching window longer than the run, a bidding threshold nothing meets, a scoring
 *  function that returns infinity, a condition that is never true. None of those is a bug in the
 *  subsystem and none can be prevented — the whole point of a substitutable policy is that the
 *  modeller decides.
 *
 *  What makes it dangerous is that it produces a run indistinguishable from a healthy one at a
 *  glance. Vehicles sit idle, loads accumulate, the simulation reaches its horizon and reports.
 *  Nothing raises, nothing hangs, and the statistics that *are* reported are computed over the empty
 *  set of completed work — so a fleet that did nothing shows no waiting time and no blocking, which
 *  reads like a fleet that had an easy day.
 *
 *  The diagnostics are the only thing standing between that and a modeller drawing a conclusion from
 *  it.
 */
class StalledPolicyTest {

    /** Waits for a window longer than any run, so the fleet never gets past its first pass. */
    private class NeverFillsPolicy(private val window: Double) : AssignmentPolicyIfc {
        var calls = 0
            private set

        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            calls++
            delay(window, suspensionName = "windowThatNeverFills")
            return emptyList()
        }

        override fun toString(): String = "NeverFillsPolicy(window=$window)"
    }

    private class Shop(parent: ModelElement, val policy: NeverFillsPolicy) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        val carts = listOf(SimpleAgvNetwork.AGV1_HOME, SimpleAgvNetwork.AGV2_HOME)
            .mapIndexed { i, home ->
                AgvVehicle(agv, TransporterPlacement.At(home), ConstantRV(10.0), name = "Cart${i + 1}")
                    .apply { homeBase = home }
            }

        var delivered = 0

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                delivered++
            }
        }

        override fun initialize() {
            delivered = 0
            repeat(5) { i -> activate(Part().p, timeUntilActivation = i * 20.0) }
        }
    }

    @Test
    @DisplayName("A policy whose window never fills stalls the fleet, and the diagnostics say so")
    fun aStalledPolicyIsCaughtByTheDiagnostics() {
        val m = Model("Stalled")
        val shop = Shop(m, NeverFillsPolicy(window = 10_000.0))
        m.numberOfReplications = 3
        m.lengthOfReplication = 500.0
        // Completes rather than hanging. A policy suspended in a delay is terminated with everything
        // else at the horizon, so the run ends normally -- which is exactly the problem.
        m.simulate()

        // The failure, stated: nothing happened at all.
        assertEquals(0, shop.delivered, "the stalled policy somehow delivered something")
        assertEquals(0.0, shop.agv.dispatcher.numAssignmentsMade.value)
        assertTrue(shop.policy.calls > 0, "the policy was never consulted, so this tests nothing")

        // And the reason it is dangerous: the statistics reported are computed over no work at all.
        // A reader glancing at them sees a fleet with no waiting and no blocking.
        assertEquals(0.0, shop.agv.dispatcher.taskQ.timeInQ.withinReplicationStatistic.count,
            "there should be no completed waits to report")
        assertEquals(0.0, shop.agv.timeAboard.withinReplicationStatistic.count)
        for (cart in shop.carts) {
            assertEquals(0.0, cart.numTasksCompleted.value)
            assertEquals(0.0, cart.fracTimeBlocked.withinReplicationStatistic.weightedAverage, 1e-9,
                "an idle fleet shows no blocking, which reads like an easy day rather than a stall")
        }

        // The diagnostics are what distinguishes the two, and they report every replication.
        assertEquals(5.0, shop.agv.numTasksNeverAssigned.value,
            "every posted task should be reported as never assigned")
        assertEquals(5.0, shop.agv.numEntitiesNeverResumed.value,
            "every load should be reported as still suspended")
        assertEquals(0.0, shop.agv.numAssignmentsStillOpen.value,
            "nothing was ever assigned, so no assignment can be open -- which is itself the tell: " +
                    "tasks stranded with no assignment open means the fleet never started, while " +
                    "tasks stranded with assignments open means it merely ran out of time")
        // Every replication contributed an observation, and they all say the same thing. A horizon
        // measurement recorded only in the replications where it was non-zero would give an average
        // over the bad ones, which looks like a fleet's performance and is not.
        assertEquals(3.0, shop.agv.numTasksNeverAssigned.acrossReplicationStatistic.count)
        assertEquals(5.0, shop.agv.numTasksNeverAssigned.acrossReplicationStatistic.average, 1e-9,
            "the stall recurred in every replication and the across-replication statistic should say so")
    }
}
