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

package ksl.app.swing.scenario

import kotlinx.datetime.Instant
import ksl.app.session.OrchestratorSummary
import ksl.app.session.RunResult
import ksl.app.swing.common.comparison.ResponseCategory
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import ksl.utilities.io.dbutil.WithinRepCounterStatTableData
import ksl.utilities.io.dbutil.WithinRepStatTableData
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Adapter tests for [BatchCompletedComparisonSource].  Build a
 *  synthetic [RunResult.BatchCompleted] in memory (no orchestrator
 *  needed) and assert the [ComparisonDataSourceIfc] surface matches.
 */
class BatchCompletedComparisonSourceTest {

    @Test
    fun `empty BatchCompleted yields empty experiments`() {
        val result = RunResult.BatchCompleted(
            summary = summary(0, 0),
            snapshots = emptyList(),
            replicationsByItem = emptyMap()
        )
        val src = BatchCompletedComparisonSource(result)
        assertTrue(src.availableExperiments().isEmpty())
    }

    @Test
    fun `each scenario surfaces as one ExperimentRow with the right metadata`() {
        val result = mixedResult()
        val rows = BatchCompletedComparisonSource(result).availableExperiments()
        assertEquals(listOf("S1", "S2"), rows.map { it.name })

        val s1 = rows.first { it.name == "S1" }
        assertEquals("MM1", s1.modelIdentifier)
        assertEquals(3, s1.numReplications)
        // Responses: NumBusy (response stat), SystemTime (response stat),
        // NumServed (counter).  Ordering preserves first-encounter order.
        assertEquals(
            listOf("NumBusy", "SystemTime", "NumServed"),
            s1.responses.map { it.name }
        )
        assertEquals(
            listOf(
                ResponseCategory.OBSERVATION,
                ResponseCategory.OBSERVATION,
                ResponseCategory.COUNTER
            ),
            s1.responses.map { it.category }
        )
    }

    @Test
    fun `observations pull averages for a Response in rep_id order`() {
        val src = BatchCompletedComparisonSource(mixedResult())
        val obs = src.observations("S1", "SystemTime")
        // S1's SystemTime values across the three reps (in rep_id order): 1.0, 2.0, 3.0
        assertContentEquals(doubleArrayOf(1.0, 2.0, 3.0), obs)
    }

    @Test
    fun `observations pull last_value for a Counter`() {
        val src = BatchCompletedComparisonSource(mixedResult())
        val obs = src.observations("S1", "NumServed")
        assertContentEquals(doubleArrayOf(100.0, 105.0, 110.0), obs)
    }

    @Test
    fun `observations returns null when the response was not recorded`() {
        val src = BatchCompletedComparisonSource(mixedResult())
        assertNull(src.observations("S1", "Nonexistent"))
    }

    @Test
    fun `observations returns null when the experiment is unknown`() {
        val src = BatchCompletedComparisonSource(mixedResult())
        assertNull(src.observations("DoesNotExist", "NumBusy"))
    }

    @Test
    fun `observations respect rep_id ordering when snapshots arrive out of order`() {
        // ReplicationCompleted entries supplied in reverse rep_id order
        // — the adapter must re-sort by repId before extracting values.
        val unsorted = listOf(
            replication(repId = 3, withinRep = listOf(withinRep("X", 30.0))),
            replication(repId = 1, withinRep = listOf(withinRep("X", 10.0))),
            replication(repId = 2, withinRep = listOf(withinRep("X", 20.0)))
        )
        val result = RunResult.BatchCompleted(
            summary = summary(1, 1),
            snapshots = listOf(experimentCompleted("S", "M")),
            replicationsByItem = mapOf("S" to unsorted)
        )
        val src = BatchCompletedComparisonSource(result)
        assertContentEquals(doubleArrayOf(10.0, 20.0, 30.0), src.observations("S", "X"))
    }

    @Test
    fun `default sourceLabel reflects scenario count`() {
        val result = mixedResult()
        val src = BatchCompletedComparisonSource(result)
        // Label format: "Scenario run · <runId-prefix> · N scenarios"
        assertTrue(src.sourceLabel.startsWith("Scenario run · "))
        assertTrue(src.sourceLabel.endsWith(" · 2 scenarios"))
    }

    // ── Synthetic-data helpers ───────────────────────────────────────────

    private fun mixedResult(): RunResult.BatchCompleted {
        val s1Reps = (1..3).map { rep ->
            replication(
                repId = rep,
                withinRep = listOf(
                    withinRep("NumBusy", 0.4 + 0.1 * rep),
                    withinRep("SystemTime", rep.toDouble())
                ),
                withinCounters = listOf(withinCounter("NumServed", 95.0 + 5.0 * rep))
            )
        }
        val s2Reps = (1..3).map { rep ->
            replication(
                repId = rep,
                withinRep = listOf(
                    withinRep("NumBusy", 0.7 + 0.1 * rep)
                )
            )
        }
        return RunResult.BatchCompleted(
            summary = summary(2, 2),
            snapshots = listOf(
                experimentCompleted("S1", "MM1"),
                experimentCompleted("S2", "MM1")
            ),
            replicationsByItem = mapOf(
                "S1" to s1Reps,
                "S2" to s2Reps
            )
        )
    }

    private fun summary(total: Int, completed: Int): OrchestratorSummary = OrchestratorSummary(
        runId = "deadbeef-cafe-1234-5678-feedfacef00d",
        orchestratorName = "TestOrchestrator",
        totalItems = total,
        completedItems = completed,
        failedItems = total - completed,
        beginTime = Instant.fromEpochMilliseconds(0L),
        endTime = Instant.fromEpochMilliseconds(1000L)
    )

    private fun experimentCompleted(name: String, model: String): SimulationSnapshot.ExperimentCompleted =
        SimulationSnapshot.ExperimentCompleted(
            simulationRun = SimulationRunTableData(),
            acrossRepStats = emptyList(),
            histograms = emptyList(),
            frequencies = emptyList(),
            timeSeries = emptyList(),
            experiment = ExperimentTableData().apply {
                exp_name = name
                model_name = model
            }
        )

    private fun replication(
        repId: Int,
        withinRep: List<WithinRepStatTableData> = emptyList(),
        withinCounters: List<WithinRepCounterStatTableData> = emptyList()
    ): SimulationSnapshot.ReplicationCompleted = SimulationSnapshot.ReplicationCompleted(
        repId = repId,
        withinRepStats = withinRep,
        withinRepCounterStats = withinCounters,
        batchStats = emptyList()
    )

    private fun withinRep(statName: String, avg: Double): WithinRepStatTableData =
        WithinRepStatTableData().apply {
            stat_name = statName
            average = avg
        }

    private fun withinCounter(statName: String, lastValue: Double): WithinRepCounterStatTableData =
        WithinRepCounterStatTableData().apply {
            stat_name = statName
            last_value = lastValue
        }
}
