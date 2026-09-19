package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.fleet.policies.Disposition
import ksl.modeling.fleet.policies.DispositionPolicyIfc
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  No disposition policy can make a vehicle idle while work is waiting -- and it cannot, structurally.
 *
 *  The guarantee is not that the default disposition is sensible. It is that the branch consulting a
 *  disposition policy is **unreachable** until the dispatcher has been given the chance to assign
 *  and has declined. So the test installs the most hostile policy it can: one that always sends the
 *  vehicle to the far side of a one-way loop, which is both a long trip and directly away from where
 *  the next load will be collected. If the guarantee held only by the good sense of the shipped
 *  policies, this would take the fleet apart.
 *
 *  Two loads, one cart, arranged so the second is already waiting when the first is delivered.
 */
class WorkBeatsDispositionTest {

    /** The worst rule the API allows: always leave, always far, always the wrong way. */
    private class AlwaysWanderOff : DispositionPolicyIfc {
        var consulted = 0
            private set

        override fun disposition(vehicle: FleetVehicle): Disposition {
            consulted++
            return Disposition.MoveTo(SimpleAgvNetwork.AGV2_HOME)
        }

        override fun toString(): String = "AlwaysWanderOff"
    }

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val disposition = AlwaysWanderOff()

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply {
            homeBase = SimpleAgvNetwork.AGV1_HOME
            dispositionPolicy = disposition
        }

        /** (task name, assigned at, delivered at) for each load, in order of delivery. */
        val record = mutableListOf<Triple<String, Double, Double>>()

        /** Sampled continuously: was the cart on the staging spur *while work was outstanding*?
         *  The qualifier is the whole assertion. The cart legitimately ends the run at staging,
         *  once the board is empty, so a flag that merely recorded "was ever there" would be set by
         *  correct behaviour and would fail a correct implementation. */
        var atStagingWithWorkWaiting = false

        inner class Part(val label: String) : Entity(label) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val task = requestFleetTransport(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                )
                awaitFleetTransport(task)
                record.add(Triple(label, task.assignedAt, time))
            }
        }

        override fun initialize() {
            activate(Part("First").p)
            // Arrives while the first is still being carried, so it is on the board at the instant
            // the cart finishes -- the exact moment the disposition policy would otherwise fire.
            activate(Part("Second").p, timeUntilActivation = 20.0)
            for (t in 1..2000) schedule(::sample, t * 0.25 - 0.05)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            // The staging point is the far home spur. `hasHolder` rather than `isCovered`: a spur's
            // single zone is the last zone of its link and is therefore reserved but never occupied.
            val spur = network.link("Link6")!!.zones.first()
            if (spur.holder === cart.transporter && agv.dispatcher.taskQ.size > 0) {
                atStagingWithWorkWaiting = true
            }
        }
    }

    @Test
    @DisplayName("A vehicle finishing with work waiting is reassigned at once and never departs")
    fun workBeatsAnAdversarialDisposition() {
        val m = Model("WorkBeatsDisposition")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 600.0
        m.simulate()

        assertEquals(2, shop.record.size, "both loads should have been delivered: ${shop.record}")
        val (firstLabel, _, firstDelivered) = shop.record[0]
        val (secondLabel, secondAssigned, _) = shop.record[1]
        assertEquals("First", firstLabel)
        assertEquals("Second", secondLabel)

        // The heart of it: the second task is committed at the very instant the first is delivered.
        // Not "soon after" -- the same instant, because the vehicle declares availability before it
        // may consult a disposition, and the dispatcher's pass happens before the branch that would
        // send it away is reachable.
        assertEquals(firstDelivered, secondAssigned, 1e-9,
            "the second load was not assigned the moment the cart came free: ${shop.record}")

        // And the cart never went wandering, though its policy would have sent it every time.
        assertTrue(shop.disposition.consulted > 0,
            "the adversarial disposition was never consulted at all, so this proves nothing about " +
                    "work beating it -- it would pass with the policy uninstalled")
        assertEquals(false, shop.atStagingWithWorkWaiting,
            "the cart was at the staging point while a task was still on the board")

        // It does go there eventually, once there is genuinely nothing to do -- otherwise the
        // guarantee would be indistinguishable from ignoring the policy.
        assertEquals(SimpleAgvNetwork.AGV2_HOME, shop.cart.currentLocationName,
            "with the board empty the cart should finally have followed its disposition policy")
    }
}
