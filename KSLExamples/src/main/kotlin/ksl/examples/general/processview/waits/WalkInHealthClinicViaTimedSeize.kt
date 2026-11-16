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

package ksl.examples.general.processview.waits

import ksl.modeling.elements.REmpiricalList
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.WaitResult
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.examples.book.chapter7.WalkInHealthClinic
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.*

/**
 *  The book's walk-in health clinic (chapter 7), with one change: a low-priority patient reneges by a seize
 *  with a time limit, `seizeOrTimeout`, instead of a scheduled event that finds its request and calls
 *  `removeAndTerminate`. The patience is sampled at the same point from the same stream, and the reneged
 *  wait is not recorded, as `removeAndTerminate` does not record it by default. So the two models must
 *  give the same results: a check of the timed seize against the older way of reneging.
 */
class WalkInHealthClinicViaTimedSeize(parent: ModelElement, name: String? = null) : ProcessModel(parent, name) {

    private val myTBArrivals: RVariableIfc = ExponentialRV(6.0, 1)

    private val triageRV = RandomVariable(this, UniformRV(2.0, 3.0, 2))
    private val highRV = RandomVariable(this, LognormalRV(38.0, 8.0 * 8.0, 3))
    private val mediumRV = RandomVariable(this, TriangularRV(16.0, 22.0, 28.0, 4))
    private val lowRV = RandomVariable(this, LognormalRV(12.0, 2.0 * 2.0, 5))

    private val renegeTimeRV = RandomVariable(this, UniformRV(10.0, 20.0, 6))

    private val doctorQ: RequestQ = RequestQ(this, "DoctorQ", discipline = Queue.Discipline.RANKED)
    private val doctorR: ResourceWithQ = ResourceWithQ(this, capacity = 5, queue = doctorQ, name = "Doctors")
//    init {
//        doctorR.stateReportingOption = true
//    }
    private val triageNurseR: ResourceWithQ = ResourceWithQ(this, capacity = 1, name = "TriageNurse")

    var balkCriteria = 10
        set(value) {
            require(value > 0) { "The balk criteria must be > 0" }
            field = value
        }

    // set up the service distribution and the random selection
    private val distributions = listOf(highRV, mediumRV, lowRV)
    private val distributionCDF = doubleArrayOf(0.25, 0.85, 1.0)
    private val serviceRV = REmpiricalList(this, distributions, distributionCDF)
    private val typeMap = mapOf(highRV to 1, mediumRV to 2, lowRV to 3)

    private val myArrivalGenerator = EntityGenerator(::Patient, myTBArrivals, myTBArrivals)

    private val timeInSystem: Response = Response(this, "${this.name}:TimeInSystem")
    val systemTime: ResponseCIfc
        get() = timeInSystem

    private val timeInSystemHigh: Response = Response(this, "${this.name}:TimeInSystemHigh")
    private val timeInSystemMedium: Response = Response(this, "${this.name}:TimeInSystemMedium")
    private val timeInSystemLow: Response = Response(this, "${this.name}:TimeInSystemLow")
    private val sysTimeMap = mapOf( 1 to timeInSystemHigh, 2 to timeInSystemMedium, 3 to timeInSystemLow)

    private val balkingProb: Response = Response(this, "${this.name}:ProbBalking")
    val probBalking: ResponseCIfc
        get() = balkingProb

    private val renegingProb: Response = Response(this, "${this.name}:ProbReneging")
    val probReneging: ResponseCIfc
        get() = renegingProb

    private val numServed = Counter(this,"${this.name}:NumServed" )
    private val numBalked = Counter(this,"${this.name}:NumBalked" )
    private val numReneged = Counter(this,"${this.name}:NumReneged" )

    // define the process
    private inner class Patient : Entity() {
        private val service = serviceRV.randomElement

        init {
            priority = typeMap[service]!!
        }

        val clinicProcess = process(isDefaultProcess = true) {
            if ((priority == 3) && (doctorQ.size >= balkCriteria)) {
                // record balking
                balkingProb.value = 1.0
                numBalked.increment()
                return@process
            }
            if (priority == 3){
                balkingProb.value = 0.0
            }
            use(triageNurseR, delayDuration = triageRV)
            // only low priority renege: a seize with a time limit, the patience sampled where the event was
            val a = if (priority == 3) {
                val patience = renegeTimeRV.value
                val result = seizeOrTimeout(doctorR, timeout = patience, recordRenegedWait = false)
                if (result is WaitResult.TimedOut) {
                    // reneging statistics
                    renegingProb.value = 1.0
                    numReneged.increment()
                    return@process
                }
                result.value
            } else {
                seize(doctorR)
            }
            delay(service)
            release(a)
            if (priority == 3){
                renegingProb.value = 0.0
            }
            val st = time - this@Patient.createTime
            timeInSystem.value = st
            sysTimeMap[priority]?.value = st
            numServed.increment()
        }

    }
}

/**
 *  Runs the book's walk-in clinic (`ksl.examples.book.chapter7.WalkInHealthClinic`, run by `Ch7Example8`)
 *  and this one with the same settings, and compares every response and counter. The two must agree
 *  exactly: the same patients arrive, triage and service take the same times, and each low-priority patient
 *  gives up at the same moment, whether by a scheduled event that removes its request or by a seize whose
 *  time limit runs out.
 */
fun main() {
    fun run(build: (Model) -> Unit): Model {
        val m = Model("WalkInClinic", autoCSVReports = false)
        build(m)
        m.lengthOfReplication = 10.0 * 60.0
        m.numberOfReplications = 30
        m.simulate()
        return m
    }
    val byEvent = run { WalkInHealthClinic(it, "Walk-In Clinic") }
    val byTimedSeize = run { WalkInHealthClinicViaTimedSeize(it, "Walk-In Clinic") }

    fun statistics(m: Model) = m.responses.associate { it.name to it.acrossReplicationStatistic } +
            m.counters.associate { it.name to it.acrossReplicationStatistic }
    val event = statistics(byEvent)
    val timed = statistics(byTimedSeize)
    println(String.format("%-40s %14s %14s %8s", "response", "by an event", "by seizeOrTimeout", "same"))
    var allSame = event.keys == timed.keys
    for (name in event.keys.sorted()) {
        val a = event.getValue(name)
        val b = timed[name]
        val same = b != null && a.average == b.average && a.variance == b.variance && a.count == b.count
        allSame = allSame && same
        println(String.format("%-40s %14.4f %14.4f %8s", name, a.average, b?.average ?: Double.NaN, if (same) "yes" else "NO"))
    }
    println()
    println(if (allSame) "Every response and counter is identical." else "The two models DIFFER.")
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 2.")
}
