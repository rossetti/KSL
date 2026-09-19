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
package ksl.modeling.guidedpath

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.exceptions.GuidedPathDeadlockException
import ksl.modeling.guidedpath.exceptions.GuidedPathObstructionException
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
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
 *  Benchmark §16.3(d), second half, and the subsystem's clearest improvement over the reference
 *  tool: a run that stops moving because an idle vehicle is standing in the way must say so, and
 *  must **not** be called a deadlock.
 *
 *  This is the harder half and the more valuable one. A circular wait is at least detectable in
 *  principle, and the source text records the reference tool detecting some cases of it. The idle
 *  vehicle is the case that tool cannot detect at all, and it is also the one a modeller is far
 *  more likely to create: nobody sets out to build a cycle, but everybody leaves a vehicle parked
 *  where it finished its last job. The symptom is a replication that completes, reports no error,
 *  and quietly contains a fleet that stopped working halfway through.
 *
 *  The classification has to be exact in both directions. Reporting an obstruction as a deadlock
 *  would end runs that are merely inefficient and are often perfectly valid -- an obstruction can
 *  clear itself the moment something dispatches the idle vehicle. Reporting a deadlock as an
 *  obstruction would let a run continue that cannot. So these tests assert not only that the right
 *  report is produced but that the wrong one is not.
 *
 *  Domain rules exercised: `R14`, goal `G5`, open issue `OI-5` as closed in v0.2.
 */
class IdleObstructionTest {

    /**
     *  A straight one-way path with a junction in the middle. One transporter is parked on that
     *  junction and given nothing to do; another is sent through it. This is the source text's idle
     *  vehicle configuration reduced to its smallest form.
     */
    private class BlockedPath(parent: ModelElement) : ModelElement(parent, "BlockedPath") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Straight")
            .link("First", "A", "B", length = 36.0, zoneLength = 12.0)
            .link("Second", "B", "C", length = 36.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
    }

