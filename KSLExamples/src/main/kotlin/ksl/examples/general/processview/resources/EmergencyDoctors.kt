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

package ksl.examples.general.processview.resources

import ksl.modeling.entity.Allocation
import ksl.modeling.entity.DelayResult
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.StatisticIfc

/** How a routine patient's treatment reacts when a critical patient takes its doctor. */
enum class Reaction {
    /** A plain `delay`: the library holds it while the doctor is away, as the doctors' rule says. */
    LIBRARY,

    /** `delayOrInterrupt`: the patient is told, waits for its doctor to come back, and goes on with what is left. */
    DELAY_OR_INTERRUPT,

    /** A wait for the first of the doctor being taken and the treatment time running out, then for the doctor. */
    TRIGGERS
}

/**
 *  An emergency department with two doctors, one resource of capacity 2. Critical patients and routine patients
 *  arrive in independent Poisson streams, and every treatment is exponential with the same mean. A routine patient
 *  takes a bay, then a doctor; a critical patient takes a doctor from a routine patient at once if none is free.
 *  Under `InterruptRule.RESUME` the routine patient's treatment goes on with the time it had left when the doctor
 *  comes back; under `InterruptRule.RESTART` it starts again in full. A routine patient whose doctor has not come
 *  back within `transferAfter` minutes is transferred to another hospital.
 */
class EmergencyDepartment(
    parent: ModelElement,
    val rule: InterruptRule = InterruptRule.RESUME,
    val reaction: Reaction = Reaction.LIBRARY,
    val transferAfter: Double = Double.POSITIVE_INFINITY,
    val criticalRate: Double = 1.0 / 60.0,
    val routineRate: Double = 1.0 / 30.0,
    val meanTreatment: Double = 30.0,
    numBays: Int = 20,
    name: String? = null
) : ProcessModel(parent, name) {

    private val criticalArrivals = RandomVariable(this, ExponentialRV(1.0 / criticalRate, 1), "TimeBetweenCritical")
    private val routineArrivals = RandomVariable(this, ExponentialRV(1.0 / routineRate, 2), "TimeBetweenRoutine")
    private val treatment = RandomVariable(this, ExponentialRV(meanTreatment, 3), "Treatment")

    private val doctors: ResourceWithQ = ResourceWithQ(
        this, name = "Doctors", capacity = 2, queue = RequestQ(this, "Doctors:Q", Queue.Discipline.RANKED)
    )
    private val bays = ResourceWithQ(this, "Bays", capacity = numBays)

    val criticalTime = Response(this, "CriticalTime")
    val routineTime = Response(this, "RoutineTime")
    val transferred = Response(this, "ProbTransfer")
    val routineTimesPreempted = Response(this, "RoutineTimesPreempted")

    init {
        doctors.interruptRule = rule
    }

    private inner class CriticalPatient : Entity() {
        init {
            priority = 1
        }

        val visit: KSLProcess = process {
            val doctor = preempt(doctors)
            delay(treatment)
            release(doctor)
            criticalTime.value = time - createTime
        }
    }

    private inner class RoutinePatient : Entity() {
        init {
            priority = 2
        }

        val visit: KSLProcess = process {
            val bay = seize(bays)
            val doctor = seize(doctors)
            val work = treatment.value
            val leaves = when (reaction) {
                Reaction.LIBRARY -> {
                    delay(work)
                    false
                }
                Reaction.DELAY_OR_INTERRUPT -> treatReacting(work, doctor, bay)
                Reaction.TRIGGERS -> treatWatching(work, doctor)
            }
            routineTimesPreempted.value = doctor.timesInterrupted.toDouble()
            release(doctor)
            release(bay)
            transferred.value = if (leaves) 1.0 else 0.0
            if (!leaves) routineTime.value = time - createTime
        }
    }

    /**
     *  Treats for [work] minutes with `delayOrInterrupt`, which ends early when a unit of [doctor] or [bay] is
     *  taken. The patient then waits for its doctor to come back, for at most [transferAfter], and goes on with the
     *  time it had left. True if it is transferred.
     */
    private suspend fun KSLProcessBuilder.treatReacting(work: Double, doctor: Allocation, bay: Allocation): Boolean {
        var left = work
        while (true) {
            when (val result = delayOrInterrupt(left, uses = listOf(doctor, bay))) {
                is DelayResult.Completed -> return false
                is DelayResult.Interrupted -> {
                    left = result.timeRemaining
                    val back = waitForFirst(result.by.restoredTrigger(), timeout = transferAfter)
                    if (back.timedOut) return true
                }
            }
        }
    }

    /**
     *  Treats for [work] minutes by waiting for the first of [doctor] being taken and the time running out. When
     *  the doctor is taken, the patient waits for it to come back, for at most [transferAfter], and goes on with
     *  the time it had left. True if it is transferred.
     */
    private suspend fun KSLProcessBuilder.treatWatching(work: Double, doctor: Allocation): Boolean {
        var left = work
        while (true) {
            val due = time + left
            val taken = waitForFirst(doctor.interruptedTrigger(), timeout = left)
            if (taken.timedOut) return false
            left = due - time
            val back = waitForFirst(doctor.restoredTrigger(), timeout = transferAfter)
            if (back.timedOut) return true
        }
    }

    override fun initialize() {
        schedule(::criticalArrival, criticalArrivals)
        schedule(::routineArrival, routineArrivals)
    }

    private fun criticalArrival(event: KSLEvent<Nothing>) {
        activate(CriticalPatient().visit)
        schedule(::criticalArrival, criticalArrivals)
    }

    private fun routineArrival(event: KSLEvent<Nothing>) {
        activate(RoutinePatient().visit)
        schedule(::routineArrival, routineArrivals)
    }
}

