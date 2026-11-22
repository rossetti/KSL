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

package ksl.examples.general.processview.resources.guiderules

import ksl.examples.general.processview.resources.RushOrderShop
import ksl.examples.general.processview.resources.TwoClassPriorityQueue
import ksl.modeling.entity.InterruptRule
import ksl.observers.ReplicationDataCollector
import ksl.simulation.Model
import ksl.utilities.statistic.Statistic
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  A figure the resources tutorial (`ksl-resources-tutorial.md`, case 3, "What to learn") states but the example does
 *  not print: with equal processing times the mean time in the shop over all jobs is the same under both policies, 40
 *  minutes exactly, a third of the jobs being rush jobs. Exactly, a third of the rush time plus two thirds of the
 *  routine time is 40 under preemptive resume and under Cobham's non-preemptive queue, the M/M/1 time in system
 *  1/(0.1 - 0.075). The example's own model is run as its `main` runs it under each rule, and 40 is required to lie
 *  inside the 99% interval of the same combination of each replication's two means.
 */
class ResourcesTutorialFiguresTest {

    private fun allJobs(rule: InterruptRule): Statistic {
        val m = Model("RushOrdersAllJobs${System.nanoTime()}", autoCSVReports = false)
        RushOrderShop(m, rule = rule, name = "Shop")
        val data = ReplicationDataCollector(m)
        data.addResponse("RushTimeInShop")
        data.addResponse("RoutineTimeInShop")
        m.numberOfReplications = 20
        m.lengthOfReplication = 50_000.0
        m.lengthOfReplicationWarmUp = 5_000.0
        m.simulate()
        val rush = data.replicationData("RushTimeInShop")
        val routine = data.replicationData("RoutineTimeInShop")
        val s = Statistic("AllJobs$rule")
        for (i in rush.indices) s.collect(rush[i] / 3.0 + 2.0 * routine[i] / 3.0)
        return s
    }

    @Test
    @DisplayName("rush orders: the mean time in the shop over all jobs is 40 minutes exactly under both policies")
    fun allJobsFortyMinutesUnderBoth() {
        val exact = TwoClassPriorityQueue(1.0 / 40.0, 1.0 / 20.0, 1.0 / 10.0)
        assertEquals(40.0, exact.preemptiveRush / 3.0 + 2.0 * exact.preemptiveRoutine / 3.0, 1e-9, "preemptive resume")
        assertEquals(40.0, exact.nonPreemptiveRush / 3.0 + 2.0 * exact.nonPreemptiveRoutine / 3.0, 1e-9, "Cobham")
        for (rule in listOf(InterruptRule.RESUME, InterruptRule.FINISH)) {
            val s = allJobs(rule)
            val ci = s.confidenceInterval(0.99)
            assertTrue(40.0 in ci, "$rule: 40 is outside the 99% interval [${ci.lowerLimit}, ${ci.upperLimit}] of the mean over all jobs")
        }
    }
}
