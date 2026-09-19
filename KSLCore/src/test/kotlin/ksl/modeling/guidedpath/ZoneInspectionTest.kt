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
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  What a model can find out for itself before it asks for space, and what it gets back when it has.
 *
 *  A subsystem that only reports trouble afterwards leaves the modeller with a diagnosis and no
 *  decision. These are the questions a model can ask *first*, so that closing an aisle that cannot
 *  drain is a choice rather than an accident:
 *
 *  - **"Would this zone refuse me?"** -- [Zone.refusalFor], which takes the claimant because the
 *    answer depends on who is asking. A reserved zone refuses a stranger and admits both the holder
 *    it was promised to and a vehicle escaping an older reservation.
 *  - **"Is any of this promised to somebody else?"** -- [GuidedPathSpace.firstPromisedZone].
 *  - **"Is anything parked in it?"** -- [GuidedPathSpace.firstZoneHeldByStationaryVehicle], kept a
 *    separate question from the one above on purpose: a promise refuses at once and loudly, while
 *    space a parked vehicle occupies accepts the request and never grants it.
 *
 *  ## The arithmetic
 *
 *  Zones are twelve feet and carts travel twelve feet a minute, so one zone costs one minute.
 *  The link is four zones, so a cart sent A to B arrives at 4.0 and then stands on junction B.
 */
class ZoneInspectionTest {

    private class Crew(id: Int) : ZoneHolderIfc {
        override val name: String = "Crew$id"
        override val awaitedZone: Zone? get() = null
    }

    private class Log : ZoneHoldActionIfc {
        var last: ZoneAllocation? = null
        val began = mutableListOf<String>()
        override fun holdBegan(allocation: ZoneAllocation) {
            last = allocation; began.add(allocation.holder.name)
        }
        override fun holdEnded(allocation: ZoneAllocation) {}
    }

