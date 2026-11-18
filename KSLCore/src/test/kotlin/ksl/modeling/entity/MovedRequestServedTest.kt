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

/**
 *  A request moved to another resource or pool, and later served there, leaves the queue it is in:
 *  the new one. Its wait is recorded there, and the units it was promised stay with it, so a request
 *  waiting behind it in the new queue is served after it, not handed its units.
 */
class MovedRequestServedTest {

    enum class Kind { RESOURCE, POOL, MOVABLE_POOL }

    /**
     *  The source is held from 0 to 30 and the target from 0 to 8. The mover waits on the source from
     *  time 1 and is moved, unwoken, to the target at 5, where it waits first in line. Another entity
     *  joins the target's queue at 6. The target's release at 8 serves the mover, who holds it for 1;
     *  the other entity is served when the mover releases.
     */
    private class MovedModel(parent: ModelElement, val kind: Kind) : ProcessModel(parent, "moved") {
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
        val p1 = ResourcePoolWithQ(this, listOf(Resource(this, "RA")), name = "P1")
        val p2 = ResourcePoolWithQ(this, listOf(Resource(this, "RB")), name = "P2")
        val f1 = MovableResourcePoolWithQ(this, listOf(MovableResource(this, locA, speed, "T1")), speed, RequestQ(this, "F1Q"), "F1")
        val f2 = MovableResourcePoolWithQ(this, listOf(MovableResource(this, locA, speed, "T2")), speed, RequestQ(this, "F2Q"), "F2")

        val log = mutableListOf<String>()
        lateinit var mover: User

        val sourceQ: RequestQ
            get() = when (kind) {
                Kind.RESOURCE -> r1.myWaitingQ
                Kind.POOL -> p1.myWaitingQ
                Kind.MOVABLE_POOL -> f1.myWaitingQ
            }

        val targetQ: RequestQ
            get() = when (kind) {
                Kind.RESOURCE -> r2.myWaitingQ
                Kind.POOL -> p2.myWaitingQ
                Kind.MOVABLE_POOL -> f2.myWaitingQ
            }

        /** Arrives at [arrive], seizes the source or the target, logs, holds for [hold], releases. */
        inner class User(val tag: String, val target: Boolean, val arrive: Double, val hold: Double) : Entity(tag) {
            val p: KSLProcess = process("use") {
                entity.currentLocation = locA
                delay(arrive)
                when (kind) {
                    Kind.RESOURCE -> { val a = seize(if (target) r2 else r1); served(); delay(hold); release(a) }
                    Kind.POOL -> { val a = seize(if (target) p2 else p1); served(); delay(hold); release(a) }
                    Kind.MOVABLE_POOL -> { val a = seize(if (target) f2 else f1, requestLocation = locA); served(); delay(hold); release(a) }
                }
            }

            private fun served() {
                log.add("$tag@$time")
            }

            fun moveToTarget() {
                val request = sourceQ.peekNext()!!
                when (kind) {
                    Kind.RESOURCE -> moveRequestToResource(request as ResourceRequest, sourceQ, r2, RESUME_PRIORITY)
                    Kind.POOL -> moveRequestToResourcePool(
                        request as ResourcePoolRequest, sourceQ, p2, p2.defaultResourceSelectionRule, RESUME_PRIORITY
                    )
                    Kind.MOVABLE_POOL -> moveRequestToMovableResourcePool(
                        request as MovableResourcePoolRequest, sourceQ, f2, f2.defaultMovableResourceSelectionRule, RESUME_PRIORITY
                    )
                }
            }
        }

        var targetWaits = Double.NaN

        override fun initialize() {
            log.clear()
            activate(User("sourceHolder", target = false, arrive = 0.0, hold = 30.0).p)
            activate(User("targetHolder", target = true, arrive = 0.0, hold = 8.0).p)
            mover = User("mover", target = false, arrive = 1.0, hold = 1.0)
            activate(mover.p)
            activate(User("other", target = true, arrive = 6.0, hold = 1.0).p)
            schedule(EventActionIfc<Nothing> { mover.moveToTarget() }, 5.0)
            schedule(EventActionIfc<Nothing> { targetWaits = targetQ.timeInQ.withinReplicationStatistic.count }, 9.5)
        }
    }

    private fun run(kind: Kind): MovedModel {
        val model = Model("Moved${System.nanoTime()}", autoCSVReports = false)
        val m = MovedModel(model, kind)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m
    }

    @Test
    @DisplayName("a moved request served from its new queue keeps its units; the request behind it is served next")
    fun movedRequestServedThenTheOther() {
        for (kind in Kind.entries) {
            val m = run(kind)
            assertEquals(listOf("sourceHolder@0.0", "targetHolder@0.0", "mover@8.0", "other@9.0"), m.log, "$kind")
            // the target holder's zero wait, the mover's wait in the target queue, and the other's wait
            assertEquals(3.0, m.targetWaits, "$kind: the mover's wait is recorded in the queue it was served from")
        }
    }

    enum class Beside { TWO_UNIT_WAITER, WOKEN_WAITER }

