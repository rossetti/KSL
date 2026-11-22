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
 *  Whether a request is served now or waits is decided alike on every path: a moved preemptor preempts at its new
 *  resource as one arriving there would; an allocation names the queue its request was served from; and a request
 *  selection rule that serves strictly from the head of its queue serves the request behind a woken one in the
 *  same instant.
 */
class RequestServiceTest {

    private fun <T> simulate(length: Double = 200.0, build: (Model) -> T): T {
        val model = Model("Service${System.nanoTime()}", autoCSVReports = false)
        val x = build(model)
        model.numberOfReplications = 1
        model.lengthOfReplication = length
        model.simulate()
        return x
    }

    /**
     *  A preempt waiting for R1 is moved to R2, whose only unit is held by a less urgent holder. With [rule] null the
     *  preempt names no rule, and R2's own rule is [r2Rule].
     */
    private class MovedPreemptor(
        parent: ModelElement, val move: Boolean, val rule: InterruptRule?, r2Rule: InterruptRule = InterruptRule.FINISH
    ) : ProcessModel(parent, "mp") {
        val r1 = ResourceWithQ(this, "R1")
        val r2 = ResourceWithQ(this, "R2").also { it.interruptRule = r2Rule }
        var servedAt = Double.NaN

        inner class Holder(val r: ResourceWithQ, pri: Int, val hold: Double) : Entity() {
            init {
                priority = pri
            }

            val p: KSLProcess = process { val a = seize(r); delay(hold); release(a) }
        }

        inner class Urgent : Entity() {
            init {
                priority = 1
            }

            val p: KSLProcess = process {
                delay(if (move) 1.0 else 5.0)
                val target = if (move) r1 else r2
                val a = if (rule != null) preempt(target, rule = rule) else preempt(target)
                servedAt = time
                delay(2.0); release(a)
            }

            fun moveToR2() {
                val request = r1.myWaitingQ.peekNext() as ResourceRequest
                moveRequestToResource(request, r1.myWaitingQ, r2, RESUME_PRIORITY)
            }
        }

        override fun initialize() {
            activate(Holder(r1, 1, 100.0).p)   // as urgent as the preemptor: never preempted
            activate(Holder(r2, 5, 50.0).p)    // less urgent: a preempt arriving at R2 takes its unit at once
            val u = Urgent(); activate(u.p)
            if (move) schedule(EventActionIfc<Nothing> { u.moveToR2() }, 5.0)
        }
    }

