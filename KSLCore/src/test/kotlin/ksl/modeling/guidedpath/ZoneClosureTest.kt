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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  A whole region of guide path is closed, and the two rules that make that safe.
 *
 *  Extent is chosen per occurrence, and closing a *set* has a hazard closing one zone does not.
 *  Two rules exist for it:
 *
 *  - **the set is taken together or not at all**, so the holder holds nothing while it waits;
 *  - **traffic already inside the region is let out**, so the drain terminates however busy the
 *    region is.
 *
 *  Without the first, a holder holding part of a region can wait on a vehicle that is waiting on
 *  the part it holds. Without the second, a vehicle inside the region can never leave it. Both are
 *  prevented here rather than left to be diagnosed, which is what makes them rules rather than
 *  preferences. Neither rule makes a deadlock through a holder impossible -- two closures over
 *  abutting regions can wait on each other through their reservations, which is
 *  `PendingClosureCycleTest`'s subject, not this file's.
 *
 *  Geometry throughout: twelve-foot zones at twelve feet a minute, so a zone is one minute, and
 *  junctions are dimensionless.
 */
class ZoneClosureTest {

    /** A holder, which is a plain object: two members, no base class, made whenever. */
    private class Crew : ZoneHolderIfc {
        override val name: String = "Crew"
        override val awaitedZone: Zone? get() = null
    }

    /** Records what the space promises to tell, so the promise itself can be asserted. */
    private class Log : ZoneHoldActionIfc {
        val began = mutableListOf<Double>()
        val ended = mutableListOf<Double>()
        override fun holdBegan(allocation: ZoneAllocation) { began.add(allocation.engagedAt) }
        override fun holdEnded(allocation: ZoneAllocation) { ended.add(allocation.releasedAt) }
    }

    /** Two links in a line, so a vehicle can be sent right through a closed region and out. */
    private class Corridor(parent: ModelElement) : ModelElement(parent, "Corridor") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Corridor")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        /** Made here only because every test in this file uses one; nothing requires it to be. */
        val crew = Crew()

        /** Records what the space promises to tell, so the promise itself can be asserted. */
        val log = Log()

        /** The whole of the first aisle: four zones, chosen by name at run time. */
        val aisle: List<Zone> get() = network.link("L1")!!.zones

        val arrived = mutableListOf<Double>()

