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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceInvariants
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 *  Rules stated in the resources tutorial (`ksl-resources-tutorial.md`), each checked on the smallest model that
 *  shows it:
 *
 *  - a job held by a preemption is not interrupted again by preemptors that arrive while it is held, and
 *    `timesInterrupted` counts one interruption of an allocation across several preemptions (case 3, pitfalls);
 *  - `numTimesPreempted` covers the whole replication, warm-up included (case 3);
 *  - a KSL counter starts again after the warm-up (case 4, pitfalls);
 *  - with the checks on, the accounting of units lent, units missing through failures, the reservations of waiting
 *    preemptors and the delays held on each allocation is checked after each event ("Checking your own model").
 */
class ResourcesTutorialRulesTest {

    private val wasEnabled = ResourceInvariants.enabled

    @AfterEach
    fun restore() {
        ResourceInvariants.enabled = wasEnabled
    }

    /** One machine of the given capacity, a ranked queue, and actors of chosen priority. */
    private class Shop(parent: ModelElement, capacity: Int, rule: InterruptRule) : ProcessModel(parent, "Shop") {
        val machine = ResourceWithQ(this, "Machine", capacity, queue = RequestQ(this, "Machine:Q", Queue.Discipline.RANKED))
        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()
        var preemptionsAtEnd = -1
        val allocations = mutableMapOf<String, Allocation>()

        init {
            machine.interruptRule = rule
        }

        inner class Actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = prio
            }

