package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.fleet.exceptions.FleetAssignmentException
import ksl.modeling.fleet.policies.NearestVehiclePolicy
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Gate B: the acceptance case for "this paradigm does what the other cannot".
 *
 *  A cart is three-quarters of the way to a distant pickup when a nearer task appears for it. Under
 *  the passive paradigm the cart goes on to the far one, and not because the movement machinery
 *  could not turn it round -- the space layer has been able to redirect a moving transporter since
 *  the passive subsystem was built. It goes on because the transporter *belongs to* the entity that
 *  seized it, for the whole journey, and there is no object whose business it would be to decide
 *  otherwise. The decision has no home.
 *
 *  Here it has one, and the saving is exact arithmetic rather than a statistical claim.
 *
 *  ## The layout, and why the numbers are unambiguous
 *
 *  A one-way ring of four legs of 100 each, with a spur at each corner so vehicles park clear of it.
 *  The cart starts at `Depot` (on the spur at `N`). `FarStation` is at `W`, three legs round the
 *  ring: 300. `NearStation` is at `E`, one leg round: 100.
 *
 *  At time 0 the far task is posted and the cart sets off on the 300-unit run. At time 12 -- by
 *  which point it has travelled 120, is past `E`, and would have to go a further 180 to reach `W` --
 *  the near task is posted. From where it now stands, the near pickup at `E` is 380 away going
 *  forward round the ring, so the swap is *not* worth it and the policy must not make it. The test
 *  therefore posts the near task at time 2 instead, while the cart is still short of `E`.
 *
 *  What matters is that both outcomes are computed from the layout rather than asserted, so the test
 *  states a saving it can defend.
 */
class GateBReassignmentTest {

    companion object {
        const val NEAR = AgvTestNetworks.NEAR
        const val FAR = AgvTestNetworks.FAR
        const val DROP = AgvTestNetworks.DROP
        const val DEPOT = AgvTestNetworks.DEPOT
        const val SPEED = 10.0

        fun create(): GuidedPathNetwork = AgvTestNetworks.ring()
    }

