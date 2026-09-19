package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

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
import kotlin.test.assertTrue

/**
 *  A hospital-shaped layout: two floors joined by a dedicated elevator.
 *
 *  This is here to demonstrate that multi-floor networks need **no code at all**, which is the sort
 *  of claim that is easy to make and easy to be wrong about. Routing uses declared link lengths and
 *  never coordinates, so a dedicated elevator is expressible as what it physically is: a one-way
 *  link of a single zone, which by the zone rules already admits one vehicle at a time and makes
 *  everyone else wait. No elevator class, no floor concept, no special case in the control loop.
 *
 *  The first floor is placed at a real height rather than offset in `y`, which is what an
 *  intersection's `z` is for and what this layout had to work around before it existed: `F3` now sits
 *  directly above `G1`, where it is. That is layout only -- `IntersectionHeightTest` holds a circuit
 *  built flat and built on two floors to bit-identical arrival times -- so none of the assertions
 *  below moved when it changed, which is itself the point.
 */
class MultiFloorTest {

    companion object {
        const val WARD_A = "WardA"
        const val WARD_B = "WardB"
        const val PHARMACY = "Pharmacy"
        const val DEPOT = "L1Depot"
        const val PARK_ONE = "ParkOne"
        const val PARK_TWO = "ParkTwo"

        /**
         *  Ground floor: depot -- ward A -- lift bottom. First floor: lift top -- pharmacy -- ward B.
         *  The lift is `Lift`, a single zone, one-way upward, with a separate one-way return.
         */
        fun create(): GuidedPathNetwork = GuidedPathNetwork.builder("Hospital")
            .intersection("G1", x = 0.0, y = 0.0)
            .intersection("G2", x = 40.0, y = 0.0)
            .intersection("G3", x = 80.0, y = 0.0)      // foot of the lift
            // The first floor, directly above the ground floor rather than beside it.
            .intersection("F1", x = 80.0, y = 0.0, z = 12.0)   // head of the lift
            .intersection("F2", x = 40.0, y = 0.0, z = 12.0)
            .intersection("F3", x = 0.0, y = 0.0, z = 12.0)
            .link("GroundOut", "G1", "G2", length = 40.0, zoneLength = 10.0, beginDirection = 0.0)
            .link("GroundIn", "G2", "G3", length = 40.0, zoneLength = 10.0, beginDirection = 0.0)
            // The elevator: one zone, so exactly one vehicle may be inside it at a time.
            .link("LiftUp", "G3", "F1", length = 12.0, zoneLength = 12.0, beginDirection = 90.0)
            .link("FirstOut", "F1", "F2", length = 40.0, zoneLength = 10.0, beginDirection = 180.0)
            .link("FirstIn", "F2", "F3", length = 40.0, zoneLength = 10.0, beginDirection = 180.0)
            .link("LiftDown", "F3", "G1", length = 12.0, zoneLength = 12.0, beginDirection = 270.0)
            // A parking spur per porter. Without one, two porters "at the depot" would be two
            // vehicles in one zone, which a guide path does not allow -- and worse, a porter left
            // standing on a through-route denies that space to the other for the rest of the run.
            .intersection("P1", x = -12.0, y = -12.0)
            .intersection("P2", x = -24.0, y = -12.0)
            .link("Park1", "G1", "P1", length = 12.0, zoneLength = 12.0,
                type = ksl.modeling.guidedpath.LinkType.SPUR, beginDirection = 225.0)
            .link("Park2", "G1", "P2", length = 12.0, zoneLength = 12.0,
                type = ksl.modeling.guidedpath.LinkType.SPUR, beginDirection = 225.0)
            .station(PARK_ONE, "P1")
            .station(PARK_TWO, "P2")
            .station(DEPOT, "G1")
            .station(WARD_A, "G2")
            .station(PHARMACY, "F2")
            .station(WARD_B, "F3")
            .build()
    }

    private class Hospital(parent: ModelElement) : ProcessModel(parent, "Hospital") {

