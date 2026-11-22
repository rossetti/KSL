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

package ksl.modeling.entity.stress

import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.DelayResult
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV

/**
 *  The model of one `ResourceStress.Scenario`: a resource with a queue, the scenario's jobs and disturbances,
 *  and a cleanup that repairs, brings back on shift and restores the capacity late in the replication. At the
 *  end of a replication it records in [problems] any job that never finished, units still busy, a capacity
 *  not restored, seize and release counts that disagree with the allocations made, and state times that do
 *  not sum to the time elapsed. While a job works with no restarts possible, it also checks that its work
 *  took its work time plus the time its allocation was interrupted.
 */
internal class ResourceStressModel(parent: ModelElement, val s: ResourceStress.Scenario) : ProcessModel(parent, "stress") {
    val res = ResourceWithQ(this, "R", capacity = s.capacity)
    val log = mutableListOf<String>()
    val done = mutableMapOf<String, Double>()
    val problems = mutableListOf<String>()
    var allocs = 0
    var releases = 0
    val killed = mutableSetOf<String>()
    val actors = mutableMapOf<String, Actor>()

    init {
        if (s.schedule == null) res.capacityChangeRule = s.changeRule
        else {
            val cs = CapacitySchedule(this, 0.0, autoStartOption = true, repeatable = true)
            for ((c, dur) in s.schedule) cs.addItem(c, dur)
            res.useSchedule(cs, s.changeRule)
        }
        res.interruptRule = s.defaultRule
        res.collectFailureStatistics()
        s.declared?.let { (basis, tb, rt) ->
            res.failures(ConstantRV(tb), ConstantRV(rt), basis, s.declaredRule)
        }
    }

    inner class Actor(tag: String, prio: Int, val body: suspend KSLProcessBuilder.() -> Unit) : Entity(tag) {
        init {
            priority = prio
        }

        val p: KSLProcess = process("p-$tag") { body() }
    }

    fun actor(tag: String, priority: Int = 5, body: suspend KSLProcessBuilder.() -> Unit) =
        Actor(tag, priority, body).also { activate(it.p) }

    private suspend fun KSLProcessBuilder.work(j: ResourceStress.JobSpec, tag: String) {
        if (j.indep) {
            delay(j.work, uses = emptyList(), suspensionName = "work")
        } else if (!j.reporting) {
            delay(j.work, suspensionName = "work")
        } else {
            var left = j.work
            var turns = 0
            while (true) {
                val rr = delayOrInterrupt(left)
                if (rr is DelayResult.Completed) break
                rr as DelayResult.Interrupted
                log.add("$tag interrupted@$time $rr")
                left = if (rr.rule == InterruptRule.RESTART) j.work else rr.timeRemaining
                waitForFirst(rr.by.restoredTrigger())
                if (++turns > 500) {
                    problems.add("$tag: 500 turns of delayOrInterrupt"); break
                }
            }
        }
    }

