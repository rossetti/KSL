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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Closures taking their turn on the same zone, which is opt-in and is not the default.
 *
 *  A zone carries **one promise at a time** unless a request says otherwise, and that default is a
 *  judgement rather than a limitation: two spills in the same aisle are usually *one* spill, and
 *  queueing them would clean it twice, in series, and quietly. So the guide path refuses, or
 *  answers null, and the model says what an overlap means.
 *
 *  [ZoneOverlap.QUEUE] is for the case where taking turns is what the model actually means —
 *  maintenance windows on one leg, work that genuinely repeats. These tests pin the ordering, and
 *  most of them exist for one sentence of the design: **abandoning from the middle of a queue must
 *  be silent, and abandoning the head must promote and then offer.** Get that backwards and a
 *  vehicle is woken for a zone that is still closed, or a promoted closure waits for ever with
 *  everything it asked for already free.
 *
 *  ## The arithmetic
 *
 *  Zones are twelve feet and carts travel twelve feet a minute, so one zone costs one minute.
 */
class ZoneQueueTest {

    private class Crew(id: Int) : ZoneHolderIfc {
        override val name: String = "Crew$id"
        override val awaitedZone: Zone? get() = null
    }

    private class Log : ZoneHoldActionIfc {
        val began = mutableListOf<String>()
        val ended = mutableListOf<String>()
        val beganAt = mutableListOf<Double>()
        override fun holdBegan(allocation: ZoneAllocation) {
            began.add(allocation.holder.name); beganAt.add(allocation.engagedAt)
        }
        override fun holdEnded(allocation: ZoneAllocation) { ended.add(allocation.holder.name) }
    }

    private class Bay(parent: ModelElement) : ModelElement(parent, "Bay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Bay")
            .link("L1", "A", "B", length = 72.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val log = Log()
        var setUp: (Bay) -> Unit = {}
        override fun initialize() {
            log.began.clear(); log.ended.clear(); log.beganAt.clear(); setUp(this)
        }
        fun at(time: Double, action: () -> Unit) =
            schedule({ _: KSLEvent<Nothing> -> action() }, time)
        fun zone(n: String): Zone = network.zone(n)!!
    }

    private fun run(length: Double = 60.0, setUp: (Bay) -> Unit): Bay {
        val m = Model("Queue")
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        val bay = Bay(m)
        bay.setUp = setUp
        m.simulate()
        return bay
    }

    // ---- the default is unchanged --------------------------------------------------------------

