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
import kotlin.test.assertNull

/**
 *  A woken request of a pool leaves its queue when it resumes; under a request selection rule, the members still free
 *  are then offered again, as after a seize of a resource, so the request the rule chooses next is served at once
 *  rather than at the next release.
 */
class PoolSeizeUnderSelectionRuleTest {

    private class Yard(parent: ModelElement, val movable: Boolean) : ProcessModel(parent, "Yard") {
        private val speed = ConstantRV(10.0)
        private val dm = DistancesModel()
        val locA = dm.Location("A")
        val locB = dm.Location("B")

        init {
            dm.addDistance(locA, locB, 100.0, symmetric = true)
            dm.defaultVelocity = speed
            spatialModel = dm
        }

        // serves, in queue order, every request the free units cover
        val q = RequestQ(this, "FleetQ").also { q ->
            q.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                var left = resource.numAvailableUnits
                requestQ.filter { r -> (r.amountRequested <= left).also { if (it) left -= r.amountRequested } }
            }
        }
        val t1 = MovableResource(this, locA, speed, "T1")
        val t2 = MovableResource(this, locA, speed, "T2")
        val fleet = MovableResourcePoolWithQ(this, listOf(t1, t2), speed, q, "Fleet")
        val q2 = RequestQ(this, "PoolQ").also { it.requestSelectionRule = q.requestSelectionRule }
        val m1 = ResourceWithQ(this, "M1")
        val m2 = ResourceWithQ(this, "M2")
        val pool = ResourcePoolWithQ(this, listOf(m1, m2), q2, "Pool")
        val got = mutableListOf<String>()

        // takes both carts, or both members, and gives both back in one event, at 2
        inner class Holder : Entity("H") {
            val p = process {
                currentLocation = locA
                if (movable) {
                    val a1 = seize(fleet)
                    val a2 = seize(fleet)
                    delay(2.0)
                    release(a1)
                    release(a2)
                } else {
                    val a = seize(pool, 2)
                    delay(2.0)
                    release(a)
                }
            }
        }

        inner class Waiter(n: String, val start: Double) : Entity(n) {
            val p = process {
                currentLocation = locA
                delay(start)
                if (movable) {
                    val a = seize(fleet)
                    got.add("$name@$time")
                    delay(1.0)
                    release(a)
                } else {
                    val a = seize(pool)
                    got.add("$name@$time")
                    delay(1.0)
                    release(a)
                }
            }
        }

        override fun initialize() {
            activate(Holder().p)
            activate(Waiter("A", 0.5).p)
            activate(Waiter("B", 0.6).p)
        }
    }

    private fun run(movable: Boolean): List<String> {
        val model = Model("PoolUnderRule$movable${System.nanoTime()}", autoCSVReports = false)
        val yard = Yard(model, movable)
        model.lengthOfReplication = 10.0
        val failure = runCatching { model.simulate() }.exceptionOrNull()
        assertNull(failure, "the run stopped: $failure")
        return yard.got
    }

    @Test
    @DisplayName("two requests of a movable resource pool woken by one event that frees two carts both start then")
    fun movablePoolBothStartAtTheRelease() {
        assertEquals(listOf("A@2.0", "B@2.0"), run(movable = true))
    }

    @Test
    @DisplayName("two requests of a resource pool woken by one release of two members both start then")
    fun resourcePoolBothStartAtTheRelease() {
        assertEquals(listOf("A@2.0", "B@2.0"), run(movable = false))
    }
}
