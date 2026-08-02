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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  Tests for running a study once per replication rather than once over the averages.
 *
 *  Comparing averages says which alternative is best on average. Comparing replication by
 *  replication says how often each comes first, which is a different question. It needs more of the
 *  runs than averaging does, and most of what is checked here is that it refuses rather than
 *  guesses when the runs do not support it.
 */
class ReplicatedModaRunnerTest {

    private fun record(
        alternative: String,
        response: String,
        replication: Int,
        value: Double,
        replications: Int = 5
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

    private val alternatives = listOf("Alpha", "Beta")

    /**
     *  Alpha is better on average, but Beta wins in the replications where Alpha's cost spikes, so
     *  the two questions have different answers and the difference is visible.
     */
    private fun splitData(): List<WithinRepViewData> {
        val costs = mapOf(
            "Alpha" to listOf(10.0, 10.0, 10.0, 40.0, 40.0),
            "Beta" to listOf(20.0, 20.0, 20.0, 20.0, 20.0)
        )
        return alternatives.flatMap { alternative ->
            costs[alternative]!!.mapIndexed { index, cost ->
                record(alternative, "Cost", index + 1, cost)
            }
        }
    }

    private fun documentFor(data: List<WithinRepViewData>): ModaDocument =
        SimulationModaSource.documentFor("Split", alternatives, mapOf("Cost" to 1.0), data)

    // ------------------------------------------------------------------------------------------
    // Running it
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study is run once for each replication the alternatives share`() {
        val data = splitData()
        val result = ReplicatedModaRunner().runPerReplication(
            documentFor(data), SimulationModaSource(data)
        )
        val completed = assertIs<PerReplicationResult.Completed>(result, "the study was refused: $result")
        assertContentEquals(listOf(1, 2, 3, 4, 5), completed.replicationIds)
        assertEquals(5, completed.byReplication.size)
        for (alternative in alternatives) {
            assertEquals(5, completed.overallValuesByAlternative[alternative]!!.size)
            assertTrue(completed.overallValuesByAlternative[alternative]!!.all { it.isFinite() })
        }
    }

    /**
     *  Winning most replications and winning on the averages are different things, and a study
     *  should be able to show the difference rather than hide it.
     */
    @Test
    fun `how often an alternative comes first is counted`() {
        val data = splitData()
        val completed = assertIs<PerReplicationResult.Completed>(
            ReplicatedModaRunner().runPerReplication(documentFor(data), SimulationModaSource(data))
        )
        // Alpha is cheaper in three replications, Beta in two.
        assertEquals(3, completed.winCounts["Alpha"])
        assertEquals(2, completed.winCounts["Beta"])
        assertEquals("Alpha", completed.mostFrequentWinner)
        assertEquals(0.6, completed.winProportions["Alpha"]!!, 1.0e-12)
        assertEquals(1.0, completed.winProportions.values.sum(), 1.0e-12)
    }

    /**
     *  The values for one replication have to line up across alternatives, otherwise comparing them
     *  in pairs, which is the whole point of running them under the same conditions, is invalid.
     */
    @Test
    fun `the values for one replication line up across alternatives`() {
        val data = splitData()
        val completed = assertIs<PerReplicationResult.Completed>(
            ReplicatedModaRunner().runPerReplication(documentFor(data), SimulationModaSource(data))
        )
        for ((position, replication) in completed.replicationIds.withIndex()) {
            val snapshot = completed.byReplication[replication]!!
            for (alternative in alternatives) {
                assertEquals(
                    snapshot.overallValues[alternative]!!,
                    completed.overallValuesByAlternative[alternative]!![position],
                    1.0e-12,
                    "the series for $alternative does not line up at replication $replication"
                )
            }
        }
    }

    @Test
    fun `each replication is recorded as its own study`() {
        val data = splitData()
        val completed = assertIs<PerReplicationResult.Completed>(
            ReplicatedModaRunner().runPerReplication(documentFor(data), SimulationModaSource(data))
        )
        for ((replication, snapshot) in completed.byReplication) {
            assertTrue(snapshot.name.contains("replication $replication"), "got '${snapshot.name}'")
            assertContentEquals(alternatives.sorted(), snapshot.alternatives.sorted())
        }
    }

    // ------------------------------------------------------------------------------------------
    // Refusing rather than guessing
    // ------------------------------------------------------------------------------------------

    /**
     *  This is the rule the plan singles out: alternatives observed different numbers of times
     *  cannot be compared replication by replication, because the comparison is between runs made
     *  under the same conditions and there is no such pairing.
     */
    @Test
    fun `alternatives observed different numbers of times are refused`() {
        val data = splitData() + record("Alpha", "Cost", 6, 12.0, 6)
        val result = ReplicatedModaRunner().runPerReplication(
            documentFor(splitData()), SimulationModaSource(data)
        )
        val refused = assertIs<PerReplicationResult.Refused>(result, "unequal counts were not refused")
        assertTrue(
            refused.reasons.any { it.contains("same number of replications") },
            "the refusal does not say why: ${refused.reasons}"
        )
        assertTrue(
            refused.reasons.any { it.contains("Alpha=6") && it.contains("Beta=5") },
            "the refusal does not say what the counts were: ${refused.reasons}"
        )
    }

    @Test
    fun `the same data is still fine to aggregate`() {
        val data = splitData() + record("Alpha", "Cost", 6, 12.0, 6)
        val source = SimulationModaSource(data)
        val runner = ModaRunner(
            resolver = ModaSourceResolver(
                providers = mapOf(
                    SimulationModaSource.SIMULATION_PROVIDER_ID to ModaSourceProviderIfc { source }
                )
            )
        )
        assertIs<ModaRunResult.Completed>(
            runner.run(SimulationModaSource.documentFor("Split", alternatives, mapOf("Cost" to 1.0), data)),
            "unequal counts should not stop the averages being compared"
        )
    }

    /**
     *  Replications are matched by number, so alternatives run over different replication numbers
     *  have nothing to compare even where they have the same count.
     */
    @Test
    fun `alternatives run over different replication numbers are refused`() {
        val data = listOf(
            record("Alpha", "Cost", 1, 10.0), record("Alpha", "Cost", 2, 11.0),
            record("Beta", "Cost", 8, 20.0), record("Beta", "Cost", 9, 21.0)
        )
        val result = ReplicatedModaRunner().runPerReplication(
            documentFor(data), SimulationModaSource(data)
        )
        val refused = assertIs<PerReplicationResult.Refused>(result, "disjoint replications were not refused")
        assertTrue(
            refused.reasons.any { it.contains("matched by") },
            "the refusal does not explain the matching: ${refused.reasons}"
        )
    }

    @Test
    fun `an alternative with no replications at all is refused by name`() {
        val data = splitData().filterNot { it.exp_name == "Beta" }
        val result = ReplicatedModaRunner().runPerReplication(
            documentFor(splitData()), SimulationModaSource(data)
        )
        val refused = assertIs<PerReplicationResult.Refused>(result)
        assertTrue(refused.reasons.any { it.contains("Beta") }, "${refused.reasons}")
    }

    @Test
    fun `a study that is wrong in itself is refused before any replication is run`() {
        val data = splitData()
        val broken = documentFor(data).copy(alternatives = listOf("Alpha"))
        val result = ReplicatedModaRunner().runPerReplication(broken, SimulationModaSource(data))
        val refused = assertIs<PerReplicationResult.Refused>(result)
        assertTrue(
            refused.issues.any { it.element == "alternatives" && it.severity == Severity.ERROR },
            "${refused.issues}"
        )
    }

    @Test
    fun `too few shared replications to compare is refused`() {
        val data = listOf(
            record("Alpha", "Cost", 1, 10.0, 1),
            record("Beta", "Cost", 1, 20.0, 1)
        )
        val result = ReplicatedModaRunner().runPerReplication(
            documentFor(data), SimulationModaSource(data)
        )
        val refused = assertIs<PerReplicationResult.Refused>(result)
        assertTrue(refused.reasons.any { it.contains("too few") }, "${refused.reasons}")
    }
}
