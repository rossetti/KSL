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

import ksl.modeling.entity.BehaviorStop
import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceFailureBasis
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.Counter
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.StatisticIfc

/**
 *  A machine that takes a break every shift, breaks down, and jams. Jobs arrive at `arrivalRate` a minute and
 *  take an exponential time with mean `meanProcessing`. The machine's own cyclic behavior works `shiftLength`
 *  minutes and then takes a `breakLength`-minute break off shift. It breaks down after an exponential amount of
 *  busy time with mean `meanBusyBetweenFailures`, and is repaired in an exponential time with mean `meanRepair`;
 *  a job in service waits out the repair and goes on (`RESUME`). It also jams, about every `meanBetweenJams`
 *  minutes, and an operator stops it by hand for `clearJam` minutes to clear the jam. When `overtimeAt` jobs or
 *  more wait, a supervisor cancels the machine's breaks, and starts them again once the queue is empty.
 */
class ShiftsAndBreakdowns(
    parent: ModelElement,
    val arrivalRate: Double = 1.0 / 12.0,
    val meanProcessing: Double = 9.0,
    val shiftLength: Double = 420.0,
    val breakLength: Double = 60.0,
    val meanBusyBetweenFailures: Double = 600.0,
    meanRepair: Double = 30.0,
    meanBetweenJams: Double = 240.0,
    private val clearJam: Double = 5.0,
    val overtimeAt: Int = Int.MAX_VALUE,
    name: String? = null
) : ProcessModel(parent, name) {

    private val timeBetweenJobs = ExponentialRV(1.0 / arrivalRate, 1)
    private val processing = RandomVariable(this, ExponentialRV(meanProcessing, 2), "Processing")
    private val timeBetweenJams = RandomVariable(this, ExponentialRV(meanBetweenJams, 5), "TimeBetweenJams")

    val machine = ResourceWithQ(this, "Machine")

    /** The time a job spends in the shop, waiting and in service. */
    val timeInShop = Response(this, "TimeInShop")
    /** The minutes of work done on the jobs finished. */
    val workDone = Counter(this, "WorkDone")
    /** The jams the operator cleared. */
    val jams = Counter(this, "Jams")
    /** The breakdowns (failures other than jams) per hour of work done, each replication. */
    val breakdownsPerHourWorked = Response(this, "BreakdownsPerHourWorked")
    /** The times the supervisor cancelled the breaks. */
    val overtimeStarts = Counter(this, "OvertimeStarts")
    /** The supervisor's checks that found the machine off shift during overtime: none, if overtime works. */
    val offShiftInOvertime = Counter(this, "OffShiftInOvertime")

    init {
        machine.interruptRule = InterruptRule.RESUME
        machine.cyclicBehavior {
            delay(shiftLength)
            machine.goOffShift()
            delay(breakLength)
            machine.goOnShift()
        }
        // a break cut short by the supervisor ends now: the machine comes back on shift
        machine.onBehaviorStopped { if (machine.isOffShift) machine.goOnShift() }
        machine.failures(
            timeBetween = ExponentialRV(meanBusyBetweenFailures, 3),
            repairTime = ExponentialRV(meanRepair, 4),
            basis = ResourceFailureBasis.BUSY_TIME
        )
    }

    private inner class Job : Entity() {
        val work: KSLProcess = process(isDefaultProcess = true) {
            val m = seize(machine)
            val t = processing.value
            delay(t)
            release(m)
            workDone.increment(t)
            timeInShop.value = time - createTime
        }
    }

    private inner class Operator : Entity() {
        val rounds: KSLProcess = process {
            while (true) {
                delay(timeBetweenJams)
                // a machine already down for a breakdown cannot jam
                if (machine.isFailed) continue
                machine.fail()
                jams.increment()
                delay(clearJam)
                machine.repair()
            }
        }
    }

    private inner class Supervisor : Entity() {
        val watch: KSLProcess = process {
            while (true) {
                delay(15.0)
                if (machine.isBehaviorActive && machine.waitingQ.size >= overtimeAt) {
                    machine.stopBehavior(BehaviorStop.NOW)
                    overtimeStarts.increment()
                } else if (!machine.isBehaviorActive && machine.waitingQ.isEmpty) {
                    machine.startBehavior()
                }
                if (!machine.isBehaviorActive && machine.isOffShift) offShiftInOvertime.increment()
            }
        }
    }

    private val generator = EntityGenerator(::Job, timeBetweenJobs, timeBetweenJobs)

    override fun initialize() {
        activate(Operator().rounds)
        if (overtimeAt != Int.MAX_VALUE) activate(Supervisor().watch)
    }

    override fun replicationEnded() {
        // the machine counts every failure, its breakdowns and the operator's jams alike
        val failures = model.counter("${machine.name}:NumFailures")!!.value
        breakdownsPerHourWorked.value = (failures - jams.value) / (workDone.value / 60.0)
    }
}

