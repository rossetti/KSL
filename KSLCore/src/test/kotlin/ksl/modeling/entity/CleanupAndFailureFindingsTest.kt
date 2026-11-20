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
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  A blockage still active when the replication ends, and a declared failure replaced or repaired by a failure
 *  listener at the instant it begins.
 */
class CleanupAndFailureFindingsTest {

    private fun simulate(model: Model, reps: Int, length: Double): Throwable? {
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        return try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
    }

    private class Owners(parent: ModelElement) : ProcessModel(parent, "Owners") {
        inner class Owner : Entity("owner") {
            val b = Blockage("work")
            val p: KSLProcess = process {
                startBlockage(b)
                delay(100.0)
                clearBlockage(b)
            }
        }

        lateinit var owner: Owner

        override fun initialize() {
            owner = Owner()
            activate(owner.p)
        }
    }

    private class Waiters(parent: ModelElement, val owners: Owners) : ProcessModel(parent, "Waiters") {
        var resumed = 0

        inner class Waiter : Entity("waiter") {
            val p: KSLProcess = process { delay(1.0); waitFor(owners.owner.b); resumed++ }
        }

        override fun initialize() {
            activate(Waiter().p)
        }
    }

    @Test
    @DisplayName("a blockage active at the end of a replication ends quietly, waking no one, whichever model is cleaned up first")
    fun blockageActiveAtTheEndOfAReplication() {
        val model = Model("HorizonBlockage${System.nanoTime()}", autoCSVReports = false)
        val owners = Owners(model)
        val waiters = Waiters(model, owners)
        val error = simulate(model, reps = 2, length = 10.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(2, model.currentReplicationNumber)
        assertEquals(0, waiters.resumed)
    }

    /** R fails every 10 for 5. A failure listener may replace the first failure by one of its own, or repair it. */
    private class FailShop(parent: ModelElement, val replace: Boolean) : ProcessModel(parent, "FailShop") {
        val r = ResourceWithQ(this, "R")
        val log = mutableListOf<String>()
        var handled = false

        init {
            r.failures(ConstantRV(10.0), ConstantRV(5.0))
            r.addAllocationListener(object : AllocationListenerIfc {
                override fun allocate(allocation: Allocation) {}
                override fun deallocate(allocation: Allocation) {}
                override fun failureStateChanged(resource: Resource) {
                    log.add("${if (r.isFailed) "failed" else "repaired"}@$time")
                    if (r.isFailed && !handled) {
                        handled = true
                        r.repair()
                        if (replace) r.fail()
                    }
                }
            })
        }

        override fun initialize() {
            log.clear()
            handled = false
            schedule({ _: KSLEvent<Nothing> -> log.add("at 16 failed=${r.isFailed}") }, 16.0)
        }
    }

    @Test
    @DisplayName("a failure a listener makes in place of a declared one lasts until it is repaired by hand")
    fun listenerReplacesADeclaredFailure() {
        val model = Model("Refail${System.nanoTime()}", autoCSVReports = false)
        val shop = FailShop(model, replace = true)
        val error = simulate(model, reps = 1, length = 16.5)
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("failed@10.0", "repaired@10.0", "failed@10.0", "at 16 failed=true"), shop.log)
    }

    @Test
    @DisplayName("a declared failure a listener repairs as it begins counts the time to the next failure from that repair")
    fun listenerRepairsADeclaredFailureAsItBegins() {
        val model = Model("ImmediateRepair${System.nanoTime()}", autoCSVReports = false)
        val shop = FailShop(model, replace = false)
        val error = simulate(model, reps = 1, length = 36.0)
        assertNull(error, "the run stopped: $error")
        assertEquals(
            listOf("failed@10.0", "repaired@10.0", "at 16 failed=false", "failed@20.0", "repaired@25.0", "failed@35.0"),
            shop.log
        )
    }
}