        init {
            cart.attachArrivalListener { arrived.add(time) }
        }
    }

    private fun model(): Pair<Model, Corridor> {
        val m = Model("ZoneClosure")
        val c = Corridor(m)
        c.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        return m to c
    }

    // ---- extent, chosen at run time ------------------------------------------------------------

    @Test
    fun `a whole link closes as one, and reopens as one`() {
        val (m, c) = model()
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.holdZonesFor(c.crew, c.aisle, 5.0, c.log)
                }, 0.0)
            }
        }
        m.simulate()

        assertEquals(4, c.aisle.size, "the extent came from the link, not from a literal")
        assertFalse(
            c.system.isHoldingZones(c.crew), "the closure ended on its own after five minutes"
        )
        assertEquals(listOf(0.0), c.log.began)
        assertEquals(listOf(5.0), c.log.ended, "and the action was told when it ended")
        for (zone in c.aisle) {
            assertTrue(zone.isAvailable, "zone (${zone.name}) was left closed")
            assertEquals(null, zone.closingFor)
        }
        // Four zones closed for five minutes of a sixty-minute run.
        assertEquals(
            4 * 5.0 / 60.0,
            c.system.numZonesClosed.withinReplicationStatistic.weightedAverage, 1e-9,
            "every zone of the set must count towards the space closed"
        )
    }

    @Test
    fun `an empty set, a repeated zone, and a foreign zone are all refused`() {
        val (_, c) = model()
        assertFailsWith<IllegalArgumentException> {
            c.system.requestZones(c.crew, emptyList(), c.log)
        }
        val z = c.aisle.first()
        assertFailsWith<IllegalArgumentException> {
            c.system.requestZones(c.crew, listOf(z, z), c.log)
        }

        // A zone of a different guide path is not this one's to close.
        val other = GuidedPathNetwork.builder("Elsewhere")
            .link("X", "P", "Q", length = 12.0, zoneLength = 12.0)
            .build()
        assertFailsWith<IllegalArgumentException> {
            c.system.requestZones(c.crew, other.zones.take(1), c.log)
        }
    }

    // ---- rule one: all or nothing --------------------------------------------------------------

    @Test
    fun `a set with one busy zone is not taken at all until every zone has drained`() {
        // The cart is crossing the aisle when the closure is asked for, so three zones are free and
        // one is not. Holding the three would be a region part held and part draining, which is the
        // state the atomicity rule exists to make impossible; the invariant checker asserts it and
        // would fire here if the grant were progressive.
        val (m, c) = model()
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> c.cart.sendTo("C") }, 0.0)
                // At 2.5 the cart has claimed L1.Zone3 and is travelling into it.
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.requestZones(c.crew, c.aisle, c.log)
                }, 2.5)
            }
        }
        m.simulate()
        val grantedAt = c.log.began

        // At 2.5 the cart covers Zone2 and has claimed Zone3. It reaches Zone3 at 3.0, giving up
        // Zone2; reaches Zone4 at 4.0, giving up Zone3; and reaches B -- dimensionless, so the same
        // instant -- giving up Zone4 at 4.0. So the last zone of the set drains at 4.0 and the
        // whole set is taken then, not when the first three became free.
        assertEquals(listOf(4.0), grantedAt, "the set must be taken when the LAST zone drains")
        assertEquals(1.5, c.system.timeToCloseZones.withinReplicationStatistic.weightedAverage, 1e-9)
        for (zone in c.aisle) {
            assertTrue(zone.hasHolder, "zone (${zone.name}) should be held once the set was taken")
        }
    }

    @Test
    fun `the cart is not held up by a closure it is already inside`() {
        // Rule two, and the reason the drain terminates. The cart is inside the aisle when the whole
        // aisle is closed: if the closure refused it the zones ahead, it could never leave, the zone
        // it stands in would never drain, and the closure would never be granted. The cart would sit
        // waiting on a zone that is free and stays free, which the rule prevents outright rather
        // than leaving to be diagnosed.
        val (m, c) = model()
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> c.cart.sendTo("C") }, 0.0)
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.requestZones(c.crew, c.aisle, c.log)
                }, 2.5)
            }
        }
        m.simulate()

        // Six link zones at a minute each; the junctions B and C are dimensionless and cost nothing.
        assertEquals(listOf(6.0), c.arrived, "the cart must have driven out of the closing region")
        assertEquals(0.0, c.cart.numTimesBlocked.value, 0.0, "and must not have waited once")
        assertTrue(c.system.isHoldingZones(c.crew), "and the closure got its region afterwards")
    }

    @Test
    fun `a vehicle outside the region is kept out of it`() {
        // The other half of rule two: letting the ones inside out must not let new ones in, or the
        // aisle never closes. The cart starts behind the region and is sent through it.
        val (m, c) = model()
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                // Closed first, while the cart is still on the junction A and holds no aisle zone.
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.holdZonesFor(c.crew, c.aisle, 10.0, c.log)
                }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> c.cart.sendTo("C") }, 1.0)
            }
        }
        m.simulate()

        assertEquals(1.0, c.cart.numTimesBlocked.value, 0.0, "the cart must have been refused entry")
        // Refused at 1.0, the closure ends at 10.0, then six link zones.
        assertEquals(listOf(16.0), c.arrived)
    }

    @Test
    fun `a zone that drains while the rest of the set is busy still wakes a vehicle inside`() {
        // A lost wake-up, and a nasty one: the vehicle stranded was the one the closure was waiting
        // for. When a zone drains, the promise on it comes first -- that is what stops a closure on
        // a busy aisle never happening. But a closure still waiting on its *other* zones cannot use
        // this one yet, and handing it over regardless left every vehicle waiting here with nothing
        // scheduled at all.
        //
        // Two carts on the aisle, both sent on their way, and the closure covers the two zones they
        // are standing on. The leading cart drives off Zone3, which frees it; the closure is
        // promised it and cannot take it, because the trailing cart is still on Zone2. Before this
        // was fixed the trailing cart was never told Zone3 had come free, so it sat on Zone2 for the
        // rest of the run -- and Zone2 is exactly the zone the closure was waiting on, so the
        // closure was stranded by the very vehicle it was waiting for.
        val m = Model("DrainedWhileBusy")
        val c = Corridor(m)
        c.system.checkInvariants = true
        val trailing = GuidedTransporter(
            c.system, TransporterPlacement.OnZone("L1.Zone2"), ConstantRV(12.0), 1, name = "Trailing"
        )
        val leading = GuidedTransporter(
            c.system, TransporterPlacement.OnZone("L1.Zone3"), ConstantRV(12.0), 1, name = "Leading"
        )
        val arrived = mutableListOf<String>()
        trailing.attachArrivalListener { arrived.add(it.name) }
        leading.attachArrivalListener { arrived.add(it.name) }
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.requestZones(
                        c.crew,
                        listOf(c.network.zone("L1.Zone2")!!, c.network.zone("L1.Zone3")!!),
                        c.log
                    )
                }, 0.5)
                schedule({ _: KSLEvent<Nothing> ->
                    // Different destinations, because two carts sent to the same junction would
                    // leave the second one obstructed by the first parked on it -- a real condition
                    // the subsystem reports, and nothing to do with what this test is about.
                    leading.sendTo("C")
                    trailing.sendTo("B")
                }, 1.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        m.simulate()

        assertEquals(
            setOf("Leading", "Trailing"), arrived.toSet(),
            "both carts must get out; the trailing one was stranded before this was fixed"
        )
        assertTrue(
            c.system.isHoldingZones(c.crew),
            "and the closure gets its region once the cart it was waiting for has left"
        )
        assertEquals(1, c.log.began.size, "granted exactly once")
    }

    // ---- giving a set back ---------------------------------------------------------------------

    @Test
    fun `giving up a set still draining reopens every zone of it`() {
        // Asked for at 2.5 while the cart is inside the aisle, so the set is still draining, and
        // given up at 3.0 before it ever finishes. Every zone must reopen -- a reservation left
        // behind on even one of them would close that zone to traffic for the rest of the run,
        // with nothing holding it and nothing ever coming to release it.
        val (m, c) = model()
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> c.cart.sendTo("C") }, 0.0)
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.requestZones(c.crew, c.aisle, c.log)
                }, 2.5)
                schedule({ _: KSLEvent<Nothing> -> c.system.releaseZones(c.crew) }, 3.0)
            }
        }
        m.simulate()

        for (zone in c.aisle) {
            assertEquals(null, zone.closingFor, "zone (${zone.name}) was left closing")
            assertTrue(zone.isAvailable, "zone (${zone.name}) was left held")
        }
        assertEquals(0.0, c.system.numZoneEngagements.value, 0.0, "the closure never took effect")
        assertTrue(c.log.began.isEmpty(), "and nothing was told of a hold that never began")
        assertFalse(c.system.isHoldingZones(c.crew))
        assertEquals(listOf(6.0), c.arrived, "and the cart was never held up by any of it")
    }

    @Test
    fun `a set is cleared between replications`() {
        val m = Model("ZoneClosureReplicated")
        val c = Corridor(m)
        c.system.checkInvariants = true
        object : ModelElement(c, "Driver") {
            override fun initialize() {
                c.arrived.clear()
                schedule({ _: KSLEvent<Nothing> ->
                    c.system.holdZonesFor(c.crew, c.aisle, 5.0, c.log)
                }, 1.0)
            }
        }
        m.numberOfReplications = 3
        m.lengthOfReplication = 60.0
        m.simulate()

        assertFalse(c.system.isHoldingZones(c.crew))
        for (zone in c.aisle) {
            assertEquals(null, zone.closingFor)
        }
        val engagements = c.system.numZoneEngagements.acrossReplicationStatistic
        assertEquals(3.0, engagements.count, 0.0)
        assertEquals(1.0, engagements.average, 1e-12)
        assertEquals(
            0.0, engagements.variance, 1e-12,
            "a deterministic model must close identically every replication; any spread means a " +
                    "reservation was carried over"
        )
    }
}
