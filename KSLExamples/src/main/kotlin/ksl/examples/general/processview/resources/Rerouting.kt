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

import ksl.modeling.entity.DelayResult
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.observers.ReplicationDataCollector
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.statistic.Statistic

/**
 *  A shop with a primary machine that fails and a slower backup machine. Jobs arrive in a Poisson stream and
 *  are processed on the primary machine, in order of arrival. When the primary machine fails during a job,
 *  the job waits up to [waitLimit] for the repair; if the machine is repaired in time, the job goes on where
 *  it left off. If not, the job leaves the primary machine and finishes its remaining work on the backup
 *  machine, which takes [backupSlowdown] times as long.
 *
 *  With [react] false the job does not react at all: its work is a plain delay, held until the repair. With
 *  no wait limit, reacting must give exactly the same results as not reacting.
 */
class ReroutingShop(
    parent: ModelElement,
    val arrivalRate: Double = 1.0 / 10.0,
    processingTime: RVariableIfc = ExponentialRV(7.0, 2),
    timeBetweenFailures: RVariableIfc = ExponentialRV(120.0, 3),
    repairTime: RVariableIfc = ExponentialRV(20.0, 4),
    val waitLimit: Double = 10.0,
    val backupSlowdown: Double = 1.5,
    val react: Boolean = true,
    name: String? = null
) : ProcessModel(parent, name) {

    init {
        require(waitLimit >= 0.0) { "The wait limit must be >= 0" }
    }

    private val arrivals = RandomVariable(this, ExponentialRV(1.0 / arrivalRate, 1), "TimeBetweenArrivals")
    private val processing = RandomVariable(this, processingTime, "ProcessingTime")

    private val primary: ResourceWithQ = ResourceWithQ(this, name = "Primary")
    private val backup: ResourceWithQ = ResourceWithQ(this, name = "Backup")

    init {
        primary.interruptRule = InterruptRule.RESUME
        primary.failures(timeBetween = timeBetweenFailures, repairTime = repairTime)
    }

    val timeInShop = Response(this, "TimeInShop")
    val rerouted = Response(this, "ProbRerouted")
    private val numInShop = TWResponse(this, "NumInShop")
    private val jobsDone = Counter(this, "JobsDone")
    private val unaccounted = Response(this, "JobsUnaccounted")

    // counted from the start of the replication, unlike the statistics, which start again after the warm-up
    private var arrived = 0
    private var finished = 0
    private var inShop = 0

    private inner class Job : Entity() {
        val work: KSLProcess = process {
            numInShop.increment()
            inShop++
            // sampled once, before the work, so reacting and not reacting use the streams alike
            var left = processing.value
            val a = seize(primary)
            var onBackup = false
            if (!react) {
                delay(left)
                release(a)
            } else {
                while (true) {
                    val r = delayOrInterrupt(left, uses = listOf(a))
                    if (r is DelayResult.Completed) {
                        release(a)
                        break
                    }
                    r as DelayResult.Interrupted
                    val back = waitForFirst(a.restoredTrigger(), timeout = waitLimit)
                    if (!back.timedOut) {
                        left = r.timeRemaining
                        continue
                    }
                    release(a)
                    onBackup = true
                    val b = seize(backup)
                    delay(r.timeRemaining * backupSlowdown)
                    release(b)
                    break
                }
            }
            rerouted.value = if (onBackup) 1.0 else 0.0
            timeInShop.value = time - createTime
            numInShop.decrement()
            inShop--
            finished++
            jobsDone.increment()
        }
    }

    override fun initialize() {
        arrived = 0
        finished = 0
        inShop = 0
        schedule(::arrival, arrivals)
    }

    private fun arrival(event: KSLEvent<Nothing>) {
        arrived++
        activate(Job().work)
        schedule(::arrival, arrivals)
    }

    /** Every job that arrived is done or still in the shop. */
    override fun replicationEnded() {
        unaccounted.value = (arrived - finished - inShop).toDouble()
    }
}

fun main() {
    println("Wait limits for the repair, on common random numbers, against waiting for it however long")
    val limits = listOf(Double.POSITIVE_INFINITY, 30.0, 10.0, 5.0, 0.0)
    val results = limits.map { limit ->
        val m = Model("Rerouting", autoCSVReports = false)
        ReroutingShop(m, waitLimit = limit, name = "Shop")
        m.numberOfReplications = 20
        m.lengthOfReplication = 50_000.0
        m.lengthOfReplicationWarmUp = 5_000.0
        val collector = ReplicationDataCollector(m)
        collector.addResponse("TimeInShop")
        collector.addResponse("ProbRerouted")
        m.simulate()
        limit to collector
    }
    val base = results.first().second.replicationData("TimeInShop")
    println(String.format("%-12s %10s %10s %12s %10s %12s", "wait limit", "TimeInShop", "half-width", "difference", "half-width", "rerouted"))
    for ((limit, collector) in results) {
        val data = collector.replicationData("TimeInShop")
        val s = Statistic(data)
        val d = Statistic(DoubleArray(data.size) { data[it] - base[it] })
        val p = Statistic(collector.replicationData("ProbRerouted")).average
        val label = if (limit.isInfinite()) "none" else String.format("%.0f", limit)
        println(String.format("%-12s %10.3f %10.3f %12.3f %10.3f %12.4f", label, s.average, s.halfWidth, d.average, d.halfWidth, p))
    }
    println()
    println("Read this alongside docs/guides/ksl-resources-tutorial.md, case 4.")
}
