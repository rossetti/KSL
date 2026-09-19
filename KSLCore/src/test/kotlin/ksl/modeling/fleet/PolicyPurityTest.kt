package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.exceptions.FleetDispatchException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  A misbehaving policy is refused, and the refusal says which policy and what it did.
 *
 *  Policies are the part of this subsystem a modeller is most likely to write, and a policy is the
 *  easiest place to break an invariant by accident: it is handed lists and asked for pairings, and
 *  nothing about that shape suggests that proposing the same task twice, or a vehicle it was not
 *  offered, is different in kind from proposing anything else.
 *
 *  Both are caught, and both raise rather than being quietly dropped. A silently discarded proposal
 *  is far worse than an exception: the model runs, the fleet under-performs, and the cause is a rule
 *  the modeller wrote and believes is being followed. The messages name the policy because by the
 *  time this surfaces the modeller may have several installed.
 */
class PolicyPurityTest {

    /** Proposes a vehicle that has not declared availability. */
    private class GreedyForOthersPolicy(private val outsider: () -> AgvVehicle?) : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            val task = context.board.unassigned.firstOrNull() ?: return emptyList()
            val v = outsider() ?: return emptyList()
            return listOf(AssignmentProposal(v, task))
        }

        override fun toString(): String = "GreedyForOthersPolicy"
    }

    /** Proposes the same task to two different vehicles. */
    private class DoubleBookingPolicy : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            val task = context.board.unassigned.firstOrNull() ?: return emptyList()
            if (context.available.size < 2) return emptyList()
            return listOf(
                AssignmentProposal(context.available[0], task),
                AssignmentProposal(context.available[1], task)
            )
        }

        override fun toString(): String = "DoubleBookingPolicy"
    }

    private class Shop(parent: ModelElement, policy: AssignmentPolicyIfc) : ProcessModel(parent, "Shop") {

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

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("A policy naming a vehicle it was not offered is refused, and the message names it")
    fun proposingAnUnavailableVehicleIsRefused() {
        lateinit var shop: Shop
        val m = Model("PurityAvailability")
        // The outsider is a vehicle of a *different* fleet, so it is real, constructible, and has
        // certainly not declared availability to this dispatcher.
        val other = ProcessModel(m, "Other").let { om ->
            val net = SimpleAgvNetwork.create()
            om.spatialModel = net
            val sys = AgvSystem(om, net, name = "OtherAgv")
            AgvVehicle(sys, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0),
                name = "Stranger").apply { homeBase = SimpleAgvNetwork.AGV1_HOME }
        }
        shop = Shop(m, GreedyForOthersPolicy { other })
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0

        val e = assertFailsWith<FleetDispatchException> { m.simulate() }
        val msg = e.message!!
        assertTrue(msg.contains("GreedyForOthersPolicy"), "the message should name the policy: $msg")
        assertTrue(msg.contains("Stranger"), "the message should name the vehicle: $msg")
        assertTrue(msg.contains("available"), "the message should say what was wrong: $msg")
        assertTrue(shop.carts.isNotEmpty())
    }

    @Test
    @DisplayName("A policy proposing one task twice is refused")
    fun doubleBookingATaskIsRefused() {
        val m = Model("PurityDoubleBooking")
        Shop(m, DoubleBookingPolicy())
        m.numberOfReplications = 1
        m.lengthOfReplication = 300.0

        val e = assertFailsWith<FleetDispatchException> { m.simulate() }
        val msg = e.message!!
        assertTrue(msg.contains("DoubleBookingPolicy"), "the message should name the policy: $msg")
        assertTrue(msg.contains("ASSIGNED"), "the message should say the task already had a vehicle: $msg")
    }
}
