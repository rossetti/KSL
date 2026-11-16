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

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.observers.ReplicationDataCollector
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.UniformRV
import ksl.utilities.statistic.Statistic

/**
 *  One machine that breaks down. Jobs arrive in a Poisson stream and are processed one at a time, in order
 *  of arrival, for a uniformly distributed time. The machine fails after an exponential amount of time
 *  counted on [basis], and is repaired in an exponential time. While it is failed no job starts, and the
 *  job on the machine follows [rule]: under `RESUME` it goes on with the time it had left once the machine
 *  is repaired, under `RESTART` it starts again in full, and under `FINISH` it is completed before the
 *  machine stops.
 */
class BreakdownShop(
    parent: ModelElement,
    val arrivalRate: Double = 1.0 / 12.0,
    processingTime: RVariableIfc = UniformRV(5.0, 11.0, 2),
    timeBetweenFailures: RVariableIfc = ExponentialRV(60.0, 3),
    repairTime: RVariableIfc = ExponentialRV(5.0, 4),
    val basis: ResourceFailureBasis = ResourceFailureBasis.BUSY_TIME,
    val rule: InterruptRule = InterruptRule.RESUME,
    name: String? = null
) : ProcessModel(parent, name) {

    private val arrivals = RandomVariable(this, ExponentialRV(1.0 / arrivalRate, 1), "TimeBetweenArrivals")
    private val processing = RandomVariable(this, processingTime, "ProcessingTime")

    private val machine: ResourceWithQ = ResourceWithQ(this, name = "Machine")

    init {
        machine.interruptRule = rule
        machine.failures(timeBetween = timeBetweenFailures, repairTime = repairTime, basis = basis)
    }

    val timeInShop = Response(this, "TimeInShop")
    val completionTime = Response(this, "CompletionTime")
    val interruptionsPerJob = Response(this, "InterruptionsPerJob")

    private inner class Job : Entity() {
        val work: KSLProcess = process {
            val a = seize(machine)
            val start = time
            delay(processing)
            completionTime.value = time - start
            interruptionsPerJob.value = a.timesInterrupted.toDouble()
            release(a)
            timeInShop.value = time - createTime
        }
    }

    override fun initialize() {
        schedule(::arrival, arrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        activate(Job().work)
        schedule(::arrival, arrivals)
    }
}

/**
 *  The M/G/1 queue whose service time is the completion time of a job on a machine that fails while it
 *  works, at rate [failureRate] per unit of busy time, and is repaired in a time with mean [meanRepair] and
 *  second moment [repairSecondMoment], each job going on where it left off. The processing time has mean
 *  [meanProcessing] and second moment [processingSecondMoment].
 */
class MG1WithBreakdowns(
    val arrivalRate: Double,
    val meanProcessing: Double,
    val processingSecondMoment: Double,
    val failureRate: Double,
    val meanRepair: Double,
    val repairSecondMoment: Double
) {
    /** The mean completion time: the processing time stretched by the repairs during it. */
    val meanCompletion: Double = meanProcessing * (1.0 + failureRate * meanRepair)

    /** The second moment of the completion time. */
    val completionSecondMoment: Double =
        processingSecondMoment * (1.0 + failureRate * meanRepair) * (1.0 + failureRate * meanRepair) +
                failureRate * meanProcessing * repairSecondMoment

    /** The utilization, counting repairs during jobs as busy. */
    val rho: Double = arrivalRate * meanCompletion

    init {
        require(rho < 1.0) { "The queue must be stable: rho = $rho" }
    }

    /** The mean wait in queue (Pollaczek-Khinchine). */
    val meanWait: Double = arrivalRate * completionSecondMoment / (2.0 * (1.0 - rho))

    /** The mean time in the shop. */
    val meanTimeInShop: Double = meanWait + meanCompletion

    /** The proportion of time the machine is failed. */
    val probFailed: Double = arrivalRate * meanProcessing * failureRate * meanRepair
}

fun main() {
    val exact = MG1WithBreakdowns(
        arrivalRate = 1.0 / 12.0, meanProcessing = 8.0, processingSecondMoment = 8.0 * 8.0 + 36.0 / 12.0,
        failureRate = 1.0 / 60.0, meanRepair = 5.0, repairSecondMoment = 50.0
    )
    println("Failures every 60 busy minutes on average, the job going on where it left off, against M/G/1")
    val m = Model("Breakdowns", autoCSVReports = false)
    val shop = BreakdownShop(m, name = "Shop")
    m.numberOfReplications = 20
    m.lengthOfReplication = 50_000.0
    m.lengthOfReplicationWarmUp = 5_000.0
    m.simulate()
    val statistics = m.responses.associate { it.name to it.acrossReplicationStatistic }
    println(String.format("%-22s %10s %22s %10s", "", "estimate", "99% interval", "exact"))
    for ((name, value) in listOf(
        "CompletionTime" to exact.meanCompletion,
        "Machine:Q:TimeInQ" to exact.meanWait,
        "TimeInShop" to exact.meanTimeInShop,
        "Machine:PTimeFailed" to exact.probFailed
    )) {
        val s = statistics.getValue(name)
        val ci = s.confidenceInterval(0.99)
        println(String.format("%-22s %10.4f   [%8.4f, %8.4f] %10.4f", name, s.average, ci.lowerLimit, ci.upperLimit, value))
    }

    println()
    println("Other rules and bases, on common random numbers, against the first")
    val configurations = listOf(
        Triple("RESUME, busy time", ResourceFailureBasis.BUSY_TIME, InterruptRule.RESUME),
        Triple("RESTART, busy time", ResourceFailureBasis.BUSY_TIME, InterruptRule.RESTART),
        Triple("FINISH, busy time", ResourceFailureBasis.BUSY_TIME, InterruptRule.FINISH),
        Triple("RESUME, calendar time", ResourceFailureBasis.CALENDAR_TIME, InterruptRule.RESUME),
        Triple("every 8 jobs", ResourceFailureBasis.USES, InterruptRule.RESUME),
    )
    val observations = configurations.map { (label, basis, rule) ->
        val model = Model("Breakdowns", autoCSVReports = false)
        val between = if (basis == ResourceFailureBasis.USES) ConstantRV(8.0) else ExponentialRV(60.0, 3)
        BreakdownShop(model, timeBetweenFailures = between, basis = basis, rule = rule, name = "Shop")
        model.numberOfReplications = 20
        model.lengthOfReplication = 50_000.0
        model.lengthOfReplicationWarmUp = 5_000.0
        val collector = ReplicationDataCollector(model)
        collector.addResponse("TimeInShop")
        collector.addResponse("Machine:PTimeFailed")
        collector.addCounterResponse("Machine:NumFailures")
        model.simulate()
        Triple(label, collector.replicationData("TimeInShop"), collector)
    }
    val base = observations.first().second
    println(String.format("%-22s %10s %10s %12s %10s %10s", "", "TimeInShop", "half-width", "difference", "half-width", "failures"))
    for ((label, data, collector) in observations) {
        val s = Statistic(data)
        val d = Statistic(DoubleArray(data.size) { data[it] - base[it] })
        val failures = Statistic(collector.replicationData("Machine:NumFailures")).average
        println(String.format("%-22s %10.3f %10.3f %12.3f %10.3f %10.1f", label, s.average, s.halfWidth, d.average, d.halfWidth, failures))
    }
    println()
    println("Read this alongside docs/guides/ksl-resources-tutorial.md, case 2.")
}
