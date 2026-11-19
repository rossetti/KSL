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

import ksl.modeling.queue.Queue
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 *  Randomized checks of the resource core against an independent reference and against itself.
 *
 *  The reference is a one-unit preemptive-resume priority server written here from the rule, not from the
 *  library: the most urgent job present is served, a more urgent arrival takes the unit from a less urgent one,
 *  which resumes its remaining work later, equals never preempt, and ties go to a job already started and then to
 *  the earliest arrival. A shift takes the unit away from new work: what is in service finishes, and a job whose
 *  unit was taken gets it back when its taker finishes, but nothing new starts and nothing is preempted until the
 *  shift returns, under either capacity change rule: under WAIT the cut waits for the unit, and a return to shift
 *  before then withdraws it, so the return has the same effect as under IGNORE.
 *
 *  The self-comparisons run one scenario two ways that the library promises are the same: an untimed seize and a
 *  timed seize whose time never runs out; and a queue with no request selection rule and one whose rule chooses
 *  every request, in the queue's order.
 *
 *  The scenarios draw their times from a continuous distribution, so no two events fall at the same instant and
 *  the result does not depend on how ties between events are broken.
 */
class RandomizedResourceCheckTest {

    private class Job(val name: String, val priority: Int, val arrival: Double, val work: Double)

    private class Shift(val off: Double, val on: Double, val rule: CapacityChangeRule)

    private class Scenario(val jobs: List<Job>, val shift: Shift?) {
        override fun toString(): String =
            jobs.joinToString(prefix = "jobs=[", postfix = "]") { "${it.name}(p${it.priority} a=${f(it.arrival)} w=${f(it.work)})" } +
                    (shift?.let { " shift off=${f(it.off)} on=${f(it.on)} ${it.rule}" } ?: "")

        private fun f(x: Double) = "%.4f".format(x)
    }

    private fun scenario(random: Random, withShift: Boolean): Scenario {
        val n = 3 + random.nextInt(6)
        val jobs = (1..n).map { i ->
            Job("J$i", 1 + random.nextInt(3), random.nextDouble(0.0, 20.0), random.nextDouble(0.5, 6.0))
        }
        val shift = if (withShift && random.nextBoolean()) {
            val off = random.nextDouble(0.0, 15.0)
            Shift(off, off + random.nextDouble(1.0, 10.0),
                if (random.nextBoolean()) CapacityChangeRule.IGNORE else CapacityChangeRule.WAIT)
        } else null
        return Scenario(jobs, shift)
    }

    // ---------------------------------------------------------------------------------------------------------
    // the reference
    // ---------------------------------------------------------------------------------------------------------

    /** The completion time of each job under the rule stated in the class comment. */
    private fun reference(s: Scenario): Map<String, Double> {
        class Live(val job: Job) {
            var remaining = job.work
            var started = false
        }
        val done = mutableMapOf<String, Double>()
        val arrivals = s.jobs.sortedBy { it.arrival }.toMutableList()
        val waiting = mutableListOf<Live>()       // not yet started, in arrival order
        val suspended = mutableListOf<Live>()     // preempted, most recent last
        var current: Live? = null
        var since = 0.0
        var onShift = true
        val offAt = s.shift?.off ?: Double.POSITIVE_INFINITY
        val onAt = s.shift?.on ?: Double.POSITIVE_INFINITY
        var offDone = s.shift == null
        var onDone = s.shift == null

        fun urgency(l: Live) = l.job.priority

        /** The next job to serve: the most urgent present; a started job before a waiting one; then by arrival. */
        fun next(): Live? {
            val pool = suspended + waiting
            return pool.minWithOrNull(
                compareBy<Live>({ urgency(it) }, { if (it.started) 0 else 1 }, { it.job.arrival })
            )
        }

        fun start(l: Live, t: Double) {
            suspended.remove(l)
            waiting.remove(l)
            l.started = true
            current = l
            since = t
        }

        var t = 0.0
        while (true) {
            val c = current
            val completion = if (c != null) since + c.remaining else Double.POSITIVE_INFINITY
            val arrival = arrivals.firstOrNull()?.arrival ?: Double.POSITIVE_INFINITY
            val off = if (!offDone) offAt else Double.POSITIVE_INFINITY
            val on = if (offDone && !onDone) onAt else Double.POSITIVE_INFINITY
            val tNext = minOf(completion, arrival, off, on)
            if (tNext == Double.POSITIVE_INFINITY) break
            t = tNext
            when (tNext) {
                completion -> {
                    done[c!!.job.name] = t
                    current = null
                    // on shift, the most urgent job present; off shift, the unit goes back only to the job it was
                    // taken from, which goes on finishing the work it was allocated, and nothing new starts
                    val n = if (onShift) next() else suspended.lastOrNull()
                    if (n != null) start(n, t)
                }
                arrival -> {
                    val l = Live(arrivals.removeAt(0))
                    val cur = current
                    if (cur == null && onShift) {
                        start(l, t)
                    } else if (cur != null && onShift && urgency(l) < urgency(cur)) {
                        cur.remaining -= t - since
                        suspended.add(cur)
                        start(l, t)
                    } else {
                        waiting.add(l)
                    }
                }
                off -> {
                    offDone = true
                    onShift = false
                }
                on -> {
                    onDone = true
                    onShift = true
                    val cur = current
                    val n = next()
                    if (cur == null) {
                        if (n != null) start(n, t)
                    } else if (n != null && urgency(n) < urgency(cur)) {
                        cur.remaining -= t - since
                        suspended.add(cur)
                        start(n, t)
                    }
                }
            }
        }
        return done
    }