    override fun initialize() {
        log.clear(); done.clear(); allocs = 0; releases = 0; killed.clear(); actors.clear()
        for (j in s.jobs) actors[j.name] = actor(j.name, j.priority) {
            delay(j.arrival)
            val a = if (j.preempt == null) seize(res, j.amount) else preempt(res, j.amount, rule = j.preempt)
            allocs++
            val got = time
            log.add("${j.name} got@$time")
            val b = if (j.second && j.amount == 1 && res.initialCapacity > 1) {
                seize(res, 1).also { allocs++; log.add("${j.name} got2@$time") }
            } else null
            val t0 = time
            work(j, j.name)
            val interrupted = a.timeInterrupted + (if (a.hasMissingUnits) Double.NaN else 0.0)
            log.add("${j.name} done@$time interrupted=${a.timeInterrupted} times=${a.timesInterrupted}")
            if (j.indep && time - t0 != j.work) problems.add("${j.name}: indep work took ${time - t0}, not ${j.work}")
            if (b == null && !j.reporting && !j.indep) {
                val elapsed = time - t0
                if (elapsed < j.work - 1e-9) problems.add("${j.name}: worked $elapsed < ${j.work}")
                if (restartFree && Math.abs(elapsed - (j.work + interrupted)) > 1e-9) {
                    // a delay due at the instant of a taking completes: interruption of zero length is fine
                    problems.add("${j.name}: got@$got workStart@$t0 done@$time work=${j.work} interrupted=$interrupted")
                }
            }
            if (b != null) {
                release(b); releases++
            }
            release(a); releases++
            done[j.name] = time
        }
        for ((i, d) in s.disturbs.withIndex()) actor("D$i", 0) {
            delay(d.at)
            when (d.kind) {
                "fail" -> if (!res.isFailed) {
                    log.add("fail(${d.rule})@$time"); res.fail(d.rule)
                    delay(d.dur)
                    if (res.isFailed) {
                        log.add("repair@$time"); res.repair()
                    }
                }
                "shift" -> if (!res.isOffShift) {
                    log.add("off@$time"); res.goOffShift()
                    delay(d.dur)
                    if (res.isOffShift) {
                        log.add("on@$time"); res.goOnShift()
                    }
                }
                "kill" -> {
                    val e = actors.getValue(s.jobs[d.cap].name)
                    if (e.currentProcess != null && e.isSuspended) {
                        log.add("kill ${e.name}@$time holding=${res.allocations(e).map { it.amount }}")
                        releases += res.allocations(e).size
                        killed.add(s.jobs[d.cap].name)
                        e.terminateProcess()
                    }
                }
                "notice" -> {
                    // a decrease that would end before a pending one under IGNORE is refused by the library
                    val end = res.pendingCapacityChangeEndTime
                    val ok = !(res.capacityChangeRule == CapacityChangeRule.IGNORE && res.isPendingCapacityChange &&
                            d.cap < res.capacity && end.isFinite() && time + d.dur <= end)
                    if (ok) {
                        log.add("notice(${d.cap},${d.dur})@$time cap=${res.capacity}")
                        res.changeCapacity(res.CapacityChangeNotice(d.cap, d.dur))
                    }
                }
            }
        }
        actor("cleanup", 0) {
            delay(40.0 * 1.0)
            if (res.isFailed) {
                log.add("repair@$time"); res.repair()
            }
            if (res.isOffShift) {
                log.add("on@$time"); res.goOnShift()
            }
            delay(1.0)
            log.add("restore@$time cap=${res.capacity} pending=${res.isPendingCapacityChange}")
            if (s.schedule == null) res.changeCapacity(res.CapacityChangeNotice(s.capacity))
        }
    }

    /** True when no rule of the scenario can restart a job's work. */
    val restartFree: Boolean
        get() = s.defaultRule != InterruptRule.RESTART && s.jobs.none { it.preempt == InterruptRule.RESTART } &&
                s.disturbs.none { it.rule == InterruptRule.RESTART } &&
                (s.declared == null || s.declaredRule != InterruptRule.RESTART)

    override fun replicationEnded() {
        for (j in s.jobs) if (j.name !in done && j.name !in killed) problems.add("${j.name} never finished")
        if (res.numBusy != 0) problems.add("numBusy=${res.numBusy} at end")
        if (s.schedule == null && res.capacity != s.capacity) problems.add("capacity=${res.capacity} at end, not ${s.capacity}")
        if (killed.isEmpty() && res.numTimesSeized != allocs) problems.add("numTimesSeized=${res.numTimesSeized} but $allocs allocations")
        if (res.numTimesReleased != releases) problems.add("numTimesReleased=${res.numTimesReleased} but $releases releases")
        if (res.numTimesSeized < releases) problems.add("numTimesSeized=${res.numTimesSeized} < $releases releases")
        val total = res.busyState.totalTimeInState + res.idleState.totalTimeInState +
                res.inactiveState.totalTimeInState + res.failedState.totalTimeInState
        if (Math.abs(total - time) > 1e-6) problems.add("state times sum to $total, not $time")
    }
}
