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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Behavioural tests for [ComparisonSelectionModel].  Each test
 *  builds an in-memory source via [InMemoryComparisonSource] and
 *  asserts that selection mutators / derived state / validation
 *  behave as the analyzer expects.
 *
 *  Response and analysis-type selection have moved into the per-
 *  analysis dialogs, so the model only owns experiment selection
 *  now — these tests reflect the trimmed surface.
 */
class ComparisonSelectionModelTest {

    @Test
    fun `initial state has no experiments selected`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        assertTrue(m.selectedExperimentNames.isEmpty())
        assertTrue(m.availableResponses().isEmpty())
    }

    @Test
    fun `selectAll checks every experiment across all sources`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source(), oneLkSource()))
        m.selectAll()
        assertEquals(setOf("S1", "S2", "S3"), m.selectedExperimentNames)
    }

    @Test
    fun `selectNone clears experiments`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        m.selectAll()
        m.selectNone()
        assertTrue(m.selectedExperimentNames.isEmpty())
    }

    @Test
    fun `toggleExperiment flips checked state`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        m.toggleExperiment("S1", checked = true)
        assertEquals(setOf("S1"), m.selectedExperimentNames)
        m.toggleExperiment("S1", checked = false)
        assertTrue(m.selectedExperimentNames.isEmpty())
    }

    @Test
    fun `availableResponses is the union across checked experiments`() {
        val m = ComparisonSelectionModel(listOf(oneMm1OneLkSource()))
        m.selectAll()
        val names = m.availableResponses().map { it.name }
        // MM1 has NumBusy; LK has OnHandLevel. Union: both.
        assertEquals(listOf("NumBusy", "OnHandLevel"), names)
    }

    @Test
    fun `experimentsRecording filters to participants only`() {
        val m = ComparisonSelectionModel(listOf(oneMm1OneLkSource()))
        m.selectAll()
        assertEquals(listOf("MM1"), m.experimentsRecording("NumBusy").map { it.name })
        assertEquals(listOf("LK"), m.experimentsRecording("OnHandLevel").map { it.name })
    }

    @Test
    fun `validateForResponse fails when response is null`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        m.selectAll()
        val v = m.validateForResponse(null, AnalysisType.BOX_PLOT)
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("Pick a response"))
    }

    @Test
    fun `validateForResponse fails when no checked experiment records the response`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        // Don't check anything.
        val v = m.validateForResponse("NumBusy", AnalysisType.BOX_PLOT)
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("No checked experiment"))
    }

    @Test
    fun `validateForResponse ok for box plot with one participant`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        m.selectAll()
        m.toggleExperiment("S2", false)         // leave only S1
        assertTrue(m.validateForResponse("NumBusy", AnalysisType.BOX_PLOT).ok)
    }

    @Test
    fun `validateForResponse fails MCA with only one participant`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        m.selectAll()
        m.toggleExperiment("S2", false)
        val v = m.validateForResponse("NumBusy", AnalysisType.MULTIPLE_COMPARISON)
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("at least 2 experiments"))
    }

    @Test
    fun `validateForResponse fails MCA on unequal replication counts`() {
        // S1: 3 reps for NumBusy; S2: 5 reps for NumBusy
        val src = InMemoryComparisonSource.builder("uneven").apply {
            experiment("S1", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(1.0, 2.0, 3.0))
            }
            experiment("S2", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0))
            }
        }.build()
        val m = ComparisonSelectionModel(listOf(src))
        m.selectAll()
        val v = m.validateForResponse("NumBusy", AnalysisType.MULTIPLE_COMPARISON)
        assertFalse(v.ok)
        assertTrue(v.reason!!.contains("equal replication counts"))
    }

    @Test
    fun `gatherObservationsFor returns map keyed by experiment in selection order`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        m.selectAll()
        val obs = m.gatherObservationsFor("NumBusy")
        assertEquals(listOf("S1", "S2"), obs.keys.toList())
        assertContentEquals(doubleArrayOf(0.5, 0.6, 0.7), obs["S1"])
        assertContentEquals(doubleArrayOf(0.8, 0.9, 1.0), obs["S2"])
    }

    @Test
    fun `gatherObservationsFor excludes experiments that do not record the response`() {
        val m = ComparisonSelectionModel(listOf(oneMm1OneLkSource()))
        m.selectAll()
        val obs = m.gatherObservationsFor("NumBusy")
        // LK doesn't record NumBusy.
        assertEquals(listOf("MM1"), obs.keys.toList())
    }

    @Test
    fun `listeners fire on every mutation`() {
        val m = ComparisonSelectionModel(listOf(twoMm1Source()))
        var fires = 0
        m.addListener { fires++ }
        m.selectAll()
        m.toggleExperiment("S1", false)
        m.selectNone()
        assertEquals(3, fires)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun twoMm1Source(): InMemoryComparisonSource =
        InMemoryComparisonSource.builder("two-mm1").apply {
            experiment("S1", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5, 0.6, 0.7))
            }
            experiment("S2", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.8, 0.9, 1.0))
            }
        }.build()

    private fun oneLkSource(): InMemoryComparisonSource =
        InMemoryComparisonSource.builder("one-lk").apply {
            experiment("S3", model = "LKInventory") {
                response("OnHandLevel", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(10.0, 12.0, 11.0))
            }
        }.build()

    private fun oneMm1OneLkSource(): InMemoryComparisonSource =
        InMemoryComparisonSource.builder("mixed").apply {
            experiment("MM1", model = "MM1") {
                response("NumBusy", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(0.5, 0.6, 0.7))
            }
            experiment("LK", model = "LKInventory") {
                response("OnHandLevel", ResponseCategory.TIME_WEIGHTED, doubleArrayOf(10.0, 12.0, 11.0))
            }
        }.build()
}
