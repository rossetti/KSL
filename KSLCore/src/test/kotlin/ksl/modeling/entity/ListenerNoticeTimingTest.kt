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
 *  An allocation listener is told of a change to a resource once the change is complete: a release's listener sees
 *  the loans it ended returned and the capacity decrease it let finish, a preemption's listener sees every victim's
 *  units lent, and a listener that changes the resource itself makes a new change, told of when it completes. The
 *  notices keep their order.
 */
class ListenerNoticeTimingTest {

    private class Shop(parent: ModelElement, capacity: Int) : ProcessModel(parent, "Notices") {
        val r = ResourceWithQ(this, "R", capacity = capacity)
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()

        inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process("p-$tag") { body() }
        }

        fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
            Actor(tag, priority, body).also { activate(it.p) }

        fun job(tag: String, from: Double, hold: Double, priority: Int = 5) = actor(tag, priority) {
            delay(from)
            val a = seize(r)
            delay(hold, suspensionName = "work")
            release(a)
        }

        override fun initialize() {
            log.clear()
            for (s in setups) s()
        }
    }

    private fun run(capacity: Int, setup: Shop.() -> Unit): Shop {
        val model = Model("Notices${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, capacity)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 100.0
        model.simulate()
        return shop
    }

    private fun Shop.listen(listener: AllocationListenerIfc) = r.addAllocationListener(listener)

    private fun Shop.lent(): Int = r.allocations().sumOf { it.missingByLoan }

    @Test
    @DisplayName("a deallocate listener sees the capacity decrease the release let finish under WAIT")
    fun deallocateSeesTheDecrease() {
        val seen = mutableListOf<Int>()
        run(capacity = 2) {
            r.capacityChangeRule = CapacityChangeRule.WAIT
            listen(object : AllocationListenerIfc {
                override fun allocate(allocation: Allocation) {}
                override fun deallocate(allocation: Allocation) {
                    seen.add(r.capacity)
                }
            })
            job("A", 0.0, 10.0)
            job("B", 0.0, 20.0)
            // both units are busy, so the cut to 1 waits for a release
            actor("Cut", 0) { delay(5.0); r.changeCapacity(r.CapacityChangeNotice(1)) }
        }
        assertEquals(1, seen.first(), "the release at 10 lets the cut finish before its listener is told: $seen")
    }

    @Test
    @DisplayName("a preemption's listener sees every victim's units lent, at each notice")
    fun lentSeesEveryLoan() {
        val atLent = mutableListOf<Int>()
        val atAllocate = mutableListOf<Int>()
        run(capacity = 2) {
            listen(object : AllocationListenerIfc {
                override fun allocate(allocation: Allocation) {
                    if (allocation.myEntity.name == "P") atAllocate.add(lent())
                }
                override fun deallocate(allocation: Allocation) {}
                override fun unitsLent(lender: Allocation, borrower: Allocation, amount: Int) {
                    atLent.add(lent())
                }
            })
            job("L1", 0.0, 50.0, priority = 9)
            job("L2", 0.0, 50.0, priority = 9)
            actor("P", 1) {
                delay(5.0)
                val a = preempt(r, 2, rule = InterruptRule.RESUME)
                delay(1.0)
                release(a)
            }
        }
        assertEquals(listOf(2, 2), atLent, "both loans are made before either is told of")
        assertEquals(listOf(2), atAllocate)
    }

    @Test
    @DisplayName("a deallocate listener sees the loans the release ended returned to their lenders")
    fun deallocateSeesTheLoansReturned() {
        val seen = mutableListOf<Int>()
        val order = mutableListOf<String>()
        run(capacity = 2) {
            listen(object : AllocationListenerIfc {
                override fun allocate(allocation: Allocation) {
                    order.add("allocate ${allocation.myEntity.name}")
                }
                override fun deallocate(allocation: Allocation) {
                    order.add("deallocate ${allocation.myEntity.name}")
                    if (allocation.myEntity.name == "P") seen.add(lent())
                }
                override fun unitsLent(lender: Allocation, borrower: Allocation, amount: Int) {
                    order.add("lent ${lender.myEntity.name}")
                }
                override fun unitsReturned(lender: Allocation, amount: Int) {
                    order.add("returned ${lender.myEntity.name}")
                }
            })
            job("L1", 0.0, 50.0, priority = 9)
            job("L2", 0.0, 50.0, priority = 9)
            actor("P", 1) {
                delay(5.0)
                val a = preempt(r, 2, rule = InterruptRule.RESUME)
                delay(1.0)
                release(a)
            }
        }
        assertEquals(listOf(0), seen, "the loans are back when the release is told of")
        assertEquals(
            listOf(
                "allocate L1", "allocate L2", "lent L2", "lent L1", "allocate P",
                "deallocate P", "returned L1", "returned L2", "deallocate L1", "deallocate L2"
            ),
            order, "the notices keep the order of the steps they tell of"
        )
    }

    @Test
    @DisplayName("a listener that fails the resource on a release starts a new change, told of after the release")
    fun listenerStartsANewChange() {
        val order = mutableListOf<String>()
        var capacityAtFailure = -1
        run(capacity = 2) {
            r.capacityChangeRule = CapacityChangeRule.WAIT
            listen(object : AllocationListenerIfc {
                override fun allocate(allocation: Allocation) {}
                override fun deallocate(allocation: Allocation) {
                    order.add("deallocate ${allocation.myEntity.name}")
                    if (allocation.myEntity.name == "A") r.fail()
                }
                override fun failureStateChanged(resource: Resource) {
                    order.add(if (resource.isFailed) "failed" else "repaired")
                    if (resource.isFailed) capacityAtFailure = resource.capacity
                }
            })
            job("A", 0.0, 10.0)
            job("B", 0.0, 20.0)
            actor("Cut", 0) { delay(5.0); r.changeCapacity(r.CapacityChangeNotice(1)) }
            actor("Fixer", 0) { delay(12.0); r.repair() }
        }
        assertEquals(1, capacityAtFailure, "the failure is made after the release has let the cut finish")
        assertEquals(listOf("deallocate A", "failed", "repaired", "deallocate B"), order)
    }
}