    /**
     *  The mover waits on a source held for good, and is moved at [moveAt] into a target queue that is not
     *  empty, beside a free unit. With [Beside.TWO_UNIT_WAITER], one of the target's two units is held until
     *  100 and a request for two waits from 1: the free unit cannot serve it, so the mover takes it. With
     *  [Beside.WOKEN_WAITER], the target's units are held until 8 and a request for one waits from 6; the
     *  releases at 8 wake it, and the move comes before it resumes, at an earlier event priority: a free unit is
     *  left for the mover only when the target has more units than the woken request takes.
     */
    private class BesideModel(parent: ModelElement, val kind: Kind, val beside: Beside, val units: Int) : ProcessModel(parent, "beside") {
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
        val r2 = ResourceWithQ(this, "R2", capacity = units)
        val p1 = ResourcePoolWithQ(this, listOf(Resource(this, "RA")), name = "P1")
        val p2 = ResourcePoolWithQ(this, (1..units).map { Resource(this, "RB$it") }, name = "P2")
        val f1 = MovableResourcePoolWithQ(this, listOf(MovableResource(this, locA, speed, "T1")), speed, RequestQ(this, "F1Q"), "F1")
        val f2 = MovableResourcePoolWithQ(
            this, (1..units).map { MovableResource(this, locA, speed, "TB$it") }, speed, RequestQ(this, "F2Q"), "F2"
        )

        val log = mutableListOf<String>()
        lateinit var mover: User

        val sourceQ: RequestQ
            get() = when (kind) {
                Kind.RESOURCE -> r1.myWaitingQ
                Kind.POOL -> p1.myWaitingQ
                Kind.MOVABLE_POOL -> f1.myWaitingQ
            }

        /** Seizes [amount] of the source or the target at [arrive], logs, holds for [hold] (its end at [endPriority]). */
        inner class User(
            val tag: String, val target: Boolean, val arrive: Double, val hold: Double, val amount: Int = 1,
            val endPriority: Int = KSLEvent.DEFAULT_PRIORITY
        ) : Entity(tag) {
            val p: KSLProcess = process("use") {
                entity.currentLocation = locA
                delay(arrive)
                when (kind) {
                    Kind.RESOURCE -> { val a = seize(if (target) r2 else r1, amount); served(); delay(hold, delayPriority = endPriority); release(a) }
                    Kind.POOL -> { val a = seize(if (target) p2 else p1, amount); served(); delay(hold, delayPriority = endPriority); release(a) }
                    Kind.MOVABLE_POOL -> {
                        val a = seize(if (target) f2 else f1, requestLocation = locA); served(); delay(hold, delayPriority = endPriority); release(a)
                    }
                }
            }

            private fun served() {
                if (tag == "mover" || tag == "waiter") log.add("$tag@$time")
            }

            fun moveToTarget() {
                val request = sourceQ.peekNext()!!
                when (kind) {
                    Kind.RESOURCE -> moveRequestToResource(request as ResourceRequest, sourceQ, r2, RESUME_PRIORITY)
                    Kind.POOL -> moveRequestToResourcePool(
                        request as ResourcePoolRequest, sourceQ, p2, p2.defaultResourceSelectionRule, RESUME_PRIORITY
                    )
                    Kind.MOVABLE_POOL -> moveRequestToMovableResourcePool(
                        request as MovableResourcePoolRequest, sourceQ, f2, f2.defaultMovableResourceSelectionRule, RESUME_PRIORITY
                    )
                }
            }
        }

        override fun initialize() {
            log.clear()
            activate(User("sourceHolder", target = false, arrive = 0.0, hold = 1000.0).p)
            mover = User("mover", target = false, arrive = 2.0, hold = 1.0)
            activate(mover.p)
            when (beside) {
                Beside.TWO_UNIT_WAITER -> {
                    activate(User("targetHolder", target = true, arrive = 0.0, hold = 100.0).p)
                    activate(User("waiter", target = true, arrive = 1.0, hold = 1.0, amount = 2).p)
                    schedule(EventActionIfc<Nothing> { mover.moveToTarget() }, 4.0)
                }
                Beside.WOKEN_WAITER -> {
                    for (k in 1..units) activate(User("targetHolder$k", target = true, arrive = 0.0, hold = 8.0, endPriority = -10).p)
                    activate(User("waiter", target = true, arrive = 6.0, hold = 1.0).p)
                    schedule(EventActionIfc<Nothing> { mover.moveToTarget() }, 8.0, priority = -5)
                }
            }
        }
    }

    private fun runBeside(kind: Kind, beside: Beside, units: Int): List<String> {
        val model = Model("Beside${System.nanoTime()}", autoCSVReports = false)
        val m = BesideModel(model, kind, beside, units)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        model.simulate()
        return m.log
    }

    @Test
    @DisplayName("a request moved beside a free unit its new queue's other request cannot use is served at the move, as a seize would be")
    fun movedBesideAFreeUnit() {
        for (kind in listOf(Kind.RESOURCE, Kind.POOL)) {
            assertEquals("mover@4.0", runBeside(kind, Beside.TWO_UNIT_WAITER, units = 2).first(), "$kind")
        }
        // A movable pool request needs one unit, so no request waits beside a free unit except in the instant of
        // its wake, and by the move it has left the queue: the mover is served at the move under either rule.
        // This checks only that the second unit freed at 8 goes to the mover.
        assertEquals(listOf("waiter@8.0", "mover@8.0"), runBeside(Kind.MOVABLE_POOL, Beside.WOKEN_WAITER, units = 2).sorted().reversed())
    }

    @Test
    @DisplayName("a request moved beside a request woken for the only free unit waits for it, for a resource and a pool")
    fun movedBesideAWokenRequestWaits() {
        for (kind in listOf(Kind.RESOURCE, Kind.POOL)) {
            assertEquals(listOf("waiter@8.0", "mover@9.0"), runBeside(kind, Beside.WOKEN_WAITER, units = 1), "$kind")
        }
    }
}
