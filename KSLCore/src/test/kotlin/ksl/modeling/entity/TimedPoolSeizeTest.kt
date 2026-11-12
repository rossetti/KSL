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

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.entityIdOrNull
import ksl.modeling.spatial.ClosestMovableResourceAllocationRule
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

/**
 *  Seizing from a resource pool or a movable resource pool with a time limit. The request waits in
 *  the pool's queue as a `seize` request does; when the time runs out it leaves without service, no
 *  member is left promised, and nothing is allocated.
 */
class TimedPoolSeizeTest {

    private class PoolModel(parent: ModelElement) : ProcessModel(parent, "timedPool") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val a = ResourceWithQ(this, "A")
        val b = ResourceWithQ(this, "B")
        val pool = ResourcePoolWithQ(this, listOf(a, b), name = "P")
        val c = ResourceWithQ(this, "C")
        val pool2 = ResourcePoolWithQ(this, listOf(c), name = "P2")
        val t1 = MovableResource(this, locA, speed, "T1")
        val t2 = MovableResource(this, locB, speed, "T2")
        val fleet = MovableResourcePoolWithQ(this, listOf(t1, t2), speed, RequestQ(this, "FleetQ"), "Fleet")
        val q: RequestQ get() = pool.myWaitingQ
        val log = mutableListOf<String>()
        val setups = mutableListOf<PoolModel.() -> Unit>()

        inner class Actor(val tag: String, val body: suspend KSLProcessBuilder.(Actor) -> Unit) : Entity(tag) {
            val p: KSLProcess = process("p-$tag") { body(this@Actor) }

            fun moveTo(target: ResourcePoolWithQ) {
                val request = q.toList().first { it.entity === this } as ResourcePoolRequest
                moveRequestToResourcePool(request, q, target, target.defaultResourceSelectionRule, RESUME_PRIORITY)
            }
        }

        fun actor(tag: String, body: suspend KSLProcessBuilder.(Actor) -> Unit): Actor =
            Actor(tag, body).also { activate(it.p) }

        /** Holds [n] units of the pool from time 0 for [hold], then runs [after]. */
        fun holder(hold: Double, n: Int = 2, endPriority: Int = DELAY_PRIORITY, after: () -> Unit = {}): Actor = actor("X") {
            val x = seize(pool, n)
            delay(hold, endPriority)
            release(x)
            after()
        }

        /** Holds the member [r] directly from time 0 for [hold]. */
        fun holdMember(tag: String, r: ResourceWithQ, hold: Double): Actor = actor(tag) {
            val x = seize(r)
            delay(hold)
            release(x)
        }

        fun timed(tag: String, from: Double, timeout: Double, n: Int = 1, timeoutPriority: Int = TIMEOUT_PRIORITY): Actor = actor(tag) {
            delay(from)
            val result = seizeOrTimeout(pool, n, timeout, timeoutPriority = timeoutPriority)
            note(tag, result.timedOut, result.timeWaited, result.valueOrNull()?.allocations?.map { it.resource.name })
            result.valueOrNull()?.let { delay(1.0); release(it) }
        }

        fun untimed(tag: String, from: Double, n: Int = 1): Actor = actor(tag) {
            delay(from)
            val x = seize(pool, n)
            log.add("$tag:served@$time")
            delay(1.0)
            release(x)
        }

        fun note(tag: String, timedOut: Boolean, waited: Double, by: List<String>?) {
            log.add(if (timedOut) "$tag:timedOut waited=$waited@$time" else "$tag:served by $by waited=$waited@$time")
        }

        fun at(t: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY, action: () -> Unit) {
            schedule(EventActionIfc<Nothing> { action() }, t, priority = priority)
        }

