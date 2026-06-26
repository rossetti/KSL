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

package ksl.service.capability.run

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.config.ExperimentRunOverrides
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.app.config.ScenarioSpec
import ksl.app.session.RunResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The document-centric path (Phase 8.2): a full [RunConfiguration] document is
 * encoded/decoded through the authoritative JSON codec (the same one the apps
 * use, with the sealed-`ModelReference` serializers), validated against the
 * provider, and submitted as authored — no flattening.
 */
class RunServiceDocumentTest {

    private fun mm1Document(reps: Int): RunConfiguration = RunConfiguration(
        scenarios = listOf(
            ScenarioSpec(
                name = "doc-run",
                modelReference = ModelReference.ByProviderId("MM1"),
                runOverrides = ExperimentRunOverrides(numberOfReplications = reps),
            ),
        ),
        outputConfig = OutputConfig(reports = emptySet()),
    )

    @Test
    fun `a run document round-trips through JSON, validates, and runs`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            // Round-trip the document through the authoritative codec.
            val original = mm1Document(reps = 3)
            val decoded = RunConfigurationJson.decode(RunConfigurationJson.encode(original))

            RunService.fromRegistry(registry).use { service ->
                assertTrue(service.validateRunConfig(decoded).isValid, "expected a valid document")
                val result = withTimeout(60.seconds) { service.submitRunConfig(decoded).result.await() }
                assertIs<RunResult.Completed>(result, "expected a completed run; got $result")
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a document referencing an unknown model fails validation`() {
        val registry = TestBundles.registry()
        try {
            val bad = RunConfiguration(
                scenarios = listOf(
                    ScenarioSpec(name = "x", modelReference = ModelReference.ByProviderId("NoSuchModel")),
                ),
            )
            RunService.fromRegistry(registry).use { service ->
                val validation = service.validateRunConfig(bad)
                assertTrue(!validation.isValid, "expected validation errors for an unknown model")
                assertTrue(validation.errors.isNotEmpty())
            }
        } finally {
            registry.close()
        }
    }
}
