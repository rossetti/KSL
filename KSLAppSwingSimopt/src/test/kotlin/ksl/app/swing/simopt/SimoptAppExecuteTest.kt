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

package ksl.app.swing.simopt

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.config.ModelReference
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.AlgorithmKind
import ksl.controls.ControlType
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Phase O7b Execute-step controller tests.
 *
 *  Pins the real run lifecycle ([SimoptAppController.submit] /
 *  [SimoptAppController.cancel]) and the hoisted validation flows.
 *
 *  Uses the assembled LK (s,S) inventory optimization model
 *  (`LKInventoryOpt`) — one decision variable (`reorderPoint`,
 *  integer) with stochastic hill climbing at 2 iterations × 1
 *  replication per evaluation.  Even though `BuildLKModel` defaults
 *  to 1000 replications × 120 horizon, the solver's
 *  `replicationsPerEvaluation` overrides the per-evaluation count,
 *  so a smoke test completes in well under 10 seconds on a
 *  developer laptop.  The 60-second test timeout is a generous
 *  safety net.
 */
class SimoptAppExecuteTest {

    @TempDir
    lateinit var bundleDir: Path

    /** SimOpt test-models bundle JAR (LKInventoryOpt + RQInventoryOpt), assembled once per test. */
    private val simoptJar: Path by lazy { SimoptBundleFixtures.simoptJar(bundleDir) }

    /** A fresh controller backed by an injected library holding the SimOpt bundle.
     *  Each call builds its own library; the controller closes it on `use {}` exit. */
    private fun controller() =
        SimoptAppController("Test", injectedBundleLibrary = SimoptBundleFixtures.library(simoptJar))

    private val lkBundleId = SimoptBundleFixtures.SIMOPT_BUNDLE_ID
    private val lkModelId = SimoptBundleFixtures.LK_OPT_MODEL_ID
    private fun lkRef() = ModelReference.ByBundleAndModelId(lkBundleId, lkModelId)

    /** Seed a tiny runnable optimization: LK inventory with the
     *  first integer control as the sole decision variable, SHC
     *  with 2 iterations × 1 reps.  Picks the input dynamically so
     *  the test survives control renames on the underlying model. */
    private fun seedRunnableProblem(c: SimoptAppController) {
        c.setModelReference(lkRef())
        val descriptor = c.currentModelDescriptor.value
        assertNotNull(descriptor, "LK descriptor must resolve")
        c.setObjectiveResponseName(descriptor.responseNames.first())
        val intControl = descriptor.controls.numericControls.firstOrNull {
            it.controlType == ControlType.INTEGER
        }
        assertNotNull(intControl, "LK must expose at least one integer control")
        // Use tight bounds (5..8) so SHC has a tiny exploration region
        // — the LK model accepts any positive integer for these
        // controls, and we want this test to run in well under a
        // second.
        c.addInput(OptimizationInputSpec(
            name = intControl.keyName,
            lowerBound = 5.0,
            upperBound = 8.0,
            granularity = 1.0
        ))
        c.setAlgorithmKind(AlgorithmKind.STOCHASTIC_HILL_CLIMBING)
        c.setCommonMaxIterations(2)
        c.setCommonReplicationsPerEvaluation(1)
    }

    /** Block the test thread until the controller's runningFlow
     *  emits `false`.  Times out at 60 seconds. */
    private fun awaitNotRunning(c: SimoptAppController) {
        runBlocking {
            withTimeout(60_000) {
                c.runningFlow.first { !it }
            }
        }
    }

    // ── Empty-document guard ──────────────────────────────────────────

    @Test
    fun `submit on an empty document is a no-op`() {
        controller().use { c ->
            c.submit()
            assertFalse(c.runningFlow.value, "submit must not start a run when no model is set")
            assertNull(c.lastResult.value)
        }
    }

    // ── Live document validation ─────────────────────────────────────

    @Test
    fun `documentValidation on an empty document carries MISSING_MODEL`() {
        controller().use { c ->
            val result = c.documentValidation.value
            assertFalse(result.isValid)
            assertTrue(
                result.errors.any { it.code == "MISSING_MODEL" },
                "Empty controller must surface MISSING_MODEL"
            )
        }
    }

    @Test
    fun `documentValidation is valid on a seeded runnable problem`() {
        controller().use { c ->
            seedRunnableProblem(c)
            val result = c.documentValidation.value
            assertTrue(
                result.isValid,
                "Seeded runnable problem must pass document checks; got: " +
                    "${result.errors.map { it.code }}"
            )
        }
    }

    // ── Model-aware validation cache + stale flag ───────────────────

    @Test
    fun `runModelAwareValidationNow populates the cache and clears the stale flag`() {
        controller().use { c ->
            seedRunnableProblem(c)
            // Pre-condition: cache is null + stale until the user
            // explicitly re-checks.
            assertNull(c.modelAwareValidation.value)
            assertTrue(c.modelAwareStale.value)

            c.runModelAwareValidationNow()

            val cached = c.modelAwareValidation.value
            assertNotNull(cached, "Cache must be populated after explicit re-check")
            assertFalse(c.modelAwareStale.value, "Stale flag must clear after explicit re-check")
        }
    }

    @Test
    fun `document edit re-asserts the stale flag`() {
        controller().use { c ->
            seedRunnableProblem(c)
            c.runModelAwareValidationNow()
            assertFalse(c.modelAwareStale.value)
            // A structural edit must mark the cached model-aware
            // result stale again.
            c.setCommonMaxIterations(3)
            assertTrue(c.modelAwareStale.value)
        }
    }

    // ── Real run lifecycle ───────────────────────────────────────────

    @Test
    fun `submit completes and populates lastResult`() {
        controller().use { c ->
            seedRunnableProblem(c)
            c.submit()
            assertTrue(c.runningFlow.value, "running must flip true synchronously inside submit()")
            awaitNotRunning(c)
            val result = c.lastResult.value
            assertNotNull(result, "A clean completion must populate lastResult")
            // `c.lastResult.value` is typed as `OptimizationCompleted?`,
            // so the assertNotNull above is enough to refine the type.
            assertTrue(
                result.iterationHistory.isNotEmpty(),
                "OptimizationOrchestrator emits at least one snapshot per iteration"
            )
        }
    }

    @Test
    fun `cancel resolves to non-running with null lastResult`() {
        controller().use { c ->
            seedRunnableProblem(c)
            c.submit()
            // Request cooperative cancellation; the solver exits at
            // the next iteration boundary.
            c.cancel()
            awaitNotRunning(c)
            assertNull(
                c.lastResult.value,
                "A cancelled run must not populate lastResult"
            )
        }
    }

    @Test
    fun `latestIteration starts null and is cleared on a fresh submit`() {
        controller().use { c ->
            assertNull(c.latestIteration.value, "Fresh controller has no live iteration")
            seedRunnableProblem(c)
            c.submit()
            awaitNotRunning(c)
            // Run completed → submit again to verify latestIteration
            // is reset to null when a new run begins.  We can't
            // observe the inner `myLatestIteration.value = null`
            // transition reliably between submit() and the first
            // iteration event, so we instead pin that lastResult is
            // null at the start of the new run.
            val priorResult = c.lastResult.value
            assertNotNull(priorResult)
            c.submit()
            assertNull(
                c.lastResult.value,
                "submit() must clear lastResult before starting the new run"
            )
            awaitNotRunning(c)
        }
    }

    // formatObjective tests live in KSLApp at
    // `ksl/app/optimization/OptimizationFormattingTest.kt` — the
    // formatter is a substrate-level helper used by both this
    // panel and the HTML report writer.
}
