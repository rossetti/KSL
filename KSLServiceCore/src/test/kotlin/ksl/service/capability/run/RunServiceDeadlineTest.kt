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
import ksl.app.config.ScenarioSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.session.RunResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end guard for the server's job-level deadline (Phase 9 A5). A run with
 * an effectively infinite replication horizon is submitted to a [RunService]
 * configured with a short deadline; the run must terminate as
 * `RunResult.Cancelled` (not `Completed`) well within the outer safety timeout.
 *
 * This exercises the whole mechanism together: the per-replication wall-clock
 * cap (KSL substrate) bounds a single runaway replication so the run loop
 * regains control, and the watchdog's cancellation then surfaces as a
 * `Cancelled` outcome — the detectable signal that keeps a timed-out run from
 * being mistaken for a clean result and cached as one.
 */
@org.junit.jupiter.api.Disabled("Heavy run test (wall-clock deadline waits); disabled initially per request to keep the suite fast.")
class RunServiceDeadlineTest {

    @Test
    fun `a runaway run is cancelled at the server deadline`(): Unit = runBlocking {
        val registry = TestBundles.registry()
        try {
            // Any discrete-event example model runs effectively forever under a
            // huge horizon; we just need one to drive past the deadline.
            val modelId = registry.listBundles().firstNotNullOfOrNull { it.modelIds.firstOrNull() }
            assertTrue(modelId != null, "expected at least one model")

            RunService.fromRegistry(registry, runDeadline = 2.seconds).use { service ->
                val config = RunConfiguration(
                    scenarios = listOf(
                        ScenarioSpec(
                            name = modelId,
                            modelReference = ModelReference.ByProviderId(modelId),
                            runOverrides = ExperimentRunOverrides(
                                numberOfReplications = 5,
                                lengthOfReplication = 1.0e12, // effectively unbounded
                            ),
                        ),
                    ),
                    outputConfig = OutputConfig(reports = emptySet()),
                )
                // The deadline is 2s; allow generous slack for CI scheduling. If the
                // guard regressed, this would run to the huge horizon and the outer
                // timeout would fire instead.
                val result = withTimeout(30.seconds) { service.submitRunConfig(config).result.await() }
                assertIs<RunResult.Cancelled>(result, "a deadline breach must surface as Cancelled; got $result")
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a runaway experiment is cancelled at the server deadline`(): Unit = runBlocking {
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

            // Author a valid experiment document, then make every design point run
            // effectively forever (CONCURRENT is the template default, so the
            // parallel path's cooperative cancellation applies).
            val template = ExperimentDocuments.template(descriptor, modelId)
            val runaway = template.copy(
                runParameterOverrides = template.runParameterOverrides.copy(lengthOfReplication = 1.0e12),
            )

            RunService.fromRegistry(registry, runDeadline = 2.seconds).use { service ->
                val result = withTimeout(60.seconds) { service.submitExperimentConfig(runaway).result.await() }
                assertIs<RunResult.Cancelled>(result, "a deadline breach must surface as Cancelled; got $result")
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a long optimization is cancelled at the server deadline`(): Unit = runBlocking {
        val registry = TestBundles.registry()
        try {
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val control = descriptor.controls.numericControls.first()
            val objective = descriptor.responseNames.first()
            val low = if (control.lowerBound.isFinite()) control.lowerBound else 1.0
            val input = OptimizationInputSpec(
                name = control.keyName, lowerBound = low, upperBound = low + 2.0, granularity = 1.0,
            )

            // The optimization path honors cancellation between solver iterations
            // (onCancelHook -> stopIterations). A heavy per-evaluation replication
            // budget makes the solver still be running when the short deadline
            // elapses, so the watchdog's cancel surfaces as Cancelled. (The
            // per-replication cap stamped by DeadlineModelProvider additionally
            // bounds any single runaway evaluation replication; here MM1's reps are
            // short, so this test exercises the between-iteration watchdog path.)
            RunService.fromRegistry(registry, runDeadline = 1.seconds).use { service ->
                val handle = service.submitOptimization(
                    modelId = "MM1", objectiveResponse = objective, inputs = listOf(input),
                    maxIterations = 200, replicationsPerEvaluation = 10_000, maximize = false,
                )
                val result = withTimeout(60.seconds) { handle.result.await() }
                assertIs<RunResult.Cancelled>(result, "a deadline breach must surface as Cancelled; got $result")
            }
        } finally {
            registry.close()
        }
    }
}
