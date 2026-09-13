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

import ksl.modeling.guidedpath.internal.ZoneInvariantViolation
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Something that is not a vehicle takes a zone, and traffic works around it.
 *
 *  The thing being demonstrated is not that a zone can be blocked -- a population did that -- but
 *  that space can be taken **exclusively** by something with no route, no body and no journey, and
 *  that the subsystem treats it exactly as it treats a parked vehicle without needing to know the
 *  difference.
 *
 *  A holder here is a plain object implementing [ZoneHolderIfc], made whenever the model wants one.
 *  Nothing about the cast is declared before the run, which is the property the whole construct
 *  turns on: a model element would have to be, and a spill arriving at 137.4 minutes cannot be.
 *
 *  ## The arithmetic, stated once
 *
 *  Zones are twelve feet and carts travel twelve feet a minute, so **one zone costs one minute**.
 *  Junctions are dimensionless and cost nothing. The aisle below is four zones, so a cart sent from
 *  A to B with a clear path arrives at 4.0; under end-of-zone control it gives a zone up on
 *  arriving in the next one, so a cart that claimed Zone3 at 2.5 releases it at 4.0 rather than at
 *  3.0. A cart refused Zone3 settles at the end of Zone2 at 2.0.
 */
class ZoneHolderTest {

    /**
     *  A holder, made whenever the model needs one and as often as it needs one.
     *
     *  Two members and no base class. It waits for nothing -- the all-or-nothing grant sees to that
     *  -- so it is a terminal node of the wait-for walk and whatever queues behind it is obstructed
     *  rather than deadlocked.
     */
    private class Crew(id: Int) : ZoneHolderIfc {
        override val name: String = "Crew$id"
        override val awaitedZone: Zone? get() = null
    }

    /**
     *  Records what the space promises to tell, so that the promise itself can be asserted rather
     *  than only its consequences.
     */
    private class Log : ZoneHoldActionIfc {
        val began = mutableListOf<Double>()
        val ended = mutableListOf<Double>()
        val heldFor = mutableListOf<Double>()

        /** Something for the action to do inside the grant, for the re-entrancy case. */
        var whenBegun: ((ZoneAllocation) -> Unit)? = null

        override fun holdBegan(allocation: ZoneAllocation) {
            began.add(allocation.engagedAt)
            whenBegun?.invoke(allocation)
        }

        override fun holdEnded(allocation: ZoneAllocation) {
            ended.add(allocation.releasedAt)
            heldFor.add(allocation.timeHeld(allocation.releasedAt))
        }

        fun clear() {
            began.clear(); ended.clear(); heldFor.clear()
        }
    }

    /** A straight aisle of four zones, with a cart that crosses it and a crew that takes one. */
    private class Aisle(parent: ModelElement) : ModelElement(parent, "Aisle") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val crew = Crew(1)
        val log = Log()

        /** The zone the crew takes: the third along, so the cart is under way before it matters. */
        val closed: Zone get() = network.zone("L1.Zone3")!!

        var requestAt: Double = Double.NaN
        var releaseAt: Double = Double.NaN
        var grantedAtRequest: Boolean? = null

        val cartArrivedAt = mutableListOf<Double>()

        init {
            cart.attachArrivalListener { cartArrivedAt.add(time) }
        }