            val p: KSLProcess = process(isDefaultProcess = true, block = body)
        }

        fun actor(tag: String, prio: Int, body: suspend KSLProcessBuilder.() -> Unit): Actor =
            Actor(tag, prio, body).also { activate(it.p) }

        /** A routine job of priority 2: seizes `amount` units at `from` and works for `work`. */
        fun routine(tag: String, from: Double, amount: Int, work: Double) = actor(tag, 2) {
            delay(from)
            val a = seize(machine, amount)
            allocations[tag] = a
            delay(work)
            log.add("$tag done@$time interrupted=${a.timesInterrupted}")
            release(a)
        }

        /** A rush job of priority 1: preempts `amount` units at `from` and holds them for `hold`. */
        fun rush(tag: String, from: Double, amount: Int, hold: Double) = actor(tag, 1) {
            delay(from)
            val a = preempt(machine, amount)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
        }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        override fun initialize() {
            log.clear()
            allocations.clear()
            for (s in setups) s()
        }

        override fun replicationEnded() {
            preemptionsAtEnd = machine.numTimesPreempted
        }
    }

    private fun run(
        capacity: Int = 1, rule: InterruptRule = InterruptRule.RESUME, length: Double = 100.0, warmUp: Double = 0.0,
        setup: Shop.() -> Unit
    ): Pair<Shop, Throwable?> {
        val m = Model("ResourcesRules${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(m, capacity, rule)
        shop.setups.add(setup)
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        m.lengthOfReplicationWarmUp = warmUp
        val error = try {
            m.simulate(); null
        } catch (e: Throwable) {
            e
        }
        return shop to error
    }

    private fun Throwable.messages(): String = generateSequence(this) { it.cause }.joinToString(" | ") { it.message ?: "" }

    // ---------- case 3: rush orders ----------

    @Test
    @DisplayName("a rush job arriving while the routine job is held does not interrupt it again; the passed units count as a preemption")
    fun heldJobIsNotInterruptedAgain() {
        // RESUME. The routine job works from 0 for 10. Rush1 takes the machine at 2 for 3; rush2 arrives at 4 and
        // waits behind rush1, and at 5 is passed the units, holding them to 8. The routine job is held from 2 to 8,
        // resumes with 8 left, and finishes at 16: interrupted once, though the machine was preempted twice.
        val (shop, error) = run {
            routine("Routine", 0.0, 1, 10.0)
            rush("Rush1", 2.0, 1, 3.0)
            rush("Rush2", 4.0, 1, 3.0)
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("Rush1 got@2.0", "Rush2 got@5.0", "Routine done@16.0 interrupted=1"), shop.log)
        assertEquals(2, shop.preemptionsAtEnd, "numTimesPreempted")
    }

    @Test
    @DisplayName("timesInterrupted counts one interruption of an allocation even across several preemptions")
    fun oneInterruptionAcrossSeveralPreemptions() {
        // RESUME, two units. The routine job holds both from 0 for 10 of work. RushA takes one unit at 2 for 4,
        // RushB the other at 3 for 5. The routine job is held from 2 until its last unit comes back at 8, resumes
        // with 8 left, and finishes at 16: two preemptions of the machine, one interruption of the allocation.
        val (shop, error) = run(capacity = 2) {
            routine("Routine", 0.0, 2, 10.0)
            rush("RushA", 2.0, 1, 4.0)
            rush("RushB", 3.0, 1, 5.0)
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(listOf("RushA got@2.0", "RushB got@3.0", "Routine done@16.0 interrupted=1"), shop.log)
        assertEquals(2, shop.preemptionsAtEnd, "numTimesPreempted")
    }

    @Test
    @DisplayName("numTimesPreempted covers the whole replication, warm-up included")
    fun numTimesPreemptedIncludesTheWarmUp() {
        // warm-up 10. The routine job holds the machine from 0; rush jobs preempt it at 2, in the warm-up, and at 15
        val (shop, error) = run(length = 50.0, warmUp = 10.0) {
            routine("Routine", 0.0, 1, 20.0)
            rush("Rush1", 2.0, 1, 1.0)
            rush("Rush2", 15.0, 1, 1.0)
        }
        assertNull(error, "the run stopped: $error")
        assertEquals(2, shop.preemptionsAtEnd, "numTimesPreempted at the end of the replication")
    }

    // ---------- case 4: counters start again after the warm-up ----------

    /** A counter incremented at 2, in the warm-up of 10, and at 15; and a plain count kept beside it. */
    private class Counting(parent: ModelElement) : ProcessModel(parent, "Counting") {
        val counter = Counter(this, "Arrived")
        var plain = 0
        var counterAtEnd = -1.0
        var plainAtEnd = -1

        override fun initialize() {
            plain = 0
            for (t in listOf(2.0, 15.0)) schedule({ _: KSLEvent<Nothing> -> counter.increment(); plain++ }, t)
        }

        override fun replicationEnded() {
            counterAtEnd = counter.value
            plainAtEnd = plain
        }
    }

    @Test
    @DisplayName("a KSL counter starts again after the warm-up; a plain count kept from the start does not")
    fun counterStartsAgainAfterTheWarmUp() {
        val m = Model("Counting${System.nanoTime()}", autoCSVReports = false)
        val c = Counting(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 30.0
        m.lengthOfReplicationWarmUp = 10.0
        m.simulate()
        assertEquals(1.0, c.counterAtEnd, "the counter at the end of the replication")
        assertEquals(1.0, c.counter.acrossReplicationStatistic.average, "the counter's replication value")
        assertEquals(2, c.plainAtEnd, "the plain count")
    }

    // ---------- checking your own model: the invariant checks ----------

    private fun setPrivateInt(target: Any, field: String, value: Int) {
        val f = target.javaClass.getDeclaredField(field)
        f.isAccessible = true
        f.setInt(target, value)
    }

    /** The routine job holds the machine from 0 to 10; at 5 its accounting is broken by `breakIt`. */
    private fun brokenAt5(breakIt: Shop.() -> Unit): String {
        ResourceInvariants.enabled = true
        val (_, error) = run(length = 20.0) {
            routine("Routine", 0.0, 1, 10.0)
            at(5.0) { breakIt() }
        }
        val e = error ?: throw AssertionError("the broken accounting was not caught")
        return e.messages()
    }

    @Test
    @DisplayName("switched on, the checks catch units missing by loan that no loan accounts for")
    fun checksCatchUnitsLent() {
        val messages = brokenAt5 { setPrivateInt(allocations.getValue("Routine"), "missingByLoan", 1) }
        assertContains(messages, "at time 5.0")
        assertContains(messages, "1 units missing by loan, but 0 are lent")
    }

    @Test
    @DisplayName("switched on, the checks catch more units missing through failures than the allocation has")
    fun checksCatchUnitsMissingThroughFailures() {
        val messages = brokenAt5 { setPrivateInt(allocations.getValue("Routine"), "missingByFailure", 2) }
        assertContains(messages, "at time 5.0")
        assertContains(messages, "2 units missing of 1")
    }

    @Test
    @DisplayName("switched on, the checks catch a delay held by an allocation with no units missing")
    fun checksCatchHeldDelays() {
        val messages = brokenAt5 {
            val a = allocations.getValue("Routine")
            a.myEntity.myFrozenBy.add(a)
        }
        assertContains(messages, "at time 5.0")
        assertContains(messages, "which has no units missing")
    }

    @Test
    @DisplayName("switched on, the checks catch a waiting preemptor's reservation that does not match what it holds")
    fun checksCatchPreemptorReservations() {
        // FINISH. The routine job holds the machine from 0 to 10; a rush job preempts at 1 and reserves it, holding
        // no units yet. At 2 the reservation is made to say it needs nothing more.
        ResourceInvariants.enabled = true
        val (_, error) = run(rule = InterruptRule.FINISH, length = 20.0) {
            routine("Routine", 0.0, 1, 10.0)
            rush("Rush", 1.0, 1, 1.0)
            at(2.0) { machine.reservations.single().stillNeeded = 0 }
        }
        val messages = (error ?: throw AssertionError("the broken reservation was not caught")).messages()
        assertContains(messages, "at time 2.0")
        assertContains(messages, "holds 0 units, not 1")
    }
}