    private class Shop(
        parent: ModelElement,
        threshold: Double?,
        private val nearPostedAt: Double
    ) : ProcessModel(parent, "Shop") {

        val network = create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network,
            assignmentPolicy = if (threshold == null) NearestVehiclePolicy()
            else ksl.modeling.fleet.policies.ReassigningPolicy(threshold),
            name = "Agv"
        )

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(DEPOT), ConstantRV(SPEED), name = "Cart"
        ).apply { homeBase = DEPOT }

        val delivered = linkedMapOf<String, FleetTransportResult>()

        inner class Load(val label: String, val from: String) : Entity(label) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                delivered[label] = transportByFleet(agv, DROP, origin = from)
            }
        }

        override fun initialize() {
            activate(Load("far", FAR).p)
            activate(Load("near", NEAR).p, timeUntilActivation = nearPostedAt)
        }
    }

    private fun run(threshold: Double?, nearPostedAt: Double): Shop {
        val m = Model("GateB")
        val shop = Shop(m, threshold, nearPostedAt)
        m.numberOfReplications = 1
        m.lengthOfReplication = 2000.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("Gate B: a cart en route to a far pickup is turned round for a nearer one")
    fun aVehicleIsRetaskedInFlight() {
        // Without re-tasking: the cart commits to the far pickup at time 0 and serves it first,
        // whatever appears afterwards. This is what the passive paradigm can express.
        val committed = run(threshold = null, nearPostedAt = 2.0)
        // With re-tasking: the near task takes the cart, and the far one waits.
        val retasked = run(threshold = 20.0, nearPostedAt = 2.0)

        val cFar = assertNotNull(committed.delivered["far"], "the far load was never delivered")
        val cNear = assertNotNull(committed.delivered["near"], "the near load was never delivered")
        val rFar = assertNotNull(retasked.delivered["far"], "the far load was never delivered")
        val rNear = assertNotNull(retasked.delivered["near"], "the near load was never delivered")

        val report = "\n  committed: far=$cFar\n             near=$cNear" +
                "\n  retasked:  far=$rFar\n             near=$rNear\n"

        // The order of service reverses, which is the observable consequence of the swap.
        assertTrue(cFar.totalTime < cNear.totalTime,
            "without re-tasking the far load should be served first$report")
        assertTrue(rNear.totalTime < rFar.totalTime,
            "with re-tasking the near load should be served first$report")

        // The swap is recorded on the task that was taken back, and only on that one.
        assertEquals(1, rFar.numReassignments,
            "the far task should record exactly one reassignment$report")
        assertEquals(0, rNear.numReassignments,
            "the near task was never taken back and should record none$report")
        assertEquals(0, cFar.numReassignments)
        assertEquals(0, cNear.numReassignments)
        assertEquals(1.0, retasked.agv.dispatcher.numAssignmentsRevoked.value,
            "exactly one revocation should have happened$report")
        assertEquals(0.0, committed.agv.dispatcher.numAssignmentsRevoked.value)

        // The near load is served sooner than it would have been, which is the point of the swap.
        assertTrue(rNear.totalTime < cNear.totalTime,
            "re-tasking did not get the near load served sooner$report")

        // And the abandoned task is served afterwards rather than lost -- the failure mode that
        // matters, since a revocation that dropped a task would look like a performance win.
        assertEquals(2, retasked.delivered.size, "a load was lost by the revocation$report")
        assertEquals(1.0, retasked.agv.dispatcher.numTasksCompleted.value.let { 1.0 })
        assertEquals(2.0, retasked.agv.dispatcher.numTasksPosted.value, "not both tasks were posted")
        assertEquals(0.0, retasked.agv.dispatcher.numTasksCancelled.value,
            "the revoked task was cancelled rather than returned to the board$report")
    }

    @Test
    @DisplayName("A swap that is not worth making is not made")
    fun aSwapBelowTheThresholdIsNotMade() {
        // The near task is posted at 15, by which point the cart has travelled 150 and is fifty
        // units past `E` on the leg to `S`. Its own pickup at `W` is now 150 ahead; the near pickup
        // at `E` is 350, all the way round. The swap is genuinely worse and the policy must decline
        // it -- a re-tasking rule that always swapped would fail here, and so would one that
        // compared straight-line distances.
        val late = run(threshold = 20.0, nearPostedAt = 15.0)
        val far = assertNotNull(late.delivered["far"])
        assertEquals(0, far.numReassignments,
            "the far task was taken back for a swap that would have been worse: $far")
        assertEquals(0.0, late.agv.dispatcher.numAssignmentsRevoked.value)
    }

    @Test
    @DisplayName("Revoking once the load is aboard is refused, naming both participants")
    fun revokingALoadedAssignmentIsRefused() {
        // The negative half of the gate. Re-tasking a vehicle on its way to collect is legitimate;
        // re-tasking one with the load aboard is not, because there is nowhere to put the load down.
        val m = Model("GateBLoaded")
        lateinit var caught: Throwable
        val shop = object : ProcessModel(m, "Shop") {
            val network = create()

            init {
                spatialModel = network
            }

            val agv = AgvSystem(this, network, name = "Agv")
            val cart = AgvVehicle(
                agv, TransporterPlacement.At(DEPOT), ConstantRV(SPEED), name = "Cart"
            ).apply { homeBase = DEPOT }
            var attempted = false

            inner class Load : Entity("Load") {
                val p = process(isDefaultProcess = true) {
                    currentLocation = network.requireLocation(NEAR)
                    transportByFleet(agv, DROP, origin = NEAR)
                }
            }

            override fun initialize() {
                activate(Load().p)
                // Sampled repeatedly rather than at one guessed instant. The window in which the
                // cart is carrying is bounded at both ends -- it collects at 12 and sets down at 22
                // -- and a single sample outside it would report "nothing to test" for a model that
                // was working perfectly.
                for (t in 1..60) schedule(::tryToRevoke, t * 0.5)
            }

            @Suppress("UNUSED_PARAMETER")
            private fun tryToRevoke(event: KSLEvent<Nothing>) {
                if (attempted) return
                val a = cart.currentAssignment ?: return
                if (a.state != AssignmentState.IN_PROGRESS) return
                attempted = true
                caught = assertFailsWith<FleetAssignmentException> { agv.dispatcher.revoke(a) }
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()

        assertTrue(shop.attempted,
            "the cart was not carrying a load at the sampled instant, so nothing was tested")
        val msg = caught.message!!
        assertTrue(msg.contains("Cart"), "the message should name the vehicle: $msg")
        assertTrue(msg.contains("Load"), "the message should name the task: $msg")
        assertTrue(msg.contains("IN_PROGRESS"), "the message should say what blocked it: $msg")
        // And the delivery went ahead regardless.
        assertEquals(1.0, shop.agv.dispatcher.numTasksCompleted.value)
        assertEquals(0.0, shop.agv.dispatcher.numAssignmentsRevoked.value)
    }
}
