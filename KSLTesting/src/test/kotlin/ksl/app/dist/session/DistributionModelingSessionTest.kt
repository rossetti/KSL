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

package ksl.app.dist.session

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.dist.DistributionModelingSession
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.DistributionKind
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DistributionModelingSessionTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private fun singleSpec(
        name: String = "x",
        data: DoubleArray = exponentialSample(1.0, 200, 31),
        estimatorIds: Set<String> = emptySet(),
        scoringModelIds: Set<String> = emptySet(),
        kind: DistributionKind = DistributionKind.CONTINUOUS
    ): FitSpec.Single = FitSpec.Single(
        FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf(name to data)),
            kind = kind,
            estimatorIds = estimatorIds,
            scoringModelIds = scoringModelIds
        )
    )

    @Test
    fun `submitAndAwaitBlocking returns a Completed result for a valid spec`() {
        DistributionModelingSession().use { session ->
            val result = session.submitAndAwaitBlocking(singleSpec(name = "expo"))
            val completed = assertIs<FitResult.Completed>(result)
            assertEquals("expo", completed.report.datasetName)
            assertTrue(completed.report.fits.isNotEmpty())
        }
    }

    @Test
    fun `submit emits exactly one Started followed by FitCompleted`() = runBlocking {
        DistributionModelingSession().use { session ->
            val handle = session.submit(singleSpec(name = "expo"))
            // Two events: Started, then a terminal FitCompleted.
            val events = withTimeout(10_000) { handle.events.take(2).toList() }
            assertIs<FitEvent.FitStarted>(events[0])
            assertEquals("expo", (events[0] as FitEvent.FitStarted).datasetName)
            val terminal = events[1]
            assertIs<FitEvent.FitCompleted>(terminal)
            assertEquals(handle.fitId, terminal.fitId)

            val result = withTimeout(10_000) { handle.result.await() }
            val completed = assertIs<FitResult.Completed>(result)
            assertEquals(terminal.report, completed.report)
        }
    }

    @Test
    fun `validation failure resolves immediately with ConfigurationError`(): Unit = runBlocking {
        DistributionModelingSession().use { session ->
            val spec = singleSpec(estimatorIds = setOf("not-a-real-estimator"))
            val handle = session.submit(spec)
            val result = withTimeout(2_000) { handle.result.await() }
            val failed = assertIs<FitResult.Failed>(result)
            val error = assertIs<FittingError.ConfigurationError>(failed.error)
            assertEquals(1, error.validationResult.errors.size)
            assertEquals("fit.estimator.unknown", error.validationResult.errors[0].code)
            // Terminal event without any preceding FitStarted.
            val terminal = withTimeout(2_000) { handle.events.first() }
            assertTrue(terminal is FitEvent.FitFailed)
        }
    }

    @Test
    fun `import failure with validation disabled surfaces as ImportError`(): Unit = runBlocking {
        DistributionModelingSession().use { session ->
            // Inline empty map slips past validation when validate = false, then
            // the importer rejects it.
            val spec = FitSpec.Single(
                FitConfiguration(dataSource = DataSourceReference.Inline(emptyMap()))
            )
            val handle = session.submit(spec, validate = false)
            val result = withTimeout(5_000) { handle.result.await() }
            val failed = assertIs<FitResult.Failed>(result)
            assertTrue(failed.error is FittingError.ImportError)
        }
    }

    @Test
    fun `discrete kind with non-integer data surfaces as RuntimeError`(): Unit = runBlocking {
        DistributionModelingSession().use { session ->
            // The default sample is continuous (non-integer); discrete fitting
            // rejects it at run time. validate = false bypasses the static
            // integer-data check so the runtime guard is exercised.
            val spec = singleSpec(kind = DistributionKind.DISCRETE)
            val handle = session.submit(spec, validate = false)
            val result = withTimeout(5_000) { handle.result.await() }
            val failed = assertIs<FitResult.Failed>(result)
            assertTrue(failed.error is FittingError.RuntimeError)
        }
    }

    @Test
    fun `submit after close returns an immediately-failed handle`() = runBlocking {
        val session = DistributionModelingSession()
        session.close()
        val handle = session.submit(singleSpec())
        val result = withTimeout(2_000) { handle.result.await() }
        val failed = assertIs<FitResult.Failed>(result)
        val error = assertIs<FittingError.RuntimeError>(failed.error)
        assertTrue(error.message.contains("closed"))
    }

    @Test
    fun `close cancels in-flight fits`() = runBlocking {
        val session = DistributionModelingSession()
        // Submit several fits to make a cancellation race likely.
        val handles = List(4) { i ->
            session.submit(singleSpec(name = "d$i", data = exponentialSample(1.0, 800, 100 + i)))
        }
        // Give the worker a moment to start, then close.
        delay(20)
        session.close()
        for (handle in handles) {
            val result = withTimeout(10_000) { handle.result.await() }
            // Either the fit completed before close won the race or it was cancelled.
            assertTrue(result is FitResult.Cancelled || result is FitResult.Completed)
        }
    }

    @Test
    fun `cancel before result resolves to Cancelled`() = runBlocking {
        DistributionModelingSession().use { session ->
            val handle = session.submit(
                singleSpec(name = "long", data = exponentialSample(1.0, 1500, 77))
            )
            // Cancel as soon as possible; tolerate a completion race.
            launch { handle.cancel("test") }
            val result = withTimeout(10_000) { handle.result.await() }
            assertTrue(result is FitResult.Cancelled || result is FitResult.Completed)
            if (result is FitResult.Cancelled) {
                assertEquals("test", result.reason)
            }
        }
    }
}