        val network = create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        // Two porters, each on its own parking spur on the ground floor, so both must use the lift
        // to serve the first floor and will contend for it.
        val porters = listOf(PARK_ONE, PARK_TWO).mapIndexed { i, spur ->
            AgvVehicle(agv, TransporterPlacement.At(spur), ConstantRV(10.0), name = "Porter${i + 1}")
                .apply { homeBase = spur }
        }

        val deliveries = mutableListOf<FleetTransportResult>()

        /** How many vehicles occupied the lift zone at each sample. */
        val liftOccupancy = mutableListOf<Int>()

        inner class Sample(private val from: String, private val to: String) : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                deliveries.add(transportByFleet(agv, to, origin = from))
            }
        }

        override fun initialize() {
            // Ward A (ground) to the pharmacy (first), and back down again: both cross floors.
            activate(Sample(WARD_A, PHARMACY).p)
            activate(Sample(WARD_B, DEPOT).p, timeUntilActivation = 1.0)
            // Finely, because a porter is inside the lift for only 1.2 time units at a time
            // (12 feet at 10 feet per unit), and **off the grid**. With a constant velocity and
            // equal zone lengths every zone transition lands on a multiple of 0.1, so an observer
            // sampling on those same instants competes with the transition events for ordering and
            // sees only whichever side of them the priority happens to put it on. That is not a
            // subtlety of this subsystem -- it is what makes a deterministic model easy to observe
            // wrongly -- and it reported an unused lift in a model that was plainly using one.
            for (t in 1..4000) schedule(::sampleLift, t * 0.1 - 0.05)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sampleLift(event: KSLEvent<Nothing>) {
            // `hasHolder`, not `isCovered`, and the difference is the whole point of this sample.
            //
            // A transporter reserves the zone ahead before entering it -- that reservation is what
            // stops two vehicles starting into the same free zone -- and only marks it OCCUPIED
            // once its body comes to rest covering it. The last zone of a link is therefore never
            // OCCUPIED: arriving at its far end means arriving at the junction beyond, so it goes
            // straight from CLAIMED back to FREE. On a single-zone lift that is the only zone there
            // is, so `isCovered` reports an empty lift throughout a run that plainly uses one,
            // while `hasHolder` reports the truth. Exclusion is on the reservation, and so is this.
            val lift = network.link("LiftUp")!!.zones.first()
            liftOccupancy.add(if (lift.hasHolder) 1 else 0)
            lift.holder?.let { liftHolders.add(it.name) }
        }

        /** Which bodies were ever seen holding the lift zone. */
        val liftHolders = sortedSetOf<String>()
    }

    @Test
    @DisplayName("Routing crosses floors through a one-way single-zone lift, one vehicle at a time")
    fun aLiftIsJustAOneWaySingleZoneLink() {
        val m = Model("MultiFloor")
        val h = Hospital(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        m.simulate()

        // Both cross-floor journeys completed, and both actually travelled.
        assertEquals(2, h.deliveries.size, "a cross-floor delivery did not complete: ${h.deliveries}")
        for (d in h.deliveries) {
            assertTrue(d.routeLength > 0.0, "a cross-floor journey covered no ground: $d")
            assertTrue(d.timeAboard > 0.0, "a cross-floor journey took no time: $d")
        }

        // The lift did its job: it was used, and never by two vehicles at once. That exclusion is
        // the zone rule doing the work -- there is no elevator object anywhere in this model.
        assertTrue(h.liftOccupancy.any { it == 1 }, "the lift was never used")
        // A zone has one holder or none, so exclusion is not something this subsystem enforces --
        // it is what a zone is. What the test confirms is that a lift modelled as a single zone
        // therefore behaves as an elevator without anything being written to make it one, and that
        // both porters used it rather than one monopolising it.
        assertEquals(
            setOf("Porter1:Body", "Porter2:Body"), h.liftHolders.toSet(),
            "both porters should have used the lift: ${h.liftHolders}"
        )

        // Routing is by declared length, not by coordinates: the network knows the floors connect.
        val wardA = h.network.requireLocation(WARD_A)
        val pharmacy = h.network.requireLocation(PHARMACY)
        assertTrue(h.network.isReachable(wardA, pharmacy), "the first floor is unreachable")
        assertTrue(h.network.distance(wardA, pharmacy) > 0.0)
    }
}
