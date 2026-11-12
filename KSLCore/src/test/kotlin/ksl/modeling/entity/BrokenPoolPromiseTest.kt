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
 *  A pool request woken with a promise of one member, which goes away before the request resumes,
 *  takes another member that is free rather than wait beside it for a release that has already
 *  happened.
 */
class BrokenPoolPromiseTest {

    /**
     *  Two members, both held by a holder until 5. A request for one unit waits from time 1. At 5 the
     *  holder releases both, which wakes the request with a promise of one member, and at once that
     *  member's capacity drops to zero. The other member is free.
     */
    private class PoolModel(parent: ModelElement, val movable: Boolean) : ProcessModel(parent, "brokenPromise") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")

        init {
            dm.addDistance(locA, dm.Location("B"), 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        val a = ResourceWithQ(this, "A")
        val b = ResourceWithQ(this, "B")
        val pool = ResourcePoolWithQ(this, listOf(a, b), name = "P")
        val t1 = MovableResource(this, locA, speed, "T1")
        val t2 = MovableResource(this, locA, speed, "T2")
        val fleet = MovableResourcePoolWithQ(this, listOf(t1, t2), speed, RequestQ(this, "FleetQ"), "Fleet")
        val queue: RequestQ get() = if (movable) fleet.myWaitingQ else pool.myWaitingQ
        val log = mutableListOf<String>()

        /** Takes the promised member of the waiting request out of service. */
        fun breakThePromise() {
            val member = queue.peekNext()!!.promises.keys.single()
            member.changeCapacity(member.CapacityChangeNotice(capacity = 0))
            log.add("${member.name} off@$time")
        }

        inner class Holder : Entity("holder") {
            val p: KSLProcess = process {
                entity.currentLocation = locA
                if (movable) {
                    val x1 = seize(fleet, requestLocation = locA)
                    val x2 = seize(fleet, requestLocation = locA)
                    delay(5.0)
                    release(x1)
                    release(x2)
                } else {
                    val x = seize(pool, 2)
                    delay(5.0)
                    release(x)
                }
                breakThePromise()
            }
        }

        inner class Requester : Entity("requester") {
            val p: KSLProcess = process {
                entity.currentLocation = locA
                delay(1.0)
                if (movable) {
                    val al = seize(fleet, requestLocation = locA)
                    log.add("served by ${al.resource.name}@$time")
                    delay(1.0)
                    release(al)
                } else {
                    val al = seize(pool, 1)
                    log.add("served by ${al.allocations.single().resource.name}@$time")
                    delay(1.0)
                    release(al)
                }
            }
        }

        override fun initialize() {
            log.clear()
            activate(Holder().p)
            activate(Requester().p)
        }
    }

    private fun run(movable: Boolean): List<String> {
        val model = Model("BrokenPromise${System.nanoTime()}", autoCSVReports = false)
        val m = PoolModel(model, movable)
        model.numberOfReplications = 1
        model.lengthOfReplication = 50.0
        model.simulate()
        return m.log
    }

    @Test
    @DisplayName("a resource pool request whose promised member goes away takes the other, free member at once")
    fun resourcePool() {
        val log = run(movable = false)
        assertEquals(2, log.size, "$log")
        val off = log[0].substringBefore(" off")
        assertEquals("served by ${if (off == "A") "B" else "A"}@5.0", log[1])
    }

    @Test
    @DisplayName("a movable pool request whose promised member goes away takes the other, free member at once")
    fun movablePool() {
        val log = run(movable = true)
        assertEquals(2, log.size, "$log")
        val off = log[0].substringBefore(" off")
        assertEquals("served by ${if (off == "T1") "T2" else "T1"}@5.0", log[1])
    }
}
