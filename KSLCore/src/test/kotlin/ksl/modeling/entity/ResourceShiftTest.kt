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

import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.spatial.MovableResourcePoolWithQ
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
 *  Shifts on every resource: `goOffShift` drives the capacity to zero so new requests wait, work
 *  already allocated finishes under the capacity change rule, and `goOnShift` restores the
 *  replication's initial capacity and serves the waiters. Every replication starts on shift.
 */
class ResourceShiftTest {

    private class ShiftModel(parent: ModelElement, capacity: Int) : ProcessModel(parent, "shifts") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")

        init {
            dm.addDistance(locA, dm.Location("B"), 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val r = ResourceWithQ(this, "R", capacity)
        val a = ResourceWithQ(this, "A")
        val b = ResourceWithQ(this, "B")
        val pool = ResourcePoolWithQ(this, listOf(a, b), name = "P")
        val t1 = MovableResource(this, locA, speed, "T1")
        val t2 = MovableResource(this, locA, speed, "T2")
        val fleet = MovableResourcePoolWithQ(this, listOf(t1, t2), speed, RequestQ(this, "FleetQ"), "Fleet")
        val log = mutableListOf<String>()
        val setups = mutableListOf<ShiftModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.() -> Unit) = Actor(tag, body).also { activate(it.p) }

        /** Arrives at [from], seizes R for [hold], and logs when it is served and done. */
        fun customer(tag: String, from: Double, hold: Double) = actor(tag) {
            delay(from)
            val x = seize(r)
            log.add("$tag:served@$time")
            delay(hold)
            release(x)
        }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        fun state(res: Resource = r) = "off=${res.isOffShift} cap=${res.capacity}"

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(capacity: Int = 1, reps: Int = 1, setup: ShiftModel.() -> Unit): ShiftModel {
        val model = Model("ResourceShift${System.nanoTime()}", autoCSVReports = false)
        val m = ShiftModel(model, capacity)
        m.setups.add(setup)
        model.numberOfReplications = reps
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("off shift under IGNORE: the busy unit finishes, new requests wait, and are served at goOnShift")
    fun offShiftIgnore() {
        val seen = mutableListOf<String>()
        val m = run {
            customer("C1", 0.0, hold = 5.0)
            customer("C2", 1.0, hold = 1.0)
            at(2.0) { r.goOffShift(); seen.add("at 2 " + state()) }
            at(10.0) { seen.add("at 10 " + state() + " waiting=${r.myWaitingQ.size}"); r.goOnShift() }
        }
        assertEquals(listOf("C1:served@0.0", "C2:served@10.0"), m.log)
        assertEquals(listOf("at 2 off=true cap=0", "at 10 off=true cap=0 waiting=1"), seen)
    }

    @Test
    @DisplayName("off shift under WAIT, back on before the release: the busy unit's customer is unaffected, the waiter served at the release")
    fun offShiftWait() {
        val m = run {
            r.capacityChangeRule = CapacityChangeRule.WAIT
            customer("C1", 0.0, hold = 5.0)
            customer("C2", 1.0, hold = 1.0)
            at(2.0) { r.goOffShift() }
            at(3.0) { r.goOnShift() }
        }
        assertEquals(listOf("C1:served@0.0", "C2:served@5.0"), m.log)
    }

    @Test
    @DisplayName("a replication that ends off shift is followed by one that starts on shift at the initial capacity")
    fun startsOnShift() {
        val seen = mutableListOf<String>()
        run(capacity = 2, reps = 3) {
            at(1.0) { seen.add(state()) }
            at(5.0) { r.goOffShift() }
        }
        assertEquals(List(3) { "off=false cap=2" }, seen)
    }

    @Test
    @DisplayName("goOnShift restores the initial capacity set for that replication, also when an experiment changes it between runs")
    fun restoresReplicationInitialCapacity() {
        val model = Model("Restore${System.nanoTime()}", autoCSVReports = false)
        val m = ShiftModel(model, 2)
        val seen = mutableListOf<String>()
        m.setups.add {
            at(1.0) { r.goOffShift() }
            at(2.0) { r.goOnShift(); seen.add(state()) }
        }
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        m.r.initialCapacity = 3
        model.simulate()
        assertEquals(listOf("off=false cap=2", "off=false cap=3"), seen)
    }

    @Test
    @DisplayName("capacity changed directly while off shift: goOnShift still restores the replication's initial capacity")
    fun changeCapacityWhileOff() {
        val seen = mutableListOf<String>()
        run(capacity = 2) {
            at(1.0) { r.goOffShift() }
            at(2.0) { r.changeCapacity(r.CapacityChangeNotice(capacity = 5)) }
            at(3.0) { seen.add(state()); r.goOnShift(); seen.add(state()) }
        }
        assertEquals(listOf("off=true cap=5", "off=false cap=2"), seen)
    }

    @Test
    @DisplayName("calling goOffShift or goOnShift twice in a row does nothing the second time")
    fun repeatedCalls() {
        val seen = mutableListOf<String>()
        run(capacity = 2) {
            at(0.5) { r.goOnShift(); seen.add("on while on " + state()) }
            at(1.0) { r.goOffShift(); r.goOffShift(); seen.add("off twice " + state()) }
            at(2.0) { r.goOnShift(); r.goOnShift(); seen.add("on twice " + state()) }
        }
        assertEquals(listOf("on while on off=false cap=2", "off twice off=true cap=0", "on twice off=false cap=2"), seen)
    }

    @Test
    @DisplayName("a resource whose initial capacity is 0 cannot go off shift; the message says why")
    fun zeroInitialCapacity() {
        // a resource is constructed with capacity 1 or more; its initial capacity can be set to 0 afterwards
        var message: String? = null
        val model = Model("ZeroCapacity${System.nanoTime()}", autoCSVReports = false)
        val m = ShiftModel(model, 1)
        m.r.initialCapacity = 0
        m.setups.add { at(1.0) { message = runCatching { r.goOffShift() }.exceptionOrNull()?.message } }
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        val msg = assertNotNull(message)
        assertTrue(msg.contains("on-shift capacity") && msg.contains("must be > 0"), msg)
    }

    @Test
    @DisplayName("a pool member off shift: the pool's waiters go to the other member, and to it when it is back")
    fun poolMemberOffShift() {
        val m = run {
            at(0.5) { a.goOffShift() }
            for (i in 1..3) actor("P$i") {
                delay(1.0 * i)
                val x = seize(pool)
                log.add("P$i:${x.allocations.single().resource.name}@$time")
                delay(10.0)
                release(x)
            }
            at(5.0) { a.goOnShift() }
        }
        assertEquals(listOf("P1:B@1.0", "P2:A@5.0", "P3:B@11.0"), m.log)
    }

    @Test
    @DisplayName("a movable pool member off shift: the pool's waiters go to the other member, and to it when it is back")
    fun movablePoolMemberOffShift() {
        val m = run {
            at(0.5) { t1.goOffShift() }
            for (i in 1..3) actor("F$i") {
                entity.currentLocation = locA
                delay(1.0 * i)
                val x = seize(fleet, requestLocation = locA)
                log.add("F$i:${x.resource.name}@$time")
                delay(10.0)
                release(x)
            }
            at(5.0) { t1.goOnShift() }
        }
        assertEquals(listOf("F1:T2@1.0", "F2:T1@5.0", "F3:T2@11.0"), m.log)
    }

    @Test
    @DisplayName("a resource with a capacity schedule: goOffShift is refused, as for any capacity change not from the schedule")
    fun withCapacitySchedule() {
        var message: String? = null
        val model = Model("Scheduled${System.nanoTime()}", autoCSVReports = false)
        val m = ShiftModel(model, 1)
        val schedule = CapacitySchedule(m, 0.0, repeatable = true, name = "S")
        schedule.addItem(capacity = 1, duration = 50.0)
        m.r.useSchedule(schedule, CapacityChangeRule.IGNORE)
        m.setups.add { at(1.0) { message = runCatching { r.goOffShift() }.exceptionOrNull()?.message } }
        model.numberOfReplications = 1
        model.lengthOfReplication = 10.0
        model.simulate()
        val msg = assertNotNull(message)
        assertTrue(msg.contains("did not come from the attached schedule"), msg)
    }
}
