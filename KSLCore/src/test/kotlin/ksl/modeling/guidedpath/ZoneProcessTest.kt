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

import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  An entity takes guide-path space, works, and gives it back.
 *
 *  The process face of general occupancy, and the case that decided the shape of the whole
 *  construct. An `Entity` is made by an arrival process at run time, so it cannot be a model
 *  element and could never have been handed one from a pool without capping concurrency, misnaming
 *  the holder in every obstruction message, and averaging a 0/1 indicator over unrelated jobs.
 *  Everything it needs is the two members of [ZoneHolderIfc], which it satisfies outright.
 *
 *  ## The arithmetic, stated once
 *
 *  Zones are twelve feet and carts travel twelve feet a minute, so **one zone costs one minute**.
 *  Junctions are dimensionless. The aisle is four zones, so a cart sent from A to B on a clear path
 *  arrives at 4.0; under end-of-zone control it gives a zone up on arriving in the next one, so a
 *  cart that claimed Zone3 at 2.5 releases it at 4.0. A cart refused Zone3 settles at the end of
 *  Zone2 at 2.0.
 */
class ZoneProcessTest {

    /**
     *  An aisle of four zones, a cart that crosses it, and a queue for whoever waits on space.
     *
     *  A spill takes Zone3 at [spillAt] and holds it for [holdFor]; the cart is sent from A to B at
     *  [cartAt], or not at all when that is NaN. What differs between tests is timing, so it is
     *  parameters here rather than a fixture per test.
     */
    private open class Shop(
        parent: ModelElement,
        val spillAt: Double = Double.NaN,
        val holdFor: Double = 6.0,
        val cartAt: Double = 0.0,
        val releases: Boolean = true
    ) : ProcessModel(parent, "Shop") {

        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val spaceQ = HoldQueue(this, "SpaceQ")

        val closed: Zone get() = network.zone("L1.Zone3")!!

        val cartArrivedAt = mutableListOf<Double>()
        val heldAt = mutableListOf<Double>()
        val resumedAt = mutableListOf<Double>()
        val releasedAt = mutableListOf<Double>()
        var everQueued: Boolean = false
        var awaitedWhileHolding: Zone? = null
        var current: Spill? = null

        init {
            system.checkInvariants = true
            cart.attachArrivalListener { cartArrivedAt.add(time) }
        }

        inner class Spill(private val which: Int = 3) : Entity("Spill$which") {
            val cleanup = process("cleanup") {
                val allocation = seizeZone(system, network.zone("L1.Zone$which")!!, spaceQ)
                heldAt.add(allocation.engagedAt)
                resumedAt.add(time)
                everQueued = everQueued || spaceQ.numInQ.withinReplicationStatistic.max > 0.0
                awaitedWhileHolding = awaitedZone
                delay(holdFor)
                if (releases) {
                    releaseZones(system)
                    releasedAt.add(time)
                }
            }
        }

        override fun initialize() {
            cartArrivedAt.clear(); heldAt.clear(); resumedAt.clear(); releasedAt.clear()
            everQueued = false
            awaitedWhileHolding = null
            if (cartAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, cartAt)
            }
            if (spillAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> ->
                    val spill = Spill()
                    current = spill
                    activate(spill.cleanup)
                }, spillAt)
            }
        }
    }

    private fun run(shop: Shop, m: Model, length: Double = 40.0, replications: Int = 1): Shop {
        m.numberOfReplications = replications
        m.lengthOfReplication = length
        m.simulate()
        return shop
    }

    // ---- the ordinary case ---------------------------------------------------------------------

    @Test
    fun `a spill takes a zone, keeps it for a while, and gives it back`() {
        val m = Model("SpillProcess")
        val shop = run(Shop(m, spillAt = 0.5, holdFor = 6.0), m)

        assertEquals(listOf(0.5), shop.heldAt, "the zone was free, so the hold began at once")
        assertEquals(listOf(6.5), shop.releasedAt)
        // The cart is refused Zone3 at 2.0, waits until 6.5, then crosses Zone3 and Zone4.
        assertEquals(listOf(8.5), shop.cartArrivedAt)
        assertEquals(1.0, shop.cart.numTimesBlocked.value, 0.0)
        assertEquals(1.0, shop.system.numZoneEngagements.value, 0.0)
    }

    @Test
    fun `space that is free is held without the process suspending`() {
        // The same contract a journey has: a process that suspended anyway would need somebody to
        // wake it for nothing, and the instant it returns in is the instant it asked in.
        val m = Model("NoSuspend")
        val shop = run(Shop(m, spillAt = 0.5, holdFor = 1.0, cartAt = Double.NaN), m, length = 20.0)

        assertEquals(listOf(0.5), shop.heldAt, "held in the instant it was asked for")
        assertEquals(listOf(0.5), shop.resumedAt, "and the process carried straight on")
        assertFalse(shop.everQueued, "so it never went into the queue")
    }

    @Test
    fun `a process waits in the queue while the zone drains, and resumes at the grant`() {
        // Asked for at 2.5 while the cart is crossing Zone3, so the zone drains until 4.0. Nothing
        // is evicted: the cart finishes and leaves, and the process suspends until it has.
        val m = Model("Drain")
        val shop = run(Shop(m, spillAt = 2.5, holdFor = 1.0), m, length = 20.0)

        assertEquals(listOf(4.0), shop.heldAt, "the cart cleared Zone3 at 4.0")
        assertEquals(listOf(4.0), shop.resumedAt, "and the process resumed then, not at the request")
        assertTrue(shop.everQueued, "having waited in the queue for it")
        assertEquals(listOf(4.0), shop.cartArrivedAt, "the cart was not held up at all")
        assertEquals(
            1.5, shop.system.timeToCloseZones.withinReplicationStatistic.weightedAverage, 1e-9
        )
    }

    @Test
    fun `an entity holding space is a sink, so a vehicle behind it is obstructed and not deadlocked`() {
        val m = Model("Sink")
        val shop = run(Shop(m, spillAt = 0.5, holdFor = 6.0), m)

        assertEquals(0.0, shop.system.numDeadlocksDetected.value, 0.0)
        assertEquals(listOf(8.5), shop.cartArrivedAt, "obstructed, then through")
        // Asserted directly, because it is the premise of the all-or-nothing rule rather than a
        // consequence of it: an entity that both held space and queued for more would have an
        // outgoing edge, and a cycle through it would be one the detector cannot see.
        assertNull(
            shop.awaitedWhileHolding, "an entity holding guide-path space waits for no more of it"
        )
    }

    // ---- the property the whole reshape is for -------------------------------------------------

    private class ConcurrentShop(parent: ModelElement) : Shop(parent, cartAt = 0.0) {
        val holdersAt15 = mutableListOf<Int>()

        override fun initialize() {
            super.initialize()
            holdersAt15.clear()
            // After 10.0, so the cart -- clear of the aisle at 4.0 -- is not part of this.
            for (i in 1..3) {
                schedule({ _: KSLEvent<Nothing> -> activate(Spill(i).cleanup) }, 9.0 + i)
            }
            schedule({ _: KSLEvent<Nothing> ->
                // Non-vehicle holders only: the cart finished at 4.0 and is parked on the junction
                // zone at B, which it holds exactly as a stopped vehicle should.
                holdersAt15.add(
                    network.zones.count { it.holder != null && it.holder !is GuidedTransporter }
                )
            }, 15.0)
        }
    }

    @Test
    fun `spills arrive at run time and hold different zones at the same moment`() {
        // Three entities that do not exist when the model is built, holding three zones at once.
        // A model element could only ever have been a fixed cast reused serially; this is the case
        // that could not be written at all before.
        val m = Model("ConcurrentSpills")
        val shop = ConcurrentShop(m)
        run(shop, m)

        assertEquals(listOf(3), shop.holdersAt15, "all three held space at the same instant")
        assertEquals(3.0, shop.system.numZoneEngagements.value, 0.0)
        assertEquals(listOf(10.0, 11.0, 12.0), shop.heldAt)
        assertEquals(listOf(16.0, 17.0, 18.0), shop.releasedAt)
        assertEquals(
            3.0, shop.system.numZonesClosed.withinReplicationStatistic.max, 0.0,
            "three concurrent closures, which one pre-declared holder could never have done"
        )
    }

    // ---- cleanup, which is where the care is ---------------------------------------------------

    @Test
    fun `a process that ends still holding space is refused`() {
        // The same rule resources already have, and it has to be the same rule: space never given
        // back stays closed for the rest of the replication with nothing holding it, which raises
        // nowhere and shows up only as a guide path that quietly stopped moving.
        val m = Model("LeakedSpace")
        Shop(m, spillAt = 0.5, holdFor = 1.0, cartAt = Double.NaN, releases = false)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0

        val e = assertFailsWith<IllegalStateException> { m.simulate() }
        val msg = e.message ?: ""
        assertTrue(msg.contains("guide-path space"), msg)
        assertTrue(msg.contains("releaseZones"), msg)
        assertTrue(msg.contains("L1.Zone3"), msg)
    }

    private class TerminatedShop(
        parent: ModelElement,
        spillAt: Double,
        private val terminateAt: Double
    ) : Shop(parent, spillAt = spillAt, holdFor = 100.0) {

        override fun initialize() {
            super.initialize()
            schedule({ _: KSLEvent<Nothing> -> current!!.terminateProcess() }, terminateAt)
        }
    }

    @Test
    fun `a terminated process gives back the space it held`() {
        val m = Model("TerminatedHolding")
        val shop = TerminatedShop(m, spillAt = 0.5, terminateAt = 6.0)
        run(shop, m)

        assertFalse(shop.system.isHoldingZones(shop.current!!), "the zone went back on termination")
        assertNull(shop.closed.holder)
        assertFalse(shop.current!!.usesZoneSpace)
        // Blocked at 2.0, freed at 6.0 when the spill was killed, then two zones.
        assertEquals(listOf(8.0), shop.cartArrivedAt, "and the waiting cart was woken by it")
    }

    @Test
    fun `a process terminated while waiting for space gives up its reservation`() {
        // The state a terminated entity is most likely to be caught in, and the one that does the
        // most damage if it is missed: a reservation left behind closes the aisle for the rest of
        // the replication with nothing named as its holder and nothing coming to release it.
        //
        // The cart claims Zone3 at 2.5 and does not clear it until 4.0, so a request made at 2.5 is
        // still draining at 3.0.
        val m = Model("TerminatedWaiting")
        val shop = TerminatedShop(m, spillAt = 2.5, terminateAt = 3.0)
        run(shop, m, length = 20.0)

        assertNull(shop.closed.closingFor, "the reservation must not outlive the entity")
        assertNull(shop.closed.holder)
        assertTrue(shop.closed.isAvailable)
        assertFalse(shop.current!!.usesZoneSpace)
        assertEquals(0.0, shop.system.numZoneEngagements.value, 0.0, "the hold never began")
        assertEquals(listOf(4.0), shop.cartArrivedAt, "and the cart was never held up")
    }

    @Test
    fun `space held when a replication ends does not carry into the next one`() {
        // Two independent nets meet here: the end of a replication terminates every suspended
        // entity, which gives the space back, and the space clears its own records at the start of
        // the next. Only a run of more than one replication can show either of them working.
        val m = Model("AcrossReplications")
        // Held for longer than the replication, so it is still held when the replication ends.
        val shop = run(
            Shop(m, spillAt = 0.5, holdFor = 1000.0, cartAt = Double.NaN), m,
            length = 20.0, replications = 3
        )

        assertNull(shop.closed.holder, "nothing may be left holding the zone")
        assertNull(shop.closed.closingFor)
        val engagements = shop.system.numZoneEngagements.acrossReplicationStatistic
        assertEquals(3.0, engagements.count, 0.0)
        assertEquals(1.0, engagements.average, 1e-12, "one hold every replication")
        assertEquals(
            0.0, engagements.variance, 1e-12,
            "a deterministic model must engage identically every replication; any spread means a " +
                    "hold was carried over"
        )
    }

    // ---- a set, taken together, from a process -------------------------------------------------

    private class RegionShop(parent: ModelElement) : Shop(parent, cartAt = 0.0) {
        val grantedAt = mutableListOf<Double>()

        inner class Crew : Entity("Crew") {
            val work = process("work") {
                val allocation = seizeZones(system, network.link("L1")!!.zones, spaceQ)
                grantedAt.add(allocation.engagedAt)
                delay(2.0)
                releaseZones(system)
            }
        }

        override fun initialize() {
            super.initialize()
            grantedAt.clear()
            schedule({ _: KSLEvent<Nothing> -> activate(Crew().work) }, 2.5)
        }
    }

    @Test
    fun `a process takes a whole region together or not at all`() {
        // The all-or-nothing rule seen from the process side, and the reason an entity's
        // awaitedZone can honestly be null: it holds nothing at all until the last zone of the set
        // has drained, so it is never both holding and queuing.
        val m = Model("RegionSeize")
        val shop = RegionShop(m)
        run(shop, m)

        // The cart claimed Zone3 at 2.5 and leaves the last aisle zone on reaching B at 4.0.
        assertEquals(listOf(4.0), shop.grantedAt, "the set is taken when the LAST zone drains")
        assertEquals(listOf(4.0), shop.cartArrivedAt, "and the cart drove out rather than being trapped")
        assertEquals(0.0, shop.cart.numTimesBlocked.value, 0.0)
        assertEquals(
            4.0, shop.system.numZonesClosed.withinReplicationStatistic.max, 0.0,
            "all four zones of the link, held together"
        )
    }
}
