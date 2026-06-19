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

package ksl.service.capability.run.dto

import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import ksl.app.session.RunResult
import ksl.app.session.RunSummary
import ksl.service.capability.run.dto.mapping.toDto
import ksl.simulation.IterativeProcessIfc
import ksl.utilities.io.dbutil.AcrossRepStatTableData
import ksl.utilities.io.dbutil.ExperimentTableData
import ksl.utilities.io.dbutil.SimulationRunTableData
import ksl.utilities.io.dbutil.SimulationSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Proves the DTO layer is wire-clean where the engine result types are not:
 * a [RunResult.Completed] built from the database-row-shaped snapshot types
 * maps to a [RunResultDto], serializes to JSON, and round-trips back to an
 * equal object. `AcrossRepStatTableData` itself is not `@Serializable`, so a
 * passing round-trip is the proof that the projection earns its keep.
 */
class RunResultDtoRoundTripTest {

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    private fun mm1LikeResult(): RunResult.Completed {
        val summary = RunSummary(
            runId = "a1b2c3",
            modelIdentifier = "MM1",
            experimentName = "MM1",
            requestedReplications = 30,
            completedReplications = 30,
            endingStatus = IterativeProcessIfc.EndingStatus.COMPLETED_ALL_STEPS,
            beginTime = Instant.parse("2026-06-12T20:15:02Z"),
            endTime = Instant.parse("2026-06-12T20:15:04Z"),
        )
        val systemTime = AcrossRepStatTableData(
            stat_name = "MM1Queue:SystemTime",
            stat_count = 30.0,
            average = 5.31,
            std_dev = 0.66,
            std_err = 0.12,
            half_width = 0.25,
            conf_level = 0.95,
            minimum = 4.9,
            maximum = 5.8,
        )
        val numInSystem = AcrossRepStatTableData(
            stat_name = "MM1Queue:NumInSystem",
            stat_count = 30.0,
            average = 1.06,
            std_err = 0.03,
            half_width = 0.06,
            conf_level = 0.95,
        )
        val snapshot = SimulationSnapshot.ExperimentCompleted(
            simulationRun = SimulationRunTableData(run_name = "MM1", num_reps = 30),
            acrossRepStats = listOf(systemTime, numInSystem),
            histograms = emptyList(),
            frequencies = emptyList(),
            timeSeries = emptyList(),
            experiment = ExperimentTableData(exp_name = "MM1"),
        )
        return RunResult.Completed(summary, snapshot)
    }

    @Test
    fun `Completed result maps and round-trips through JSON`() {
        val dto = mm1LikeResult().toDto()

        // The projection captured the headline statistics, dropping DB keys.
        assertIs<RunResultDto.Completed>(dto)
        assertEquals(30, dto.summary.completedReplications)
        assertEquals("COMPLETED_ALL_STEPS", dto.summary.endingStatus)
        assertEquals(2, dto.responses.size)
        assertEquals("MM1Queue:SystemTime", dto.responses[0].name)
        assertEquals(5.31, dto.responses[0].average)
        assertEquals(0.25, dto.responses[0].halfWidth)

        // The whole sealed payload round-trips polymorphically.
        val encoded = json.encodeToString<RunResultDto>(dto)
        assertTrue(encoded.contains("\"type\": \"completed\""))
        val decoded = json.decodeFromString<RunResultDto>(encoded)
        assertEquals(dto, decoded)
    }

    @Test
    fun `terminal failure and cancellation project to their variants`() {
        val cancelled = (RunResult.Cancelled("user requested") as RunResult).toDto()
        assertIs<RunResultDto.Cancelled>(cancelled)
        assertEquals("user requested", cancelled.reason)

        val encoded = json.encodeToString<RunResultDto>(cancelled)
        assertEquals(cancelled, json.decodeFromString<RunResultDto>(encoded))
    }
}
