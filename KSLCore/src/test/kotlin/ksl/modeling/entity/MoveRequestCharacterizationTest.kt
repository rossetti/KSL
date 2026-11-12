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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Pins what the three `moveRequestTo…` functions do today, and the double resume a move can cause.
 *
 *  A move takes a waiting request out of its queue and puts it in the queue of another resource,
 *  pool or movable pool. When the target has units free and the moved request is alone in the new
 *  queue, the request is promised them and its entity is resumed at once; otherwise it waits there.
 *  The old queue records the wait only when the move asks it to.
 */
class MoveRequestCharacterizationTest {

    enum class Kind { RESOURCE, POOL, MOVABLE_POOL }

    /**
     *  A blocker holds the source from time 0 to 30, and the target from 0 to 8 unless the target is
     *  free. The mover waits on the source; at time 5 its request is moved to the target.
     */
    private class MoveModel(
        parent: ModelElement,
        val kind: Kind,
        val targetFree: Boolean,
        val waitStats: Boolean
    ) : ProcessModel(parent, "move") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val r1 = ResourceWithQ(this, "R1")
        val r2 = ResourceWithQ(this, "R2")
        val ra = Resource(this, "RA")
        val rb = Resource(this, "RB")
        val p1 = ResourcePoolWithQ(this, listOf(ra), name = "P1")
        val p2 = ResourcePoolWithQ(this, listOf(rb), name = "P2")
        val t1 = MovableResource(this, locA, speed, "T1")
        val t2 = MovableResource(this, locA, speed, "T2")
        val f1 = MovableResourcePoolWithQ(this, listOf(t1), speed, RequestQ(this, "F1Q"), "F1")
        val f2 = MovableResourcePoolWithQ(this, listOf(t2), speed, RequestQ(this, "F2Q"), "F2")

        val log = mutableListOf<String>()
        var sourceWaits = Double.NaN
        lateinit var mover: Mover

        val sourceQ: RequestQ
            get() = when (kind) {
                Kind.RESOURCE -> r1.myWaitingQ
                Kind.POOL -> p1.myWaitingQ
                Kind.MOVABLE_POOL -> f1.myWaitingQ
            }

        inner class Blocker : Entity("blocker") {
            val p: KSLProcess = process("block") {
                entity.currentLocation = locA
                val target = if (targetFree) null else when (kind) {
                    Kind.RESOURCE -> seize(r2)
                    Kind.POOL -> null.also { seizeAndHold(p2, 8.0) }
                    Kind.MOVABLE_POOL -> seize(f2, requestLocation = locA)
                }
                val source = when (kind) {
                    Kind.RESOURCE -> seize(r1)
                    Kind.POOL -> null.also { seizeAndHold(p1, 30.0) }
                    Kind.MOVABLE_POOL -> seize(f1, requestLocation = locA)
                }
                if (kind != Kind.POOL) {
                    delay(8.0)
                    if (target != null) release(target)
                    delay(22.0)
                    release(source!!)
                }
            }
        }

        /** Pools hand back a different allocation type; a helper entity holds a pool for a while. */
        fun seizeAndHold(pool: ResourcePoolWithQ, hold: Double) {
            activate(PoolHolder(pool, hold).p)
        }

        inner class PoolHolder(val pool: ResourcePoolWithQ, val hold: Double) : Entity("holder-${pool.name}") {
            val p: KSLProcess = process("hold") {
                val a = seize(pool)
                delay(hold)
                release(a)
            }
        }

        inner class Mover : Entity("mover") {
            val p: KSLProcess = process("wait") {
                entity.currentLocation = locA
                delay(1.0)
                when (kind) {
                    Kind.RESOURCE -> { val a = seize(r1); log.add("served@$time"); release(a) }
                    Kind.POOL -> { val a = seize(p1); log.add("served@$time"); release(a) }
                    Kind.MOVABLE_POOL -> { val a = seize(f1, requestLocation = locA); log.add("served@$time"); release(a) }
                }
            }

            fun moveNow() {
                val request = sourceQ.peekNext()!!
                when (kind) {
                    Kind.RESOURCE -> moveRequestToResource(
                        request as ResourceRequest, sourceQ, r2, RESUME_PRIORITY, waitStats
                    )
                    Kind.POOL -> moveRequestToResourcePool(
                        request as ResourcePoolRequest, sourceQ, p2, p2.defaultResourceSelectionRule, RESUME_PRIORITY, waitStats
                    )
                    Kind.MOVABLE_POOL -> moveRequestToMovableResourcePool(
                        request as MovableResourcePoolRequest, sourceQ, f2, f2.defaultMovableResourceSelectionRule,
                        RESUME_PRIORITY, waitStats
                    )
                }
            }
        }

