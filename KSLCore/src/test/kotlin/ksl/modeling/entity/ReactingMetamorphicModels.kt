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

package ksl.modeling.entity

import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc

/**
 *  The models of the metamorphic checks for work that reacts to an interruption: a shop of two machines
 *  whose jobs seize a machine, work, and release it. The work is a parameter, so that the same model, on the
 *  same random streams, can do its work with a plain delay or with a delay that reports an interruption and
 *  a loop that reacts to it; the checks compare the two. The duration of each job's work is sampled once,
 *  before the work, so both forms consume the streams identically.
 *
 *  The shop can have failures of its machines, or urgent jobs that preempt the ordinary ones, under a chosen
 *  interrupt rule. All times are continuous, so same-instant ties do not arise.
 */
internal object ReactingMetamorphicModels {

    /** How a job does its work: [allocation] is its machine, [duration] the sampled time of the work. */
    fun interface Work {
        suspend fun KSLProcessBuilder.work(allocation: Allocation, duration: Double)
    }

    /** The plain form: the work is a delay, which is held under RESUME and RESTART until the units come back. */
    val plainDelay = Work { _, duration -> delay(duration) }

    enum class Interruptions { NONE, FAILURES, PREEMPTION, BOTH }

    class Shop(
        parent: ModelElement,
        val interruptions: Interruptions,
        val rule: InterruptRule,
        val work: Work,
        val constantTimes: Boolean = false
    ) : ProcessModel(parent, "Shop") {
        val machines = ResourceWithQ(this, "Machines", capacity = 2)

        /** Exponential with [mean], or the constant [constant] when the times are constant. */
        private fun time(mean: Double, stream: Int, constant: Double): RVariableIfc =
            if (constantTimes) ConstantRV(constant) else ExponentialRV(mean, stream)

        // the constants are exact in binary, so the events of different jobs fall on the same instants
        private val arrivals = time(1.0, 1, 1.0)
        private val workTime = time(1.6, 2, 1.5)
        private val urgentArrivals = time(8.0, 5, 8.0)
        private val urgentWork = time(1.0, 6, 1.0)
        val systemTime = Response(this, "SystemTime")
        val urgentSystemTime = Response(this, "UrgentSystemTime")

        /** Per replication: the jobs' average system time, their number, and the machines' average number busy. */
        val perReplication = mutableListOf<List<Double>>()

        override fun replicationEnded() {
            val st = systemTime.withinReplicationStatistic
            perReplication.add(listOf(st.weightedAverage, st.count, machines.numBusyUnits.withinReplicationStatistic.weightedAverage))
        }

        init {
            machines.interruptRule = rule
            if (interruptions == Interruptions.FAILURES || interruptions == Interruptions.BOTH) {
                machines.failures(time(30.0, 3, 30.0), time(3.0, 4, 3.0), rule = rule)
            }
        }

        private inner class Job : Entity() {
            init {
                // an entity's priority is 1 by default; ordinary jobs are lower, so urgent ones can preempt them
                priority = 5
            }

            val run: KSLProcess = process("job") {
                val start = time
                val duration = workTime.value
                val a = seize(machines)
                with(work) { work(a, duration) }
                release(a)
                systemTime.value = time - start
            }
        }

        private inner class Urgent : Entity() {
            init {
                priority = 1
            }

            val run: KSLProcess = process("urgent") {
                val start = time
                val duration = urgentWork.value
                val a = preempt(machines, rule = rule)
                delay(duration)
                release(a)
                urgentSystemTime.value = time - start
            }
        }

        override fun initialize() {
            schedule(::arrive, arrivals.value)
            if (interruptions == Interruptions.PREEMPTION || interruptions == Interruptions.BOTH) {
                schedule(::urgentArrive, urgentArrivals.value)
            }
        }

        private fun arrive(event: KSLEvent<Nothing>) {
            activate(Job().run)
            schedule(::arrive, arrivals.value)
        }

        private fun urgentArrive(event: KSLEvent<Nothing>) {
            activate(Urgent().run)
            schedule(::urgentArrive, urgentArrivals.value)
        }
    }

    /** A variant of the shop: what interrupts it, under which rule, and whether its times are constant. */
    data class Variant(val interruptions: Interruptions, val rule: InterruptRule, val constantTimes: Boolean = false)

    /** The variants the checks run, by name. The constant ones make same-instant ties happen. */
    val variants: Map<String, Variant> = linkedMapOf(
        "none" to Variant(Interruptions.NONE, InterruptRule.FINISH),
        "failures FINISH" to Variant(Interruptions.FAILURES, InterruptRule.FINISH),
        "failures RESUME" to Variant(Interruptions.FAILURES, InterruptRule.RESUME),
        "failures RESTART" to Variant(Interruptions.FAILURES, InterruptRule.RESTART),
        "preemption RESUME" to Variant(Interruptions.PREEMPTION, InterruptRule.RESUME),
        "preemption RESTART" to Variant(Interruptions.PREEMPTION, InterruptRule.RESTART),
        "constant failures RESUME" to Variant(Interruptions.FAILURES, InterruptRule.RESUME, constantTimes = true),
        "constant failures RESTART" to Variant(Interruptions.FAILURES, InterruptRule.RESTART, constantTimes = true),
        "constant preemption RESUME" to Variant(Interruptions.PREEMPTION, InterruptRule.RESUME, constantTimes = true),
        "constant preemption RESTART" to Variant(Interruptions.PREEMPTION, InterruptRule.RESTART, constantTimes = true),
        "both RESUME" to Variant(Interruptions.BOTH, InterruptRule.RESUME),
        "both RESTART" to Variant(Interruptions.BOTH, InterruptRule.RESTART),
        "constant both RESUME" to Variant(Interruptions.BOTH, InterruptRule.RESUME, constantTimes = true),
        "constant both RESTART" to Variant(Interruptions.BOTH, InterruptRule.RESTART, constantTimes = true)
    )

    /**
     *  Runs [variant] with [work] and returns, per replication, the average system time of the jobs, the
     *  number of jobs, and the machines' time-average number busy, so that two forms can be compared exactly.
     */
    fun results(variant: String, work: Work, reps: Int = 5, length: Double = 2_000.0): List<List<Double>> {
        val v = variants.getValue(variant)
        val model = Model("ReactingShop${System.nanoTime()}", autoCSVReports = false)
        val shop = Shop(model, v.interruptions, v.rule, work, v.constantTimes)
        model.numberOfReplications = reps
        model.lengthOfReplication = length
        model.simulate()
        return shop.perReplication.toList()
    }
}
