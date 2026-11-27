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
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourcePoolWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.StatisticIfc

/**
 *  A ward whose beds are a pool: `numRooms` rooms of `bedsPerRoom` beds each. Patients arrive at
 *  `arrivalRate` an hour and stay an exponential time with mean `meanStay` hours. A patient waits for a bed at
 *  most an exponential time with mean `meanPatience` hours, with `seizeOrTimeout` on the pool, and one who
 *  gives up is sent to an overflow unit.
 *
 *  Any bed serves any patient, so the pool is one resource of all its beds: with exponential times, an
 *  M/M/c+M queue with c the number of beds, checked against [ErlangA].
 */
class Ward(
    parent: ModelElement,
    numRooms: Int = 3,
    bedsPerRoom: Int = 4,
    val arrivalRate: Double = 1.4,
    meanStay: Double = 8.0,
    meanPatience: Double = 4.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val rooms = (1..numRooms).map { Resource(this, "Room$it", capacity = bedsPerRoom) }
    private val beds = ResourcePoolWithQ(this, rooms, name = "Beds")

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val stay = RandomVariable(this, ExponentialRV(meanStay, 2), "Stay")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")

    private val overflowed = Response(this, "ProbOverflow")

    /** The proportion of patients sent to the overflow unit. */
    val probOverflow get() = overflowed
    /** The time-average number of patients waiting for a bed. */
    val numWaiting get() = beds.waitingQ.numInQ
    /** The time patients wait, those who give up included. */
    val timeWaiting get() = beds.waitingQ.timeInQ

    private inner class Patient : Entity() {
        val admission: KSLProcess = process(isDefaultProcess = true) {
            val result = seizeOrTimeout(beds, timeout = patience.value)
            if (result.timedOut) {
                overflowed.value = 1.0
                return@process
            }
            overflowed.value = 0.0
            delay(stay)
            release(result.value)
        }
    }

    private val generator = EntityGenerator(::Patient, timeBetweenArrivals, timeBetweenArrivals)
}

/**
 *  The ward of [Ward] with a step-down unit beside it, a second pool of `stepDownRooms` rooms of
 *  `bedsPerStepDownRoom` beds. A patient waits for whichever comes first: a ward bed, a step-down bed, or the end
 *  of its patience. The bed it gets is the one it uses; its request in the other pool is withdrawn at once.
 *
 *  Every waiting patient waits in both pools' queues, in arrival order, and a bed freed in either goes to the
 *  earliest of them, so the two pools act as one pool of all their beds: an M/M/c+M queue with c the beds of
 *  both, checked against [ErlangA].
 */
class WardWithStepDown(
    parent: ModelElement,
    numRooms: Int = 3,
    bedsPerRoom: Int = 4,
    stepDownRooms: Int = 2,
    bedsPerStepDownRoom: Int = 2,
    val arrivalRate: Double = 1.4,
    meanStay: Double = 8.0,
    meanPatience: Double = 4.0,
    name: String? = null
) : ProcessModel(parent, name) {

    private val wardBeds = ResourcePoolWithQ(this, (1..numRooms).map { Resource(this, "Room$it", capacity = bedsPerRoom) }, name = "Beds")
    private val stepDownBeds = ResourcePoolWithQ(
        this, (1..stepDownRooms).map { Resource(this, "StepDownRoom$it", capacity = bedsPerStepDownRoom) }, name = "StepDownBeds"
    )

    private val timeBetweenArrivals = ExponentialRV(1.0 / arrivalRate, 1)
    private val stay = RandomVariable(this, ExponentialRV(meanStay, 2), "Stay")
    private val patience = RandomVariable(this, ExponentialRV(meanPatience, 3), "Patience")

    private val overflowed = Response(this, "ProbOverflow")
    private val placedInStepDown = Response(this, "ProbStepDown")
    private val waitForBed = Response(this, "WaitForBed")

    /** The proportion of patients sent to the overflow unit. */
    val probOverflow get() = overflowed
    /** The proportion of placed patients given a step-down bed. */
    val probStepDown get() = placedInStepDown
    /** The time-average number of patients waiting: each waits in both queues, so the ward's counts them all. */
    val numWaiting get() = wardBeds.waitingQ.numInQ
    /**
     *  The time patients wait, those who give up included. A patient who takes a step-down bed at once never
     *  enters the ward's queue, so each patient records its own wait.
     */
    val timeWaiting get() = waitForBed

    private inner class Patient : Entity() {
        val admission: KSLProcess = process(isDefaultProcess = true) {
            val ward = wardBeds.seizeTrigger()
            val stepDown = stepDownBeds.seizeTrigger()
            val result = waitForFirst(ward, stepDown, timeout = patience.value)
            waitForBed.value = result.timeWaited
            if (result.timedOut) {
                overflowed.value = 1.0
                return@process
            }
            overflowed.value = 0.0
            placedInStepDown.value = if (result.firedBy(stepDown)) 1.0 else 0.0
            val bed = if (result.firedBy(ward)) result.valueOf(ward) else result.valueOf(stepDown)
            delay(stay)
            release(bed)
        }
    }

    private val generator = EntityGenerator(::Patient, timeBetweenArrivals, timeBetweenArrivals)
}

/**
 *  Runs the ward alone and the ward with its step-down unit, and sets each estimate beside its exact M/M/c+M
 *  value, with a 99% confidence interval from the replications.
 */
fun main() {
    fun run(build: (Model) -> Unit): Model {
        val m = Model("HospitalBeds", autoCSVReports = false)
        build(m)
        m.numberOfReplications = 20
        m.lengthOfReplication = 20_000.0
        m.lengthOfReplicationWarmUp = 2_000.0
        m.simulate()
        return m
    }
    lateinit var ward: Ward
    run { ward = Ward(it, name = "Ward") }
    val exactWard = ErlangA(lambda = 1.4, mu = 1.0 / 8.0, theta = 1.0 / 4.0, c = 12)
    println("The ward alone, 12 beds, against the M/M/12+M answers, 99% confidence intervals")
    println(String.format("%-30s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBeds("probability of overflow", ward.probOverflow.acrossReplicationStatistic, exactWard.probAbandon)
    compareBeds("mean number waiting", ward.numWaiting.acrossReplicationStatistic, exactWard.meanNumberWaiting)
    compareBeds("mean wait, all patients", ward.timeWaiting.acrossReplicationStatistic, exactWard.meanWait)

    lateinit var both: WardWithStepDown
    run { both = WardWithStepDown(it, name = "Ward") }
    val exactBoth = ErlangA(lambda = 1.4, mu = 1.0 / 8.0, theta = 1.0 / 4.0, c = 16)
    println()
    println("The ward with its step-down unit, 12 + 4 beds, against the M/M/16+M answers")
    println(String.format("%-30s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareBeds("probability of overflow", both.probOverflow.acrossReplicationStatistic, exactBoth.probAbandon)
    compareBeds("mean number waiting", both.numWaiting.acrossReplicationStatistic, exactBoth.meanNumberWaiting)
    compareBeds("mean wait, all patients", both.timeWaiting.acrossReplicationStatistic, exactBoth.meanWait)
    println(String.format("%-30s %10.4f", "share placed in step-down", both.probStepDown.acrossReplicationStatistic.average))
    println()
    println("Read this alongside docs/guides/ksl-waits-tutorial.md, case 6.")
}

private fun compareBeds(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-30s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
