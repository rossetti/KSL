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

package ksl.modeling.entity.guiderules

import ksl.modeling.entity.InterruptRule
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.entity.ResourceWithQ
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The rules of the resources tutorial's case 3, "Rush orders" (`ksl-resources-tutorial.md`), checked case by case:
 *
 *  - a holder of equal or higher priority is never preempted, under every interrupt rule, and also when a holder of
 *    lower priority holds another unit beside it;
 *  - `numTimesPreempted` counts each `preempt` that took units from a holder, once however many holders it took
 *    them from, or that reserved them under `FINISH`;
 *  - with equal processing times, the mean time in the shop over all jobs is the same under both policies, 40
 *    minutes, a third of the jobs being rush jobs.
 */
class ResourcesRushOrdersCasesTest {

    /** Holds [n] units of [r] from [from] for [hold] at [priority], by a seize. */
    private fun RuleModel.job(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, n: Int = 1) =
        actor(tag, priority) {
            delay(from)
            val a = seize(r, n)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

    /** Takes [n] units of [r] at [from] for [hold] at [priority], by a preempt under [rule]. */
    private fun RuleModel.rush(tag: String, r: ResourceWithQ, from: Double, hold: Double, priority: Int, rule: InterruptRule, n: Int = 1) =
        actor(tag, priority) {
            delay(from)
            val a = preempt(r, n, rule = rule)
            log.add("$tag got@$time")
            delay(hold)
            release(a)
            log.add("$tag done@$time")
        }

    // ---------- equal or higher priority is never preempted ----------

    /**
     *  L, of priority `holderPriority`, holds the machine from 0 to 10. S, a plain seize of priority 3, asks at 1; P
     *  preempts at 2 with priority 3 under `rule`. As L is not of lower priority, nothing is taken and nothing is
     *  reserved: P waits like a seize, behind S.
     */
    @Test
    @DisplayName("a holder of equal or higher priority is not preempted under RESTART or FINISH: the preemptor waits like a seize")
    fun equalOrHigherNotPreemptedUnderEveryRule() {
        for (rule in listOf(InterruptRule.RESTART, InterruptRule.FINISH)) {
            for (holderPriority in listOf(1, 3)) {
                val m = RuleModel("NoVictim")
                val machine = ResourceWithQ(m, "Machine")
                m.plan {
                    job("L", machine, 0.0, 10.0, holderPriority)
                    job("S", machine, 1.0, 1.0, 3)
                    rush("P", machine, 2.0, 1.0, 3, rule)
                }
                m.simulate(50.0)
                assertEquals(
                    listOf("L got@0.0", "L done@10.0", "S got@10.0", "S done@11.0", "P got@11.0", "P done@12.0"),
                    m.log, "rule $rule, holder priority $holderPriority"
                )
                assertEquals(0, machine.numTimesPreempted, "rule $rule, holder priority $holderPriority")
            }
        }
    }

    /**
     *  Two units. L (priority 9) holds one from 0 to 10, E (priority 1, as urgent as the preemptor) the other from 1
     *  to 11. P preempts one unit at 2 with priority 1, for 1. Only L's unit may be taken, though E's allocation is the
     *  more recent. Under RESUME L goes on at 3 and ends at 11; under RESTART it starts again at 3 and ends at 13; under
     *  FINISH nothing is taken, P reserves, and takes L's unit when L releases it at 10.
     */
    @Test
    @DisplayName("beside a lower-priority holder, an equally urgent holder is never the one preempted, under each rule")
    fun equalHolderBesideALowerOne() {
        val expected = mapOf(
            InterruptRule.RESUME to listOf("L got@0.0", "E got@1.0", "P got@2.0", "P done@3.0", "E done@11.0", "L done@11.0"),
            InterruptRule.RESTART to listOf("L got@0.0", "E got@1.0", "P got@2.0", "P done@3.0", "E done@11.0", "L done@13.0"),
            InterruptRule.FINISH to listOf("L got@0.0", "E got@1.0", "L done@10.0", "P got@10.0", "E done@11.0", "P done@11.0")
        )
        for ((rule, log) in expected) {
            val m = RuleModel("EqualBeside")
            val machine = ResourceWithQ(m, "Machine", capacity = 2)
            m.plan {
                job("L", machine, 0.0, 10.0, 9)
                job("E", machine, 1.0, 10.0, 1)
                rush("P", machine, 2.0, 1.0, 1, rule)
            }
            m.simulate(50.0)
            assertEquals(log.sorted(), m.log.sorted(), "rule $rule: ${m.log}")
            assertEquals(1, machine.numTimesPreempted, "rule $rule")
        }
    }

    // ---------- numTimesPreempted: one per preempt ----------

    /** Two units, held by L1 and L2 (priority 9) from 0 to 10. P preempts both at 2 for 1: one preempt, two holders. */
    @Test
    @DisplayName("numTimesPreempted counts a preempt that took units from two holders once, under RESUME and RESTART")
    fun onePreemptTwoHolders() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.RESTART)) {
            val m = RuleModel("TwoHolders")
            val machine = ResourceWithQ(m, "Machine", capacity = 2)
            m.plan {
                job("L1", machine, 0.0, 10.0, 9)
                job("L2", machine, 0.0, 10.0, 9)
                rush("P", machine, 2.0, 1.0, 1, rule, n = 2)
            }
            m.simulate(50.0)
            assertTrue("P got@2.0" in m.log, "rule $rule: ${m.log}")
            assertEquals(1, machine.numTimesPreempted, "rule $rule")
        }
    }

    // ---------- the mean time in the shop over all jobs ----------

    /**
     *  The two-class M/M/1 of case 3: routine jobs every 20 minutes on average, rush jobs every 40, every processing
     *  time exponential with mean 10, rush jobs taking the machine by `preempt` under [rule]. Every job's time in the
     *  shop is one observation of `TimeInShop`.
     */
    private class Shop(parent: ModelElement, val rule: InterruptRule) : ProcessModel(parent, "Shop") {
        val machine = ResourceWithQ(this, "Machine")
        private val rushArrivals = RandomVariable(this, ExponentialRV(40.0, 1))
        private val routineArrivals = RandomVariable(this, ExponentialRV(20.0, 2))
        private val processingTime = RandomVariable(this, ExponentialRV(10.0, 3))
        val timeInShop = Response(this, "TimeInShop")

        private inner class RushJob : Entity() {
            init {
                priority = 1
            }

            val work: KSLProcess = process {
                val p = processingTime.value
                val a = preempt(machine, rule = rule)
                delay(p)
                release(a)
                timeInShop.value = time - createTime
            }
        }

        private inner class RoutineJob : Entity() {
            init {
                priority = 2
            }

            val work: KSLProcess = process {
                val p = processingTime.value
                val a = seize(machine)
                delay(p)
                release(a)
                timeInShop.value = time - createTime
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
    }

    @Test
    @DisplayName("the mean time in the shop over all jobs is 40 under RESUME and under FINISH")
    fun meanTimeInShopIsFortyUnderBothPolicies() {
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.FINISH)) {
            val model = Model("RushOrders$rule${System.nanoTime()}", autoCSVReports = false)
            val shop = Shop(model, rule)
            model.numberOfReplications = 20
            model.lengthOfReplication = 50_000.0
            model.lengthOfReplicationWarmUp = 5_000.0
            model.simulate()
            val ci = shop.timeInShop.acrossReplicationStatistic.confidenceInterval(0.99)
            assertTrue(40.0 in ci, "rule $rule: the 99% interval $ci does not contain 40")
        }
    }
}
