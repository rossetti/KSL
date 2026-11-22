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

/**
 *  A pool allocation records its release as an allocation of one resource does: its amount becomes zero and its
 *  time deallocated is the time of the release.
 */
class PoolAllocationRecordTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "PoolRecord") {
        val r1 = Resource(this, "R1")
        val r2 = Resource(this, "R2")
        val pool = ResourcePoolWithQ(this, listOf(r1, r2), name = "Pool")
        var kept: ResourcePoolAllocation? = null
        var amountHeld = -1

        inner class Job : Entity("Job") {
            val p: KSLProcess = process {
                delay(1.0)
                val a = seize(pool, 2)
                amountHeld = a.amount
                delay(4.0)
                release(a)
                kept = a
            }
        }

        override fun initialize() {
            activate(Job().p)
        }
    }

    @Test
    @DisplayName("a released pool allocation has amount zero and the release time as its time deallocated")
    fun releaseIsRecorded() {
        val model = Model("PoolRecord${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        val a = shop.kept!!
        assertEquals(2, shop.amountHeld)
        assertEquals(1.0, a.timeAllocated)
        assertEquals(0, a.amount, "the amount after the release")
        assertEquals(5.0, a.timeDeallocated, "the time of the release")
    }
}
