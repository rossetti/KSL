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
import ksl.app.session.RunResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the live designed-experiment submit path: a two-level factorial over
 * two of a model's numeric controls (a 2^2 design → four design points). The
 * result is a `RunResult.BatchCompleted` built from in-memory design-point
 * snapshots — proving the experiment execution path does not depend on the
 * experiment's internal database (an incidental output).
 *
 * Targets the first example model with ≥2 numeric controls (a factorial needs
 * ≥2 factors), discovered from the descriptors so the test is robust to which
 * bundle supplies it.
 */
class RunServiceExperimentTest {

    @Test
    fun `submits a 2x2 factorial experiment and completes with per-point snapshots`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            // Find a model with at least two numeric controls.
            val target = registry.listBundles().firstNotNullOfOrNull { bundle ->
                bundle.modelIds.firstNotNullOfOrNull { modelId ->
                    val descriptor = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                    if (descriptor != null && descriptor.controls.numericControls.size >= 2) {
                        Pair(modelId, descriptor)
                    } else {
                        null
                    }
                }
            }
            assertTrue(target != null, "expected an example model with >= 2 numeric controls")
            val (modelId, descriptor) = target

            val factors = descriptor.controls.numericControls.take(2).map { control ->
                // Anchor the levels at the control's current (valid) value.
                ExperimentFactorSpec(
                    name = control.keyName,
                    controlKey = control.keyName,
                    low = control.value,
                    high = control.value + 1.0,
                )
            }

            RunService.fromRegistry(registry).use { service ->
                val handle = service.submitExperiment(
                    modelId = modelId,
                    factors = factors,
                    numRepsPerDesignPoint = 2,
                )
                val result = withTimeout(120.seconds) { handle.result.await() }
                assertIs<RunResult.BatchCompleted>(result, "expected a batch result; got $result")
                assertTrue(
                    result.snapshots.isNotEmpty(),
                    "expected per-design-point snapshots; got ${result.snapshots.size}",
                )
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `runs a templated ExperimentConfiguration document end to end`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            val target = registry.listBundles().firstNotNullOfOrNull { bundle ->
                bundle.modelIds.firstNotNullOfOrNull { modelId ->
                    val descriptor = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                    if (descriptor != null && descriptor.controls.numericControls.size >= 2) Pair(modelId, descriptor) else null
                }
            }
            assertTrue(target != null, "expected an example model with >= 2 numeric controls")
            val (modelId, descriptor) = target

            // Author the document via the scaffold, round-trip through the codec,
            // validate against the model, then run it.
            val document = ExperimentDocuments.template(descriptor, modelId)
            val decoded = ExperimentDocuments.decode(ExperimentDocuments.encode(document))
            assertTrue(ExperimentDocuments.validate(decoded, descriptor).isValid, "templated document should validate")

            RunService.fromRegistry(registry).use { service ->
                val handle = service.submitExperimentConfig(decoded)
                val result = withTimeout(120.seconds) { handle.result.await() }
                assertIs<RunResult.BatchCompleted>(result, "expected a batch result; got $result")
                assertTrue(result.snapshots.isNotEmpty(), "expected per-design-point snapshots")
            }
        } finally {
            registry.close()
        }
    }
}