/**
 *  The mean time in the department of each class of patient, for an M/M/c queue with two priority classes of equal
 *  treatment rate [mu], class 1 (critical, arrival rate [lambda1]) preempting class 2 (routine, [lambda2]) and
 *  resuming it. The critical patients see an M/M/c queue of their own. All patients together are an M/M/c queue
 *  of the combined rate, since preemption with resumption of equal exponential work changes who is treated, not
 *  how many; so the routine patients' mean number is the combined mean number less the critical one.
 */
class TwoClassPreemptiveMMc(val lambda1: Double, val lambda2: Double, val mu: Double, val c: Int) {

    /** The mean time in an M/M/c queue with arrival rate [lambda]. */
    private fun timeInSystem(lambda: Double): Double {
        val a = lambda / mu
        require(a < c) { "The queue must be stable: offered load $a with $c servers" }
        var term = 1.0
        var sum = 1.0
        for (k in 1 until c) {
            term *= a / k
            sum += term
        }
        val waitingTerm = term * a / c / (1.0 - a / c)
        val probWait = waitingTerm / (sum + waitingTerm)
        return probWait / (c * mu - lambda) + 1.0 / mu
    }

    /** The mean time in the department of a critical patient. */
    val criticalTime: Double = timeInSystem(lambda1)

    /** The mean time in the department of a routine patient. */
    val routineTime: Double = ((lambda1 + lambda2) * timeInSystem(lambda1 + lambda2) - lambda1 * criticalTime) / lambda2
}

/**
 *  Runs the department under `RESUME`, against the exact values, and under `RESTART`; then the three ways of
 *  reacting to a preemption, which must agree exactly; then transfers after 20 minutes without a doctor.
 */
fun main() {
    fun run(build: (Model) -> EmergencyDepartment): EmergencyDepartment {
        val m = Model("EmergencyDoctors", autoCSVReports = false)
        val ed = build(m)
        m.numberOfReplications = 30
        m.lengthOfReplication = 200_000.0
        m.lengthOfReplicationWarmUp = 20_000.0
        m.simulate()
        return ed
    }
    val resume = run { EmergencyDepartment(it, name = "ED") }
    val exact = TwoClassPreemptiveMMc(resume.criticalRate, resume.routineRate, 1.0 / resume.meanTreatment, 2)
    println("Two doctors under RESUME, against the exact two-class M/M/2 values, 99% confidence intervals")
    println(String.format("%-24s %10s %22s %10s %8s", "time in department", "estimate", "interval", "exact", "inside"))
    compareTimes("critical patients", resume.criticalTime.acrossReplicationStatistic, exact.criticalTime)
    compareTimes("routine patients", resume.routineTime.acrossReplicationStatistic, exact.routineTime)

    val restart = run { EmergencyDepartment(it, rule = InterruptRule.RESTART, name = "ED") }
    println()
    println("The same department under RESTART: a taken treatment starts again in full")
    println(String.format("%-24s %10s %12s %14s", "time in department", "RESUME", "RESTART", "half-width"))
    for ((label, a, b) in listOf(
        Triple("critical patients", resume.criticalTime, restart.criticalTime),
        Triple("routine patients", resume.routineTime, restart.routineTime)
    )) {
        val x = a.acrossReplicationStatistic
        val y = b.acrossReplicationStatistic
        println(String.format("%-24s %10.4f %12.4f %14.4f", label, x.average, y.average, y.halfWidth))
    }

    val reacting = run { EmergencyDepartment(it, reaction = Reaction.DELAY_OR_INTERRUPT, name = "ED") }
    val watching = run { EmergencyDepartment(it, reaction = Reaction.TRIGGERS, name = "ED") }
    println()
    println("Three ways of going on after a preemption under RESUME")
    println(String.format("%-24s %10s %18s %10s %6s", "statistic", "library", "delayOrInterrupt", "triggers", "same"))
    var allSame = true
    for ((label, pick) in listOf<Pair<String, (EmergencyDepartment) -> StatisticIfc>>(
        "critical time" to { it.criticalTime.acrossReplicationStatistic },
        "routine time" to { it.routineTime.acrossReplicationStatistic },
        "times preempted" to { it.routineTimesPreempted.acrossReplicationStatistic }
    )) {
        val (a, b, c) = Triple(pick(resume), pick(reacting), pick(watching))
        val same = listOf(b, c).all { it.average == a.average && it.variance == a.variance && it.count == a.count }
        allSame = allSame && same
        println(String.format("%-24s %10.4f %18.4f %10.4f %6s", label, a.average, b.average, c.average, if (same) "yes" else "NO"))
    }
    println(if (allSame) "The three agree exactly." else "The three DIFFER.")

    val transfers = run { EmergencyDepartment(it, reaction = Reaction.DELAY_OR_INTERRUPT, transferAfter = 20.0, name = "ED") }
    println()
    println("Routine patients transferred when their doctor is not back within 20 minutes")
    println(String.format("%-36s %10.4f", "share of routine patients transferred", transfers.transferred.acrossReplicationStatistic.average))
    println(String.format("%-36s %10.4f", "routine time, those who stay", transfers.routineTime.acrossReplicationStatistic.average))
    println()
    println("Read this alongside docs/guides/ksl-resources-tutorial.md, case 5.")
}

private fun compareTimes(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-24s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