    private class Bay(parent: ModelElement) : ModelElement(parent, "Bay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Bay")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val log = Log()
        var setUp: (Bay) -> Unit = {}
        override fun initialize() { log.began.clear(); log.last = null; setUp(this) }
        fun at(time: Double, action: () -> Unit) =
            schedule({ _: KSLEvent<Nothing> -> action() }, time)
        fun zone(n: String): Zone = network.zone(n)!!
    }

    private fun run(length: Double = 60.0, setUp: (Bay) -> Unit): Bay {
        val m = Model("Inspect")
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        val bay = Bay(m)
        bay.setUp = setUp
        m.simulate()
        return bay
    }

    // ---- refusalFor: the three causes, in the order a claim tests them --------------------------

    @Test
    fun `a free zone refuses nobody`() {
        val seen = mutableListOf<ZoneRefusal?>()
        run { b -> b.at(1.0) { seen.add(b.zone("L1.Zone3").refusalFor(Crew(1))) } }
        assertEquals(1, seen.size, "the probe should have run once")
        assertNull(seen.single(), "a free zone refuses nobody")
    }

    @Test
    fun `a zone something stands in refuses as HELD`() {
        val seen = mutableListOf<ZoneRefusal?>()
        run { b ->
            b.at(0.0) { b.cart.sendTo("B") }
            // At 2.5 the cart is crossing Zone3.
            b.at(2.5) { seen.add(b.zone("L1.Zone3").refusalFor(Crew(1))) }
        }
        assertEquals(ZoneRefusal.HELD, seen.single())
    }

    @Test
    fun `a reserved zone refuses a stranger and admits the holder it was promised to`() {
        // The reason refusalFor takes a claimant at all. Crew2 asks for Zone3 and Zone4 while the
        // cart is crossing Zone3, so the closure is pending and Zone4 is free-but-reserved. The
        // same zone, in the same instant, gives two different answers.
        var toStranger: ZoneRefusal? = null
        var toPromisee: ZoneRefusal? = null
        val crew = Crew(2)
        run { b ->
            b.at(0.0) { b.cart.sendTo("B") }
            b.at(2.5) {
                b.system.requestZones(crew, listOf(b.zone("L1.Zone3"), b.zone("L1.Zone4")), b.log)
            }
            b.at(2.6) {
                val zone4 = b.zone("L1.Zone4")
                check(zone4.holder == null) { "Zone4 should be free, which is the point" }
                toStranger = zone4.refusalFor(Crew(99))
                toPromisee = zone4.refusalFor(crew)
            }
        }
        assertEquals(ZoneRefusal.RESERVED, toStranger, "a stranger must be refused")
        assertNull(toPromisee, "the holder it is promised to must not be")
    }

    @Test
    fun `what refusalFor answers is what the request then does`() {
        // The check is only worth having if it predicts the claim. Two runs rather than one, so
        // that neither probe disturbs the other: a crew left holding a zone in the first would
        // block the cart's journey in the second.
        var predictedFree: ZoneRefusal? = ZoneRefusal.HELD
        var grantedAtOnce = false
        run { b ->
            b.at(1.0) {
                predictedFree = b.zone("L1.Zone1").refusalFor(Crew(3))
                grantedAtOnce =
                    b.system.requestZones(Crew(3), listOf(b.zone("L1.Zone1")), b.log).isGranted
            }
        }
        assertNull(predictedFree, "an empty zone should be predicted claimable")
        assertTrue(grantedAtOnce, "and a zone predicted claimable must be granted at once")

        var predictedHeld: ZoneRefusal? = null
        var heldGrantedAtOnce = true
        run { b ->
            // Four zones at a minute each: the cart reaches B at 4.0 and stands there.
            b.at(0.0) { b.cart.sendTo("B") }
            b.at(4.5) {
                predictedHeld = b.zone("B").refusalFor(Crew(4))
                heldGrantedAtOnce =
                    b.system.requestZones(Crew(4), listOf(b.zone("B")), b.log).isGranted
            }
        }
        assertEquals(ZoneRefusal.HELD, predictedHeld, "the parked cart should be seen")
        assertFalse(heldGrantedAtOnce, "and a zone predicted held must not be granted")
    }

    // ---- is anything parked in it? --------------------------------------------------------------

    @Test
    fun `a moving vehicle holds a zone that will drain, a parked one does not`() {
        var whileMoving: Zone? = null
        var afterParking: Zone? = null
        val bay = run { b ->
            b.at(0.0) { b.cart.sendTo("B") }
            b.at(2.5) { whileMoving = b.system.firstZoneHeldByStationaryVehicle(b.network.link("L1")!!.zones) }
            b.at(5.0) { afterParking = b.system.firstZoneHeldByStationaryVehicle(listOf(b.zone("B"))) }
        }
        assertNull(whileMoving, "a zone a moving cart is crossing drains on its own")
        assertEquals("B", afterParking?.name, "a parked cart's zone does not")
        check(bay.system.name.isNotEmpty())
    }

    @Test
    fun `an untimed hold is not called hopeless, timed or not`() {
        // The narrowness of this predicate, asserted so it cannot creep back. An earlier version
        // called an untimed hold indefinite, reasoning that a timed hold puts its release on the
        // calendar and an untimed one does not. GuidePathDisturbancesExample disproved it: a spill
        // entity holding space through trySeizeZones releases when its process resumes, which is as
        // certain as a clock and simply invisible from in here. That version raised 139 warnings in
        // a model with nothing wrong with it. A hold nobody will end and a hold whose owner is
        // mid-process look identical from the guide path, so it does not guess between them.
        var afterUntimed: Zone? = null
        var afterTimed: Zone? = null
        run { b ->
            b.at(1.0) { b.system.requestZones(Crew(5), listOf(b.zone("L1.Zone1")), b.log) }
            b.at(1.5) {
                afterUntimed = b.system.firstZoneHeldByStationaryVehicle(listOf(b.zone("L1.Zone1")))
            }
            b.at(2.0) { b.system.holdZonesFor(Crew(6), listOf(b.zone("L1.Zone3")), 10.0, b.log) }
            b.at(2.5) {
                afterTimed = b.system.firstZoneHeldByStationaryVehicle(listOf(b.zone("L1.Zone3")))
            }
        }
        assertNull(afterUntimed, "an untimed hold is not evidence that nothing will release it")
        assertNull(afterTimed, "and neither is a timed one")
    }

    @Test
    fun `asking over a parked vehicle does not warn, and the run says so only at the end`() {
        // A deliberate decision, recorded here because the obvious alternative was built and
        // rejected. Warning at the moment of asking looks helpful and is not: the guide path has no
        // evidence at that instant, only a reading that a vehicle happens to be still. The
        // end-of-replication report has the evidence -- the request really never was granted -- and
        // is accurate because of it. A warning that cries wolf at ask time would cost the accurate
        // one its readers, so the model asks and decides, and the library reports what happened.
        val logger = org.slf4j.LoggerFactory.getLogger(GuidedPathSpace::class.java)
                as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val seen = mutableListOf<String?>()
        try {
            run { b ->
                b.at(0.0) { b.cart.sendTo("B") }
                b.at(6.0) {
                    // What the model can find out for itself, which is the whole point.
                    seen.add(b.system.firstZoneHeldByStationaryVehicle(listOf(b.zone("B")))?.name)
                    b.system.requestZones(Crew(7), listOf(b.zone("B")), b.log)
                }
            }
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        assertEquals("B", seen.single(), "the model could have known before it asked")
        val warnings = appender.list
            .filter { it.level == ch.qos.logback.classic.Level.WARN }
            .map { it.formattedMessage }
        assertTrue(warnings.none { it.contains("has asked for") }, "no warning at ask time: $warnings")
        // And the accurate one, at the end, where the evidence is.
        assertTrue(
            warnings.any { it.contains("request(s) for space were still waiting") },
            "the end-of-replication report must still name it: $warnings"
        )
    }

    // ---- a holder is an identity, not a value ---------------------------------------------------

    /** The first idiom a Kotlin modeller reaches for. Two crews with the same id compare equal. */
    private data class ValueCrew(val id: Int) : ZoneHolderIfc {
        override val name: String get() = "ValueCrew$id"
        override val awaitedZone: Zone? get() = null
    }

    @Test
    fun `two holders that compare equal are still two holders`() {
        // Before the maps were keyed by identity this raised "already holds space ... give it back
        // before asking for more" -- a true statement about the map and a false one about the
        // model, which sent its reader looking for a release that was not missing. A holder is
        // whatever the model makes, and what makes two of them the same is being the same object.
        val first = ValueCrew(1)
        val second = ValueCrew(1)
        check(first == second) { "the premise of this test is that they compare equal" }
        check(first !== second) { "and that they are nonetheless two crews" }

        val bay = run { b ->
            b.at(1.0) { b.system.holdZonesFor(first, listOf(b.zone("L1.Zone1")), 100.0, b.log) }
            b.at(2.0) { b.system.holdZonesFor(second, listOf(b.zone("L1.Zone3")), 100.0, b.log) }
        }
        assertEquals(listOf("ValueCrew1", "ValueCrew1"), bay.log.began, "both should have begun")
        assertTrue(bay.system.isHoldingZones(first))
        assertTrue(bay.system.isHoldingZones(second))
        assertSame(first, bay.zone("L1.Zone1").holder, "each holds its own zone")
        assertSame(second, bay.zone("L1.Zone3").holder)
    }

    @Test
    fun `releasing one equal holder leaves the other holding`() {
        // The consequence that matters. Under equality keying this released the wrong crew's space.
        val first = ValueCrew(2)
        val second = ValueCrew(2)
        val bay = run { b ->
            b.at(1.0) { b.system.holdZonesFor(first, listOf(b.zone("L1.Zone1")), 100.0, b.log) }
            b.at(2.0) { b.system.holdZonesFor(second, listOf(b.zone("L1.Zone3")), 100.0, b.log) }
            b.at(3.0) { b.system.releaseZones(first) }
        }
        assertNull(bay.zone("L1.Zone1").holder, "the one released must be free")
        assertSame(second, bay.zone("L1.Zone3").holder, "the other must not have been touched")
        assertFalse(bay.system.isHoldingZones(first))
        assertTrue(bay.system.isHoldingZones(second))
    }

    // ---- releasing through the allocation -------------------------------------------------------

    @Test
    fun `an allocation gives back exactly its own space`() {
        val bay = run { b ->
            b.at(1.0) { b.system.holdZonesFor(Crew(9), listOf(b.zone("L1.Zone2")), 100.0, b.log) }
            b.at(5.0) { b.system.releaseZones(b.log.last!!) }
        }
        assertEquals(listOf("Crew9"), bay.log.began)
        assertNull(bay.zone("L1.Zone2").holder, "the zone must have gone back at 5.0")
    }

    @Test
    fun `releasing an allocation twice raises instead of passing silently`() {
        // The whole reason this form exists. The holder-keyed form forgives a second release, which
        // is what a process cleanup needs and what makes a slip invisible.
        val m = Model("Inspect-double")
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        val bay = Bay(m)
        bay.setUp = { b ->
            b.at(1.0) { b.system.holdZonesFor(Crew(10), listOf(b.zone("L1.Zone2")), 100.0, b.log) }
            b.at(5.0) { b.system.releaseZones(b.log.last!!) }
            b.at(6.0) { b.system.releaseZones(b.log.last!!) }
        }
        val thrown = assertFailsWith<IllegalStateException> { m.simulate() }
        assertTrue(thrown.message!!.contains("Crew10"), thrown.message!!)
        assertTrue(thrown.message!!.contains("already released at 5.0"), thrown.message!!)
    }

    @Test
    fun `releasing a superseded allocation names what the holder actually holds now`() {
        val m = Model("Inspect-stale")
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        val crew = Crew(11)
        val bay = Bay(m)
        var stale: ZoneAllocation? = null
        bay.setUp = { b ->
            b.at(1.0) { b.system.holdZonesFor(crew, listOf(b.zone("L1.Zone1")), 100.0, b.log) }
            b.at(2.0) { stale = b.log.last; b.system.releaseZones(crew) }
            b.at(3.0) { b.system.holdZonesFor(crew, listOf(b.zone("L1.Zone3")), 100.0, b.log) }
            b.at(4.0) { b.system.releaseZones(stale!!) }
        }
        val thrown = assertFailsWith<IllegalStateException> { m.simulate() }
        assertTrue(thrown.message!!.contains("not its current grant"), thrown.message!!)
        assertTrue(thrown.message!!.contains("L1.Zone3"), thrown.message!!)
        assertTrue(thrown.message!!.contains("L1.Zone1"), thrown.message!!)
    }
}
