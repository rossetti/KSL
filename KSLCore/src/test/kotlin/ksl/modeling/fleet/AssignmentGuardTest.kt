package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.exceptions.FleetAssignmentException
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  An assignment is revocable until the load is aboard, and not one instant longer.
 *
 *  Re-tasking a vehicle on its way to a pickup is legitimate and is one of the things this paradigm
 *  can express that the passive one cannot. Re-tasking it with a load aboard is not: there is
 *  nowhere to put the load down. The boundary between the two is the moment possession is taken,
 *  and this test samples the assignment on both sides of it in one run.
 */
class AssignmentGuardTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        /** Snapshots taken AT the sample instant. An `Assignment` is mutable and outlives the
         *  moment, so holding the object and reading its state after the run reports the state it
         *  ended in, not the state it was in when it mattered. */
        class Snapshot(
            val vehicleName: String,
            val taskName: String,
            val state: AssignmentState,
            val revocable: Boolean,
            val refusal: String?
        )

        var whileFetching: Snapshot? = null
        var whileCarrying: Snapshot? = null

        /** Kept so the completed-state guard can be exercised after the run. */
        var finalAssignment: Assignment? = null

        private fun snapshot(): Snapshot? {
            val a = cart.currentAssignment ?: return null
            val refusal = try {
                a.requireRevocable(); null
            } catch (e: ksl.modeling.fleet.exceptions.FleetAssignmentException) {
                e.message
            }
            return Snapshot(a.vehicle.name, a.task.name, a.state, a.isRevocable, refusal)
        }

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            activate(Part().p)
            // The cart reaches the pickup at about t = 19.8 and delivers at about t = 40.2, so
            // these two samples straddle the moment possession is taken. Asserted, not assumed.
            schedule(::sampleFetching, 10.0)
            schedule(::sampleCarrying, 30.0)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sampleFetching(event: KSLEvent<Nothing>) {
            whileFetching = snapshot()
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sampleCarrying(event: KSLEvent<Nothing>) {
            whileCarrying = snapshot()
            finalAssignment = cart.currentAssignment
        }
    }

    @Test
    @DisplayName("An assignment is revocable while fetching and not once the load is aboard")
    fun revocabilityEndsAtPossession() {
        val m = Model("AssignmentGuard")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        val fetching = assertNotNull(shop.whileFetching, "the cart held no assignment while fetching")
        val carrying = assertNotNull(shop.whileCarrying, "the cart held no assignment while carrying")

        // On its way to collect: revocable, and requireRevocable let it through.
        assertEquals(AssignmentState.ASSIGNED, fetching.state,
            "at t=10 the cart should still be fetching; the layout or velocity changed")
        assertTrue(fetching.revocable, "an assignment should be revocable before pickup")
        assertNull(fetching.refusal, "requireRevocable refused a revocable assignment: ${fetching.refusal}")

        // With the load aboard: not revocable, and requireRevocable refused.
        assertEquals(AssignmentState.IN_PROGRESS, carrying.state,
            "at t=30 the cart should be carrying; the layout or velocity changed")
        assertFalse(carrying.revocable, "an assignment must not be revocable once the load is aboard")

        val refusal = assertNotNull(carrying.refusal, "requireRevocable allowed a loaded assignment")
        // The message names both participants: "cannot revoke" without saying which assignment
        // sends a modeller looking through a whole fleet for it.
        assertTrue(refusal.contains(carrying.vehicleName), "the message should name the vehicle: $refusal")
        assertTrue(refusal.contains(carrying.taskName), "the message should name the task: $refusal")
        assertTrue(refusal.contains("IN_PROGRESS"), "the message should say what blocked it: $refusal")

        // The same guard applies to a completed assignment, not only a loaded one: the field a
        // policy would test is `isRevocable`, and it is false for every state but ASSIGNED.
        assertFailsWith<FleetAssignmentException> {
            assertNotNull(shop.cart.currentAssignment ?: shop.finalAssignment).requireRevocable()
        }
    }
}
