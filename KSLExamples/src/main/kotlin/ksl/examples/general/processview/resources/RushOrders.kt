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
import ksl.modeling.entity.RequestQ
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.queue.Queue
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  A machine shop with one machine and two classes of job. Routine jobs and rush jobs arrive in
 *  independent Poisson streams, and every job's processing time is exponential with the same mean. A rush
 *  job takes the machine from a routine job: under `InterruptRule.RESUME` at once, the routine job going on
 *  with the time it had left when the rush job is done; under `InterruptRule.FINISH` as soon as the routine
 *  job finishes, ahead of every job waiting. Rush jobs wait for each other in order of arrival, ahead of
 *  every waiting routine job.
 */
class RushOrderShop(
    parent: ModelElement,
    val rushRate: Double = 1.0 / 40.0,
    val routineRate: Double = 1.0 / 20.0,
    val meanProcessingTime: Double = 10.0,
    val rule: InterruptRule = InterruptRule.RESUME,
    name: String? = null
) : ProcessModel(parent, name) {

    private val rushArrivals = RandomVariable(this, ExponentialRV(1.0 / rushRate, 1), "TimeBetweenRushJobs")
    private val routineArrivals = RandomVariable(this, ExponentialRV(1.0 / routineRate, 2), "TimeBetweenRoutineJobs")
    private val processing = RandomVariable(this, ExponentialRV(meanProcessingTime, 3), "ProcessingTime")

    private val machine: ResourceWithQ = ResourceWithQ(
        this, name = "Machine", queue = RequestQ(this, "Machine:Q", Queue.Discipline.RANKED)
    )

    val rushTime = Response(this, "RushTimeInShop")
    val routineTime = Response(this, "RoutineTimeInShop")
    val routineTimesPreempted = Response(this, "RoutineTimesPreempted")
    val numPreemptions = Response(this, "NumPreemptions")

    init {
        machine.interruptRule = rule
    }

    private inner class RushJob : Entity() {
        init {
            priority = 1
        }

        val work: KSLProcess = process {
            val a = preempt(machine)
            delay(processing)
            release(a)
            rushTime.value = time - createTime
        }
    }

    private inner class RoutineJob : Entity() {
        init {
            priority = 2
        }

        val work: KSLProcess = process {
            val a = seize(machine)
            delay(processing)
            routineTimesPreempted.value = a.timesInterrupted.toDouble()
            release(a)
            routineTime.value = time - createTime
        }
    }

    override fun initialize() {
        schedule(::rushArrival, rushArrivals)
        schedule(::routineArrival, routineArrivals)
    }

    private fun rushArrival(event: KSLEvent<Nothing>) {
        activate(RushJob().work)
        schedule(::rushArrival, rushArrivals)
    }

    private fun routineArrival(event: KSLEvent<Nothing>) {
        activate(RoutineJob().work)
        schedule(::routineArrival, routineArrivals)
    }

    override fun replicationEnded() {
        numPreemptions.value = machine.numTimesPreempted.toDouble()
    }
}

/**
 *  The mean time in the shop of each class of job, for an M/M/1 queue with two priority classes of equal
 *  processing rate [mu]: class 1 (rush, arrival rate [lambda1]) ahead of class 2 (routine, [lambda2]).
 *  With preemption the rush jobs see an M/M/1 queue of their own; without it, the waiting time of each class
 *  is Cobham's.
 */
class TwoClassPriorityQueue(val lambda1: Double, val lambda2: Double, val mu: Double) {
    private val rho1 = lambda1 / mu
    private val rho = (lambda1 + lambda2) / mu

    init {
        require(rho < 1.0) { "The queue must be stable: rho = $rho" }
    }

    /** Half the second moment of the processing time, weighted by all the arrival rates. */
    private val residual = (lambda1 + lambda2) / (mu * mu)

    /** Preemptive resume: the time in the shop of a rush job. */
    val preemptiveRush: Double = 1.0 / (mu - lambda1)

    /** Preemptive resume: the time in the shop of a routine job. */
    val preemptiveRoutine: Double = (1.0 / mu) / (1.0 - rho1) + residual / ((1.0 - rho1) * (1.0 - rho))

    /** Non-preemptive (Cobham): the time in the shop of a rush job. */
    val nonPreemptiveRush: Double = residual / (1.0 - rho1) + 1.0 / mu

    /** Non-preemptive (Cobham): the time in the shop of a routine job. */
    val nonPreemptiveRoutine: Double = residual / ((1.0 - rho1) * (1.0 - rho)) + 1.0 / mu
}

fun main() {
    for (rule in listOf(InterruptRule.RESUME, InterruptRule.FINISH)) {
        val m = Model("RushOrders", autoCSVReports = false)
        val shop = RushOrderShop(m, rule = rule, name = "Shop")
        m.numberOfReplications = 20
        m.lengthOfReplication = 50_000.0
        m.lengthOfReplicationWarmUp = 5_000.0
        m.simulate()
        val exact = TwoClassPriorityQueue(shop.rushRate, shop.routineRate, 1.0 / shop.meanProcessingTime)
        val against = if (rule == InterruptRule.RESUME) "preemptive resume" else "non-preemptive (Cobham)"
        println("Rush jobs take the machine under $rule, against the exact $against priority queue")
        println(String.format("%-18s %10s %22s %10s", "", "estimate", "99% interval", "exact"))
        for ((label, stat, value) in listOf(
            Triple("RushTimeInShop", shop.rushTime.acrossReplicationStatistic,
                if (rule == InterruptRule.RESUME) exact.preemptiveRush else exact.nonPreemptiveRush),
            Triple("RoutineTimeInShop", shop.routineTime.acrossReplicationStatistic,
                if (rule == InterruptRule.RESUME) exact.preemptiveRoutine else exact.nonPreemptiveRoutine)
        )) {
            val ci = stat.confidenceInterval(0.99)
            println(String.format("%-18s %10.4f   [%8.4f, %8.4f] %10.4f", label, stat.average, ci.lowerLimit, ci.upperLimit, value))
        }
        println(String.format("preemptions per replication %.1f; times a routine job is preempted %.4f",
            shop.numPreemptions.acrossReplicationStatistic.average, shop.routineTimesPreempted.acrossReplicationStatistic.average))
        println()
    }
    println("Read this alongside docs/guides/ksl-resources-tutorial.md, case 3.")
}