/**
 *  Runs the shop on its breaks alone and with the supervisor's overtime, and sets each estimate beside its exact
 *  value, with a 99% confidence interval from the replications.
 */
fun main() {
    fun run(overtimeAt: Int): ShiftsAndBreakdowns {
        val m = Model("ShiftsAndBreakdowns", autoCSVReports = false)
        val shop = ShiftsAndBreakdowns(m, overtimeAt = overtimeAt, name = "Shop")
        m.numberOfReplications = 20
        // a whole number of 480-minute cycles, before and after the warm-up
        m.lengthOfReplicationWarmUp = 9_600.0
        m.lengthOfReplication = 105_600.0
        m.simulate()
        return shop
    }
    val breaks = run(overtimeAt = Int.MAX_VALUE)
    val cycle = breaks.shiftLength + breaks.breakLength
    println("The machine on its breaks, against the exact values, 99% confidence intervals")
    println(String.format("%-30s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareShop("breakdowns per hour worked", breaks.breakdownsPerHourWorked.acrossReplicationStatistic, 60.0 / breaks.meanBusyBetweenFailures)
    compareShop("proportion of time busy", breaks.machine.proportionOfTimeBusy.acrossReplicationStatistic, breaks.arrivalRate * breaks.meanProcessing)
    compareShop("mean capacity on shift", breaks.machine.numActiveUnits.acrossReplicationStatistic, breaks.shiftLength / cycle)

    val overtime = run(overtimeAt = 15)
    println()
    println("With overtime when 15 jobs wait")
    println(String.format("%-30s %10s %22s %10s %8s", "quantity", "estimate", "interval", "exact", "inside"))
    compareShop("breakdowns per hour worked", overtime.breakdownsPerHourWorked.acrossReplicationStatistic, 60.0 / overtime.meanBusyBetweenFailures)
    compareShop("proportion of time busy", overtime.machine.proportionOfTimeBusy.acrossReplicationStatistic, overtime.arrivalRate * overtime.meanProcessing)
    println(String.format("%-30s %10.4f", "overtime starts", overtime.overtimeStarts.acrossReplicationStatistic.average))
    println(String.format("%-30s %10.4f", "mean capacity on shift", overtime.machine.numActiveUnits.acrossReplicationStatistic.average))
    println(String.format("%-30s %10.0f", "most checks off shift in OT", overtime.offShiftInOvertime.acrossReplicationStatistic.max))
    println()
    println(String.format("%-30s %10s %12s %14s", "", "breaks", "overtime", "half-width"))
    val a = breaks.timeInShop.acrossReplicationStatistic
    val b = overtime.timeInShop.acrossReplicationStatistic
    println(String.format("%-30s %10.4f %12.4f %14.4f", "time in shop", a.average, b.average, b.halfWidth))
    println()
    println("Read this alongside docs/guides/ksl-resources-tutorial.md, case 6.")
}

private fun compareShop(label: String, stat: StatisticIfc, exact: Double) {
    val ci = stat.confidenceInterval(0.99)
    println(
        String.format(
            "%-30s %10.4f %22s %10.4f %8s", label, stat.average,
            String.format("[%.4f, %.4f]", ci.lowerLimit, ci.upperLimit), exact, if (exact in ci) "yes" else "NO"
        )
    )
}
