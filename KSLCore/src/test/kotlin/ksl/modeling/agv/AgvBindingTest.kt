/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.agv

import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.FleetVehicle
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.MovementWait
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.TransporterState
import ksl.modeling.guidedpath.VelocitySampling
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 *  `ksl.modeling.agv` is three files that bind a fleet vehicle to a guide-path body, and until now
 *  it had no test class of its own: it was exercised only through the guidedpath and fleet suites
 *  and the examples, which run it but assert about what is on either side of it rather than about
 *  the binding.
 *
 *  That distinction is what this file is for. A delegating adapter fails quietly -- a property
 *  wired to the wrong source, a setter that reads but does not write, a default that is applied to
 *  one side and not the other -- and an end-to-end run goes on producing plausible numbers. So the
 *  tests here assert about identity and round-tripping rather than about simulation results, which
 *  the suites on either side already cover.
 *
 *  Three of them are about behaviour the adapter *adds* rather than forwards, and those are the
 *  ones worth reading: the hold queues that must stop reporting, the tow state that resets only
 *  from one state, and the obstruction list that has to be mapped back from bodies to vehicles.
 */
class AgvBindingTest {

    /** A network carries the zone state of one system, so each fixture builds its own. */
    private fun ring(name: String): GuidedPathNetwork = GuidedPathNetwork.builder(name)
        .intersection("A", x = 0.0, y = 0.0)
        .intersection("B", x = 100.0, y = 0.0)
        .link("AB", "A", "B", length = 100.0, zoneLength = 10.0, beginDirection = 0.0)
        .link("BA", "B", "A", length = 100.0, zoneLength = 10.0, beginDirection = 180.0)
        .station("P", "A")
        .build()

    /**
     *  `substrateReporting` and `spaceHeldBy` are protected: they are the substrate's answers to
     *  the fleet machinery above it, not a modeller's controls. A subclass is how the fleet reaches
     *  them, so a subclass is how these tests reach them too.
     */
    private class TestableAgvSystem(
        parent: ModelElement,
        network: GuidedPathNetwork,
        name: String
    ) : AgvSystem(parent, network, name = name) {
        fun reportSubstrate(option: Boolean) = substrateReporting(option)
        fun heldBy(vehicle: FleetVehicle): String = spaceHeldBy(vehicle)
    }

    private inner class Shop(
        parent: ModelElement,
        networkName: String,
        physicalLength: Double? = null
    ) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = ring(networkName)

        init {
            spatialModel = network
        }

        val agv = TestableAgvSystem(this, network, "Fleet")

