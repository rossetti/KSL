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

import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A walk-in clinic with one doctor and one nurse practitioner, either of whom can see a patient. A patient
 *  waits for whichever is free first, and leaves after waiting an exponential time with mean `meanPatience`.
 *  The patient waits with `waitForFirst` on two seize triggers, one per provider, and a timeout.
 *
 *  A waiting patient is in both providers' queues at once, and leaves the other one the moment it is seen.
 *  Each queue is first come, first served, so each provider takes the patient who has waited longest:
 *  the two queues act as one, and with equal service rates the clinic is the M/M/2+M queue.
 */
class TwoProviderClinic(
    parent: ModelElement,
    val arrivalRate: Double = 1.0 / 12.0,
    meanServiceTime: Double = 20.0,
    meanPatience: Double = 30.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val doctor = ResourceWithQ(this, "Doctor")
    private val nursePractitioner = ResourceWithQ(this, "NursePractitioner")
    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val serviceTime = RandomVariable(this, ExponentialRV(meanServiceTime, 2), "ServiceTime")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")

    private val leftUnseen = Response(this, "ProbLeftUnseen")
    private val seenByDoctor = Response(this, "ProbSeenByDoctor")
    private val numLeftUnseen = Counter(this, "NumLeftUnseen")

    /** The proportion of patients who leave without being seen. */
    val probLeftUnseen get() = leftUnseen
    /** Of the patients seen, the proportion seen by the doctor. */
    val probSeenByDoctor get() = seenByDoctor
    /** The time-average number of patients waiting. Every waiting patient is in the doctor's queue. */
    val numWaiting get() = doctor.waitingQ.numInQ

    private inner class Patient : Entity() {
        val visit: KSLProcess = process(isDefaultProcess = true) {
            val byDoctor = doctor.seizeTrigger()
            val byNursePractitioner = nursePractitioner.seizeTrigger()
            val result = waitForFirst(byDoctor, byNursePractitioner, timeout = patience.value)
            if (result.timedOut) {
                leftUnseen.value = 1.0
                numLeftUnseen.increment()
                return@process
            }
            leftUnseen.value = 0.0
            val provider = if (result.firedBy(byDoctor)) byDoctor else byNursePractitioner
            seenByDoctor.value = if (provider === byDoctor) 1.0 else 0.0
            val allocation = result.valueOf(provider)
            delay(serviceTime)
            release(allocation)
        }
    }

    private val generator = EntityGenerator(::Patient, timeBetweenArrivals, timeBetweenArrivals)
}

/**
 *  Runs the clinic and sets each estimate beside the exact M/M/2+M value, with a 99% confidence interval.
 */
fun main() {
    val exact = ErlangA(lambda = 1.0 / 12.0, mu = 1.0 / 20.0, theta = 1.0 / 30.0, c = 2)

    val model = Model("FirstFreeOfTwo", autoCSVReports = false)
    val clinic = TwoProviderClinic(model, name = "Clinic")
    model.numberOfReplications = 20
    model.lengthOfReplication = 100_000.0
    model.lengthOfReplicationWarmUp = 10_000.0
    model.simulate()

    println("The two-provider clinic against the M/M/2+M answers, 99% confidence intervals")
    println(String.format("%-28s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    val left = clinic.probLeftUnseen.acrossReplicationStatistic
    val waiting = clinic.numWaiting.acrossReplicationStatistic
    for ((label, stat, value) in listOf(
        Triple("probability of leaving", left, exact.probAbandon),
        Triple("mean number waiting", waiting, exact.meanNumberWaiting)
    )) {
        val ci = stat.confidenceInterval(0.99)
        println(
            String.format(
                "%-28s %10.4f %22s %10.4f %8s", label, stat.average,
                String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), value, if (value in ci) "yes" else "NO"
            )
        )
    }
    println()
    println(String.format("Of the patients seen, %.4f were seen by the doctor.", clinic.probSeenByDoctor.acrossReplicationStatistic.average))
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 3.")
}
