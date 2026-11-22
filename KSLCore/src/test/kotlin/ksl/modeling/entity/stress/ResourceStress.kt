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
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.ResourceFailureBasis
import ksl.simulation.Model
import kotlin.random.Random

/**
 *  Random scenarios for one resource with a queue: jobs that seize or preempt it, some holding a second
 *  unit, some reporting interruptions through `delayOrInterrupt`, some working without the resource, against
 *  failures, shifts, capacity change notices, declared failures, capacity schedules and terminations. Times
 *  are whole multiples of a step so that many events tie. Not a test class itself; see `ResourceStressTest`.
 */
internal object ResourceStress {

    class JobSpec(
        val name: String, val priority: Int, val arrival: Double, val work: Double, val amount: Int,
        val preempt: InterruptRule?, val reporting: Boolean, val second: Boolean, val indep: Boolean = false
    ) {
        override fun toString() =
            "$name(p$priority a=$arrival w=$work n=$amount ${preempt?.let { "preempt-$it" } ?: "seize"}" +
                    "${if (reporting) " report" else ""}${if (second) " two" else ""}${if (indep) " indep" else ""})"
    }

    class Disturb(val kind: String, val at: Double, val dur: Double, val rule: InterruptRule, val cap: Int) {
        override fun toString() = "$kind@$at dur=$dur rule=$rule cap=$cap"
    }

    class Scenario(
        val seed: Int, val capacity: Int, val changeRule: CapacityChangeRule, val defaultRule: InterruptRule,
        val jobs: List<JobSpec>, val disturbs: List<Disturb>,
        val declared: Triple<ResourceFailureBasis, Double, Double>? = null,
        val declaredRule: InterruptRule = InterruptRule.FINISH,
        val schedule: List<Pair<Int, Double>>? = null
    ) {
        override fun toString() =
            "seed=$seed cap=$capacity $changeRule default=$defaultRule declared=$declared/$declaredRule schedule=$schedule\n" +
                    "  jobs=$jobs\n  disturbs=$disturbs"
    }

    /**
     *  The scenario of [seed]. [allowRestart] false keeps to FINISH and RESUME; [withDeclared] may add a
     *  declared failure process; [extras] adds jobs that work without the resource and terminations;
     *  [withSchedule] drives the capacity by a repeating schedule, keeping only failures and terminations.
     */
    fun scenario(
        seed: Int, allowRestart: Boolean = true, timeStep: Double = 1.0, allowTwo: Boolean = false,
        withDeclared: Boolean = false, extras: Boolean = false, withSchedule: Boolean = false
    ): Scenario {
        val r = Random(seed)
        val cap = 1 + r.nextInt(3)
        val rules = if (allowRestart) InterruptRule.entries else listOf(InterruptRule.FINISH, InterruptRule.RESUME)
        fun rule() = rules[r.nextInt(rules.size)]
        val n = 3 + r.nextInt(5)
        val jobs = (1..n).map { i ->
            JobSpec(
                "J$i", 1 + r.nextInt(4), r.nextInt(16) * timeStep, (1 + r.nextInt(5)) * timeStep,
                1 + r.nextInt(minOf(2, cap)),
                if (r.nextInt(3) == 0) null else rule(), r.nextInt(4) == 0, r.nextInt(6) == 0 && allowTwo
            )
        }
        val mode = r.nextInt(3)
        val d = (0 until r.nextInt(5)).map {
            val kind = when (r.nextInt(if (mode == 0) 1 else 2)) {
                0 -> "fail"
                else -> if (mode == 1) "shift" else "notice"
            }
            Disturb(
                kind, (1 + r.nextInt(18)) * timeStep,
                if (kind == "notice" && r.nextInt(3) == 0) Double.POSITIVE_INFINITY else (1 + r.nextInt(6)) * timeStep,
                rule(), r.nextInt(cap + 1)
            )
        }
        val cr = if (r.nextBoolean()) CapacityChangeRule.IGNORE else CapacityChangeRule.WAIT
        val dr = rule()
        val declared = if (withDeclared && r.nextInt(2) == 0) {
            val basis = ResourceFailureBasis.entries[r.nextInt(3)]
            Triple(
                basis,
                if (basis == ResourceFailureBasis.USES) (1 + r.nextInt(3)).toDouble() else (6 + r.nextInt(5)) * timeStep,
                (1 + r.nextInt(3)) * timeStep
            )
        } else null
        val declRule = rule()
        var jobs2 = jobs
        var d2 = d
        var schedule: List<Pair<Int, Double>>? = null
        if (extras) {
            jobs2 = jobs.map {
                JobSpec(it.name, it.priority, it.arrival, it.work, it.amount, it.preempt, it.reporting && r.nextInt(2) == 0, false, r.nextInt(4) == 0)
            }.map {
                if (it.indep) JobSpec(it.name, it.priority, it.arrival, it.work, it.amount, it.preempt, false, false, true) else it
            }
            d2 = d + (0 until r.nextInt(3)).map { Disturb("kill", (1 + r.nextInt(18)) * timeStep, 0.0, InterruptRule.FINISH, r.nextInt(n)) }
        }
        if (withSchedule) {
            d2 = d2.filter { it.kind == "fail" || it.kind == "kill" }
            val items = mutableListOf(cap to (6 + r.nextInt(4)) * timeStep)
            for (k in 0 until 1 + r.nextInt(3)) items.add(r.nextInt(cap + 1) to (1 + r.nextInt(6)) * timeStep)
            schedule = items
        }
        return Scenario(seed, cap, cr, dr, jobs2, d2, declared, declRule, schedule)
    }

    /**
     *  Runs [s] for one replication. The first of the pair is null when the run was clean, else a description
     *  of what went wrong (the model's own checks, or the exception that stopped it); the second is the
     *  model's event log.
     */
    fun run(s: Scenario): Pair<String?, List<String>> {
        val model = Model("Stress${s.seed}_${System.nanoTime()}", autoCSVReports = false)
        val m = ResourceStressModel(model, s)
        model.numberOfReplications = 1
        model.lengthOfReplication = 2000.0
        return try {
            model.simulate()
            (if (m.problems.isEmpty()) null else m.problems.joinToString("; ")) to m.log
        } catch (e: Throwable) {
            var c: Throwable = e
            while (c.cause != null && c.cause !== c) c = c.cause!!
            "THREW ${c::class.simpleName}: ${c.message?.take(400)}" to m.log
        }
    }
}
