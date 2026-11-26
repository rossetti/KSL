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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Units a change leaves free beside a request that could take them are offered by the end of the event, even when
 *  the path that freed them offers nothing, as a model's direct call of `Resource.deallocate` does.
 */
class UnofferedUnitsTest {

    // the invariant checks, run after every event, would report the request left beside the free unit before the
    // offer at the end of the event serves it; this test is about that offer, so they are off here
    private var myInvariantsWereEnabled = false

    @BeforeEach
    fun disableInvariants() {
        myInvariantsWereEnabled = ResourceInvariants.enabled
        ResourceInvariants.enabled = false
    }

    @AfterEach
    fun restoreInvariants() {
        ResourceInvariants.enabled = myInvariantsWereEnabled
    }

    /** R's one unit is allocated directly to X; W seizes R at 1 and waits; at 5 the model deallocates X's unit directly. */
    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val r = ResourceWithQ(this, "R")
        val log = mutableListOf<String>()
        lateinit var xAllocation: Allocation

        override fun initialize() {
            log.clear()
            xAllocation = r.allocate(object : Entity("X") {}, 1, r.myWaitingQ)
            activate(object : Entity("W") {
                val p = process { delay(1.0); val a = seize(r); log.add("W got@$time"); delay(1.0); release(a) }
            }.p)
            schedule({ _: KSLEvent<Nothing> -> r.deallocate(xAllocation) }, 5.0)
        }
    }

    @Test
    @DisplayName("a unit a model frees directly, with no offer, is offered by the end of the event to the request waiting for it")
    fun directDeallocateIsOffered() {
        val model = Model("Unoffered${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model)
        model.lengthOfReplication = 20.0
        model.simulate()
        assertEquals(listOf("W got@5.0"), shop.log)
    }
}
