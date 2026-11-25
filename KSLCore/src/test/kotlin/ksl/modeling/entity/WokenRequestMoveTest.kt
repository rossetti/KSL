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
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  A request the library serves is allocated its units at that moment and leaves its queue, so in the instant
 *  before its entity resumes there is no request left to move: it is served where it was chosen, whatever a model
 *  tries to do with the queue then. Each kind of request is checked: of a resource, a pool and a movable pool.
 */
class WokenRequestMoveTest {

    enum class Kind { RESOURCE, POOL, MOVABLE_POOL }

    /**
     *  A holder has the source from time 0 to 5; at 5 it releases it, which serves the mover, and at once
     *  looks for the mover's request in the source queue, to move it to the target. The target is free, or held
     *  until 8. The mover, when served, logs where from and the time, waits 10, logs again and releases.
     */
    private class WokenModel(parent: ModelElement, val kind: Kind, val targetFree: Boolean, val behind: Boolean = false) : ProcessModel(parent, "woken") {
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
        lateinit var mover: Mover

        val sourceQ: RequestQ
            get() = when (kind) {
                Kind.RESOURCE -> r1.myWaitingQ
                Kind.POOL -> p1.myWaitingQ
                Kind.MOVABLE_POOL -> f1.myWaitingQ
            }

        /** Seizes the source or the target, holds it, releases it, then runs [after]. */
        inner class Holder(val target: Boolean, val hold: Double, val after: () -> Unit = {}) : Entity() {
            val p: KSLProcess = process("hold") {
                entity.currentLocation = locA
                when (kind) {
                    Kind.RESOURCE -> { val a = seize(if (target) r2 else r1); delay(hold); release(a) }
                    Kind.POOL -> { val a = seize(if (target) p2 else p1); delay(hold); release(a) }
                    Kind.MOVABLE_POOL -> { val a = seize(if (target) f2 else f1, requestLocation = locA); delay(hold); release(a) }
                }
                after()
            }
        }

        inner class Mover : Entity("mover") {
            val p: KSLProcess = process("wait") {
                entity.currentLocation = locA
                delay(1.0)
                when (kind) {
                    Kind.RESOURCE -> { val a = seize(r1); served(a.myResource.name); delay(10.0); log.add("after@$time"); release(a) }
                    Kind.POOL -> { val a = seize(p1); served(a.allocations.single().myResource.name); delay(10.0); log.add("after@$time"); release(a) }
                    Kind.MOVABLE_POOL -> { val a = seize(f1, requestLocation = locA); served(a.myResource.name); delay(10.0); log.add("after@$time"); release(a) }
                }
            }

            /** Logs whether the unit came from the source (R1, RA, T1) or the target. */
            private fun served(resourceName: String) {
                val from = if (resourceName in setOf("R1", "RA", "T1")) "source" else "target"
                log.add("served from $from@$time")
            }

            /** Looks for this entity's request in the source queue, as a model moving it would, and moves it if found. */
            fun tryToMove() {
                val request = sourceQ.firstOrNull { it.entity === this }
                log.add("mover queued=${request != null}@$time")
                if (request != null) {
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
        }

        /** Waits for the source from time 2, behind the mover. */
        inner class Behind : Entity("behind") {
            val p: KSLProcess = process("behind") {
                entity.currentLocation = locA
                delay(2.0)
                when (kind) {
                    Kind.RESOURCE -> { val a = seize(r1); log.add("behind served@$time"); release(a) }
                    Kind.POOL -> { val a = seize(p1); log.add("behind served@$time"); release(a) }
                    Kind.MOVABLE_POOL -> { val a = seize(f1, requestLocation = locA); log.add("behind served@$time"); release(a) }
                }
            }
        }

        override fun initialize() {
            log.clear()
            mover = Mover()
            activate(Holder(target = false, hold = 5.0) { mover.tryToMove() }.p)
            if (!targetFree) activate(Holder(target = true, hold = 8.0).p)
            activate(mover.p)
            if (behind) activate(Behind().p)
        }
    }

    private fun run(kind: Kind, targetFree: Boolean, behind: Boolean = false): List<String> {
        val model = Model("Woken${System.nanoTime()}", autoCSVReports = false)
        val m = WokenModel(model, kind, targetFree, behind)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m.log
    }

    @Test
    @DisplayName("a request served at a release is out of its queue at once and served where it was chosen, the target free or busy")
    fun servedRequestIsNotMoved() {
        for (kind in Kind.entries) for (targetFree in listOf(true, false)) {
            assertEquals(
                listOf("mover queued=false@5.0", "served from source@5.0", "after@15.0"), run(kind, targetFree),
                "$kind, target free = $targetFree"
            )
        }
    }

    @Test
    @DisplayName("a request served at a release keeps the source's unit: the request behind it waits for the next release")
    fun theRequestBehindWaits() {
        for (kind in Kind.entries) {
            val log = run(kind, targetFree = true, behind = true)
            assertEquals(listOf("mover queued=false@5.0", "served from source@5.0", "after@15.0", "behind served@15.0"), log, "$kind")
        }
    }
}
