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

package ksl.app.session

import kotlinx.datetime.Instant
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 *  Focused tests for [RunResult.BatchCompleted.withoutScenario].
 *
 *  Pin down the four contract corners:
 *
 *  1. matching name → snapshot removed and the corresponding
 *     `replicationsByItem` entry removed,
 *  2. unmatched name → receiver returned unchanged (no copy),
 *  3. removing the last snapshot → null,
 *  4. `OrchestratorSummary` is preserved (audit record of the
 *     batch that actually ran).
 */
class BatchCompletedWithoutScenarioTest {

    @Test
    fun `removing a matching scenario filters snapshots and replicationsByItem`() {
        val original = threeScenarioBatch()
        val updated = original.withoutScenario("S2")
        requireNotNull(updated) { "expected non-null result; only one of three snapshots removed" }
        assertEquals(
            listOf("S1", "S3"),
            updated.snapshots.map { it.experiment.exp_name },
            "snapshot for S2 should be filtered out, order otherwise preserved"
        )
        assertEquals(
            setOf("S1", "S3"),
            updated.replicationsByItem.keys,
            "replicationsByItem entry for S2 should be dropped"
        )
        assertNotSame(original, updated, "matching name must return a new instance, not the receiver")
    }

    @Test
    fun `removing an unknown name returns the receiver unchanged`() {
        val original = threeScenarioBatch()
        val updated = original.withoutScenario("DoesNotExist")
        // Contract: identity-preserving no-op when the name isn't present.
        assertSame(original, updated, "no-op call must return the receiver instance")
    }

    @Test
    fun `removing the last remaining snapshot returns null`() {
        val original = oneScenarioBatch("Solo")
        val updated = original.withoutScenario("Solo")
        assertNull(updated, "emptying snapshots must collapse to null")
    }

    @Test
    fun `summary is preserved when filtering`() {
        val original = threeScenarioBatch()
        val updated = original.withoutScenario("S2")
        requireNotNull(updated)
        // The summary is an audit record of the batch that ran;
        // post-hoc filtering of one of its outputs must not rewrite it.
        assertSame(
            original.summary, updated.summary,
            "summary must be carried through unchanged"
        )
        assertEquals(3, updated.summary.totalItems)
        assertEquals(3, updated.summary.completedItems)
        assertEquals(0, updated.summary.failedItems)
    }

    @Test
    fun `filtering is robust to a missing replicationsByItem entry`() {
        // ExperimentOrchestrator leaves replicationsByItem empty; that
        // path must still work — the snapshot removal happens, the
        // replications map stays empty.
        val base = threeScenarioBatch().copy(replicationsByItem = emptyMap())
        val updated = base.withoutScenario("S1")
        requireNotNull(updated)
        assertEquals(listOf("S2", "S3"), updated.snapshots.map { it.experiment.exp_name })
        assertTrue(updated.replicationsByItem.isEmpty())
    }

    // ── Fixtures ────────────────────────────────────────────────────────

    private fun threeScenarioBatch(): RunResult.BatchCompleted = RunResult.BatchCompleted(
        summary = OrchestratorSummary(
            runId = "deadbeef-cafe-1234-5678-feedfacef00d",
            orchestratorName = "TestOrchestrator",
            totalItems = 3,
            completedItems = 3,
            failedItems = 0,
            beginTime = Instant.fromEpochMilliseconds(0L),
            endTime = Instant.fromEpochMilliseconds(1000L)
        ),
        snapshots = listOf(
            experimentCompleted("S1"),
            experimentCompleted("S2"),
            experimentCompleted("S3")
        ),
        replicationsByItem = mapOf(
            "S1" to emptyList(),
            "S2" to emptyList(),
            "S3" to emptyList()
        )
    )

    private fun oneScenarioBatch(name: String): RunResult.BatchCompleted = RunResult.BatchCompleted(
        summary = OrchestratorSummary(
            runId = "deadbeef-cafe-1234-5678-feedfacef00d",
            orchestratorName = "TestOrchestrator",
            totalItems = 1,
            completedItems = 1,
            failedItems = 0,
            beginTime = Instant.fromEpochMilliseconds(0L),
            endTime = Instant.fromEpochMilliseconds(1000L)
        ),
        snapshots = listOf(experimentCompleted(name)),
        replicationsByItem = mapOf(name to emptyList())
    )

    private fun experimentCompleted(name: String): SimulationSnapshot.ExperimentCompleted =
        SimulationSnapshot.ExperimentCompleted(
            simulationRun = SimulationRunTableData(),
            acrossRepStats = emptyList(),
            histograms = emptyList(),
            frequencies = emptyList(),
            timeSeries = emptyList(),
            experiment = ExperimentTableData().apply {
                exp_name = name
                model_name = "M"
            }
        )
}
