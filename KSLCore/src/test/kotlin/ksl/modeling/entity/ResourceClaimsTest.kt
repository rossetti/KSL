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

package ksl.modeling.entity

import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The claims on a resource's units besides being allocated: units promised to a woken request, units promised to a
 *  `FINISH` preemption still collecting its units, and units owed to a capacity decrease waiting under WAIT. A
 *  decrease and a part-filled preemption cannot wait for each other; a `FINISH` preemption ranks its claim against
 *  less urgent preemptors; a schedule item whose turn has not come does not stop a preemption; a moved request's
 *  unit is counted once.
 */
class ResourceClaimsTest {

    private class Shop(parent: ModelElement, capacity: Int) : ProcessModel(parent, "Claims") {
        val r = ResourceWithQ(this, "R", capacity = capacity)
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun at(t: Double, action: () -> Unit) = actor("at$t", 0) { delay(t); action() }

        fun job(tag: String, from: Double, hold: Double, priority: Int = 5, n: Int = 1) = actor(tag, priority) {
            delay(from)
            val a = seize(r, n)
            log.add("$tag got@$time")
            delay(hold, suspensionName = "work")
            release(a)
            log.add("$tag done@$time")
        }

        fun urgent(tag: String, from: Double, hold: Double, priority: Int = 1, n: Int = 1, rule: InterruptRule = InterruptRule.RESUME) =
            actor(tag, priority) {
                delay(from)
                val a = preempt(r, n, rule = rule)
                log.add("$tag got@$time")
                delay(hold, suspensionName = "urgent work")
                release(a)
                log.add("$tag done@$time")
            }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(capacity: Int = 1, length: Double = 100.0, configure: Shop.() -> Unit = {}, setup: Shop.() -> Unit): Shop {
        val model = Model("Claims${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, capacity)
        shop.configure()
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return shop
    }

    private fun Shop.repeatingWaitSchedule(on: Int, onFor: Double, offFor: Double) {
        val s = CapacitySchedule(this, 0.0, autoStartOption = true, repeatable = true)
        s.addItem(capacity = on, duration = onFor)
        s.addItem(capacity = 0, duration = offFor)
        r.useSchedule(s, CapacityChangeRule.WAIT)
    }

    @Test
    @DisplayName("a WAIT decrease and a part-filled FINISH preemption do not wait for each other: the decrease takes the promised unit")
    fun decreaseAndPartFilledReservation() {
        // capacity 2, on for 10 and off for 10, repeating; L holds one unit 5..15; P preempts for 2 under FINISH at
        // 6 (promised the free unit, waits for L's); N is an ordinary later job
        val shop = run(capacity = 2, length = 200.0, configure = { repeatingWaitSchedule(2, 10.0, 10.0) }) {
            job("L", 5.0, 10.0, priority = 9)
            urgent("P", 6.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            job("N", 50.0, 1.0)
        }
        assertTrue(shop.log.any { it.startsWith("P done@") }, "P is served: ${shop.log}")
        assertTrue(shop.log.any { it.startsWith("N done@") }, "N is served: ${shop.log}")
    }

    @Test
    @DisplayName("the same under IGNORE does not stall")
    fun decreaseAndPartFilledReservationUnderIgnore() {
        val shop = run(capacity = 2, length = 200.0, configure = {
            val s = CapacitySchedule(this, 0.0, autoStartOption = true, repeatable = true)
            s.addItem(capacity = 2, duration = 10.0)
            s.addItem(capacity = 0, duration = 10.0)
            r.useSchedule(s, CapacityChangeRule.IGNORE)
        }) {
            job("L", 5.0, 10.0, priority = 9)
            urgent("P", 6.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            job("N", 50.0, 1.0)
        }
        assertTrue(shop.log.any { it.startsWith("P done@") }, "P is served: ${shop.log}")
        assertTrue(shop.log.any { it.startsWith("N done@") }, "N is served: ${shop.log}")
    }

    @Test
    @DisplayName("going off shift under WAIT takes a unit promised to a part-filled FINISH preemption, and the resource reaches capacity 0")
    fun offShiftAndPartFilledReservation() {
        var capacityAt16 = -1
        var stateAt16 = ""
        val shop = run(capacity = 2, length = 100.0, configure = { r.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("L", 5.0, 10.0, priority = 9)
            urgent("P", 6.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            at(10.0) { r.goOffShift() }
            at(16.0) { capacityAt16 = r.capacity; stateAt16 = r.state.name }
            at(50.0) { r.goOnShift() }
        }
        assertEquals(0, capacityAt16, "off shift from 15, when L's unit is released")
        assertTrue(stateAt16.contains("Inactive", ignoreCase = true), "off-shift time is inactive, not idle: $stateAt16")
        assertTrue(shop.log.contains("P got@50.0"), "P collects its units when the shift returns: ${shop.log}")
    }

    @Test
    @DisplayName("under a WAIT schedule, a schedule item whose turn has not come does not stop a preemption")
    fun waitingScheduleItemDoesNotStopPreemption() {
        val shop = run(length = 120.0, configure = { repeatingWaitSchedule(1, 20.0, 20.0) }) {
            job("L1", 15.0, 10.0, priority = 9)     // holds the unit past the shift's end at 20: the cut waits until 25
            job("L2", 50.0, 20.0, priority = 9)     // on the machine from 50, in the on-shift item that runs 45..65
            urgent("P", 62.0, 1.0)                  // capacity is 1, the holder is of lower priority
        }
        assertTrue(shop.log.contains("P got@62.0"), "capacity was 1 and the holder of lower priority: ${shop.log}")
    }

    @Test
    @DisplayName("a FINISH preemption collecting units is not overtaken by a less urgent preemptor arriving later")
    fun finishReservationRanksAgainstALessUrgentPreemptor() {
        // H holds the unit to 10; R, the most urgent, reserves it under FINISH at 1; P, less urgent, preempts under
        // RESUME at 2
        val shop = run(length = 100.0) {
            job("H", 0.0, 10.0, priority = 9)
            urgent("R", 1.0, 1.0, priority = 1, rule = InterruptRule.FINISH)
            urgent("P", 2.0, 20.0, priority = 5, rule = InterruptRule.RESUME)
        }
        val got = shop.log.filter { "got@" in it && !it.startsWith("H") }
        assertEquals(listOf("R got@10.0", "P got@11.0"), got)
    }

    /**
     *  A pool request woken with a unit of R1 is moved, before it resumes, to another pool that also has R1, while a
     *  capacity decrease of R1 under WAIT is waiting for units.
     */
    private class OverlapMove(parent: ModelElement, val move: Boolean) : ProcessModel(parent, "om") {
        val r0 = Resource(this, "R0")
        val r1 = Resource(this, "R1", capacity = 2).also { it.capacityChangeRule = CapacityChangeRule.WAIT }
        val r2 = Resource(this, "R2")
        val p0 = ResourcePoolWithQ(this, listOf(r0, r1), name = "P0")
        val p1 = ResourcePoolWithQ(this, listOf(r1, r2), name = "P1")
        val q = RequestQ(this, "DirectQ")
        val trace = mutableListOf<String>()
        lateinit var waiter: Waiter

        inner class Holder(val r: Resource, val hold: Double, val disturb: Boolean) : Entity() {
            val p: KSLProcess = process {
                val a = seize(r, queue = q); delay(hold); release(a)
                if (disturb) {
                    // in the instant of the release, before the woken waiter resumes
                    r1.changeCapacity(r1.CapacityChangeNotice(0, 10.0))
                    if (move) waiter.moveToP1()
                }
            }
        }

        inner class Waiter : Entity() {
            val p: KSLProcess = process {
                delay(1.0)
                val a = seize(p0)
                delay(1.0); release(a)
            }

            fun moveToP1() = moveRequestToResourcePool(
                p0.myWaitingQ.peekNext() as ResourcePoolRequest, p0.myWaitingQ, p1, p1.defaultResourceSelectionRule, RESUME_PRIORITY
            )
        }

        override fun initialize() {
            activate(Holder(r0, 150.0, false).p)
            activate(Holder(r2, 150.0, false).p)
            activate(Holder(r1, 5.0, true).p)     // releases at 5, then asks for the decrease and moves the waiter
            activate(Holder(r1, 20.0, false).p)   // still busy until 20: the decrease must wait for this unit
            waiter = Waiter()
            activate(waiter.p)
            for (t in listOf(6.0, 19.0, 21.0, 29.0, 31.0)) schedule({ _: ksl.simulation.KSLEvent<Nothing> ->
                trace.add("$time: R1 capacity=${r1.capacity} busy=${r1.numBusy} pending=${r1.isPendingCapacityChange}")
            }, t)
        }
    }

    @Test
    @DisplayName("a woken pool request moved to an overlapping pool has its unit counted once by a waiting decrease")
    fun movedRequestsUnitCountedOnce() {
        val model = Model("Overlap${System.nanoTime()}", autoCSVReports = false)
        val om = OverlapMove(model, move = true)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        model.simulate()
        // the decrease to 0 takes the unit the moved request gives up at 5 (capacity 1), then waits for the unit
        // busy until 20, and lasts its 10 time units from then, to 30
        val expected = listOf(
            "6.0: R1 capacity=1 busy=1 pending=true", "19.0: R1 capacity=1 busy=1 pending=true",
            "21.0: R1 capacity=0 busy=0 pending=true", "29.0: R1 capacity=0 busy=0 pending=true",
            "31.0: R1 capacity=0 busy=0 pending=false"
        )
        assertEquals(expected, om.trace)
    }

    @Test
    @DisplayName("a cut asked for by hand and an off-shift, each waiting behind another change, answer a preemption alike")
    fun manualCutAndOffShiftAgree() {
        // capacity 2, WAIT; L1 and L2 hold a unit each 0..30; at 5 a cut to 1 for 100 is pending (it needs a unit);
        // at 6 a second change, to 0, waits behind it; at 7 P, more urgent than both holders, preempts one unit. The
        // second change cannot start before the first ends, but it is what the resource is heading to either way.
        fun runWith(offShift: Boolean): List<String> = run(capacity = 2, length = 200.0, configure = { r.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("L1", 0.0, 30.0, priority = 9)
            job("L2", 0.0, 30.0, priority = 9)
            actor("FirstCut", 0) { delay(5.0); r.changeCapacity(r.CapacityChangeNotice(1, 100.0)) }
            actor("SecondCut", 0) {
                delay(6.0)
                if (offShift) r.goOffShift() else r.changeCapacity(r.CapacityChangeNotice(0))
                log.add("second@$time offShift=${r.isOffShift}")
            }
            urgent("P", 7.0, 1.0)
        }.log.filter { it.startsWith("P got") || it.startsWith("second") }
        val byHand = runWith(offShift = false)
        val offShift = runWith(offShift = true)
        assertEquals(byHand.filter { it.startsWith("P got") }, offShift.filter { it.startsWith("P got") })
    }

    @Test
    @DisplayName("a FINISH preemption needing more than the capacity the resource is heading to waits again, and others are served")
    fun reservationBeyondTheCapacityGoesBackToWaiting() {
        // capacity 2, WAIT; L1 and L2 hold 0..10; R reserves both under FINISH at 1; at 5 a cut to 1 for good; N
        // seizes one at 20, and P, less urgent than R, preempts one at 25
        val shop = run(capacity = 2, length = 100.0, configure = { r.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("L1", 0.0, 10.0, priority = 9)
            job("L2", 0.0, 10.0, priority = 9)
            urgent("R", 1.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            at(5.0) { r.changeCapacity(r.CapacityChangeNotice(1)) }
            job("N", 20.0, 1.0)
            urgent("P", 25.0, 1.0, priority = 3)
        }
        assertTrue(shop.log.contains("N got@20.0"), "${shop.log}")
        assertTrue(shop.log.any { it.startsWith("P got@") }, "${shop.log}")
        assertTrue(shop.log.none { it.startsWith("R got@") }, "R can never have 2 units of a resource of capacity 1: ${shop.log}")
    }

    @Test
    @DisplayName("units a returned reservation gives back, beyond what the decrease takes, are offered at once")
    fun unitsOfAReturnedReservationAreOffered() {
        // capacity 5; L holds 2 units 0..30; R preempts 4 under FINISH at 1 (promised 3); N seizes 1 at 2; at 5 the
        // capacity is cut to 3, which R can never have 4 of: N takes a unit R gives back, at 5
        for (rule in listOf(CapacityChangeRule.WAIT, CapacityChangeRule.IGNORE)) {
            val shop = run(capacity = 5, length = 100.0, configure = { r.capacityChangeRule = rule }) {
                job("L", 0.0, 30.0, priority = 9, n = 2)
                urgent("R", 1.0, 1.0, n = 4, rule = InterruptRule.FINISH)
                job("N", 2.0, 1.0)
                actor("Cut", 0) { delay(5.0); r.changeCapacity(r.CapacityChangeNotice(3)) }
            }
            assertTrue(shop.log.contains("N got@5.0"), "$rule: ${shop.log}")
        }
    }

    @Test
    @DisplayName("a reservation reopened for more units than the capacity holds none and blocks no one")
    fun reopenedReservationBeyondTheCapacity() {
        // capacity 2, IGNORE; L holds a unit 0..10 and, as it releases, cuts the capacity to 1 for good; R reserved
        // both under FINISH at 1, and its promise breaks at its resume; N seizes one at 20
        val shop = run(capacity = 2, length = 100.0, configure = { r.capacityChangeRule = CapacityChangeRule.IGNORE }) {
            actor("L", 9) {
                val a = seize(r)
                delay(10.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(1))
            }
            urgent("R", 1.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            job("N", 20.0, 1.0)
        }
        assertTrue(shop.log.contains("N got@20.0"), "${shop.log}")
    }

    @Test
    @DisplayName("a reservation that stops collecting as a cut starts waiting gives its units back at once")
    fun reservationStoppedByAWaitingCut() {
        // WAIT, capacity 3: Y holds all 3 to 2, so a cut to 2 for 50 runs 2..52; X holds 1 from 2.5 to 30; R (FINISH,
        // 2 units) at 3 is promised 1; going off shift at 4 waits behind the cut, so the capacity heading to is 0
        // and R no longer collects; N, a plain seize of 1, at 5
        val shop = run(capacity = 3, length = 100.0, configure = { r.capacityChangeRule = CapacityChangeRule.WAIT }) {
            job("Y", 0.0, 2.0, n = 3)
            actor("Cut", 0) { delay(1.0); r.changeCapacity(r.CapacityChangeNotice(2, 50.0)) }
            job("X", 2.5, 27.5, priority = 9)
            urgent("R", 3.0, 1.0, n = 2, rule = InterruptRule.FINISH)
            actor("Off", 0) { delay(4.0); r.goOffShift() }
            job("N", 5.0, 1.0)
        }
        assertTrue(shop.log.contains("N got@5.0"), "${shop.log}")
    }

    @Test
    @DisplayName("under IGNORE, a change to the current capacity that clears a waiting cut lets a held-back preemptor plan again")
    fun ignoreChangeClearingAWaitingCut() {
        // capacity 2, IGNORE; two holders 0..50; a cut to 1 for 10 at 5; a cut to 0 for 20 at 6 waits; P (RESUME) at
        // 7; a change to 1 at 8 clears the waiting cut
        val shop = run(capacity = 2, length = 100.0, configure = { r.capacityChangeRule = CapacityChangeRule.IGNORE }) {
            job("H1", 0.0, 50.0, priority = 9)
            job("H2", 0.0, 50.0, priority = 9)
            actor("Cut1", 0) { delay(5.0); r.changeCapacity(r.CapacityChangeNotice(1, 10.0)) }
            actor("Cut2", 0) { delay(6.0); r.changeCapacity(r.CapacityChangeNotice(0, 20.0)) }
            urgent("P", 7.0, 1.0)
            actor("Restore", 0) { delay(8.0); r.changeCapacity(r.CapacityChangeNotice(1)) }
        }
        assertTrue(shop.log.contains("P got@8.0"), "${shop.log}")
    }

    @Test
    @DisplayName("a preemptor beside units promised to a less urgent request plans again once that request takes them")
    fun preemptorPlansAgainWhenPromisedUnitsAreTaken() {
        // capacity 2; L holds 1 unit 0..10; R (FINISH, less urgent) reserves both at 1; U (RESUME, more urgent than
        // R) wants both at 2: at 10 R takes its units, and U, which outranks it, takes them from it
        val shop = run(capacity = 2, length = 100.0) {
            job("L", 0.0, 10.0, priority = 9)
            urgent("R", 1.0, 5.0, priority = 5, n = 2, rule = InterruptRule.FINISH)
            urgent("U", 2.0, 1.0, priority = 1, n = 2)
        }
        assertTrue(shop.log.contains("U got@10.0"), "${shop.log}")
    }

    @Test
    @DisplayName("under IGNORE, units a reservation gives back count toward a pending cut, so the cut behind it is not late")
    fun givenBackUnitsCountTowardAPendingCut() {
        // capacity 4, IGNORE; L1..L4 hold a unit each, L1 to 2, L2 to 20, L3 to 30, L4 to 90; P (FINISH, 2 units) at
        // 1 is promised L1's unit at 2; a cut to 2 for 100 at 3 is pending; a cut to 1 for 200 at 4 waits behind it,
        // which stops P collecting. Without P the second cut starts at 20.
        fun capacityAt21(withP: Boolean): Int {
            var cap = -1
            run(capacity = 4, length = 100.0, configure = { r.capacityChangeRule = CapacityChangeRule.IGNORE }) {
                job("L1", 0.0, 2.0, priority = 9)
                job("L2", 0.0, 20.0, priority = 9)
                job("L3", 0.0, 30.0, priority = 9)
                job("L4", 0.0, 90.0, priority = 9)
                if (withP) urgent("P", 1.0, 1.0, n = 2, rule = InterruptRule.FINISH)
                actor("Cut1", 0) { delay(3.0); r.changeCapacity(r.CapacityChangeNotice(2, 100.0)) }
                actor("Cut2", 0) { delay(4.0); r.changeCapacity(r.CapacityChangeNotice(1, 200.0)) }
                actor("Probe", 0) { delay(21.0); cap = r.capacity }
            }
            return cap
        }
        assertEquals(capacityAt21(withP = false), capacityAt21(withP = true))
    }

    /** One resource of capacity 2 in a one-member pool; W asks the pool, or the resource, for both units. */
    private class PoolSeize(parent: ModelElement, val throughPool: Boolean) : ProcessModel(parent, "PoolSeize") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val pool = ResourcePoolWithQ(this, listOf(r), name = "Pool")
        val log = mutableListOf<String>()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        override fun initialize() {
            log.clear()
            activate(Actor("L", 9) { val a = seize(r); delay(10.0, delayPriority = 10); release(a, releasePriority = 50) }.p)
            activate(Actor("W", 5) {
                delay(1.0)
                if (throughPool) {
                    val a = seize(pool, 2); log.add("W got@$time"); delay(5.0); release(a)
                } else {
                    val a = seize(r, 2); log.add("W got@$time"); delay(5.0); release(a)
                }
            }.p)
            // L releases at 10 with event priority 10 and W is woken to resume at 50; U arrives between, at 20
            activate(Actor("U", 1) { val a = preempt(r, 2, rule = InterruptRule.RESUME); log.add("U got@$time"); delay(1.0); release(a) }.p, 10.0, priority = 20)
        }
    }

    @Test
    @DisplayName("a preemptor beside units promised through a pool plans again once the pool request takes them")
    fun preemptorPlansAgainAfterAPoolSeize() {
        fun log(throughPool: Boolean): List<String> {
            val model = Model("PoolSeize${System.nanoTime()}", autoCSVReports = false)
            val m = PoolSeize(model, throughPool)
            model.numberOfReplications = 1
            model.lengthOfReplication = 100.0
            model.simulate()
            return m.log
        }
        assertEquals(log(throughPool = false), log(throughPool = true))
    }
}
