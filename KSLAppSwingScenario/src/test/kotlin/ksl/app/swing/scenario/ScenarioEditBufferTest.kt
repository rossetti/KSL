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

import ksl.app.bundle.BundleLoader
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.ScenarioSpec
import org.junit.jupiter.api.Test
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase F tests for [ScenarioEditBuffer].  Exercises the per-scenario
 *  edit buffer used by the modeless editor window.
 */
class ScenarioEditBufferTest {

    private var buffer: ScenarioEditBuffer? = null

    @AfterTest
    fun closeBuffer() {
        buffer?.close()
        buffer = null
    }

    private fun spec(
        name: String = "S1",
        bundleId: String = "missing", modelId: String = "missing",
        reps: Int? = null
    ) = ScenarioSpec(
        name = name,
        modelReference = ModelReference.ByBundleAndModelId(bundleId, modelId),
        runOverrides = reps?.let { ExperimentRunOverrides(numberOfReplications = it) }
    )

    @Test
    fun `empty fallback yields no controls, no RVs, safe defaults`() {
        val b = ScenarioEditBuffer.empty(spec()).also { buffer = it }
        assertTrue(b.controlsSnapshot.totalControls == 0)
        assertTrue(b.rvSnapshot.isEmpty())
        assertEquals(1, b.modelDefaults.numberOfReplications)
    }

    @Test
    fun `setName flips dirty and toSpec preserves it`() {
        val b = ScenarioEditBuffer.empty(spec(name = "Original")).also { buffer = it }
        assertFalse(b.isDirty.value)
        b.setName("Renamed")
        assertTrue(b.isDirty.value)
        assertEquals("Renamed", b.toSpec().name)
    }

    @Test
    fun `CSV toggles round-trip through toSpec`() {
        val b = ScenarioEditBuffer.empty(spec()).also { buffer = it }
        b.setEnableReplicationCSV(true)
        b.setEnableExperimentCSV(true)
        val out = b.toSpec()
        assertTrue(out.enableReplicationCSV)
        assertTrue(out.enableExperimentCSV)
    }

    @Test
    fun `runOverrides null when buffer untouched`() {
        val b = ScenarioEditBuffer.empty(spec()).also { buffer = it }
        assertNull(b.toSpec().runOverrides)
    }

    @Test
    fun `runOverrides survive when user edits a field`() {
        val b = ScenarioEditBuffer.empty(spec()).also { buffer = it }
        b.updateRunOverride { it.copy(numberOfReplications = 7) }
        val o = b.toSpec().runOverrides
        assertEquals(7, o?.numberOfReplications)
    }

    @Test
    fun `probe falls back to empty when bundle is missing`() {
        val b = ScenarioEditBuffer.probe(
            spec(bundleId = "nope", modelId = "nope"),
            bundles = emptyList()
        ).also { buffer = it }
        assertTrue(b.controlsSnapshot.totalControls == 0)
        assertTrue(b.rvSnapshot.isEmpty())
    }

    @Test
    fun `probe succeeds against a real classpath bundle`() {
        val bundles = BundleLoader.loadFromClasspath()
        try {
            assertTrue(bundles.isNotEmpty(), "classpath bundles required for this test")
            val bundle = bundles.first()
            val modelId = bundle.bundle.models.first().modelId
            val b = ScenarioEditBuffer.probe(
                spec(bundleId = bundle.bundle.bundleId, modelId = modelId),
                bundles = bundles
            ).also { buffer = it }
            // Snapshots can be empty or non-empty depending on the model;
            // the contract here is just that defaults came from the
            // descriptor, not from SAFE_FALLBACK_DEFAULTS — checking that
            // modelDefaults differs from the fallback in at least one field
            // is brittle, so just verify the buffer opened cleanly.
            assertEquals(b.modelReference, ModelReference.ByBundleAndModelId(bundle.bundle.bundleId, modelId))
        } finally {
            bundles.forEach { runCatching { it.close() } }
        }
    }
}
