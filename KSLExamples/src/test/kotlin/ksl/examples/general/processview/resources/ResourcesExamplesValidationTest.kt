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
import ksl.simulation.Model
import ksl.utilities.statistic.StatisticIfc
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The resources tutorial's examples checked against answers found another way: the exact results of a
 *  queueing model, or the same system written another way. Each runs as its example's `main` runs, with the
 *  same settings and seeds, so a pass here is a pass for the numbers the tutorial reports.
 *
 *  An exact value is required to lie inside the 99% confidence interval of the estimate. The run is seeded,
 *  so the check is deterministic: if it ever fails, the model or the library changed, and the tutorial's
 *  numbers with it.
 */
class ResourcesExamplesValidationTest {

    private fun run(reps: Int, length: Double, warmUp: Double = 0.0, build: (Model) -> Unit): Model {
        val m = Model("Validation${System.nanoTime()}", autoCSVReports = false)
        build(m)
        m.numberOfReplications = reps
        m.lengthOfReplication = length
        m.lengthOfReplicationWarmUp = warmUp
        m.simulate()
        return m
    }

    private fun statistics(m: Model): Map<String, StatisticIfc> =
        m.responses.associate { it.name to it.acrossReplicationStatistic } +
            m.counters.associate { it.name to it.acrossReplicationStatistic }

    private fun assertInside(label: String, stat: StatisticIfc, exact: Double) {
        val ci = stat.confidenceInterval(0.99)
        assertTrue(exact in ci, "$label: the exact value $exact is outside the 99% interval [${ci.lowerLimit}, ${ci.upperLimit}]")
    }

    private fun assertIdentical(a: Map<String, StatisticIfc>, b: Map<String, StatisticIfc>, names: Collection<String>) {
        for (name in names) {
            val x = a.getValue(name)
            val y = b.getValue(name)
            assertEquals(x.count, y.count, "$name: count")
            assertEquals(x.average, y.average, "$name: average")
            assertEquals(x.variance, y.variance, "$name: variance")
        }
    }

    @Test
    @DisplayName("clinic breaks: with the behavior turned off by its control, the clinic is the exact M/M/1 queue")
    fun clinicWithoutBreaks() {
        lateinit var clinic: ClinicWithBreaks
        val m = run(20, 50_000.0, 5_000.0) {
            clinic = ClinicWithBreaks(it, name = "Clinic")
            it.controls().control("Doctor.behaviorActiveAtStart")!!.value = 0.0
        }
        val s = statistics(m)
        assertEquals(0.0, s.getValue("BreaksTaken").max, "no break may be taken with the behavior off")
        val lambda = clinic.arrivalRate
        val mu = 1.0 / clinic.meanServiceTime
        val rho = lambda / mu
        assertInside("mean wait", s.getValue("PatientWait"), rho / (mu - lambda))
        assertInside("mean number waiting", s.getValue("Doctor:Q:NumInQ"), rho * rho / (1.0 - rho))
        assertInside("mean number busy", s.getValue("Doctor:NumBusyUnits"), rho)
    }

    @Test
    @DisplayName("clinic breaks: breaks taken at once by a behavior equal the same breaks made by a capacity schedule")
    fun clinicBreaksByBehaviorAndBySchedule() {
        val byBehavior = statistics(run(20, 50_000.0, 5_000.0) { ClinicWithBreaks(it, policy = BreakPolicy.AT_ONCE, name = "Clinic") })
        val bySchedule = statistics(run(20, 50_000.0, 5_000.0) { ClinicWithBreaks(it, policy = BreakPolicy.AT_ONCE_BY_SCHEDULE, name = "Clinic") })
        assertTrue(byBehavior.getValue("BreaksTaken").average > 0.0, "the doctor must take breaks for this to test anything")
        assertIdentical(byBehavior, bySchedule, listOf("PatientWait", "TimeInClinic", "Doctor:Q:NumInQ", "Doctor:NumBusyUnits"))
    }

    @Test
    @DisplayName("breakdowns: completion time, wait, time in shop and time failed agree with M/G/1 on the completion time")
    fun machineBreakdowns() {
        val exact = MG1WithBreakdowns(
            arrivalRate = 1.0 / 12.0, meanProcessing = 8.0, processingSecondMoment = 8.0 * 8.0 + 36.0 / 12.0,
            failureRate = 1.0 / 60.0, meanRepair = 5.0, repairSecondMoment = 50.0
        )
        val s = statistics(run(20, 50_000.0, 5_000.0) { BreakdownShop(it, name = "Shop") })
        assertInside("mean completion time", s.getValue("CompletionTime"), exact.meanCompletion)
        assertInside("mean wait", s.getValue("Machine:Q:TimeInQ"), exact.meanWait)
        assertInside("mean time in shop", s.getValue("TimeInShop"), exact.meanTimeInShop)
        assertInside("proportion of time failed", s.getValue("Machine:PTimeFailed"), exact.probFailed)
    }

    @Test
    @DisplayName("rush orders under FINISH: both classes agree with Cobham's non-preemptive priority M/M/1")
    fun rushOrdersFinish() {
        lateinit var shop: RushOrderShop
        run(20, 50_000.0, 5_000.0) { shop = RushOrderShop(it, rule = InterruptRule.FINISH, name = "Shop") }
        val exact = TwoClassPriorityQueue(shop.rushRate, shop.routineRate, 1.0 / shop.meanProcessingTime)
        assertInside("rush time in shop", shop.rushTime.acrossReplicationStatistic, exact.nonPreemptiveRush)
        assertInside("routine time in shop", shop.routineTime.acrossReplicationStatistic, exact.nonPreemptiveRoutine)
    }

    @Test
    @DisplayName("rerouting: with no wait limit, reacting to the failure gives exactly the results of not reacting")
    fun reroutingWithoutALimit() {
        val reacting = statistics(run(10, 20_000.0, 2_000.0) { ReroutingShop(it, waitLimit = Double.POSITIVE_INFINITY, react = true, name = "Shop") })
        val holding = statistics(run(10, 20_000.0, 2_000.0) { ReroutingShop(it, react = false, name = "Shop") })
        assertTrue(holding.getValue("Primary:NumFailures").average > 0.0, "the machine must fail for this to test anything")
        assertIdentical(reacting, holding, listOf("TimeInShop", "ProbRerouted", "Primary:Q:NumInQ", "Primary:NumBusyUnits", "JobsDone"))
    }

    @Test
    @DisplayName("rerouting: with wait limits, every job is done or still in the shop, and some are rerouted")
    fun reroutingAccounting() {
        for (limit in listOf(30.0, 10.0, 0.0)) {
            val s = statistics(run(10, 20_000.0, 2_000.0) { ReroutingShop(it, waitLimit = limit, name = "Shop") })
            val unaccounted = s.getValue("JobsUnaccounted")
            assertEquals(0.0, unaccounted.min, "limit $limit")
            assertEquals(0.0, unaccounted.max, "limit $limit")
            assertTrue(s.getValue("ProbRerouted").average > 0.0, "limit $limit: some jobs must be rerouted")
        }
    }
}
