package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  Re-tasking a moving vehicle goes through the space layer's existing redirect, and its two rules
 *  still hold under active control.
 *
 *  The passive subsystem built this machinery for its own reasons: a transporter mid-traversal
 *  defers a redirection to the next zone boundary, because something between two places cannot stop
 *  and turn round, and a *blocked* transporter gives up its place on the zone's waiter list first,
 *  because leaving it there would enter it twice and wake it later for a journey it is no longer
 *  making.
 *
 *  This subsystem now drives that path for a new reason, and reuse of that kind is worth an explicit
 *  test rather than an assumption. The failure it guards against is not a crash: a vehicle left on a
 *  waiter list is woken for a journey it has abandoned, and what follows is a fleet that behaves
 *  oddly in ways no single assertion elsewhere would localise.
 *
 *  The redirect is asserted through its consequences -- the vehicle finishes the leg it was on and
 *  then goes somewhere else, having never reversed -- because "deferred to the boundary" is a
 *  property of when the change takes effect, not of any value that can be read afterwards.
 */
class RedirectDuringTraversalTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = AgvTestNetworks.ring()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(
            this, network,
            assignmentPolicy = ksl.modeling.fleet.policies.ReassigningPolicy(20.0),
            name = "Agv"
        )

        val cart = AgvVehicle(
            agv, TransporterPlacement.At(AgvTestNetworks.DEPOT), ConstantRV(10.0), name = "Cart"
        ).apply { homeBase = AgvTestNetworks.DEPOT }

        val delivered = linkedMapOf<String, FleetTransportResult>()

        /** Where the cart was, sampled finely and off the event grid, so the path it actually took
         *  can be checked for a reversal rather than inferred from where it ended up. */
        val path = mutableListOf<String>()
        private var lastZone = ""

        inner class Load(val label: String, val from: String) : Entity(label) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                delivered[label] = transportByFleet(agv, AgvTestNetworks.DROP, origin = from)
            }
        }

        override fun initialize() {
            activate(Load("far", AgvTestNetworks.FAR).p)
            activate(Load("near", AgvTestNetworks.NEAR).p, timeUntilActivation = 2.0)
            for (t in 1..4000) schedule(::sample, t * 0.1 - 0.05)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            val z = cart.transporter.frontZone?.name ?: return
            if (z != lastZone) {
                lastZone = z
                path.add(z)
            }
        }
    }

    @Test
    @DisplayName("A redirect takes effect at the next zone boundary; the vehicle never reverses")
    fun aMovingVehicleFinishesItsLegBeforeTurning() {
        val m = Model("Redirect")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 1500.0
        m.simulate()

        assertEquals(2, shop.delivered.size, "both loads should have been delivered")
        val far = assertNotNull(shop.delivered["far"])
        assertEquals(1, far.numReassignments,
            "the far task should have been taken back mid-journey, or this tests nothing: $far")

        // The cart was redirected while under way, and it never went backwards to do it. On a
        // one-way ring a reversal would show as a zone whose position on its link decreases, or as
        // a return to a link already left -- either of which would mean the space layer had let a
        // vehicle turn round between two places.
        val zones = shop.path
        assertTrue(zones.size > 10, "too little movement recorded to check the path: $zones")

        val linkOf = { z: String -> z.substringBefore(".") }
        val positionOf = { z: String ->
            z.substringAfter(".Zone", "").toIntOrNull() ?: 0
        }
        var previousLink = ""
        val linksEntered = mutableListOf<String>()
        for (z in zones) {
            val link = linkOf(z)
            if (link != previousLink) {
                linksEntered.add(link)
                previousLink = link
            }
        }
        // Within a link, zone positions only ever increase.
        var lastLink = ""
        var lastPos = 0
        for (z in zones) {
            val link = linkOf(z)
            val pos = positionOf(z)
            if (link == lastLink) {
                assertTrue(pos > lastPos,
                    "the cart went backwards along $link, from zone $lastPos to $pos: $zones")
            }
            lastLink = link
            lastPos = pos
        }

        // And the redirect changed where it went, rather than merely being survivable: the cart
        // reached the near pickup at `E` before the far one at `W`, which is the reverse of the
        // route it set off on.
        val near = assertNotNull(shop.delivered["near"])
        assertTrue(near.totalTime < far.totalTime,
            "the near load was not served first, so no redirect took effect: near=$near far=$far")

        // Nothing was left behind: no vehicle stuck waiting for a zone it no longer wants, and the
        // fleet ended idle with an empty board.
        assertEquals(0, shop.agv.dispatcher.taskQ.size, "a task was left on the board")
        assertEquals(0, shop.agv.spaceSystem.drivingHoldQ.size,
            "a vehicle was left in the movement queue -- the symptom of a waiter list not given up")
        assertEquals(0, shop.cart.transporter.numBusy, "the body was left allocated")
    }
}
