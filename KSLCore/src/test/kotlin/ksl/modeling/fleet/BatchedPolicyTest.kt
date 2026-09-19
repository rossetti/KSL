package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.BatchedAssignmentPolicy
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.NearestVehiclePolicy
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
 *  The policy that consumes simulated time, and the reason `assign` had to be a suspending function.
 *
 *  Every other rule in the subsystem answers immediately and could have been an ordinary function.
 *  This one waits, and while it waits the board fills. That is not a nicety: it is the whole
 *  argument for giving the dispatcher a process of its own rather than making dispatching a rule
 *  evaluated inside the asking entity's process. Under that arrangement there is nowhere to put a
 *  batching window -- "wait and see what else arrives" would mean suspending the entity that asked,
 *  for reasons that have nothing to do with it, and it would still be waiting when the decision
 *  concerned some other load entirely.
 *
 *  Two things are asserted, and the second is the one that matters. The batch is decided **together**
 *  -- one policy call disposing of several tasks, not several calls -- and the window is visible in
 *  the answer, as waiting that the same fleet under an immediate rule does not incur.
 */
class BatchedPolicyTest {

    companion object {
        const val WINDOW = 30.0
    }

    /** Wraps a policy to record how many tasks each call disposed of. */
    private class Counting(private val inner: AssignmentPolicyIfc) : AssignmentPolicyIfc {
        val proposalsPerCall = mutableListOf<Int>()
        val boardSizeAtCall = mutableListOf<Int>()

        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            val out = with(inner) { assign(context) }
            if (out.isNotEmpty()) {
                proposalsPerCall.add(out.size)
                boardSizeAtCall.add(context.board.numWaiting + out.size)
            }
            return out
        }

        override fun toString(): String = "Counting($inner)"
    }

    private class Shop(parent: ModelElement, val policy: Counting) : ProcessModel(parent, "Shop") {

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

        val waits = mutableListOf<Double>()

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val r = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                waits.add(r.waitForAssignment)
            }
        }

        override fun initialize() {
            // Two loads a little apart, so that under batching the first must wait for the window
            // and both are then decided at once -- while under an immediate rule the first is
            // assigned on arrival.
            activate(Part().p)
            activate(Part().p, timeUntilActivation = 5.0)
        }
    }

    private fun run(policy: AssignmentPolicyIfc): Shop {
        val counting = Counting(policy)
        val m = Model("Batched")
        val shop = Shop(m, counting)
        m.numberOfReplications = 1
        m.lengthOfReplication = 800.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("A batching window holds tasks, decides them together, and shows up in the answer")
    fun batchingDelaysAndDecidesTogether() {
        val batched = run(BatchedAssignmentPolicy(WINDOW, NearestVehiclePolicy()))
        val immediate = run(NearestVehiclePolicy())

        assertEquals(2, batched.waits.size, "both loads should have been delivered")
        assertEquals(2, immediate.waits.size, "both loads should have been delivered")

        // Decided together: one call disposed of both tasks. Under the immediate rule the same two
        // loads take two calls of one, because the second is not on the board when the first is
        // decided.
        assertTrue(batched.policy.proposalsPerCall.any { it == 2 },
            "no single batching call assigned both tasks: ${batched.policy.proposalsPerCall}")
        assertTrue(immediate.policy.proposalsPerCall.all { it == 1 },
            "the immediate policy assigned more than one task in a call, so the comparison is not " +
                    "between batched and unbatched: ${immediate.policy.proposalsPerCall}")

        // The window is visible in the answer. The first load arrives at t=0 and waits the whole
        // window; under the immediate rule it waits for nothing at all.
        assertEquals(WINDOW, batched.waits[0], 1e-9,
            "the first load did not wait the batching window: ${batched.waits}")
        assertEquals(0.0, immediate.waits[0], 1e-9,
            "the first load waited under an immediate rule: ${immediate.waits}")

        // And batching costs the load that arrives first, which is the trade it makes.
        assertTrue(batched.waits[0] > immediate.waits[0],
            "batching did not delay the first load, so it cannot be doing anything: " +
                    "${batched.waits} vs ${immediate.waits}")
    }

    @Test
    @DisplayName("A zero or negative window is refused rather than silently behaving as no window")
    fun aWindowMustBePositive() {
        for (bad in listOf(0.0, -1.0)) {
            val e = runCatching { BatchedAssignmentPolicy(bad) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException,
                "a window of $bad should be refused: a zero window is the inner policy alone, and " +
                        "accepting it silently would hide a configuration mistake behind behaviour " +
                        "that looks deliberate")
        }
    }
}