        fun state(): String = "waiting=${q.size}, waits=${q.timeInQ.withinReplicationStatistic.count.toInt()}, " +
                "available=${a.numAvailableUnits + b.numAvailableUnits}, promised=${a.myPromisedUnits + b.myPromisedUnits}"

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(sink: AnimationSink? = null, setup: PoolModel.() -> Unit): PoolModel {
        val model = Model("TimedPool${System.nanoTime()}", autoCSVReports = false)
        if (sink != null) model.animationSink = sink
        val m = PoolModel(model)
        m.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("fast path: the pool can allocate, served at once with a wait of zero")
    fun fastPath() {
        val m = run { timed("T", 0.0, timeout = 5.0, n = 2) }
        assertEquals(listOf("T:served by [A, B] waited=0.0@0.0"), m.log)
    }

    @Test
    @DisplayName("served before the deadline")
    fun served() {
        val m = run {
            holder(hold = 3.0)
            timed("T", 1.0, timeout = 10.0)
        }
        assertEquals(listOf("T:served by [A] waited=2.0@3.0"), m.log)
    }

    @Test
    @DisplayName("timed out: the request leaves its queue with its wait recorded, no member left promised; the next release serves the next")
    fun timedOut() {
        var seen = ""
        val m = run {
            holder(hold = 10.0)
            timed("T", 1.0, timeout = 4.0)
            untimed("B", 2.0)
            at(6.0) { seen = state() }
        }
        assertEquals(listOf("T:timedOut waited=4.0@5.0", "B:served@10.0"), m.log)
        assertEquals("waiting=1, waits=2, available=0, promised=0", seen)
    }

    @Test
    @DisplayName("a request for several units times out while some members are free; nothing stays promised")
    fun severalUnitsSomeFree() {
        var seen = ""
        val m = run {
            holdMember("Y", a, hold = 20.0)
            timed("T", 1.0, timeout = 4.0, n = 2)
            at(6.0) { seen = state() }
        }
        assertEquals(listOf("T:timedOut waited=4.0@5.0"), m.log)
        assertEquals("waiting=0, waits=1, available=1, promised=0", seen)
    }

    @Test
    @DisplayName("a release at the deadline instant serves the request at default priorities; an earlier timer wins")
    fun deadlineInstant() {
        val served = run {
            holder(hold = 5.0)
            timed("T", 1.0, timeout = 4.0)
        }
        assertEquals(listOf("T:served by [A] waited=4.0@5.0"), served.log)
        val timedOut = run {
            holder(hold = 5.0)
            timed("T", 1.0, timeout = 4.0, timeoutPriority = -100)
        }
        assertEquals(listOf("T:timedOut waited=4.0@5.0"), timedOut.log)
    }

    @Test
    @DisplayName("the timer claiming at the instant of a release: no member is stranded, the next waiter is served")
    fun timerAtReleaseStrandsNothing() {
        var seen = ""
        val m = run {
            holder(hold = 5.0)
            timed("T", 1.0, timeout = 4.0, timeoutPriority = -100)
            untimed("B", 2.0)
            at(5.5) { seen = state() }
        }
        assertEquals(listOf("T:timedOut waited=4.0@5.0", "B:served@5.0"), m.log)
        assertEquals("waiting=0, waits=3, available=1, promised=0", seen)
    }

    @Test
    @DisplayName("the promised member going away with no other free: the re-wait keeps the deadline; served if it comes back in time")
    fun brokenPromiseOfOneMember() {
        fun scenario(backAt: Double?): List<String> = run {
            holdMember("Y", b, hold = 20.0)
            actor("X") {
                val x = seize(pool, 1)
                delay(5.0)
                release(x)
                a.changeCapacity(a.CapacityChangeNotice(capacity = 0))
            }
            timed("T", 1.0, timeout = 6.0)
            if (backAt != null) at(backAt) { a.changeCapacity(a.CapacityChangeNotice(capacity = 1)) }
        }.log
        assertEquals(listOf("T:timedOut waited=6.0@7.0"), scenario(null))
        assertEquals(listOf("T:served by [A] waited=5.0@6.0"), scenario(6.0))
    }

    @Test
    @DisplayName("termination while waiting: the request leaves with nothing recorded and nothing fires at the deadline")
    fun termination() {
        var seen = ""
        val m = run {
            holder(hold = 10.0)
            val t = timed("T", 1.0, timeout = 4.0)
            untimed("B", 2.0)
            at(3.0) { t.terminateProcess() }
            at(6.0) { seen = state() }
        }
        assertEquals(listOf("B:served@10.0"), m.log)
        assertEquals("waiting=1, waits=1, available=0, promised=0", seen)
    }

    @Test
    @DisplayName("a timed pool request moved to a pool with units free is served there at once")
    fun movedAndServed() {
        val m = run {
            holder(hold = 20.0)
            val t = actor("T") {
                delay(1.0)
                val result = seizeOrTimeout(pool, timeout = 10.0)
                note("T", result.timedOut, result.timeWaited, result.valueOrNull()?.allocations?.map { it.resource.name })
                release(result.value)
                delay(20.0)
                log.add("T:delayed@$time")
            }
            at(3.0) { t.moveTo(pool2) }
        }
        assertEquals(listOf("T:served by [C] waited=2.0@3.0", "T:delayed@23.0"), m.log)
    }

    @Test
    @DisplayName("a member registered with its own queue and the pool's: freed units go to the earlier request; a timed-out one is never chosen")
    fun memberInTwoQueues() {
        val poolFirst = run {
            holdMember("Y", b, hold = 50.0)
            holdMember("X", a, hold = 5.0)
            timed("T", 1.0, timeout = 10.0)
            actor("U") { delay(2.0); val x = seize(a); log.add("U:served@$time"); delay(1.0); release(x) }
        }
        assertEquals(listOf("T:served by [A] waited=4.0@5.0", "U:served@6.0"), poolFirst.log)
        val timedOutFirst = run {
            holdMember("Y", b, hold = 50.0)
            holdMember("X", a, hold = 5.0)
            timed("T", 1.0, timeout = 3.0)
            actor("U") { delay(2.0); val x = seize(a); log.add("U:served@$time"); delay(1.0); release(x) }
        }
        assertEquals(listOf("T:timedOut waited=3.0@4.0", "U:served@5.0"), timedOutFirst.log)
    }

    @Test
    @DisplayName("a member seized directly by one timed request while a timed pool request waits: the earlier request gets it, the other times out")
    fun directAndPoolTimed() {
        val m = run {
            holdMember("Y", b, hold = 50.0)
            holdMember("X", a, hold = 5.0)
            actor("D") {
                delay(1.0)
                val r = seizeOrTimeout(a, timeout = 10.0)
                log.add(if (r.timedOut) "D:timedOut@$time" else "D:served@$time")
                r.valueOrNull()?.let { delay(30.0); release(it) }
            }
            timed("T", 2.0, timeout = 10.0)
        }
        assertEquals(listOf("D:served@5.0", "T:timedOut waited=10.0@12.0"), m.log)
    }

    @Test
    @DisplayName("a movable pool's rules decide for a timed request as for an untimed one: closest when both are free, the freed one when woken")
    fun movablePoolByDistance() {
        fun scenario(timedForm: Boolean, held: Boolean): List<String> = run {
            if (held) actor("X") {
                entity.currentLocation = locA
                val x1 = seize(fleet, requestLocation = locA)
                val x2 = seize(fleet, requestLocation = locA)
                delay(5.0)
                release(x1)
                release(x2)
            }
            actor("T") {
                entity.currentLocation = locB
                delay(1.0)
                val a = if (timedForm) seizeOrTimeout(fleet, timeout = 10.0, requestLocation = locB,
                    resourceAllocationRule = ClosestMovableResourceAllocationRule()).value
                else seize(fleet, requestLocation = locB, resourceAllocationRule = ClosestMovableResourceAllocationRule())
                log.add("T:served by ${a.resource.name}@$time")
                release(a)
            }
        }.log
        assertEquals(listOf("T:served by T2@1.0"), scenario(timedForm = true, held = false), "both free: the closest")
        assertEquals(scenario(timedForm = false, held = false), scenario(timedForm = true, held = false))
        assertEquals(scenario(timedForm = false, held = true), scenario(timedForm = true, held = true), "woken by a release: as untimed")
    }

    @Test
    @DisplayName("a movable pool: served at once, then a second request times out")
    fun movablePoolTimedOut() {
        val m = run {
            actor("A") { entity.currentLocation = locA; val r = seizeOrTimeout(fleet, timeout = 5.0); log.add("A:${r.timedOut}@$time"); delay(10.0); release(r.value) }
            actor("B") { entity.currentLocation = locA; val r = seizeOrTimeout(fleet, timeout = 5.0); log.add("B:${r.timedOut}@$time"); delay(10.0); release(r.value) }
            actor("C") { entity.currentLocation = locA; delay(1.0); val r = seizeOrTimeout(fleet, timeout = 3.0); log.add("C:${r.timedOut}@$time") }
        }
        assertEquals(listOf("A:false@0.0", "B:false@0.0", "C:true@4.0"), m.log)
    }

    private class CollectingSink : AnimationSink {
        val events = mutableListOf<AnimationEvent>()
        override val isActive: Boolean = true
        override fun emit(event: AnimationEvent) { events.add(event) }
    }

    @Test
    @DisplayName("trace: SeizeQueued and SeizeWaiting as for seize; WaitTimedOut once on a timeout")
    fun trace() {
        val sink = CollectingSink()
        lateinit var t: PoolModel.Actor
        run(sink = sink) {
            holder(hold = 10.0)
            t = timed("T", 1.0, timeout = 2.0)
        }
        val seen = sink.events.filter { it.entityIdOrNull == t.id }.mapNotNull {
            when (it) {
                is AnimationEvent.SeizeQueued -> "SeizeQueued(${it.resourceName})@${it.simTime}"
                is AnimationEvent.SeizeWaiting -> "SeizeWaiting@${it.simTime}"
                is AnimationEvent.WaitTimedOut -> "WaitTimedOut@${it.simTime}"
                else -> null
            }
        }
        assertEquals(listOf("SeizeQueued(P)@1.0", "SeizeWaiting@1.0", "WaitTimedOut@3.0"), seen)
    }
}