    @Test
    @DisplayName("a waiting preempt moved to another resource preempts there as one arriving there would")
    fun movedPreemptorPreempts() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART, InterruptRule.FINISH)) {
            val arriving = simulate { MovedPreemptor(it, move = false, rule = rule) }.servedAt
            val moved = simulate { MovedPreemptor(it, move = true, rule = rule) }.servedAt
            assertEquals(arriving, moved, "$rule: moved at 5, as arriving at 5")
        }
    }

    @Test
    @DisplayName("a preempt that names no rule keeps the rule of the resource it called for when it is moved")
    fun movedPreemptorKeepsItsRule() {
        // R1's rule is FINISH and R2's RESUME: arriving at R2 it takes the unit at once, under RESUME; moved there it
        // reserves under FINISH and takes the unit at the holder's release
        val arriving = simulate { MovedPreemptor(it, move = false, rule = null, r2Rule = InterruptRule.RESUME) }.servedAt
        val moved = simulate { MovedPreemptor(it, move = true, rule = null, r2Rule = InterruptRule.RESUME) }.servedAt
        assertEquals(5.0, arriving)
        assertEquals(50.0, moved)
    }

    /** A plain seize waiting for R1 is moved to R2 and served there. */
    private class MovedSeize(parent: ModelElement) : ProcessModel(parent, "ms") {
        val r1 = ResourceWithQ(this, "R1")
        val r2 = ResourceWithQ(this, "R2")
        var queueOfAllocation = ""

        inner class Holder(val r: ResourceWithQ, val hold: Double) : Entity() {
            val p: KSLProcess = process { val a = seize(r); delay(hold); release(a) }
        }

        inner class Mover : Entity() {
            val p: KSLProcess = process {
                delay(1.0)
                val a = seize(r1)
                queueOfAllocation = a.queue.name
                delay(2.0); release(a)
            }

            fun moveToR2() = moveRequestToResource(r1.myWaitingQ.peekNext() as ResourceRequest, r1.myWaitingQ, r2, RESUME_PRIORITY)
        }

        override fun initialize() {
            activate(Holder(r1, 100.0).p)
            activate(Holder(r2, 8.0).p)
            val u = Mover(); activate(u.p)
            schedule(EventActionIfc<Nothing> { u.moveToR2() }, 5.0)
        }
    }

    @Test
    @DisplayName("the allocation of a moved seize names the queue it was served from")
    fun allocationOfAMovedSeizeNamesItsQueue() {
        assertEquals("R2:Q", simulate { MovedSeize(it) }.queueOfAllocation)
    }

    /** A queue whose rule serves strictly in order: the requests from the head that fit, stopping at the first that does not. */
    private class StrictOrder(parent: ModelElement, val useRule: Boolean) : ProcessModel(parent, "so") {
        val r = ResourceWithQ(this, "R", capacity = 2)
        val served = mutableMapOf<String, Double>()

        inner class Job(val tag: String, val arrive: Double, val amount: Int, val hold: Double) : Entity(tag) {
            val p: KSLProcess = process {
                delay(arrive)
                val a = seize(r, amount)
                served[tag] = time
                delay(hold); release(a)
            }
        }

        override fun initialize() {
            served.clear()
            if (useRule) r.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                val chosen = mutableListOf<Entity.Request>()
                var left = resource.numAvailableUnits
                for (request in requestQ) {
                    if (request.resource !== resource) continue
                    if (request.amountRequested > left) break     // strictly in order: nobody passes the head
                    chosen.add(request); left -= request.amountRequested
                }
                chosen
            }
            activate(Job("H", 0.0, 2, 10.0).p)    // holds both units until 10
            activate(Job("A", 1.0, 1, 20.0).p)
            activate(Job("B", 2.0, 1, 20.0).p)
            activate(Job("C", 30.0, 1, 1.0).p)     // the next release after 10 is A's, at 30
        }
    }

    @Test
    @DisplayName("a rule serving strictly from the head serves the request behind a woken one in the same instant")
    fun strictOrderRule() {
        val plain = simulate { StrictOrder(it, useRule = false) }.served
        val ruled = simulate { StrictOrder(it, useRule = true) }.served
        assertEquals(plain, ruled)
    }

    /** A queue whose rule lists the waiting requests in reverse order of arrival; A then B wait for H's unit. */
    private class ReverseRule(parent: ModelElement) : ProcessModel(parent, "rr") {
        val r = ResourceWithQ(this, "R")
        val served = mutableListOf<String>()

        inner class Job(val tag: String, val arrive: Double, val hold: Double) : Entity(tag) {
            val p: KSLProcess = process {
                delay(arrive)
                val a = seize(r)
                served.add("$tag@$time")
                delay(hold); release(a)
            }
        }

        override fun initialize() {
            served.clear()
            r.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                requestQ.filter { it.resource === resource }.reversed()
            }
            activate(Job("H", 0.0, 10.0).p)
            activate(Job("A", 1.0, 1.0).p)
            activate(Job("B", 2.0, 1.0).p)
        }
    }

    @Test
    @DisplayName("with a request selection rule, a release serves the first of the rule's list, as an arrival takes it")
    fun releaseTakesTheRulesListOrder() {
        assertEquals(listOf("H@0.0", "B@10.0", "A@11.0"), simulate { ReverseRule(it) }.served)
    }

    /** A pool of one free resource whose queue's rule chooses only entities named X: Y asks the pool at 1. */
    private class RuledPool(parent: ModelElement) : ProcessModel(parent, "rp") {
        val r = Resource(this, "R")
        val pool = ResourcePoolWithQ(this, listOf(r), name = "Pool")
        val served = mutableListOf<String>()

        inner class Job(val tag: String, val arrive: Double) : Entity(tag) {
            val p: KSLProcess = process {
                delay(arrive)
                val a = seize(pool)
                served.add("$tag@$time")
                delay(1.0); release(a)
            }
        }

        override fun initialize() {
            served.clear()
            pool.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { _, requestQ ->
                requestQ.filter { it.entity.name.startsWith("X") }
            }
            activate(Job("Y", 1.0).p)
            activate(Job("X", 5.0).p)
        }
    }

    @Test
    @DisplayName("a pool seize arriving respects its queue's request selection rule, as a seize of a resource does")
    fun poolArrivalRespectsTheRule() {
        assertEquals(listOf("X@5.0"), simulate(length = 50.0) { RuledPool(it) }.served)
    }

    /** A pool of R1 and R2, one unit each, held until 5; A asks for 2 that one member must supply, B for 1 at 6. */
    private class UnservablePoolRequest(parent: ModelElement) : ProcessModel(parent, "up") {
        val r1 = Resource(this, "R1")
        val r2 = Resource(this, "R2")
        val pool = ResourcePoolWithQ(this, listOf(r1, r2), name = "P")
        val holdersQ = RequestQ(this, "HoldersQ")
        val served = mutableListOf<String>()

        inner class Holder(val r: Resource) : Entity() {
            val p: KSLProcess = process { val a = seize(r, queue = holdersQ); delay(5.0); release(a) }
        }

        override fun initialize() {
            served.clear()
            activate(Holder(r1).p)
            activate(Holder(r2).p)
            activate(object : Entity("A") {
                val p: KSLProcess = process {
                    delay(1.0)
                    val a = seize(pool, 2, resourceSelectionRule = FirstFullyAvailableResource())
                    served.add("A@$time"); release(a)
                }
            }.p)
            activate(object : Entity("B") {
                val p: KSLProcess = process { delay(6.0); val a = seize(pool, 1); served.add("B@$time"); delay(1.0); release(a) }
            }.p)
        }
    }

    @Test
    @DisplayName("a pool request its own selection rule cannot serve does not keep a later one from units the pool has free")
    fun unservablePoolRequestDoesNotBlock() {
        assertEquals(listOf("B@6.0"), simulate { UnservablePoolRequest(it) }.served)
    }

    /**
     *  R2 has two units, one held to 50 by an entity as urgent as P; its queue's rule chooses only once two requests
     *  wait, then the first that fits. X waits alone beside the free unit from 2; at 5 P arrives at R2, or is moved
     *  there from R1.
     */
    private class MovedPreemptorUnderARule(parent: ModelElement, val move: Boolean) : ProcessModel(parent, "mr") {
        val r1 = ResourceWithQ(this, "R1")
        val r2 = ResourceWithQ(this, "R2", capacity = 2)
        val served = mutableMapOf<String, Double>()

        inner class Job(tag: String, pri: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
            init {
                priority = pri
            }

            val p: KSLProcess = process { body() }

            fun moveTo(from: ResourceWithQ, to: ResourceWithQ) =
                moveRequestToResource(from.myWaitingQ.peekNext() as ResourceRequest, from.myWaitingQ, to, RESUME_PRIORITY)
        }

        override fun initialize() {
            served.clear()
            r2.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { resource, requestQ ->
                val mine = requestQ.filter { it.resource === resource }
                if (mine.size >= 2) mine.filter { it.amountRequested <= resource.numAvailableUnits }.take(1) else emptyList()
            }
            activate(Job("H1", 1) { val a = seize(r1); delay(100.0); release(a) }.p)
            activate(Job("H2", 1) { val a = seize(r2); delay(50.0); release(a) }.p)
            activate(Job("X", 5) { delay(2.0); val a = seize(r2); served["X"] = time; delay(1.0); release(a) }.p)
            val p = Job("P", 1) {
                delay(if (move) 1.0 else 5.0)
                val a = preempt(if (move) r1 else r2)
                served["P"] = time; delay(1.0); release(a)
            }
            activate(p.p)
            if (move) schedule(EventActionIfc<Nothing> {
                p.moveTo(r1, r2)
            }, 5.0)
        }

    }

    @Test
    @DisplayName("a preemptor moved into a queue with a rule lets the rule choose among the waiting requests, as an arriving one does")
    fun movedPreemptorUnderARule() {
        val arriving = simulate { MovedPreemptorUnderARule(it, move = false) }.served["X"]
        val moved = simulate { MovedPreemptorUnderARule(it, move = true) }.served["X"]
        assertEquals(arriving, moved)
    }

    /**
     *  Pool PB has one member of capacity 2; its queue's rule chooses only once two requests wait, then the first. W
     *  waits from 2; at 5 N arrives at PB, or is moved into it from PA.
     */
    private class MovedIntoARuledPool(parent: ModelElement, val move: Boolean) : ProcessModel(parent, "mp") {
        val ra = Resource(this, "RA")
        val rb = Resource(this, "RB", capacity = 2)
        val pa = ResourcePoolWithQ(this, listOf(ra), name = "PA")
        val pb = ResourcePoolWithQ(this, listOf(rb), name = "PB")
        val served = mutableMapOf<String, Double>()
        lateinit var n: Entity

        override fun initialize() {
            served.clear()
            pb.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { _, requestQ ->
                if (requestQ.size >= 2) requestQ.take(1) else emptyList()
            }
            activate(object : Entity("HA") { val p: KSLProcess = process { val a = seize(pa); delay(100.0); release(a) } }.p)

            activate(object : Entity("W") { val p: KSLProcess = process { delay(2.0); val a = seize(pb); served["W"] = time; delay(1.0); release(a) } }.p)
            val nEntity = object : Entity("N") {
                val p: KSLProcess = process {
                    delay(if (move) 1.0 else 5.0)
                    val a = seize(if (move) pa else pb); served["N"] = time; delay(1.0); release(a)
                }

                fun moveIn() = moveRequestToResourcePool(
                    pa.myWaitingQ.peekNext() as ResourcePoolRequest, pa.myWaitingQ, pb, pb.defaultResourceSelectionRule, RESUME_PRIORITY
                )
            }
            activate(nEntity.p)
            if (move) schedule(EventActionIfc<Nothing> { nEntity.moveIn() }, 5.0)
        }
    }

    @Test
    @DisplayName("a request moved into a pool's queue is served as one arriving there would be, by the queue's rule")
    fun movedIntoARuledPool() {
        val arriving = simulate { MovedIntoARuledPool(it, move = false) }.served
        val moved = simulate { MovedIntoARuledPool(it, move = true) }.served
        assertEquals(arriving["W"], moved["W"])
    }

    /**
     *  As `MovedIntoARuledPool`, but N waits at PA from 1 and is woken there at 5, when HA releases, and is moved into
     *  PB in that instant, before it resumes. PB's rule chooses W, which has waited since 2.
     */
    private class WokenMovedIntoARuledPool(parent: ModelElement, val move: Boolean) : ProcessModel(parent, "wm") {
        val ra = Resource(this, "RA")
        val rb = Resource(this, "RB", capacity = 2)
        val pa = ResourcePoolWithQ(this, listOf(ra), name = "PA")
        val pb = ResourcePoolWithQ(this, listOf(rb), name = "PB")
        val served = mutableMapOf<String, Double>()

        override fun initialize() {
            served.clear()
            pb.myWaitingQ.requestSelectionRule = RequestSelectionRuleIfc { _, requestQ ->
                if (requestQ.size >= 2) requestQ.take(1) else emptyList()
            }
            // the release at 5 wakes N to resume at priority 20000
            activate(object : Entity("HA") { val p: KSLProcess = process { val a = seize(pa); delay(5.0); release(a, releasePriority = 20000) } }.p)
            activate(object : Entity("W") { val p: KSLProcess = process { delay(2.0); val a = seize(pb); served["W"] = time; delay(1.0); release(a) } }.p)
            val nEntity = object : Entity("N") {
                val p: KSLProcess = process {
                    delay(if (move) 1.0 else 5.0)
                    val a = seize(if (move) pa else pb); served["N"] = time; delay(1.0); release(a)
                }

                fun moveIn() = moveRequestToResourcePool(
                    pa.myWaitingQ.peekNext() as ResourcePoolRequest, pa.myWaitingQ, pb, pb.defaultResourceSelectionRule, RESUME_PRIORITY
                )
            }
            activate(nEntity.p)
            // after HA's release at 5 has woken N, before N resumes
            if (move) schedule(EventActionIfc<Nothing> { nEntity.moveIn() }, 5.0, priority = 15000)
        }
    }

    @Test
    @DisplayName("a woken request moved into a queue with a rule is served only if the rule chooses it, as one arriving there")
    fun wokenRequestMovedIntoARuledPool() {
        val arriving = simulate { WokenMovedIntoARuledPool(it, move = false) }.served
        val moved = simulate { WokenMovedIntoARuledPool(it, move = true) }.served
        assertEquals(arriving["W"], moved["W"], "W, which the rule chooses, is served at 5")
    }
}
