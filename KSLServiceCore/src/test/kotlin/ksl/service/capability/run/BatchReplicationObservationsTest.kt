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
import kotlinx.serialization.json.Json
import ksl.app.session.RunResult
import ksl.service.capability.run.dto.BatchItemDto
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.capability.run.dto.mapping.toDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Proves C1: the per-replication response values captured by the batch orchestrators
 * survive the DTO projection. A two-level factorial (2^2 → four design points, three
 * reps each) is projected with [toDto], and every design point's `replicationObservations`
 * must carry, per response, an array of length = rep count whose mean equals the aggregate
 * average. Also proves the field is backward-compatible (an old-shape payload decodes).
 */
class BatchReplicationObservationsTest {

    @Test
    fun `batch DTO carries per-replication observations matching the aggregate means`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            // A factorial needs >= 2 numeric controls; discover a model that has them.
            val target = registry.listBundles().firstNotNullOfOrNull { bundle ->
                bundle.modelIds.firstNotNullOfOrNull { modelId ->
                    val d = runCatching { registry.describeModel(bundle.bundleId, modelId) }.getOrNull()
                    if (d != null && d.controls.numericControls.size >= 2) Pair(modelId, d) else null
                }
            }
            assertTrue(target != null, "expected a model with >= 2 numeric controls")
            val (modelId, descriptor) = target

            val factors = descriptor.controls.numericControls.take(2).map { control ->
                ExperimentFactorSpec(
                    name = control.keyName,
                    controlKey = control.keyName,
                    low = control.value,
                    high = control.value + 1.0,
                )
            }
            val numReps = 3

            RunService.fromRegistry(registry).use { service ->
                val result = withTimeout(120.seconds) {
                    service.submitExperiment(modelId, factors, numRepsPerDesignPoint = numReps).result.await()
                }
                assertIs<RunResult.BatchCompleted>(result, "expected a batch result; got $result")

                val dto = result.toDto()
                assertIs<RunResultDto.BatchCompleted>(dto)
                assertTrue(dto.items.isNotEmpty(), "expected design-point items")

                for (item in dto.items) {
                    assertEquals(numReps, item.numReplications, "item '${item.itemName}' should span $numReps reps")
                    assertTrue(item.replicationObservations.isNotEmpty(), "item '${item.itemName}' should carry per-rep data")
                    for (response in item.responses) {
                        val perRep = item.replicationObservations[response.name]
                        assertTrue(perRep != null, "response '${response.name}' should have per-rep observations")
                        assertEquals(numReps, perRep!!.size, "per-rep length should equal the rep count for '${response.name}'")
                        response.average?.let { avg ->
                            assertEquals(avg, perRep.average(), 1e-6, "re-averaged per-rep should equal the aggregate for '${response.name}'")
                        }
                    }
                }
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `an old-shape batch item without the new field still deserializes`() {
        // A payload retained before C1: no replicationObservations / numReplications.
        val oldShape = """{"itemName":"scenario-1","responses":[]}"""
        val item = Json.decodeFromString(BatchItemDto.serializer(), oldShape)
        assertEquals("scenario-1", item.itemName)
        assertTrue(item.replicationObservations.isEmpty(), "the new field should default to an empty map")
        assertEquals(0, item.numReplications)
    }
}