        override fun initialize() {
            log.clear()
            activate(Blocker().p)
            mover = Mover()
            activate(mover.p)
            schedule(EventActionIfc<Nothing> {
                mover.moveNow()
                sourceWaits = sourceQ.timeInQ.withinReplicationStatistic.count
            }, 5.0)
        }
    }

    private fun run(kind: Kind, targetFree: Boolean, waitStats: Boolean = false): MoveModel {
        val model = Model("Move${System.nanoTime()}", autoCSVReports = false)
        val m = MoveModel(model, kind, targetFree, waitStats)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("moved to a target with units free, the request is served at once, for each kind of move")
    fun movedWithUnitsFree() {
        for (kind in Kind.entries) {
            assertEquals(listOf("served@5.0"), run(kind, targetFree = true).log, "$kind")
        }
    }

    @Test
    @DisplayName("moved to a busy target, the request waits there and is served when the target frees up")
    fun movedWithoutUnitsFree() {
        for (kind in Kind.entries) {
            assertEquals(listOf("served@8.0"), run(kind, targetFree = false).log, "$kind")
        }
    }

    @Test
    @DisplayName("the source queue records the wait only when the move asks it to")
    fun sourceQueueStatistics() {
        for (kind in Kind.entries) {
            // The blocker's own seize at time 0 passes through the source queue and records a wait of zero.
            assertEquals(1.0, run(kind, targetFree = true, waitStats = false).sourceWaits, "$kind, waitStats = false")
            assertEquals(2.0, run(kind, targetFree = true, waitStats = true).sourceWaits, "$kind, waitStats = true")
        }
    }

    /**
     *  A release wakes the waiting request; in the same instant its resource's capacity drops to
     *  zero, the resource goes inactive, and the entity's override moves the woken request to a
     *  resource with a unit free. The move schedules a second resume for an entity that already has
     *  one pending.
     */
    private class WokenMoveModel(parent: ModelElement) : ProcessModel(parent, "wokenMove") {
        val r1 = ResourceWithQ(this, "R1")
        val r2 = ResourceWithQ(this, "R2")
        val log = mutableListOf<String>()

        inner class Holder : Entity("holder") {
            val p: KSLProcess = process("hold") {
                val a = seize(r1)
                delay(5.0)
                release(a)
                r1.changeCapacity(r1.CapacityChangeNotice(capacity = 0))
                log.add("holder done@$time")
            }
        }

        inner class Waiter : Entity("waiter") {
            val p: KSLProcess = process("wait") {
                yield()
                val a = seize(r1)
                log.add("served by ${a.resource.name}@$time")
            }

            override fun resourceBecameInactiveWhileWaitingInQueueWithSeizeRequest(
                queue: RequestQ, resource: Resource, request: Request
            ) {
                log.add("moving, resumePending=${request.resumePending}@$time")
                moveRequestToResource(request as ResourceRequest, queue, r2, RESUME_PRIORITY)
            }
        }

        override fun initialize() {
            log.clear()
            activate(Holder().p)
            activate(Waiter().p)
        }
    }

    @Test
    @DisplayName("moving a woken request schedules a second resume, which the double-resume guard refuses")
    fun movingAWokenRequest() {
        val model = Model("WokenMove${System.nanoTime()}", autoCSVReports = false)
        val m = WokenMoveModel(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        val e = assertFailsWith<IllegalStateException> { model.simulate() }
        val message = generateSequence<Throwable>(e) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        assertTrue(message.contains("already has a pending resume"), message)
        assertEquals(listOf("moving, resumePending=true@5.0"), m.log)
    }
}