    private fun straightModel(
        strict: Boolean = false,
        detection: Boolean = true
    ): Triple<Model, BlockedPath, GuidedTransporter> {
        val m = Model("IdleObstruction")
        val path = BlockedPath(m)
        // Parked on the junction and never told to go anywhere: nothing in the model will move it.
        GuidedTransporter(
            path.system, TransporterPlacement.At("B"), ConstantRV(10.0), 1, EndOfZoneControl(), "Parked"
        )
        val traveller = GuidedTransporter(
            path.system, TransporterPlacement.At("A"), ConstantRV(10.0), 1, EndOfZoneControl(), "Traveller"
        )
        object : ModelElement(path, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> traveller.sendTo("C") }, 0.0)
            }
        }
        path.system.strictObstructionPolicy = strict
        path.system.deadlockDetectionEnabled = detection
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        return Triple(m, path, traveller)
    }

    @Test
    @DisplayName("An idle transporter in the way is reported and counted, not raised")
    fun anObstructionIsCountedRatherThanRaised() {
        val (m, path, traveller) = straightModel()
        m.simulate()
        assertEquals(
            1.0, path.system.numObstructionsDetected.value, 0.0,
            "the condition must appear in the standard report, where an analyst will see it, " +
                    "rather than only in a log nobody reads"
        )
        assertEquals(
            TransporterState.BLOCKED, traveller.transporterState,
            "and the traveller must indeed still be stuck at the end of the run"
        )
    }

    @Test
    @DisplayName("An obstruction is never reported as a deadlock")
    fun anObstructionIsNotADeadlock() {
        // The whole point of the classification. There is no cycle here: the parked transporter is
        // not waiting for anything, so there is no edge back from it and nothing to close a loop.
        val (m, _, _) = straightModel()
        m.simulate() // must not throw
    }

    @Test
    @DisplayName("Under the strict policy the same obstruction ends the replication")
    fun theStrictPolicyRaises() {
        val (m, _, _) = straightModel(strict = true)
        val thrown = assertFailsWith<GuidedPathObstructionException>(
            "a study that treats an obstruction as a design failure must be able to say so"
        ) { m.simulate() }
        val obstruction = thrown.obstruction
        assertTrue(obstruction.blockedTransporterName.endsWith("Traveller"), obstruction.toString())
        assertTrue(obstruction.idleTransporterName.endsWith("Parked"), obstruction.toString())
        assertEquals("B", obstruction.awaitedZoneName)
    }

    @Test
    @DisplayName("The report names who is stuck, who is in the way, and where")
    fun theReportIsSelfContained() {
        val (m, _, _) = straightModel(strict = true)
        val thrown = assertFailsWith<GuidedPathObstructionException> { m.simulate() }
        val message = assertNotNull(thrown.message)
        assertTrue(message.contains("Traveller"), message)
        assertTrue(message.contains("Parked"), message)
        assertTrue(message.contains("(B)"), message)
        assertTrue(
            message.contains("not a circular wait"),
            "the message must say what this is not, because the two conditions are easy to " +
                    "confuse and call for different fixes: $message"
        )
    }

    @Test
    @DisplayName("With detection off nothing is reported and nothing is counted")
    fun detectionOffReportsNothing() {
        val (m, path, traveller) = straightModel(detection = false)
        m.simulate()
        assertEquals(0.0, path.system.numObstructionsDetected.value, 0.0)
        assertEquals(
            TransporterState.BLOCKED, traveller.transporterState,
            "the run is just as stuck; the only difference is that nothing says so"
        )
    }

    @Test
    @DisplayName("A transporter held up by one that is moving is not an obstruction")
    fun aMovingObstructorIsNotReported() {
        // The discriminator that keeps the count meaningful. If every block behind a busy
        // transporter were counted, the counter would grow with traffic and say nothing about
        // whether the fleet had stopped.
        val m = Model("MovingAhead")
        val path = BlockedPath(m)
        val leader = GuidedTransporter(
            path.system, TransporterPlacement.OnZone("First.Zone2"), ConstantRV(10.0), 1, EndOfZoneControl(), "Leader"
        )
        val follower = GuidedTransporter(
            path.system, TransporterPlacement.OnZone("First.Zone1"), ConstantRV(10.0), 1, EndOfZoneControl(), "Follower"
        )
        object : ModelElement(path, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> leader.sendTo("C") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> follower.sendTo("B") }, 0.0)
            }
        }
        path.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        assertTrue(follower.numTimesBlocked.value > 0.0, "the follower must have blocked at least once")
        assertEquals(0.0, path.system.numObstructionsDetected.value, 0.0)
    }

    // ---- the same thing in the shape a modeller will actually meet it -------------------------

    /**
     *  The Phase 5 shop with its carts left to park wherever they finish. The first delivery ends
     *  at the exit station, which is the only way off the exit spur, so every later delivery stops
     *  at the spur mouth. The run completes and reports nothing wrong.
     */
    private class ParkingShop(parent: ModelElement, val idleRuleParks: Boolean) :
        ProcessModel(parent, "ParkingShop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")
        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }
        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2), ClosestByNetworkDistanceRule(),
            if (idleRuleParks) ParkInPlaceRule() else ReturnToHomeBaseRule(), "Carts"
        )

        var completed = 0

        inner class Part : Entity() {
            val make = process("part") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                guidedTransport(
                    carts,
                    destination = SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION
                )
                completed++
            }
        }

        override fun initialize() {
            completed = 0
            repeat(4) { activate(Part().make) }
        }
    }

    private fun runShop(idleRuleParks: Boolean): ParkingShop {
        val m = Model("ParkingShopRun")
        val shop = ParkingShop(m, idleRuleParks)
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("The realistic case: a parked cart on the exit spur is reported as an obstruction")
    fun theParkedCartIsDiagnosed() {
        val parking = runShop(idleRuleParks = true)
        assertEquals(3, parking.completed, "the fourth delivery cannot get past the parked cart")
        assertTrue(
            parking.system.numObstructionsDetected.value > 0.0,
            "this is the single most likely way for a working-looking guide path model to be " +
                    "quietly wrong, and it must not pass in silence"
        )
    }

    @Test
    @DisplayName("Sending carts home instead leaves nothing to report")
    fun sendingCartsHomeClearsIt() {
        val goingHome = runShop(idleRuleParks = false)
        assertEquals(4, goingHome.completed)
        assertEquals(
            0.0, goingHome.system.numObstructionsDetected.value, 0.0,
            "the source text's remedy must actually remove the condition, not merely mask it"
        )
    }
}
