/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.moda

import ksl.utilities.io.dbutil.WithinRepViewData
import ksl.utilities.moda.MODAAnalyzer
import ksl.utilities.moda.MODAAnalyzerData
import ksl.utilities.moda.MetricIfc
import ksl.utilities.random.rng.RNStreamProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Requires a study run over simulated responses to reach the same numbers as the analyzer that
 *  already existed for the purpose.
 *
 *  There are now two ways to compare simulated alternatives: the analyzer, which does it directly,
 *  and a written-down study reading the same records through a source. Two ways of doing the same
 *  arithmetic that disagree would be worse than having one, because whichever a person happened to
 *  use would decide the recommendation. These tests hold them to the same answers.
 *
 *  The agreement is on equal replication counts, which is the ordinary case. Where the counts
 *  differ the two deliberately part company, and the last test here pins that difference rather
 *  than leaving it to be discovered.
 */
class SimulationModaAgreementTest {

    private fun record(
        alternative: String,
        response: String,
        replication: Int,
        value: Double,
        replications: Int
    ) = WithinRepViewData(
        exp_name = alternative,
        run_name = "run",
        num_reps = replications,
        start_rep_id = 1,
        last_rep_id = replications,
        stat_name = response,
        rep_id = replication,
        rep_value = value
    )

    private val alternatives = listOf("Alpha", "Beta", "Gamma")
    private val responses = listOf("Cost", "Delay", "Throughput")

    /**
     *  Replication data with noise on it, so the two paths are compared on numbers that do not fall
     *  out evenly. Its own stream provider, so it does not depend on what else has run.
     */
    private fun noisyData(replications: Int = 8): List<WithinRepViewData> {
        val rng = RNStreamProvider().rnStream(3)
        val list = mutableListOf<WithinRepViewData>()
        for ((index, alternative) in alternatives.withIndex()) {
            for (response in responses) {
                val base = when (response) {
                    "Cost" -> 100.0 + 15.0 * index
                    "Delay" -> 40.0 - 6.0 * index
                    else -> 500.0 + 25.0 * index
                }
                for (replication in 1..replications) {
                    list.add(record(alternative, response, replication, base + rng.randU01() * 10.0, replications))
                }
            }
        }
        return list
    }

    private fun analyzerOver(data: List<WithinRepViewData>): MODAAnalyzer = MODAAnalyzer(
        alternativeNames = alternatives.toSet(),
        responseDefinitions = responses.map { MODAAnalyzerData(it) }.toSet(),
        responseData = data
    )

    private fun studyOver(data: List<WithinRepViewData>): ModaRunResult {
        val document = SimulationModaSource.documentFor(
            name = "Average MODA",
            alternatives = alternatives,
            responseWeights = responses.associateWith { 1.0 },
            responseData = data
        )
        val source = SimulationModaSource(data, ReplicationAggregation.MEAN)
        val runner = ModaRunner(
            resolver = ModaSourceResolver(
                providers = mapOf(
                    SimulationModaSource.SIMULATION_PROVIDER_ID to ModaSourceProviderIfc { source }
                )
            )
        )
        return runner.run(document)
    }

