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

package ksl.app.swing.common.comparison

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals

/**
 *  Behavioural tests for [ComparisonDataSourceIfc] and the helper
 *  extension functions in `ComparisonDataSourceIfc.kt`.  The
 *  fixture is [InMemoryComparisonSource]; the same shapes apply to
 *  every real adapter so passing these is a precondition for
 *  shipping an adapter.
 */
class ComparisonDataSourceTest {

    @Test
    fun `empty source returns empty experiment list`() {
        val src = InMemoryComparisonSource.builder("empty").build()
        assertTrue(src.availableExperiments().isEmpty())
    }

    @Test
    fun `single-experiment source surfaces that experiment`() {
        val src = InMemoryComparisonSource.builder("one").apply {
            experiment("S1", model = "MM1") {
                response("SystemTime", ResponseCategory.OBSERVATION, doubleArrayOf(1.0, 2.0, 3.0))
            }
        }.build()
        val rows = src.availableExperiments()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals("S1", row.name)
        assertEquals("MM1", row.modelIdentifier)
        assertEquals(3, row.numReplications)
        assertEquals(1, row.responses.size)
        assertEquals("SystemTime", row.responses.single().name)
        assertEquals(ResponseCategory.OBSERVATION, row.responses.single().category)
    }

    @Test
    fun `observations returns a defensive copy`() {
        val src = InMemoryComparisonSource.builder("o").apply {
            experiment("S1", model = "MM1") {
                response("SystemTime", ResponseCategory.OBSERVATION, doubleArrayOf(1.0, 2.0))
            }
        }.build()
        val obs = src.observations("S1", "SystemTime")!!
        assertContentEquals(doubleArrayOf(1.0, 2.0), obs)
        obs[0] = 99.0
        // Mutation does not leak back into the source.
        assertContentEquals(doubleArrayOf(1.0, 2.0), src.observations("S1", "SystemTime"))
    }

    @Test
    fun `observations returns null for unknown experiment or response`() {
        val src = InMemoryComparisonSource.builder("o").apply {
            experiment("S1", model = "MM1") {
                response("SystemTime", ResponseCategory.OBSERVATION, doubleArrayOf(1.0, 2.0))
            }
        }.build()
        assertNull(src.observations("S2", "SystemTime"))      // unknown experiment
        assertNull(src.observations("S1", "Throughput"))      // unknown response on known experiment
    }

    @Test
    fun `unionOfResponses returns sorted distinct names`() {
        val src = mixedSource()
        val all = src.availableExperiments().unionOfResponses()
        // S1: SystemTime, NumBusy   ; S2: NumBusy, NumServed   ; S3: OnHandLevel
        assertEquals(
            listOf("NumBusy", "NumServed", "OnHandLevel", "SystemTime"),
            all.map { it.name }
        )
    }

    @Test
    fun `unionOfResponses preserves category of first occurrence`() {
        val src = InMemoryComparisonSource.builder("u").apply {
            experiment("A", model = "M") {
                response("X", ResponseCategory.OBSERVATION, doubleArrayOf(1.0))
            }
            experiment("B", model = "M") {
                response("X", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(1.0))
            }
        }.build()
        val union = src.availableExperiments().unionOfResponses()
        assertEquals(1, union.size)
        // First-occurrence wins.
        assertEquals(ResponseCategory.OBSERVATION, union.single().category)
    }

    @Test
    fun `recordingResponse filters to experiments that have the response`() {
        val src = mixedSource()
        val rows = src.availableExperiments()
        // NumBusy is in S1 and S2 only.
        assertEquals(
            listOf("S1", "S2"),
            rows.recordingResponse("NumBusy").map { it.name }
        )
        // OnHandLevel is in S3 only.
        assertEquals(
            listOf("S3"),
            rows.recordingResponse("OnHandLevel").map { it.name }
        )
        // Unknown response → empty.
        assertTrue(rows.recordingResponse("DoesNotExist").isEmpty())
    }

    /**
     *  Test fixture mimicking a mixed-model sweep: two MM1 scenarios
     *  with overlapping responses + one LK Inventory scenario with
     *  none in common.
     */
    private fun mixedSource(): InMemoryComparisonSource =
        InMemoryComparisonSource.builder("mixed").apply {
            experiment("S1", model = "MM1") {
                response("SystemTime", ResponseCategory.OBSERVATION, doubleArrayOf(1.0, 2.0, 3.0))
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.4, 0.5, 0.6))
            }
            experiment("S2", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.7, 0.8, 0.9))
                response("NumServed", ResponseCategory.COUNTER, doubleArrayOf(100.0, 110.0, 95.0))
            }
            experiment("S3", model = "LKInventory") {
                response("OnHandLevel", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(10.0, 12.0, 11.0))
            }
        }.build()
}
