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
import ksl.utilities.random.rvariable.parameters.RVParameterSetter
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Proves overrides are not merely accepted but actually *applied* by the run.
 * Inflating MM1's service-time mean (an RV parameter) drives average time in
 * system up, since time in system is bounded below by service time. A control
 * override (number of servers) is exercised end to end as a smoke check (its
 * value-routing correctness is covered by [RunInputsTest]).
 */
class RunServiceInputOverrideTest {

    private fun RunResult.Completed.systemTimeAverage(): Double {
        // GIGcQueue's time-in-system response is named "System Time".
        val stat = snapshot.acrossRepStats.first { it.stat_name.contains("System Time", ignoreCase = true) }
        return stat.average ?: error("no average for ${stat.stat_name}")
    }

    @Test
    fun `inflating the service-time mean drives time-in-system up`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val serviceRv = descriptor.rvParameterData.first { it.paramName == "mean" }
            val serviceKey = "${serviceRv.rvName}${RVParameterSetter.rvParamConCatChar}${serviceRv.paramName}"

            RunService.fromRegistry(registry).use { service ->
                val baseline = withTimeout(60.seconds) {
                    service.submitSingle("MM1", numberOfReplications = 20).result.await()
                }
                assertIs<RunResult.Completed>(baseline)
                val baselineSystemTime = baseline.systemTimeAverage()

                val inflated = RunInputs.bind(descriptor, mapOf(serviceKey to 100.0))
                val slow = withTimeout(60.seconds) {
                    service.submitSingle(
                        "MM1",
                        numberOfReplications = 20,
                        controlOverrides = inflated.controlOverrides,
                        rvOverrides = inflated.rvOverrides,
                    ).result.await()
                }
                assertIs<RunResult.Completed>(slow)

                // Time in system >= service time, so a mean service of 100 forces it well
                // above the small baseline — proving the RV override reached the engine.
                assertTrue(
                    slow.systemTimeAverage() > baselineSystemTime,
                    "service-mean override had no effect: baseline=$baselineSystemTime, inflated=${slow.systemTimeAverage()}",
                )
                assertTrue(slow.systemTimeAverage() > 50.0, "expected time in system >> service mean of 100")
            }
        } finally {
            registry.close()
        }
    }

    @Test
    fun `a numeric control override is accepted and the run completes`() = runBlocking {
        val registry = TestBundles.registry()
        try {
            val descriptor = registry.describeModel("ksl.examples.mm1", "MM1")!!
            val controlKey = descriptor.controls.numericControls.first().keyName // numServers
            val bound = RunInputs.bind(descriptor, mapOf(controlKey to 3.0))

            RunService.fromRegistry(registry).use { service ->
                val result = withTimeout(60.seconds) {
                    service.submitSingle(
                        "MM1",
                        numberOfReplications = 5,
                        controlOverrides = bound.controlOverrides,
                    ).result.await()
                }
                assertIs<RunResult.Completed>(result, "control override should run cleanly; got $result")
            }
        } finally {
            registry.close()
        }
    }
}
