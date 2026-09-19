package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.PullFromBoardPolicy
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The same inputs give the same answer, and declaration order is not a hidden input to the
 *  machinery.
 *
 *  An active-resource model has more places for an arbitrary choice to hide than a passive one:
 *  which of several available vehicles is offered first, which of several dormant agents is
 *  resumed first, which of several equally-good carts wins a tie. Any of those resolved by
 *  iteration order over a hash-based collection would give a model reproducible on one machine and
 *  not another -- the worst kind of irreproducibility, because it survives every test you run.
 *
 *  The second test needs care to be about the right thing. Declaration order is a genuine **input**
 *  to [ksl.modeling.fleet.policies.PullFromBoardPolicy], which by definition gives the first
 *  unassigned task to the first available vehicle; reversing the fleet under that policy sends a
 *  different cart, and the answer legitimately changes. So the order-independence of the machinery
 *  is shown with a policy that does not consult list order except to break exact ties. If that
 *  distinction were not drawn, the test would either fail against correct code or be weakened until
 *  it asserted nothing.
 */
class AgvDeterminismTest {

    /**
     *  Nearest vehicle by network distance, ties broken by name rather than by list position.
     *
     *  Order-independent by construction, which is what makes it the right instrument here.
     */
    private class NearestByNamePolicy : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            if (context.available.isEmpty()) return emptyList()
            val free = context.available.sortedBy { it.name }.toMutableList()
            val proposals = mutableListOf<AssignmentProposal>()
            for (task in context.board.unassigned) {
                if (free.isEmpty()) break
                val best = free.minWithOrNull(
                    compareBy({ context.distanceTo(it, task.pickupLocation) }, { it.name })
                ) ?: break
                free.remove(best)
                proposals.add(AssignmentProposal(best, task))
            }
            return proposals
        }

        override fun toString(): String = "NearestByNamePolicy"
    }

    private class Shop(
        parent: ModelElement,
        reversed: Boolean,
        policy: AssignmentPolicyIfc
    ) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        // Name and random stream follow the VEHICLE, not its position in the list, so that
        // reversing changes declaration order and nothing else. Binding a stream to the loop index
        // would make the reversed fleet a different model rather than the same one declared
        // backwards, and the test would be checking nothing.
        private val spec = listOf(
            Triple("CartNorth", SimpleAgvNetwork.AGV1_HOME, 3),
            Triple("CartSouth", SimpleAgvNetwork.AGV2_HOME, 4)
        ).let { if (reversed) it.reversed() else it }

        val carts = spec.map { (nm, home, stream) ->
            AgvVehicle(
                agv, TransporterPlacement.At(home), ExponentialRV(10.0, streamNum = stream), name = nm
            ).apply { homeBase = home }
        }

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        private val timeBetween = ExponentialRV(60.0, streamNum = 1)

        inner class Generator : Entity() {
            val g = process(isDefaultProcess = true) {
                repeat(30) {
                    delay(timeBetween)
                    activate(Part().p)
                }
            }
        }

        override fun initialize() {
            activate(Generator().g)
        }
    }

    private fun run(reversed: Boolean, policy: () -> AssignmentPolicyIfc): Map<String, Double> {
        val m = Model("Determinism")
        val shop = Shop(m, reversed, policy())
        m.numberOfReplications = 3
        m.lengthOfReplication = 1500.0
        m.simulate()
        return mapOf(
            "tasksCompleted" to shop.agv.dispatcher.numTasksCompleted.acrossReplicationStatistic.average,
            "waitForAssignment" to shop.agv.dispatcher.waitForAssignment.acrossReplicationStatistic.average,
            "timeAboard" to shop.agv.timeAboard.acrossReplicationStatistic.average,
            "timeInQ" to shop.agv.dispatcher.taskQ.timeInQ.acrossReplicationStatistic.average,
            "numInQ" to shop.agv.dispatcher.taskQ.numInQ.acrossReplicationStatistic.average
        )
    }

    @Test
    @DisplayName("Identical inputs give identical output, to the digit")
    fun sameSeedsSameAnswer() {
        val first = run(false) { PullFromBoardPolicy() }
        val second = run(false) { PullFromBoardPolicy() }
        assertEquals(first, second, "two runs of the same model disagreed")
        // Enough transports that the comparison is over real work rather than an idle model. The
        // fleet is busy -- two carts sharing a one-way loop block each other, so throughput is well
        // below what their travel times alone would suggest -- which is fine here: a determinism
        // check wants the model exercising its contended paths, not avoiding them.
        assertTrue(first["tasksCompleted"]!! > 8.0,
            "the model did too little work to be a meaningful check: $first")
    }

    @Test
    @DisplayName("Declaration order is not an input to the machinery, only to a policy that asks for it")
    fun fleetOrderDoesNotChangeTheAnswer() {
        val forward = run(false) { NearestByNamePolicy() }
        val backward = run(true) { NearestByNamePolicy() }
        for ((k, v) in forward) {
            assertEquals(v, backward[k]!!, 1e-9,
                "reversing the fleet's declaration order changed '$k' under an order-independent " +
                        "policy: $forward vs $backward")
        }
        assertTrue(forward["tasksCompleted"]!! > 8.0, "too little work to be meaningful: $forward")
    }

    @Test
    @DisplayName("Under PullFromBoardPolicy, declaration order IS an input -- deliberately")
    fun pullFromBoardIsOrderDependentByDesign() {
        val forward = run(false) { PullFromBoardPolicy() }
        val backward = run(true) { PullFromBoardPolicy() }
        // Recorded rather than merely tolerated. "The first available vehicle takes the first
        // unassigned task" is a rule about a list, so which cart is first in that list is part of
        // the rule. A reader who expects order-independence everywhere should meet this here rather
        // than in a model whose numbers moved when they renamed a vehicle.
        assertTrue(
            forward != backward,
            "PullFromBoardPolicy gave the same answer with the fleet reversed. Either the two carts " +
                    "became interchangeable, or the policy stopped consulting list order -- in which " +
                    "case it is no longer the degenerate rule the equivalence benchmark relies on."
        )
    }
}
