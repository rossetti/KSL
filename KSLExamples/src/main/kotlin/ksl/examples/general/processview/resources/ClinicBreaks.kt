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

import ksl.modeling.entity.CapacityChangeRule
import ksl.modeling.entity.CapacitySchedule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.observers.ReplicationDataCollector
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.Statistic

/** How the doctor of [ClinicWithBreaks] takes a break. */
enum class BreakPolicy {
    /** At the set time: no patient starts until the break is over, but the one being seen is finished. */
    AT_ONCE,

    /** The same breaks as [AT_ONCE], made by a capacity schedule instead of a behavior. */
    AT_ONCE_BY_SCHEDULE,

    /** In turn: the break joins the patients' queue at the set time and starts when the doctor is free for it. */
    IN_TURN
}

/**
 *  A walk-in clinic with one doctor. Patients arrive in a Poisson stream and are seen in order of arrival,
 *  for an exponential time. Every [timeBetweenBreaks] minutes of the doctor's working time, the doctor takes
 *  a break of [breakLength] minutes, as [policy] says.
 */
class ClinicWithBreaks(
    parent: ModelElement,
    val arrivalRate: Double = 1.0 / 6.0,
    val meanServiceTime: Double = 4.0,
    val timeBetweenBreaks: Double = 120.0,
    val breakLength: Double = 15.0,
    val policy: BreakPolicy = BreakPolicy.AT_ONCE,
    name: String? = null
) : ProcessModel(parent, name) {

    private val arrivals = RandomVariable(this, ExponentialRV(1.0 / arrivalRate, 1), "TimeBetweenArrivals")
    private val service = RandomVariable(this, ExponentialRV(meanServiceTime, 2), "ServiceTime")

    val doctor: ResourceWithQ = ResourceWithQ(this, name = "Doctor")

    val patientWait = Response(this, "PatientWait")
    val timeInClinic = Response(this, "TimeInClinic")
    private val breaksTaken = Counter(this, "BreaksTaken")
    val breakLateBy = Response(this, "BreakLateBy")

    init {
        when (policy) {
            BreakPolicy.AT_ONCE -> doctor.cyclicBehavior {
                delay(timeBetweenBreaks)
                doctor.goOffShift()
                breaksTaken.increment()
                delay(breakLength)
                doctor.goOnShift()
            }
            BreakPolicy.AT_ONCE_BY_SCHEDULE -> {
                val schedule = CapacitySchedule(this, startTime = 0.0, autoStartOption = true, repeatable = true)
                schedule.addItem(capacity = 1, duration = timeBetweenBreaks)
                schedule.addItem(capacity = 0, duration = breakLength)
                doctor.useSchedule(schedule, CapacityChangeRule.IGNORE)
            }
            BreakPolicy.IN_TURN -> doctor.cyclicBehavior {
                delay(timeBetweenBreaks)
                val due = time
                val onBreak = seize(doctor)
                breakLateBy.value = time - due
                breaksTaken.increment()
                delay(breakLength)
                release(onBreak)
            }
        }
    }

    private inner class Patient : Entity() {
        val visit: KSLProcess = process {
            val a = seize(doctor)
            patientWait.value = time - createTime
            delay(service)
            release(a)
            timeInClinic.value = time - createTime
        }
    }

    override fun initialize() {
        schedule(::arrival, arrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        activate(Patient().visit)
        schedule(::arrival, arrivals)
    }
}

fun main() {
    println("No breaks, against the exact M/M/1 answers")
    val plain = Model("ClinicBreaks", autoCSVReports = false)
    val noBreaks = ClinicWithBreaks(plain, name = "Clinic")
    plain.controls().control("Doctor.behaviorActiveAtStart")!!.value = 0.0
    plain.numberOfReplications = 20
    plain.lengthOfReplication = 50_000.0
    plain.lengthOfReplicationWarmUp = 5_000.0
    plain.simulate()
    val lambda = noBreaks.arrivalRate
    val mu = 1.0 / noBreaks.meanServiceTime
    val rho = lambda / mu
    val exact = mapOf(
        "PatientWait" to rho / (mu - lambda),
        "Doctor:Q:NumInQ" to rho * rho / (1.0 - rho),
        "Doctor:NumBusyUnits" to rho
    )
    val statistics = plain.responses.associate { it.name to it.acrossReplicationStatistic }
    println(String.format("%-18s %10s %22s %10s", "", "estimate", "99% interval", "exact"))
    for ((name, value) in exact) {
        val s = statistics.getValue(name)
        val ci = s.confidenceInterval(0.99)
        println(String.format("%-18s %10.4f   [%8.4f, %8.4f] %10.4f", name, s.average, ci.lowerLimit, ci.upperLimit, value))
    }

    println()
    println("A 15-minute break every 120 minutes, on common random numbers, against no breaks")
    val cases = listOf<Pair<String, BreakPolicy?>>(
        "no breaks" to null,
        "at once" to BreakPolicy.AT_ONCE,
        "at once, scheduled" to BreakPolicy.AT_ONCE_BY_SCHEDULE,
        "in turn" to BreakPolicy.IN_TURN,
    )
    val results = cases.map { (label, policy) ->
        val m = Model("ClinicBreaks", autoCSVReports = false)
        ClinicWithBreaks(m, policy = policy ?: BreakPolicy.AT_ONCE, name = "Clinic")
        if (policy == null) m.controls().control("Doctor.behaviorActiveAtStart")!!.value = 0.0
        m.numberOfReplications = 20
        m.lengthOfReplication = 50_000.0
        m.lengthOfReplicationWarmUp = 5_000.0
        val collector = ReplicationDataCollector(m)
        collector.addResponse("PatientWait")
        collector.addResponse("Doctor:NumBusyUnits")
        m.simulate()
        label to collector
    }
    val base = results.first().second.replicationData("PatientWait")
    println(String.format("%-20s %10s %10s %12s %10s %10s", "", "wait", "half-width", "difference", "half-width", "busy"))
    for ((label, collector) in results) {
        val data = collector.replicationData("PatientWait")
        val s = Statistic(data)
        val d = Statistic(DoubleArray(data.size) { data[it] - base[it] })
        val busy = Statistic(collector.replicationData("Doctor:NumBusyUnits")).average
        println(String.format("%-20s %10.3f %10.3f %12.3f %10.3f %10.4f", label, s.average, s.halfWidth, d.average, d.halfWidth, busy))
    }
    println()
    println("Read this alongside docs/guides/ksl-resources-tutorial.md, case 1.")
}