    // ---------------------------------------------------------------------------------------------------------
    // the library
    // ---------------------------------------------------------------------------------------------------------

    private enum class Way { PREEMPT, SEIZE, TIMED_SEIZE }

    private class Shop(
        parent: ModelElement, val s: Scenario, val way: Way, capacity: Int, discipline: Queue.Discipline,
        everyoneRule: Boolean
    ) : ProcessModel(parent, "Shop") {
        val queue = RequestQ(this, "Q", discipline).apply {
            if (everyoneRule) {
                requestSelectionRule = RequestSelectionRuleIfc { resource, q -> q.filter { it.resource == resource } }
            }
        }
        val r = ResourceWithQ(this, "R", capacity = capacity, queue = queue)
        val done = mutableMapOf<String, Double>()
        val log = mutableListOf<String>()

        init {
            s.shift?.let { r.capacityChangeRule = it.rule }
        }

        inner class Worker(val job: Job) : Entity(job.name) {
            init {
                priority = job.priority
            }

            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(job.arrival)
                val a = when (way) {
                    Way.PREEMPT -> preempt(r, rule = InterruptRule.RESUME)
                    Way.SEIZE -> seize(r)
                    Way.TIMED_SEIZE -> seizeOrTimeout(r, timeout = 1.0e6).value
                }
                log.add("${job.name} start@${"%.9f".format(time)}")
                delay(job.work)
                release(a)
                done[job.name] = time
                log.add("${job.name} done@${"%.9f".format(time)}")
            }
        }

        override fun initialize() {
            done.clear()
            log.clear()
            for (j in s.jobs) activate(Worker(j).p)
            s.shift?.let { sh ->
                schedule({ _: ksl.simulation.KSLEvent<Nothing> -> r.goOffShift() }, sh.off)
                schedule({ _: ksl.simulation.KSLEvent<Nothing> -> r.goOnShift() }, sh.on)
            }
        }
    }

    private fun simulate(
        s: Scenario, way: Way, capacity: Int = 1, discipline: Queue.Discipline = Queue.Discipline.RANKED,
        everyoneRule: Boolean = false
    ): Shop {
        val model = Model("Randomized${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, s, way, capacity, discipline, everyoneRule)
        model.numberOfReplications = 1
        model.lengthOfReplication = 1000.0
        model.simulate()
        return shop
    }

    @Test
    @DisplayName("preemptive resume on one unit, with and without a shift under either rule, agrees with the reference")
    fun preemptiveResumeAgreesWithTheReference() {
        val random = Random(20261119)
        val failures = mutableListOf<String>()
        repeat(400) { k ->
            val s = scenario(random, withShift = true)
            val expected = reference(s)
            val actual = try {
                simulate(s, Way.PREEMPT).done
            } catch (e: Throwable) {
                failures.add("scenario $k: the run stopped: $e\n  $s")
                return@repeat
            }
            val wrong = s.jobs.filter { j ->
                val e = expected[j.name]
                val a = actual[j.name]
                e == null || a == null || abs(e - a) > 1e-6
            }
            if (wrong.isNotEmpty()) {
                failures.add("scenario $k: " + wrong.joinToString { "${it.name} expected ${expected[it.name]} got ${actual[it.name]}" } + "\n  $s")
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of 400 scenarios disagree:\n" + failures.take(10).joinToString("\n"))
    }

    @Test
    @DisplayName("a timed seize whose time never runs out serves as an untimed seize does")
    fun timedSeizeMatchesSeize() {
        val random = Random(1119)
        repeat(300) { k ->
            val s = scenario(random, withShift = true)
            val capacity = 1 + random.nextInt(2)
            val discipline = if (random.nextBoolean()) Queue.Discipline.FIFO else Queue.Discipline.RANKED
            val rule = random.nextBoolean()
            val seize = simulate(s, Way.SEIZE, capacity, discipline, rule).log
            val timed = simulate(s, Way.TIMED_SEIZE, capacity, discipline, rule).log
            assertEquals(seize, timed, "scenario $k (capacity $capacity, $discipline, rule=$rule): $s")
        }
    }

    @Test
    @DisplayName("a request selection rule that chooses every request, in the queue's order, serves as no rule does")
    fun everyoneRuleMatchesNoRule() {
        val random = Random(2026)
        repeat(300) { k ->
            val s = scenario(random, withShift = true)
            val capacity = 1 + random.nextInt(2)
            val discipline = if (random.nextBoolean()) Queue.Discipline.FIFO else Queue.Discipline.RANKED
            val way = Way.entries[random.nextInt(Way.entries.size)]
            val plain = simulate(s, way, capacity, discipline, everyoneRule = false).log
            val ruled = simulate(s, way, capacity, discipline, everyoneRule = true).log
            assertEquals(plain, ruled, "scenario $k ($way, capacity $capacity, $discipline): $s")
        }
    }
}