    // ------------------------------------------------------------------------------------------
    // The gate
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study over the averages reaches the same overall values as the analyzer`() {
        val data = noisyData()
        val fromAnalyzer = analyzerOver(data).averageMODA().multiObjectiveValuesByAlternative()
        val completed = assertIs<ModaRunResult.Completed>(studyOver(data), "the study did not run")

        assertEquals(fromAnalyzer.keys.sorted(), completed.snapshot.alternatives.sorted())
        for (alternative in alternatives) {
            assertEquals(
                fromAnalyzer[alternative]!!, completed.snapshot.overallValues[alternative]!!, 1.0e-12,
                "the two paths disagree about $alternative"
            )
        }
    }

    @Test
    fun `a study over the averages reaches the same recommendation as the analyzer`() {
        val data = noisyData()
        val fromAnalyzer = analyzerOver(data).averageMODA().multiObjectiveValuesByAlternative()
        val completed = assertIs<ModaRunResult.Completed>(studyOver(data))
        val analyzerBest = fromAnalyzer.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .first().key
        assertEquals(analyzerBest, completed.snapshot.primaryRecommendation)
    }

    @Test
    fun `the two paths score each response over the same range`() {
        val data = noisyData()
        val analyzerModel = analyzerOver(data).averageMODA()
        val completed = assertIs<ModaRunResult.Completed>(studyOver(data))
        for (metric in analyzerModel.metrics) {
            val record = completed.snapshot.metric(metric.name)
            assertTrue(record != null, "the study has no metric named ${metric.name}")
            val analyzerDomain = analyzerModel.effectiveDomainOf(metric)
            assertEquals(
                analyzerDomain.lowerLimit, record.effectiveLowerLimit, 1.0e-12,
                "the two paths disagree about the lower limit for ${metric.name}"
            )
            assertEquals(
                analyzerDomain.upperLimit, record.effectiveUpperLimit, 1.0e-12,
                "the two paths disagree about the upper limit for ${metric.name}"
            )
        }
    }

    @Test
    fun `the two paths agree on each individual value, not only on the totals`() {
        val data = noisyData()
        val analyzerValues = analyzerOver(data).averageMODA().alternativeValuesByMetric()
        val completed = assertIs<ModaRunResult.Completed>(studyOver(data))
        for ((alternative, byMetric) in analyzerValues) {
            for ((metric, value) in byMetric) {
                assertEquals(
                    value, completed.snapshot.values[alternative]!![metric.name]!!, 1.0e-12,
                    "the two paths disagree about $alternative on ${metric.name}"
                )
            }
        }
    }

    @Test
    fun `the two paths agree when a response is read the other way round`() {
        val data = noisyData()
        val directions = mapOf("Throughput" to MetricIfc.Direction.BiggerIsBetter.name)
        val analyzer = MODAAnalyzer(
            alternativeNames = alternatives.toSet(),
            responseDefinitions = responses.map {
                if (it == "Throughput") {
                    MODAAnalyzerData(it, direction = MetricIfc.Direction.BiggerIsBetter)
                } else {
                    MODAAnalyzerData(it)
                }
            }.toSet(),
            responseData = data
        )
        val document = SimulationModaSource.documentFor(
            "Average MODA", alternatives, responses.associateWith { 1.0 }, data, directions
        )
        val source = SimulationModaSource(data, ReplicationAggregation.MEAN)
        val runner = ModaRunner(
            resolver = ModaSourceResolver(
                providers = mapOf(
                    SimulationModaSource.SIMULATION_PROVIDER_ID to ModaSourceProviderIfc { source }
                )
            )
        )
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        val fromAnalyzer = analyzer.averageMODA().multiObjectiveValuesByAlternative()
        for (alternative in alternatives) {
            assertEquals(
                fromAnalyzer[alternative]!!, completed.snapshot.overallValues[alternative]!!, 1.0e-12,
                "the two paths disagree about $alternative when a response is read the other way round"
            )
        }
    }

    @Test
    fun `the two paths agree when the responses are weighted unevenly`() {
        val data = noisyData()
        val weights = mapOf("Cost" to 3.0, "Delay" to 2.0, "Throughput" to 1.0)
        val analyzer = analyzerOver(data)
        analyzer.changeWeights(weights)

        val document = SimulationModaSource.documentFor("Average MODA", alternatives, weights, data)
        val source = SimulationModaSource(data, ReplicationAggregation.MEAN)
        val runner = ModaRunner(
            resolver = ModaSourceResolver(
                providers = mapOf(
                    SimulationModaSource.SIMULATION_PROVIDER_ID to ModaSourceProviderIfc { source }
                )
            )
        )
        val completed = assertIs<ModaRunResult.Completed>(runner.run(document))
        val fromAnalyzer = analyzer.averageMODA().multiObjectiveValuesByAlternative()
        for (alternative in alternatives) {
            assertEquals(
                fromAnalyzer[alternative]!!, completed.snapshot.overallValues[alternative]!!, 1.0e-12,
                "the two paths disagree about $alternative under uneven weights"
            )
        }
    }

    // ------------------------------------------------------------------------------------------
    // Where they deliberately part company
    // ------------------------------------------------------------------------------------------

    /**
     *  The analyzer keeps only the experiments whose replication count matches the smallest it
     *  finds, and drops the rest outright. An alternative run more times than the others therefore
     *  disappears from its results altogether rather than being summarised over fewer runs. A
     *  source summarises each alternative over everything it has, so the same alternative is still
     *  compared.
     *
     *  Neither is wrong on its own terms, but they answer different questions, and a study should
     *  not silently get one when the reader expects the other. This pins the difference so it is a
     *  decision rather than a surprise.
     */
    @Test
    fun `where the replication counts differ the source keeps the alternative and the analyzer drops it`() {
        val base = noisyData(replications = 6)
        // Alpha gets two more replications than the others, and says so on its records.
        val extended = base.map {
            if (it.exp_name == "Alpha") it.copy(num_reps = 8, last_rep_id = 8) else it
        } + responses.flatMap { response ->
            (7..8).map { replication ->
                record("Alpha", response, replication, 1000.0, 8)
            }
        }

        val source = SimulationModaSource(extended, ReplicationAggregation.MEAN)
        assertEquals(8, source.replicationIds("Alpha").size, "the source should hold every replication")
        assertEquals(6, source.replicationIds("Beta").size)

        // The source still scores Alpha, over all eight of its replications.
        val table = source.scores(alternatives.toSet(), setOf("Cost"))
        assertTrue("Alpha" in table.values, "the source dropped an alternative it had data for")
        val plainAverage = source.replicationScores("Alpha", "Cost")!!.average()
        assertEquals(plainAverage, table.values["Alpha"]!!["Cost"]!!, 1.0e-12)

        // The analyzer has nothing for Alpha at all, having discarded every one of its records.
        val fromAnalyzer = analyzerOver(extended).averagePerformance()
        assertTrue(
            "Alpha" !in fromAnalyzer,
            "the analyzer was expected to discard the alternative whose count differs, but kept it"
        )
        assertTrue("Beta" in fromAnalyzer, "the analyzer should have kept the matching alternatives")
    }
}
