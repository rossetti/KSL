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
package ksl.examples.general.agv

import ksl.controls.experiments.ScenarioRunner
import ksl.simulation.Model
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  [TwoParadigmsExample] claims the two paradigms agree *exactly*. This is where that is held.
 *
 *  The example's own KDoc calls the agreement "the load-bearing result", and its `main` prints the
 *  paired differences on `Delivered` and `TimeInSystem` as `0.000000 +/- 0.000000`. Nothing failed
 *  if that stopped being true. A regression in either subsystem would leave the example compiling,
 *  running, and printing a table that only a reader who checked the digits would question.
 *
 *  `GateAEquivalenceTest` does not cover this. It is a different shop, and it accepts agreement to
 *  within two percent -- deliberately, since a difference of event ordering could plausibly produce
 *  one; it computes exact agreement and reports it rather than requiring it. `StatisticParityTest`
 *  compares the two subsystems' statistic *names*, not their values. `WorkInFlightParityTest`
 *  asserts equal deliveries, but as its premise, from two plain models at a fixed horizon.
 *
 *  What is new here is the example's own path: two scenarios through a [ScenarioRunner], paired
 *  replication by replication. That path is the reason for the second assertion below. Two means can
 *  agree while the pairing falls apart -- if the scenarios stop drawing common random numbers, the
 *  difference stays near zero and its half-width explodes. A **zero half-width is only possible
 *  under genuine common random numbers**, so it pins the mechanism as well as the result.
 *
 *  The response names are written out rather than imported: the example declares them file-private,
 *  and restating them here means a rename has to be made deliberately in two places instead of
 *  silently passing a test that pairs nothing.
 */
// One instance for the whole class: the three tests read one pair of runs rather than
// re-simulating the shop for each of them.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TwoParadigmsExactAgreementTest {

    private val replications = 20
    private val horizon = 8_000.0
    private val warmUp = 1_000.0
    private val passiveName = "Passive"
    private val activeName = "Active"

    private val runner: ScenarioRunner by lazy { runBothParadigms() }

    private fun runBothParadigms(): ScenarioRunner {
        // The example's setup, with the database sink left off: the comparison reads the in-memory
        // per-replication observations, and nothing here needs them persisted.
        val r = ScenarioRunner("TwoParadigmsExactAgreement", kslDb = null)
        val passiveModel = Model("ExactAgreement_Passive", autoCSVReports = false)
        PassiveShop(passiveModel, name = "PassiveShop")
        r.addScenario(
            model = passiveModel, name = passiveName, inputs = emptyMap(),
            numberReplications = replications, lengthOfReplication = horizon,
            lengthOfReplicationWarmUp = warmUp
        )
        val activeModel = Model("ExactAgreement_Active", autoCSVReports = false)
        ActiveShop(activeModel, name = "ActiveShop")
        r.addScenario(
            model = activeModel, name = activeName, inputs = emptyMap(),
            numberReplications = replications, lengthOfReplication = horizon,
            lengthOfReplicationWarmUp = warmUp
        )
        r.simulate()
        return r
    }

    private fun observations(response: String): Pair<DoubleArray, DoubleArray> {
        val obs = runner.observationsAsMap(response)
        assertEquals(
            setOf(passiveName, activeName), obs.keys,
            "both paradigms must report '$response' under that name or there is nothing to pair"
        )
        val p = obs.getValue(passiveName)
        val a = obs.getValue(activeName)
        assertEquals(replications, p.size, "expected $replications paired observations of '$response'")
        assertEquals(replications, a.size, "expected $replications paired observations of '$response'")
        return p to a
    }

    @Test
    @DisplayName("the two paradigms agree replication by replication, to the digit")
    fun everyReplicationAgrees() {
        for (response in listOf("Delivered", "TimeInSystem")) {
            val (passive, active) = observations(response)
            // Element-wise rather than only in aggregate, because the failure message then names
            // the replication that diverged, which is where a fault is localized.
            for (i in passive.indices) {
                assertEquals(
                    passive[i], active[i], 0.0,
                    "replication ${i + 1} of '$response' differs between the paradigms: " +
                            "passive=${passive[i]}, active=${active[i]}. With one cart the pool's " +
                            "closest-idle rule and the fleet's nearest-vehicle policy are the same " +
                            "rule, so the two models are supposed to be the same world."
                )
            }
        }
    }

    @Test
    @DisplayName("the paired difference the example prints is zero, half-width included")
    fun thePairedDifferenceIsZero() {
        for (response in listOf("Delivered", "TimeInSystem")) {
            val obs = runner.observationsAsMap(response)
            val mca = MultipleComparisonAnalyzer(obs, response)
            val d = checkNotNull(mca.pairedDifferenceStatistic(passiveName, activeName)) {
                "no paired difference for '$response'; the example's comparison would fail too"
            }
            assertEquals(
                0.0, d.average, 0.0,
                "the mean paired difference in '$response' is no longer zero: ${d.average}"
            )
            // A zero half-width is what rules out a lucky cancellation and, with it, a loss of
            // common random numbers between the two scenarios.
            assertEquals(
                0.0, d.halfWidth, 0.0,
                "the paired differences in '$response' are no longer identically zero " +
                        "(half-width ${d.halfWidth}). Either the paradigms disagree replication by " +
                        "replication, or the scenarios have stopped sharing random number streams."
            )
        }
    }

    @Test
    @DisplayName("the run does enough work for the agreement to mean something")
    fun theComparisonIsNotVacuous() {
        val (passive, active) = observations("Delivered")
        // Zero deliveries on both sides would satisfy every assertion above.
        assertTrue(
            passive.min() > 100.0,
            "this horizon is supposed to deliver on the order of 175 parts per replication; the " +
                    "smallest was ${passive.min()}, so the exact agreement above is being asserted " +
                    "over a run that barely ran"
        )
        assertEquals(passive.min(), active.min(), 0.0)
    }
}