        val cart = AgvVehicle(
            agv, TransporterPlacement.At("A"), ConstantRV(10.0),
            name = "Cart", physicalLength = physicalLength
        )
    }

    private fun shop(networkName: String, physicalLength: Double? = null): Shop {
        val m = Model("AgvBinding")
        return Shop(m, networkName, physicalLength)
    }

    // ── The facade and the space it owns ──────────────────────────────────────

    @Test
    @DisplayName("the fleet's space is the network it was given, not a copy of it")
    fun theFleetSpaceIsTheNetworkItself() {
        val s = shop("SpaceIdentity")
        // Stated in AgvSystem's own KDoc as the reason the two are one object: a network and a
        // space that could disagree about where a station is would be two sources of truth.
        assertSame(s.network, s.agv.space)
    }

    @Test
    @DisplayName("the facade's statistics are the space layer's own, not a second copy")
    fun theFacadeRepublishesTheSpaceLayersStatistics() {
        val s = shop("StatIdentity")
        val space = s.agv.spaceSystem
        // Identity, not equality. A facade that copied a value at construction would read the
        // right number here and a stale one after the run, which no end-to-end assertion sees.
        assertSame(space.numZoneTraversals, s.agv.numZoneTraversals)
        assertSame(space.numEventsScheduled, s.agv.numEventsScheduled)
        assertSame(space.eventsPerZoneTraversal, s.agv.eventsPerZoneTraversal)
        assertSame(space.numDeadlocksDetected, s.agv.numDeadlocksDetected)
        assertSame(space.numObstructionsDetected, s.agv.numObstructionsDetected)
        assertSame(space.numTransportersMoving, s.agv.numVehiclesMoving)
        assertSame(space.numTransportersBlocked, s.agv.numVehiclesBlocked)
        assertSame(space.zoneUtilization, s.agv.zoneUtilization)
        assertSame(space.approachTime, s.agv.approachTime)
        assertSame(space.rideTime, s.agv.rideTime)
        assertSame(space.transportBlockedTime, s.agv.transportBlockedTime)
        assertSame(space.zonesTraversedPerTransport, s.agv.zonesTraversedPerTransport)
        assertSame(space.routeLengthPerTransport, s.agv.routeLengthPerTransport)
    }

    /**
     *  The movement hold queues must not report, and the facade must be able to switch them back.
     *
     *  Under the passive paradigm those queues hold *loads* being carried, so their number in queue
     *  is a line of work waiting and reporting it is right. Under an active model they hold vehicle
     *  agents, so the same row is a count of moving carts wearing the name of a queue -- the most
     *  misleading row the subsystem could produce, and the one a reader is least likely to question.
     *
     *  Note where the guarantee actually comes from. `Queue` reports by default, and it is
     *  `GuidedPathSpace`'s own initializer that silences these three; `AgvSystem`'s initializer
     *  repeats the call, and removing that repeat changes nothing observable. This test was written
     *  believing the facade was the thing being tested and it is not, so it says so rather than
     *  leaving the next reader to find out the same way. What it does pin is the property itself,
     *  wherever it is established, and the facade's own `substrateReporting`, which is not a repeat
     *  of anything.
     */
    @Test
    @DisplayName("the movement hold queues do not report, and can be switched back on")
    fun theMovementHoldQueuesAreSilentByDefault() {
        val s = shop("HoldQueues")
        val queues = MovementWait.entries.map { s.agv.spaceSystem.holdQueueFor(it) }
        assertTrue(queues.isNotEmpty(), "the space layer publishes no movement hold queues")
        assertEquals(3, queues.distinct().size, "the three movement waits should name three queues")

        for (q in queues) {
            assertFalse(q.defaultReportingOption) { "${q.name} reports by default" }
            assertFalse(q.waitTimeStatOption) { "${q.name} collects waiting time by default" }
        }

        s.agv.reportSubstrate(true)
        for (q in queues) {
            assertTrue(q.defaultReportingOption) { "${q.name} stayed silent when asked to report" }
            assertTrue(q.waitTimeStatOption) { "${q.name} did not resume collecting waiting time" }
        }

        s.agv.reportSubstrate(false)
        for (q in queues) {
            assertFalse(q.defaultReportingOption) { "${q.name} could not be silenced again" }
        }
    }

    @Test
    @DisplayName("each switch on the facade writes through to the space layer, both ways")
    fun theFacadeSwitchesWriteThroughToTheSpaceLayer() {
        val s = shop("Switches")
        val space = s.agv.spaceSystem

        // Read-and-write, not read-only. A getter wired to the space with a setter that assigned a
        // field of its own would pass any test that only read back what it had just set.
        val switches: List<Triple<String, (Boolean) -> Unit, () -> Boolean>> = listOf(
            Triple("checkInvariants", { v: Boolean -> s.agv.checkInvariants = v }, { space.checkInvariants }),
            Triple("auditAtReplicationEnd", { v: Boolean -> s.agv.auditAtReplicationEnd = v }, { space.auditAtReplicationEnd }),
            Triple("deadlockDetectionEnabled", { v: Boolean -> s.agv.deadlockDetectionEnabled = v }, { space.deadlockDetectionEnabled }),
            Triple("strictObstructionPolicy", { v: Boolean -> s.agv.strictObstructionPolicy = v }, { space.strictObstructionPolicy }),
            Triple("collectLinkStatistics", { v: Boolean -> s.agv.collectLinkStatistics = v }, { space.collectLinkStatistics }),
            Triple("collectZoneStatistics", { v: Boolean -> s.agv.collectZoneStatistics = v }, { space.collectZoneStatistics })
        )
        for ((label, set, readSpace) in switches) {
            set(true)
            assertTrue(readSpace()) { "$label did not reach the space layer when set true" }
            set(false)
            assertFalse(readSpace()) { "$label did not reach the space layer when set false" }
        }
    }

    /**
     *  `spaceHeldBy` is asked about any fleet vehicle, and a fleet may hold vehicles that are not
     *  on a guide path at all. The empty string is the honest answer for one of those -- it holds
     *  no guide-path space -- and the cast that produces it is the kind that silently becomes a
     *  crash if it is ever tightened.
     */
    @Test
    @DisplayName("space held is reported for a guided vehicle and empty for any other")
    fun spaceHeldIsEmptyForAVehicleThatIsNotOnTheGuidePath() {
        // Read inside the replication: a transporter takes the zone it stands on when the model
        // initializes, so a vehicle holds nothing at construction time and this would otherwise
        // assert against an empty string that means "not started yet" rather than "holds nothing".
        val m = Model("SpaceHeldRun")
        val s = Shop(m, "SpaceHeld")
        var heldDuringRun: String? = null
        var zonesDuringRun: String? = null
        object : ModelElement(s, "Probe") {
            override fun initialize() {
                schedule({ _: ksl.simulation.KSLEvent<Nothing> ->
                    heldDuringRun = s.agv.heldBy(s.cart)
                    zonesDuringRun = s.cart.transporter.heldZones.joinToString { it.name }
                }, 0.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 1.0
        m.simulate()

        val held = heldDuringRun
        assertTrue(held != null && held.isNotEmpty(), "a vehicle placed at A should hold the zone it stands on, got '$held'")
        assertEquals(
            zonesDuringRun, held,
            "the facade should name exactly the zones the transporter holds"
        )

        // A real free-path vehicle, not a stub: the branch this exercises is the `as?` that has
        // to cope with a fleet vehicle which is not on a guide path at all.
        val other = Model("OtherFleet")
        val plane = ksl.modeling.spatial.DistancesModel()
        plane.addDistance("Q", "Q", 0.0)
        val freeFleet = ksl.modeling.fleet.FreePathFleet(other, plane, name = "Free")
        val notGuided = ksl.modeling.fleet.FreePathVehicle(freeFleet, "Q", ConstantRV(1.0), name = "NotGuided")
        assertEquals("", s.agv.heldBy(notGuided))
    }

    // ── The vehicle and its body ──────────────────────────────────────────────

    @Test
    @DisplayName("the vehicle's body is its own transporter, named after it")
    fun theBodyIsTheVehiclesOwnTransporter() {
        val s = shop("BodyBinding")
        assertEquals("${s.cart.name}:Body", s.cart.transporter.name)
        assertSame(s.cart.transporter, s.cart.body.seizable)
    }

    /**
     *  The body's movement queue is the space layer's DRIVING queue, which is a different object
     *  from the two beside it. Wiring it to RIDING or AWAITING_PICKUP would put driving vehicles
     *  into a queue of waiting loads, and every count either queue reports would be wrong while the
     *  simulation still ran.
     */
    @Test
    @DisplayName("the body waits in the driving queue, not one of the other two")
    fun theBodyUsesTheDrivingHoldQueue() {
        val s = shop("DrivingQueue")
        val space = s.agv.spaceSystem
        assertSame(space.holdQueueFor(MovementWait.DRIVING), s.cart.body.movementQueue)
        for (other in listOf(MovementWait.RIDING, MovementWait.AWAITING_PICKUP)) {
            assertTrue(space.holdQueueFor(other) !== s.cart.body.movementQueue) {
                "the driving queue is the same object as the $other queue"
            }
        }
    }

    @Test
    @DisplayName("physical length and velocity sampling reach the transporter")
    fun theGuidePathOnlyPropertiesReachTheTransporter() {
        val plain = shop("NoLength")
        assertNull(plain.cart.physicalLength, "a vehicle sized in zones reports no physical length")

        val sized = shop("WithLength", physicalLength = 7.5)
        assertEquals(7.5, sized.cart.physicalLength)
        assertEquals(sized.cart.transporter.physicalLength, sized.cart.physicalLength)

        // Default stated in the KDoc, then a round trip through to the transporter.
        assertEquals(VelocitySampling.PER_MOVE, sized.cart.velocitySampling)
        sized.cart.velocitySampling = VelocitySampling.PER_ZONE
        assertEquals(VelocitySampling.PER_ZONE, sized.cart.transporter.velocitySampling)
    }

    @Test
    @DisplayName("a vehicle obstructing nobody reports an empty list")
    fun anUnobstructedVehicleObstructsNobody() {
        val s = shop("Obstruction")
        assertEquals(emptyList(), s.cart.vehiclesObstructed())
    }

    // ── The adapter's own behaviour ───────────────────────────────────────────

    /**
     *  `endTow` is the one method on the body that is not a forward. It clears the tow velocity and
     *  returns the transporter to idle -- but only from TOWED, because a vehicle that broke down
     *  mid-tow must not be quietly marked idle by the tow ending.
     */
    @Test
    @DisplayName("ending a tow clears the tow velocity and returns a towed vehicle to idle")
    fun endingATowResetsATowedVehicle() {
        val s = shop("EndTow")
        val body = s.cart.body
        s.cart.transporter.transporterState = TransporterState.TOWED
        body.towVelocity = 3.0
        assertEquals(3.0, s.cart.transporter.towVelocity)

        body.endTow()

        assertNull(s.cart.transporter.towVelocity, "the tow velocity survived the tow ending")
        assertEquals(TransporterState.IDLE, s.cart.transporter.transporterState)
    }

    @Test
    @DisplayName("ending a tow leaves a state other than towed alone")
    fun endingATowDoesNotDisturbAnotherState() {
        val s = shop("EndTowGuard")
        val body = s.cart.body
        s.cart.transporter.transporterState = TransporterState.MOVING_EMPTY
        body.towVelocity = 3.0

        body.endTow()

        assertNull(s.cart.transporter.towVelocity, "the tow velocity should clear whatever the state")
        assertEquals(
            TransporterState.MOVING_EMPTY, s.cart.transporter.transporterState,
            "ending a tow marked a moving vehicle idle"
        )
    }

    /**
     *  The gate is a `() -> Boolean` on the fleet side and a two-argument function on the guide
     *  path's, so the adapter has to wrap it -- and pass null straight through, because a wrapper
     *  around null would be a gate that always refuses.
     */
    @Test
    @DisplayName("a continuation gate is wrapped, and detaching passes null through")
    fun attachingAndDetachingAContinuationGate() {
        val s = shop("Gate")
        val body = s.cart.body
        var asked = 0
        body.attachContinuationGate { asked++; true }
        assertEquals(0, asked, "attaching a gate should not consult it")

        // Detaching must reach the transporter as a null, not as a wrapper that returns false.
        body.attachContinuationGate(null)
        assertEquals(0, asked, "the detached gate was still consulted")
    }
}
