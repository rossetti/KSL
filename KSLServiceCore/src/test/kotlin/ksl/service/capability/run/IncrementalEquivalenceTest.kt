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
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.capability.run.dto.mapping.toDto
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The soundness gate for incremental-replication caching (Option B): a cached
 * *N*-rep run combined with a top-up of the next *M−N* reps must reproduce a
 * monolithic *M*-rep run, response for response. The top-up reproduces reps
 * `N+1..M` by resetting the streams and advancing *N* substreams before running
 * (`numberOfStreamAdvancesPriorToRunning`), which the substrate guarantees is
 * bit-identical to those replications in the full run; the combination is Chan's
 * exact parallel merge of the sufficient statistics. Anything but a match here
 * means the optimization is unsound, so this test must pass before the cache is
 * allowed to combine.
 */
class IncrementalEquivalenceTest {

    private fun runConfig(reps: Int, resetStart: Boolean? = null, advance: Int? = null) =
        RunConfiguration(
            scenarios = listOf(
                ScenarioSpec(
                    name = "MM1",
                    modelReference = ModelReference.ByProviderId("MM1"),
                    runOverrides = ExperimentRunOverrides(
                        numberOfReplications = reps,
                        resetStartStreamOption = resetStart,
                        numberOfStreamAdvancesPriorToRunning = advance,
                    ),
                ),
            ),
            outputConfig = OutputConfig(reports = emptySet()),
        )

    @Test
    fun `cached N plus top-up M minus N equals a monolithic M-rep run`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            RunService.fromRegistry(registry).use { svc ->
                val m = 8
                val n = 3

                suspend fun run(config: RunConfiguration): RunResultDto.Completed =
                    withTimeout(120.seconds) { svc.submitRunConfig(config).result.await() }.toDto() as RunResultDto.Completed

                val mono = run(runConfig(m))
                val cached = run(runConfig(n))
                val topUp = run(runConfig(m - n, resetStart = true, advance = n))
                val combined = IncrementalCombine.completed(cached, topUp)

                assertEquals(m, combined.summary.completedReplications, "combined replication count")
                assertTrue(mono.responses.isNotEmpty(), "expected response statistics")

                val monoByName = mono.responses.associateBy { it.name }
                for (r in combined.responses) {
                    val expected = monoByName[r.name] ?: error("response '${r.name}' missing from the monolithic run")
                    // Bit-identical replications → values agree to floating-point accumulation.
                    fun tol(x: Double) = 1e-6 * (1.0 + abs(x))
                    assertEquals(expected.count!!, r.count!!, 1e-9, "${r.name} count")
                    assertEquals(expected.average!!, r.average!!, tol(expected.average!!), "${r.name} average")
                    assertEquals(expected.stdDev!!, r.stdDev!!, tol(expected.stdDev!!), "${r.name} stdDev")
                    assertEquals(expected.halfWidth!!, r.halfWidth!!, tol(expected.halfWidth!!), "${r.name} halfWidth")
                }
            }
        } finally {
            registry.close()
        }
    }
}