        override fun initialize() {
            grantedAtRequest = null
            cartArrivedAt.clear()
            log.clear()
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 0.0)
            if (requestAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> ->
                    grantedAtRequest = system.requestZone(crew, closed, log).isGranted
                }, requestAt)
            }
            if (releaseAt.isFinite()) {
                schedule({ _: KSLEvent<Nothing> -> system.releaseZones(crew) }, releaseAt)
            }
        }
    }

    private fun run(
        requestAt: Double = Double.NaN,
        releaseAt: Double = Double.NaN,
        length: Double = 40.0,
        replications: Int = 1
    ): Aisle {
        val m = Model("ZoneHolder")
        val a = Aisle(m)
        a.requestAt = requestAt
        a.releaseAt = releaseAt
        a.system.checkInvariants = true
        m.numberOfReplications = replications
        m.lengthOfReplication = length
        m.simulate()
        return a
    }

    // ---- taking space, and traffic working around it -------------------------------------------

    @Test
    fun `a free zone is granted in the instant it is asked for`() {
        // Asked for at 0.5, while the cart is still crossing Zone1, so there is nothing to drain.
        val a = run(requestAt = 0.5)
        assertEquals(true, a.grantedAtRequest, "an empty zone has nothing to drain")
        assertTrue(a.system.isHoldingZones(a.crew))
        assertSame(a.crew, a.closed.holder, "the zone must name the crew as its holder")
        assertEquals(0.0, a.system.timeToCloseZones.withinReplicationStatistic.weightedAverage, 1e-12)
        assertEquals(listOf(0.5), a.log.began, "and the action is told in that same instant")
    }

    @Test
    fun `a vehicle is stopped by the holder and released when it gives the zone back`() {
        val a = run(requestAt = 0.5, releaseAt = 10.0)
        assertEquals(1.0, a.cart.numTimesBlocked.value, 0.0, "the cart must have been stopped once")
        // Settles at the end of Zone2 at 2.0, refused Zone3, waits until 10.0, then two zones.
        assertEquals(listOf(12.0), a.cartArrivedAt)
        assertFalse(a.system.isHoldingZones(a.crew), "the crew gave the zone back")
        assertNull(a.closed.holder)
    }

    @Test
    fun `a vehicle held up by a non-vehicle holder is not a deadlock`() {
        // The reason `awaitedZone` is null on such a holder. A holder that never queues has no
        // outgoing edge in the wait-for graph, so no cycle can run through it -- the cart behind it
        // is obstructed, which is a different condition with a different remedy, and reporting a
        // circular wait here would be the one mistake the walk exists to avoid.
        val a = run(requestAt = 0.5, releaseAt = 10.0)
        assertEquals(0.0, a.system.numDeadlocksDetected.value, 0.0)
        assertNull(a.crew.awaitedZone)
    }

    // ---- draining, which is the part that had to be built --------------------------------------

    @Test
    fun `a zone a vehicle is crossing drains rather than being taken from it`() {
        // Asked for at 2.5, while the cart is crossing Zone3 itself. Nothing is evicted: the cart
        // finishes its traversal and leaves, and only then does the crew have the zone.
        val a = run(requestAt = 2.5)
        assertEquals(false, a.grantedAtRequest, "the cart was in it, so there was something to drain")
        // The cart crosses Zone4 and reaches B unimpeded -- the closure does not reach backwards.
        assertEquals(listOf(4.0), a.cartArrivedAt, "the cart must not have been held up at all")
        assertEquals(0.0, a.cart.numTimesBlocked.value, 0.0)
        assertTrue(a.system.isHoldingZones(a.crew), "and the crew has it once the cart has gone")
        // Claimed Zone3 at 2.5 and gives it up on arriving in Zone4 at 4.0, so the crew waits 1.5.
        assertEquals(1.5, a.system.timeToCloseZones.withinReplicationStatistic.weightedAverage, 1e-9)
        assertEquals(1.0, a.system.numZoneEngagements.value, 0.0)
        assertEquals(listOf(4.0), a.log.began, "told at the grant, not at the request")
    }

    @Test
    fun `a reservation beats a vehicle that was already waiting for the zone`() {
        // The property that makes a drain terminate. A zone that merely refused everyone would go
        // to whichever vehicle asked next, and on a busy aisle the closure would never happen.
        //
        // Parked sits on Zone3 from the start. Runner blocks behind it at 2.0. The crew asks for
        // Zone3 at 3.0, while Parked still holds it. At 5.0 Parked is sent away down a second link,
        // so it leaves the aisle entirely rather than settling on Runner's destination, and gives
        // up Zone3 on arriving in Zone4 at 6.0.
        val m = Model("DrainPriority")
        val yard = object : ModelElement(m, "Yard") {
            val network: GuidedPathNetwork = GuidedPathNetwork.builder("Yard")
                .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
                .link("L2", "B", "C", length = 24.0, zoneLength = 12.0)
                .build()
            val system = GuidedPathTransportSystem(this, network, name = "Sys")
            val runner = GuidedTransporter(
                system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Runner"
            )
            val parked = GuidedTransporter(
                system, TransporterPlacement.OnZone("L1.Zone3"), ConstantRV(12.0), 1, name = "Parked"
            )
            val crew = Crew(1)
            val log = Log()
            val closed: Zone get() = network.zone("L1.Zone3")!!
            val runnerArrived = mutableListOf<Double>()

            init {
                runner.attachArrivalListener { runnerArrived.add(time) }
            }

            override fun initialize() {
                runnerArrived.clear()
                log.clear()
                schedule({ _: KSLEvent<Nothing> -> runner.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> -> system.requestZone(crew, closed, log) }, 3.0)
                schedule({ _: KSLEvent<Nothing> -> parked.sendTo("C") }, 5.0)
                schedule({ _: KSLEvent<Nothing> -> system.releaseZones(crew) }, 20.0)
            }
        }
        yard.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        // The arrival time is the proof, and a stronger one than any end state: Parked gives up
        // Zone3 at 6.0, and had the waiting vehicle been given it then, it would have reached B at
        // 8.0. It arrives at 22.0 instead -- it waited until the crew released at 20.0, which is
        // only possible if the promise beat it to the zone.
        assertEquals(listOf(22.0), yard.runnerArrived)
        assertEquals(1.0, yard.system.numZoneEngagements.value, 0.0)
        // Asked at 3.0, taken at 6.0 when Parked cleared the zone.
        assertEquals(3.0, yard.system.timeToCloseZones.withinReplicationStatistic.weightedAverage, 1e-9)
        assertEquals(listOf(6.0), yard.log.began)
        assertEquals(listOf(20.0), yard.log.ended)
    }

    @Test
    fun `giving up a request before it is granted reopens the zone and wakes the vehicle`() {
        // A closure cancelled before it took effect. The zone is free, empty and wanted, and every
        // vehicle refused while it was closing is still waiting with nothing scheduled -- so
        // reopening has to go through the same handover a release does.
        val m = Model("AbandonedRequest")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val arrived = mutableListOf<Double>()
        a.cart.attachArrivalListener { arrived.add(a.time) }
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                // Asked for at 0.5 with nothing to drain, so granted at once; given up at 7.0.
                schedule({ _: KSLEvent<Nothing> -> a.system.requestZone(a.crew, a.closed, a.log) }, 0.5)
                schedule({ _: KSLEvent<Nothing> -> a.system.releaseZones(a.crew) }, 7.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertEquals(listOf(9.0), arrived, "the cart waited from 2.0 to 7.0 and then crossed two zones")
        assertNull(a.closed.holder)
        assertNull(a.closed.closingFor)
    }

    // ---- the contract on ZoneHoldActionIfc, asserted rather than documented ---------------------

    @Test
    fun `every hold that began is told exactly once that it ended`() {
        // The half that was missing, and the reason it was required rather than offered. A holder
        // not told when a timed hold ends has to keep its own copy of the duration and add it to a
        // grant time it was also not told, which is one fact with two owners.
        val a = run(requestAt = 2.5, releaseAt = 20.0)
        assertEquals(listOf(4.0), a.log.began, "the drain ended at 4.0")
        assertEquals(listOf(20.0), a.log.ended)
        assertEquals(listOf(16.0), a.log.heldFor, "and the allocation agrees about how long it was")
    }

    @Test
    fun `a request given up while still draining is told nothing at all`() {
        // The third clause of the contract, and why there are two members and not three:
        // abandonment is always the caller's own act, so there is nothing the caller could learn
        // from being told about it. Asked for at 2.5 while the cart is crossing Zone3, so the
        // request is still draining, and given up at 3.0 before it ever finishes.
        val m = Model("AbandonedWhileDraining")
        val a = Aisle(m)
        a.system.checkInvariants = true
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.system.requestZone(a.crew, a.closed, a.log) }, 2.5)
                schedule({ _: KSLEvent<Nothing> -> a.system.releaseZones(a.crew) }, 3.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertTrue(a.log.began.isEmpty(), "the hold never began")
        assertTrue(a.log.ended.isEmpty(), "so nothing ended either")
        assertEquals(0.0, a.system.numZoneEngagements.value, 0.0)
        assertNull(a.closed.closingFor, "and the reservation was cleared")
    }

    @Test
    fun `an action may give the zone straight back inside the grant`() {
        // Re-entrancy, and it is not hypothetical: a crew that finds nothing to do releases at
        // once. The statistics must already be settled when the action runs, or the hold would be
        // recorded as ending before it was recorded as starting.
        val m = Model("ImmediateRelease")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val arrived = mutableListOf<Double>()
        a.cart.attachArrivalListener { arrived.add(a.time) }
        a.log.whenBegun = { a.system.releaseZones(a.crew) }
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.system.requestZone(a.crew, a.closed, a.log) }, 0.5)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertFalse(a.system.isHoldingZones(a.crew), "the action gave it back")
        assertEquals(1.0, a.system.numZoneEngagements.value, 0.0, "and the hold was still counted")
        assertEquals(listOf(0.5), a.log.began)
        assertEquals(listOf(0.5), a.log.ended, "told it ended, inside the same instant")
        // Taken and given back at 0.5, before the cart ever wanted Zone3, so nothing was held up.
        assertEquals(listOf(4.0), arrived)
        assertEquals(0.0, a.cart.numTimesBlocked.value, 0.0)
    }

    // ---- the statistics, which are the point ---------------------------------------------------

    @Test
    fun `the space reports how much of the guide path is closed, and why vehicles are stopped`() {
        val a = run(requestAt = 0.5, releaseAt = 10.0, length = 20.0)

        // One zone closed from 0.5 to 10.0 of a twenty-minute run.
        assertEquals(
            9.5 / 20.0, a.system.numZonesClosed.withinReplicationStatistic.weightedAverage, 1e-9,
            "the mean number of zones closed to traffic"
        )
        // The cart was held up by the holder from 2.0 to 10.0, and by nothing else ever.
        assertEquals(
            8.0 / 20.0,
            a.system.numBlockedByOccupier.withinReplicationStatistic.weightedAverage, 1e-9
        )
        assertEquals(
            0.0, a.system.numBlockedByVehicle.withinReplicationStatistic.weightedAverage, 1e-12
        )
        assertEquals(
            0.0, a.system.numBlockedByPopulation.withinReplicationStatistic.weightedAverage, 1e-12
        )
        // And the decomposition accounts for all of the blocked time, which is the claim that
        // makes it worth collecting: a residue would mean a cause nobody is naming.
        assertEquals(
            a.system.numTransportersBlocked.withinReplicationStatistic.weightedAverage,
            a.system.numBlockedByOccupier.withinReplicationStatistic.weightedAverage,
            1e-9
        )
    }

    @Test
    fun `the drain delay is reported by the space and not folded into the hold`() {
        // Asked for at 2.5, granted at 4.0, of a forty-minute run. The cost of draining rather
        // than evicting: a closure wanted *now* that begins late because an aisle was busy is a
        // real effect, and invisible unless it is counted.
        val a = run(requestAt = 2.5, releaseAt = 20.0)
        assertEquals(
            1.5 / 40.0, a.system.numWaitingForZones.withinReplicationStatistic.weightedAverage, 1e-9,
            "the mean number of holders waiting for space to drain"
        )
        assertEquals(1.5, a.system.timeToCloseZones.withinReplicationStatistic.weightedAverage, 1e-9)
        assertEquals(1.0, a.system.timeToCloseZones.withinReplicationStatistic.count, 0.0)
    }

    @Test
    fun `a hold does not count as guide path covered by vehicles`() {
        // `zoneUtilization` measures vehicle bodies and `numZonesClosed` measures space denied by
        // something else. Three things can now make a zone unavailable and collapsing them into one
        // number would lose exactly the decomposition this work exists to expose.
        val a = run(requestAt = 0.5, length = 20.0)
        assertEquals(ZoneState.CLAIMED, a.closed.state, "a holder reserves space; it has no body")
        assertFalse(a.closed.isCovered)
        assertTrue(a.closed.hasHolder)
        assertFalse(a.closed.isAvailable)
    }

    @Test
    fun `the hold is cleared between replications`() {
        // The defect family this work has met four times -- a manifest, a position, a zone
        // population and an occupier's own copy of what it held, each left behind by a reset. There
        // is one owner now, and only a run of more than one replication can show it is cleared.
        val a = run(requestAt = 0.5, releaseAt = 10.0, replications = 3)
        assertFalse(a.system.isHoldingZones(a.crew))
        val engagements = a.system.numZoneEngagements.acrossReplicationStatistic
        assertEquals(3.0, engagements.count, 0.0, "one observation per replication")
        assertEquals(1.0, engagements.average, 1e-12, "the crew took the zone once every replication")
        assertEquals(
            0.0, engagements.variance, 1e-12,
            "a deterministic model must engage identically every replication; any spread means a " +
                    "hold was carried over"
        )
    }

    // ---- holding for a stated duration ---------------------------------------------------------

    @Test
    fun `holding for a duration measures it from the grant and not from the request`() {
        // The trap this verb exists for. Asked for at 2.5 while the cart is crossing Zone3, so the
        // zone drains until 4.0; a ten-minute closure must then run to 14.0, not to 12.5. Measuring
        // from the request would shorten every closure by however long the drain took -- which
        // depends on traffic, so it would differ between replications and never announce itself.
        val m = Model("HoldForDuration")
        val a = Aisle(m)
        a.system.checkInvariants = true
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.holdZoneFor(a.crew, a.closed, 10.0, a.log)
                }, 2.5)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertEquals(listOf(4.0), a.log.began, "the hold began at 4.0, when the cart cleared the zone")
        assertEquals(listOf(14.0), a.log.ended, "and ran the ten minutes asked for, not the 8.5 left")
        assertEquals(listOf(10.0), a.log.heldFor)
        assertFalse(a.system.isHoldingZones(a.crew), "and ended without being told to")
        assertNull(a.system.allocationFor(a.crew), "the allocation is given up with the hold")
    }

    @Test
    fun `a zone held for a duration is given back to a waiting vehicle`() {
        // The whole point, end to end: the closure runs its stated time and traffic resumes without
        // anybody scheduling a release by hand.
        val m = Model("HoldThenResume")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val arrived = mutableListOf<Double>()
        a.cart.attachArrivalListener { arrived.add(a.time) }
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.holdZoneFor(a.crew, a.closed, 6.0, a.log)
                }, 0.5)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        // Granted at 0.5 with nothing to drain, released at 6.5; the cart blocked at 2.0 and then
        // has Zone3 and Zone4 to cross.
        assertEquals(listOf(8.5), arrived)
        assertEquals(1.0, a.cart.numTimesBlocked.value, 0.0)
    }

    @Test
    fun `a duration must be a duration`() {
        val m = Model("BadDuration")
        val a = Aisle(m)
        val one = assertFailsWith<IllegalArgumentException> {
            a.system.holdZoneFor(a.crew, a.closed, 0.0, a.log)
        }
        assertTrue((one.message ?: "").contains("requestZone"), one.message ?: "")
        val many = assertFailsWith<IllegalArgumentException> {
            a.system.holdZonesFor(a.crew, listOf(a.closed), -1.0, a.log)
        }
        assertTrue((many.message ?: "").contains("requestZones"), many.message ?: "")
    }

    // ---- a cast that is not known before the run -----------------------------------------------

    @Test
    fun `holders are made during the run, and several hold space at once`() {
        // The property the whole reshape is for, and one that could not be written at all while a
        // holder had to be a model element: three crews that do not exist when the model is built,
        // each closing a zone, all three holding at the same time. Nothing about the cast is
        // declared in advance -- not how many, not which zones, not when.
        //
        // The closures start at 10, 11 and 12 and run eight minutes each, so the cart (which is
        // clear of the aisle at 4.0) is not part of this and the three holds overlap from 12 to 18.
        val m = Model("UnboundedHolders")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val crews = mutableListOf<Crew>()
        val allHeldAt15 = mutableListOf<Boolean>()
        object : ModelElement(a, "Spills") {
            override fun initialize() {
                crews.clear()
                allHeldAt15.clear()
                for (i in 1..3) {
                    schedule({ _: KSLEvent<Nothing> ->
                        // Made here, at run time, one per occurrence.
                        val crew = Crew(i)
                        crews.add(crew)
                        a.system.holdZoneFor(crew, a.network.zone("L1.Zone$i")!!, 8.0, a.log)
                    }, 9.0 + i)
                }
                schedule({ _: KSLEvent<Nothing> ->
                    allHeldAt15.addAll(crews.map { a.system.isHoldingZones(it) })
                }, 15.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertEquals(listOf("Crew1", "Crew2", "Crew3"), crews.map { it.name })
        assertEquals(listOf(true, true, true), allHeldAt15, "all three must hold at the same instant")
        assertEquals(3.0, a.system.numZoneEngagements.value, 0.0, "three separate closures")
        assertEquals(listOf(10.0, 11.0, 12.0), a.log.began, "each granted at once; the zones were free")
        assertEquals(listOf(18.0, 19.0, 20.0), a.log.ended)
        assertEquals(
            3.0, a.system.numZonesClosed.withinReplicationStatistic.max, 0.0,
            "three zones closed at the same time, which one holder could never have done"
        )
        // Three zones for eight minutes each, of a forty-minute run.
        assertEquals(
            (8.0 * 3) / 40.0,
            a.system.numZonesClosed.withinReplicationStatistic.weightedAverage, 1e-9,
            "every zone of every concurrent closure must count towards the space closed"
        )
    }

    @Test
    fun `one holder may have only one request outstanding`() {
        // A holder is the identity of a closure, so two overlapping closures are two holders --
        // which costs nothing now that a holder is a plain object. Asking twice with the same one
        // is a modelling mistake and says so rather than quietly replacing the first.
        val m = Model("OneAtATime")
        val a = Aisle(m)
        a.system.requestZone(a.crew, a.closed, a.log)
        val e = assertFailsWith<IllegalStateException> {
            a.system.requestZone(a.crew, a.network.zone("L1.Zone1")!!, a.log)
        }
        assertTrue((e.message ?: "").contains("One request at a time"), e.message ?: "")
    }

    @Test
    fun `a closure re-taken the instant it ends keeps the zone, and says so in the statistics`() {
        // The consequence of the drain-priority rule, pinned so that nobody "fixes" it by accident.
        // A reservation beats a waiting vehicle -- without that, a closure on a busy aisle would
        // never happen at all -- and asking again from holdEnded makes an ordinary reservation,
        // which wins as any other would. So a closure re-taken every time it ends holds its zone
        // for the rest of the run and the cart never gets through.
        //
        // That is a modelling error rather than a mechanism defect, and the point of the test is
        // that it is not a silent one: the aggregates say exactly what happened.
        val m = Model("ReTaken")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val arrived = mutableListOf<Double>()
        a.cart.attachArrivalListener { arrived.add(a.time) }
        val greedy = object : ZoneHoldActionIfc {
            override fun holdBegan(allocation: ZoneAllocation) {}
            override fun holdEnded(allocation: ZoneAllocation) {
                a.system.requestZone(a.crew, a.closed, this)
            }
        }
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.holdZoneFor(a.crew, a.closed, 9.5, greedy)
                }, 0.5)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertTrue(arrived.isEmpty(), "the cart never gets the zone back")
        assertEquals(2.0, a.system.numZoneEngagements.value, 0.0, "the first hold and its successor")
        // Closed from 0.5 to the end of the run, with no gap at 10.0 where it changed hands.
        assertEquals(
            39.5 / 40.0, a.system.numZonesClosed.withinReplicationStatistic.weightedAverage, 1e-9,
            "a re-taken closure must show as closed throughout, which is how a modeller sees it"
        )
        // And the cart's wait is attributed to the holder from 2.0 onwards, not left unexplained.
        assertEquals(
            38.0 / 40.0,
            a.system.numBlockedByOccupier.withinReplicationStatistic.weightedAverage, 1e-9
        )
    }

    @Test
    fun `a holder that holds space and waits for more is caught`() {
        // Until now this was guaranteed by the type: ZoneOccupier declared awaitedZone final and
        // null. A holder is any ZoneHolderIfc now, so the guarantee is asserted instead. It matters
        // because every argument that a closure cannot deadlock rests on such a holder being a sink
        // in the wait-for graph: give it an outgoing edge and a cycle through it becomes possible
        // *and* invisible, since the detector only follows blocked transporters.
        val m = Model("NotASink")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val liar = object : ZoneHolderIfc {
            override val name = "Liar"
            override val awaitedZone: Zone?
                get() = a.network.zone("L1.Zone1")
        }
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.requestZone(liar, a.closed, a.log)
                }, 0.5)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        val e = assertFailsWith<ZoneInvariantViolation> { m.simulate() }
        assertTrue((e.message ?: "").contains("not a sink"), e.message ?: "")
    }

    @Test
    fun `two holders may not queue for the same zone, and a refusal changes nothing`() {
        // The limit of concurrent holders as it stands. Two holders may close guide path at the
        // same moment and may even want the same zone -- asking for one another holder *holds* is
        // fine and simply waits for the hold to end. What cannot be expressed is two holders queued
        // for the same zone, because a zone carries one promise at a time.
        //
        // What matters as much as the refusal is that it changes nothing. The reservations are made
        // in a loop, so a failure part way along would leave part of a set closed for a request
        // that never existed: closed to traffic for the rest of the replication with nothing coming
        // to release it.
        val m = Model("OverlappingHolders")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val first = Crew(1)
        val second = Crew(2)
        var refusal: String? = null
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.cart.sendTo("B") }, 0.0)
                // At 2.5 the cart is crossing Zone3, so a request for it is still draining and
                // both of its zones stay promised to the first crew.
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.requestZones(first, listOf(a.closed, a.network.zone("L1.Zone4")!!), a.log)
                }, 2.5)
                schedule({ _: KSLEvent<Nothing> ->
                    try {
                        a.system.requestZones(
                            second, listOf(a.network.zone("L1.Zone4")!!, a.network.zone("L1.Zone1")!!),
                            a.log
                        )
                    } catch (e: IllegalArgumentException) {
                        refusal = e.message
                    }
                }, 2.6)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertTrue(refusal?.contains("already promised") == true, refusal ?: "no refusal at all")
        assertTrue(a.system.isHoldingZones(first), "the first crew still got its region")
        assertFalse(a.system.isWaitingForZones(second))
        assertFalse(a.system.isHoldingZones(second))
        // The zone the refused request would have taken second must be untouched: reserving it and
        // then failing would close it for the rest of the run with nothing coming to release it.
        val untouched = a.network.zone("L1.Zone1")!!
        assertNull(untouched.closingFor, "the refused request reserved nothing at all")
        assertTrue(untouched.isAvailable)
    }

    // ---- being answered instead of refused -----------------------------------------------------

    @Test
    fun `tryRequestZones answers null on an overlap, and reserves nothing`() {
        // The refusal above turned into an answer, for the model that cannot design the overlap
        // away: a spill picks its aisle at random, so sooner or later two of them want one zone.
        //
        // The same set-up: at 2.5 the cart is crossing Zone3, so the first crew's request is still
        // draining and both of its zones stay promised to it.
        val m = Model("TryOverlap")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val first = Crew(1)
        val second = Crew(2)
        val answers = mutableListOf<ZoneRequest?>()
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.cart.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.requestZones(first, listOf(a.closed, a.network.zone("L1.Zone4")!!), a.log)
                }, 2.5)
                schedule({ _: KSLEvent<Nothing> ->
                    answers.add(
                        a.system.tryRequestZones(
                            second,
                            listOf(a.network.zone("L1.Zone4")!!, a.network.zone("L1.Zone1")!!),
                            a.log
                        )
                    )
                }, 2.6)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertEquals(listOf<ZoneRequest?>(null), answers, "answered, not refused")
        assertTrue(a.system.isHoldingZones(first), "the first crew still got its region")
        assertFalse(a.system.isWaitingForZones(second))
        val untouched = a.network.zone("L1.Zone1")!!
        assertNull(untouched.closingFor, "and the null answer reserved nothing at all")
        assertTrue(untouched.isAvailable)
    }

    @Test
    fun `a zone another holder merely holds is not an overlap and is waited for`() {
        // The distinction the whole verb turns on, and the one a hand-written guard gets backwards.
        // It is the *promise* that cannot be shared, not the hold: a zone is promised only between
        // the moment it is asked for and the moment it has drained. Asking for a zone somebody has
        // already taken succeeds, and waits.
        val m = Model("HeldNotPromised")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val first = Crew(1)
        val second = Crew(2)
        val secondLog = Log()
        var answeredNull = true
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                // Granted at 0.5 with nothing to drain, so the promise is cleared and Zone3 is
                // simply held from then on.
                schedule({ _: KSLEvent<Nothing> -> a.system.requestZone(first, a.closed, a.log) }, 0.5)
                schedule({ _: KSLEvent<Nothing> ->
                    answeredNull = a.system.tryRequestZones(second, listOf(a.closed), secondLog) == null
                }, 1.0)
                schedule({ _: KSLEvent<Nothing> -> a.system.releaseZones(first) }, 5.0)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertFalse(answeredNull, "a held zone is not an overlap; the request must be accepted")
        assertEquals(listOf(5.0), secondLog.began, "and granted when the first hold ended")
        assertTrue(a.system.isHoldingZones(second))
        assertNull(a.system.allocationFor(first))
    }

    @Test
    fun `tryHoldZonesFor answers null on an overlap too`() {
        val m = Model("TryHoldOverlap")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val first = Crew(1)
        val second = Crew(2)
        val answers = mutableListOf<ZoneRequest?>()
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.cart.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.requestZone(first, a.closed, a.log)
                }, 2.5)
                schedule({ _: KSLEvent<Nothing> ->
                    answers.add(a.system.tryHoldZonesFor(second, listOf(a.closed), 4.0, a.log))
                }, 2.6)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertEquals(listOf<ZoneRequest?>(null), answers)
        assertFalse(a.system.isWaitingForZones(second))
    }

    @Test
    fun `the try form answers null only for an overlap, and still raises on a defect`() {
        // Answering null to a malformed request would hide a defect rather than express a
        // condition, so only the overlap becomes an answer. Everything else still raises.
        val m = Model("TryStillRaises")
        val a = Aisle(m)
        val crew = Crew(1)
        assertFailsWith<IllegalArgumentException> { a.system.tryRequestZones(crew, emptyList(), a.log) }
        assertFailsWith<IllegalArgumentException> {
            a.system.tryRequestZones(crew, listOf(a.closed, a.closed), a.log)
        }
        val elsewhere = GuidedPathNetwork.builder("Elsewhere")
            .link("X", "P", "Q", length = 12.0, zoneLength = 12.0)
            .build()
        assertFailsWith<IllegalArgumentException> {
            a.system.tryRequestZones(crew, elsewhere.zones.take(1), a.log)
        }
        assertFailsWith<IllegalArgumentException> {
            a.system.tryHoldZonesFor(crew, listOf(a.closed), 0.0, a.log)
        }
        // A holder that already has space is a modelling mistake, not a condition of the space.
        a.system.requestZone(crew, a.closed, a.log)
        assertFailsWith<IllegalStateException> {
            a.system.tryRequestZones(crew, listOf(a.network.zone("L1.Zone1")!!), a.log)
        }
    }

    @Test
    fun `firstPromisedZone names the zone that would refuse the request`() {
        val m = Model("FirstPromised")
        val a = Aisle(m)
        a.system.checkInvariants = true
        val first = Crew(1)
        val found = mutableListOf<String?>()
        object : ModelElement(a, "Driver") {
            override fun initialize() {
                schedule({ _: KSLEvent<Nothing> -> a.cart.sendTo("B") }, 0.0)
                schedule({ _: KSLEvent<Nothing> ->
                    found.add(a.system.firstPromisedZone(a.network.link("L1")!!.zones)?.name)
                }, 2.4)
                schedule({ _: KSLEvent<Nothing> ->
                    a.system.requestZone(first, a.closed, a.log)
                }, 2.5)
                schedule({ _: KSLEvent<Nothing> ->
                    found.add(a.system.firstPromisedZone(a.network.link("L1")!!.zones)?.name)
                }, 2.6)
            }
        }
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        assertEquals(listOf(null, "L1.Zone3"), found, "nothing promised at 2.4, Zone3 promised at 2.6")
    }

    @Test
    fun `a zone of another guide path is not this one's to close`() {
        val m = Model("ForeignZone")
        val a = Aisle(m)
        val elsewhere = GuidedPathNetwork.builder("Elsewhere")
            .link("X", "P", "Q", length = 12.0, zoneLength = 12.0)
            .build()
        assertFailsWith<IllegalArgumentException> {
            a.system.requestZone(a.crew, elsewhere.zones.first(), a.log)
        }
    }
}
