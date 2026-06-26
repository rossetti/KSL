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
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.session.RunResult
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the live optimization submit path: a stochastic-hill-climbing run
 * over the MM1 model (which declares SIMOPT support), minimizing a response over
 * its numeric control. The decision variable and objective are read from the
 * model descriptor so the test is robust to exact key names.
 */
class RunServiceOptimizationTest {

    @Test
    fun `submits an optimization for MM1 and completes with an iteration history`() = runBlocking {
        val registry = BundleRegistry.fromClasspath()
        try {
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val control = descriptor.controls.numericControls.first() // numServers
            val objective = descriptor.responseNames.first()

            val low = if (control.lowerBound.isFinite()) control.lowerBound else 1.0
            val high = if (control.upperBound.isFinite()) maxOf(low + 2.0, control.upperBound).coerceAtMost(low + 2.0) else low + 2.0
            val input = OptimizationInputSpec(
                name = control.keyName,
                lowerBound = low,
                upperBound = high,
                granularity = 1.0, // integer search
            )

            RunService.fromRegistry(registry).use { service ->
                val handle = service.submitOptimization(
                    modelId = "MM1",
                    objectiveResponse = objective,
                    inputs = listOf(input),
                    maxIterations = 3,
                    replicationsPerEvaluation = 2,
                    maximize = false,
                )
                val result = withTimeout(60.seconds) { handle.result.await() }
                assertIs<RunResult.OptimizationCompleted>(result, "expected an optimization result; got $result")
                assertTrue(result.iterationHistory.isNotEmpty(), "expected a per-iteration history")
            }
        } finally {
            registry.close()
        }
    }
}
