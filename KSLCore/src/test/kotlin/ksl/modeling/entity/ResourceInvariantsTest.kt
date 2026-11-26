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
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/** The invariant checks: switched on, they catch broken accounting after the event that broke it. */
class ResourceInvariantsTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    /** A resource used by one customer, whose accounting is broken on purpose at time 5. */
    private class Broken(parent: ModelElement) : ProcessModel(parent, "broken") {
        val r = ResourceWithQ(this, "R")

        inner class Customer : Entity() {
            val run: KSLProcess = process("customer") { val a = seize(r); delay(10.0); release(a) }
        }

        override fun initialize() {
            activate(Customer().run)
            schedule({ _: KSLEvent<Nothing> -> r.myPromisedUnits += 1 }, 5.0)
        }
    }

    private fun runBroken() {
        val model = Model("Broken${System.nanoTime()}", autoCSVReports = false)
        Broken(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
    }

    @Test
    @DisplayName("switched on, a unit promised to no request is caught after the event that promised it")
    fun onCatchesBrokenAccounting() {
        ResourceInvariants.enabled = true
        val error = assertFailsWith<RuntimeException> { runBroken() }
        val messages = generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertContains(messages, "1 units are promised, but the requests in its queues hold 0")
    }

    /**
     *  An IGNORE cut to 1 made while both units of R are promised to a woken request for 2: the cut waits
     *  for a unit. The woken request's promise breaks at its resume, and nothing else wants R, so no release
     *  follows. Only the withdrawn promise can give the cut its unit.
     */
    private class CutOverPromise(parent: ModelElement) : ProcessModel(parent, "cutOverPromise") {
        val r = ResourceWithQ(this, "R", capacity = 2)

        inner class Holder : Entity() {
            val p: KSLProcess = process("holder") {
                val a = seize(r, 2)
                delay(5.0)
                release(a)
                r.changeCapacity(r.CapacityChangeNotice(capacity = 1, duration = Double.POSITIVE_INFINITY))
            }
        }

        inner class Big : Entity() {
            val p: KSLProcess = process("big") {
                delay(1.0)
                seizeOrTimeout(r, amountNeeded = 2, timeout = 10.0).valueOrNull()?.let { release(it) }
            }
        }

        override fun initialize() {
            activate(Holder().p)
            activate(Big().p)
        }
    }

    @Test
    @DisplayName("switched on, a pending capacity cut never waits for more units than are busy or promised")
    fun pendingCutWaitsOnlyForUnitsThatCanCome() {
        ResourceInvariants.enabled = true
        val model = Model("CutOverPromise${System.nanoTime()}", autoCSVReports = false)
        val m = CutOverPromise(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        model.simulate()
        kotlin.test.assertFalse(m.r.isPendingCapacityChange)
    }

    /** In one event: capacity 1 to 2, both units allocated, and back to 1 under IGNORE; the work stays. */
    private class PeakInOneEvent(parent: ModelElement) : ProcessModel(parent, "peak") {
        val r = ResourceWithQ(this, "R")
        val q = RequestQ(this, "PeakQ")
        var done = false

        inner class Controller : Entity() {
            val p: KSLProcess = process("controller") {
                delay(1.0)
                r.changeCapacity(r.CapacityChangeNotice(2))
                val a = r.allocate(entity, amountNeeded = 2, queue = q)
                r.changeCapacity(r.CapacityChangeNotice(1))
                delay(1.0)
                release(a)
                done = true
            }
        }

        override fun initialize() {
            activate(Controller().p)
        }
    }

    @Test
    @DisplayName("switched on, a capacity peak within one event is counted: the work it allowed is not a violation")
    fun peakWithinOneEvent() {
        ResourceInvariants.enabled = true
        val model = Model("Peak${System.nanoTime()}", autoCSVReports = false)
        val m = PeakInOneEvent(model)
        model.numberOfReplications = 2
        model.lengthOfReplication = 20.0
        model.simulate()
        kotlin.test.assertTrue(m.done)
    }

    /** A unit freed at 5 without being offered to the queue, so the request waiting for it is left beside it. */
    private class Stranded(parent: ModelElement) : ProcessModel(parent, "stranded") {
        val r = ResourceWithQ(this, "R")
        var held: Allocation? = null

        inner class Holder : Entity() {
            val run: KSLProcess = process("holder") { held = seize(r); delay(10.0) }
        }

        inner class Waiter : Entity() {
            val run: KSLProcess = process("waiter") { delay(1.0); val a = seize(r); release(a) }
        }

        override fun initialize() {
            activate(Holder().run)
            activate(Waiter().run)
            schedule({ _: KSLEvent<Nothing> -> r.deallocate(held!!) }, 5.0)
        }
    }

    @Test
    @DisplayName("switched on, a request left waiting beside a unit it could take is caught")
    fun onCatchesAStrandedRequest() {
        ResourceInvariants.enabled = true
        val model = Model("Stranded${System.nanoTime()}", autoCSVReports = false)
        Stranded(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        val error = assertFailsWith<RuntimeException> { model.simulate() }
        val messages = generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
        assertContains(messages, "for 1 units, un-served, while 1 are available")
    }

    /**
     *  W waits for the first of a unit of R, held by another until 10, an item of a blocking queue, and a signal.
     *  At 5 its wait is closed on purpose without releasing what it placed, as a registration left out of its
     *  wait would be: its request stays in R's queue, or in the blocking queue's request queue, for a wait that
     *  has ended.
     */
    private class LeftBehind(parent: ModelElement, val inResource: Boolean) : ProcessModel(parent, "leftBehind") {
        val r = ResourceWithQ(this, "R")
        val items = BlockingQueue<Item>(this, name = "Items")
        val signal = Signal(this, "Go")

        inner class Item : QObject()

        inner class Holder : Entity() {
            val run: KSLProcess = process("holder") { val a = seize(r); delay(10.0); release(a) }
        }

        inner class Waiter : Entity() {
            val run: KSLProcess = process("waiter") {
                delay(1.0)
                if (inResource) waitForFirst(r.seizeTrigger(), signal.trigger()) else waitForFirst(items.itemsTrigger(), signal.trigger())
            }
        }

        override fun initialize() {
            activate(Holder().run)
            activate(Waiter().run)
            schedule({ _: KSLEvent<Nothing> ->
                val registration = if (inResource) r.myWaitingQ.peekFirst()!!.registration
                else (items.requestQ as ksl.modeling.queue.Queue<*>).peekFirst()
                    .let { (it as BlockingQueue<*>.ChannelRequest).registration }
                registration!!.group.closeOnTermination()
            }, 5.0)
        }
    }

    private fun leftBehindMessages(inResource: Boolean): String {
        ResourceInvariants.enabled = true
        val model = Model("LeftBehind${System.nanoTime()}", autoCSVReports = false)
        LeftBehind(model, inResource)
        model.numberOfReplications = 1
        model.lengthOfReplication = 20.0
        val error = assertFailsWith<RuntimeException> { model.simulate() }
        return generateSequence<Throwable>(error) { it.cause }.joinToString(" | ") { it.message ?: "" }
    }

    @Test
    @DisplayName("switched on, a resource request left in its queue after its wait ended is caught")
    fun onCatchesAResourceRequestOfAnEndedWait() {
        val messages = leftBehindMessages(inResource = true)
        assertContains(messages, "at time 5.0")
        assertContains(messages, "waits in R:Q for a wait that has already ended")
    }

    @Test
    @DisplayName("switched on, a blocking queue request left in its queue after its wait ended is caught")
    fun onCatchesAnItemsRequestOfAnEndedWait() {
        val messages = leftBehindMessages(inResource = false)
        assertContains(messages, "at time 5.0")
        assertContains(messages, "waits in Items:RequestQ for a wait that has already ended")
    }

    @Test
    @DisplayName("switched off, nothing is checked")
    fun offChecksNothing() {
        ResourceInvariants.enabled = false
        runBroken()
    }
}