    @Test
    fun `without asking, an overlap still raises`() {
        // The default has to stay the noisy one, or the decision the refusal forces is lost.
        // Crew1 is genuinely *promised* Zone2 and still draining behind the blocker's hold, which
        // is what makes Crew2's ask an overlap rather than an ordinary wait.
        val m = Model("Queue-default")
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        val bay = Bay(m)
        bay.setUp = { b ->
            b.at(0.5) { b.system.holdZonesFor(Crew(9), listOf(b.zone("L1.Zone2")), 40.0, b.log) }
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 5.0, b.log) }
            b.at(2.0) { b.system.holdZonesFor(Crew(2), listOf(b.zone("L1.Zone2")), 5.0, b.log) }
        }
        val thrown = assertFailsWith<IllegalArgumentException> { m.simulate() }
        assertTrue(thrown.message!!.contains("one promise at a time"), thrown.message!!)
        assertTrue(thrown.message!!.contains("ZoneOverlap.QUEUE"), "the message should name the way out")
        check(bay.system.name.isNotEmpty())
    }

    @Test
    fun `a queued closure is granted when the one ahead of it gives the zone back`() {
        // Crew9 holds Zone2 from 0.5 to 10.5. Crew1 is promised it at 1.0 and waits; Crew2 queues
        // behind Crew1 at 2.0. The turns then run 10.5 -> 13.5 -> 16.5.
        val bay = run(length = 40.0) { b ->
            b.at(0.5) { b.system.holdZonesFor(Crew(9), listOf(b.zone("L1.Zone2")), 10.0, b.log) }
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 3.0, b.log) }
            b.at(2.0) {
                b.system.holdZonesFor(
                    Crew(2), listOf(b.zone("L1.Zone2")), 3.0, b.log, ZoneOverlap.QUEUE
                )
            }
        }
        assertEquals(listOf("Crew9", "Crew1", "Crew2"), bay.log.began, "in the order they asked")
        assertEquals(listOf(0.5, 10.5, 13.5), bay.log.beganAt, "each begins as the one before ends")
        assertNull(bay.zone("L1.Zone2").holder, "and the zone is free again at 16.5")
    }

    @Test
    fun `three closures on one zone run in the order they asked`() {
        val bay = run { b ->
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 2.0, b.log) }
            b.at(1.1) {
                b.system.holdZonesFor(Crew(2), listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
            b.at(1.2) {
                b.system.holdZonesFor(Crew(3), listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
        }
        assertEquals(listOf("Crew1", "Crew2", "Crew3"), bay.log.began)
        assertEquals(listOf(1.0, 3.0, 5.0), bay.log.beganAt, "each begins as the one before ends")
    }

    @Test
    fun `a queued closure waits for every zone of its set, not only the contended one`() {
        // Crew2 queues behind Crew1 on Zone2 and is the only claim on Zone4 -- but Zone4 is held by
        // Crew8 until 20.0. Being at the head of one zone of a set grants nothing.
        val bay = run(length = 40.0) { b ->
            b.at(0.5) { b.system.holdZonesFor(Crew(9), listOf(b.zone("L1.Zone2")), 5.0, b.log) }
            b.at(0.6) { b.system.holdZonesFor(Crew(8), listOf(b.zone("L1.Zone4")), 19.4, b.log) }
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 2.0, b.log) }
            b.at(1.1) {
                b.system.holdZonesFor(
                    Crew(2), listOf(b.zone("L1.Zone2"), b.zone("L1.Zone4")), 2.0, b.log,
                    ZoneOverlap.QUEUE
                )
            }
        }
        // Crew1 holds Zone2 from 5.5 to 7.5, so Zone2 is Crew2's from 7.5 -- but Zone4 only at 20.0.
        assertTrue("Crew2" in bay.log.began, "Crew2 should have been granted eventually")
        val crew2Began = bay.log.beganAt[bay.log.began.indexOf("Crew2")]
        assertEquals(20.0, crew2Began, 1e-9, "it must wait for the last zone of its set, not the first")
    }

    @Test
    fun `giving up from the middle of a queue changes nothing for anybody else`() {
        // Crew2 is behind Crew1 and Crew3 is behind Crew2. Crew2 gives up while still draining.
        // Nothing about the promise in force has changed, so nothing may be offered -- and Crew3
        // must move up rather than be stranded behind a closure that has gone.
        val crew2 = Crew(2)
        val bay = run { b ->
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 4.0, b.log) }
            b.at(1.1) {
                b.system.holdZonesFor(crew2, listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
            b.at(1.2) {
                b.system.holdZonesFor(Crew(3), listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
            b.at(2.0) { b.system.releaseZones(crew2) }
        }
        assertEquals(listOf("Crew1", "Crew3"), bay.log.began, "Crew2 gave up and never held anything")
        assertEquals(listOf(1.0, 5.0), bay.log.beganAt, "Crew3 takes Crew2's turn, not a later one")
    }

    @Test
    fun `giving up the head promotes the next, which is granted at once when its set is free`() {
        // The case that needs the offer after a promotion. Crew1 is promised Zone2 AND Zone4, and
        // waits only because Crew8 holds Zone4; Zone2 itself is empty. Crew2 queues behind Crew1 on
        // Zone2 alone. When Crew1 gives up, Zone2's head becomes Crew2 -- whose whole set is
        // already free, so it must be granted in that instant rather than left holding a promise
        // nobody will ever act on.
        val crew1 = Crew(1)
        val bay = run(length = 40.0) { b ->
            b.at(0.5) { b.system.holdZonesFor(Crew(8), listOf(b.zone("L1.Zone4")), 30.0, b.log) }
            b.at(1.0) {
                b.system.holdZonesFor(crew1, listOf(b.zone("L1.Zone2"), b.zone("L1.Zone4")), 4.0, b.log)
            }
            b.at(2.0) {
                b.system.holdZonesFor(Crew(2), listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
            b.at(3.0) { b.system.releaseZones(crew1) }
        }
        assertEquals(listOf("Crew8", "Crew2"), bay.log.began, "Crew1 gave up and never held anything")
        assertEquals(3.0, bay.log.beganAt[1], 1e-9, "granted in the instant it was promoted")
    }

    @Test
    fun `a vehicle is not woken over the head of a closure still draining`() {
        // The failure the two-pass shape exists to prevent, seen from the vehicle's side. Crew1
        // holds Zone2 with Crew2 queued behind it; the cart is refused Zone2 and waits. When Crew1
        // releases, the zone belongs to Crew2, not to the cart.
        val bay = run { b ->
            b.at(0.5) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 4.0, b.log) }
            b.at(0.6) {
                b.system.holdZonesFor(Crew(2), listOf(b.zone("L1.Zone2")), 3.0, b.log, ZoneOverlap.QUEUE)
            }
            b.at(1.0) { b.cart.sendTo("B") }
        }
        assertEquals(listOf("Crew1", "Crew2"), bay.log.began)
        assertEquals(listOf(0.5, 4.5), bay.log.beganAt, "the queue comes before the waiting cart")
    }

    // ---- what the model can see ----------------------------------------------------------------

    @Test
    fun `the zone reports who is promised it and who is waiting behind`() {
        var promisedTo: String? = null
        var behind: List<String> = emptyList()
        run(length = 40.0) { b ->
            b.at(0.5) { b.system.holdZonesFor(Crew(9), listOf(b.zone("L1.Zone2")), 20.0, b.log) }
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 5.0, b.log) }
            b.at(2.0) {
                b.system.holdZonesFor(Crew(2), listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
            b.at(3.0) {
                promisedTo = b.zone("L1.Zone2").closingFor?.name
                behind = b.zone("L1.Zone2").queuedClosures.map { it.holder.name }
            }
        }
        // The head is the promise in force and is what firstPromisedZone answers about; the rest
        // are waiting their turn and are invisible to it.
        assertEquals("Crew1", promisedTo)
        assertEquals(listOf("Crew2"), behind)
    }

    @Test
    fun `a queue left standing at the end of a replication is reported and counted`() {
        // Both of them: the one holding and the one behind it. A queued closure that never got its
        // turn is exactly the quiet failure the end-of-replication report exists for.
        val bay = run(length = 3.0) { b ->
            b.at(1.0) { b.system.holdZonesFor(Crew(1), listOf(b.zone("L1.Zone2")), 50.0, b.log) }
            b.at(1.1) {
                b.system.holdZonesFor(Crew(2), listOf(b.zone("L1.Zone2")), 2.0, b.log, ZoneOverlap.QUEUE)
            }
        }
        assertEquals(listOf("Crew1"), bay.log.began)
        assertEquals(1, bay.system.waitingRequests.size)
        assertEquals("Crew2", bay.system.waitingRequests.single().holder.name)
        assertEquals(1.0, bay.system.numRequestsUnfilled.value, 0.0)
    }
}
