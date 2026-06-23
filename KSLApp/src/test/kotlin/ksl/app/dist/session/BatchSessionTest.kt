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

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import ksl.app.dist.DistributionModelingSession
import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.config.FitSpec
import ksl.app.dist.config.NamedFitConfiguration
import ksl.app.dist.reporting.toDocument
import ksl.app.dist.result.BatchFitResultData
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * End-to-end verification of batch execution through the async session:
 * BatchFitStarted -> DatasetCompleted x N -> terminal BatchFitCompleted,
 * resolving to FitResult.BatchCompleted with per-dataset failures captured.
 */
class BatchSessionTest {

    private fun exponentialSample(mean: Double, n: Int, streamNumber: Int): DoubleArray {
        val rv = ExponentialRV(mean = mean, streamNum = streamNumber)
        return DoubleArray(n) { rv.value }
    }

    private val estimators = setOf("exponential-mle", "weibull-mle", "gamma-mle")

    private fun continuousEntry(name: String, stream: Int) = NamedFitConfiguration(
        name = name,
        config = FitConfiguration(
            dataSource = DataSourceReference.Inline(mapOf(name to exponentialSample(1.0, 150, stream))),
            estimatorIds = estimators
        )
    )

    private fun goodBatch() = FitSpec.Batch(
        listOf(continuousEntry("a", 1), continuousEntry("b", 2), continuousEntry("c", 3))
    )

    private fun tempContext(): RenderContext {
        val dir = Files.createTempDirectory("batch-session")
        return RenderContext(outputDir = dir, plotDir = dir)
    }

    @Test
    fun `submitAndAwaitBlocking returns BatchCompleted with one result per dataset`() {
        DistributionModelingSession().use { session ->
            val result = session.submitAndAwaitBlocking(goodBatch())
            val report = assertIs<FitResult.BatchCompleted>(result).report
            assertEquals(listOf("a", "b", "c"), report.results.map { it.datasetName })
            assertTrue(report.failures.isEmpty())
        }
    }

    @Test
    fun `batch emits a start, one progress per dataset, and a terminal event`(): Unit = runBlocking {
        DistributionModelingSession().use { session ->
            val handle = session.submit(goodBatch())
            // Collect through (and including) the terminal BatchFitCompleted.
            val events = withTimeout(20_000) {
                buildList {
                    handle.events.takeWhile { add(it); it !is FitEvent.BatchFitCompleted }.toList()
                }
            }
            val started = assertIs<FitEvent.BatchFitStarted>(events.first())
            assertEquals(3, started.datasetCount)
            val datasetEvents = events.filterIsInstance<FitEvent.DatasetCompleted>()
            assertEquals(3, datasetEvents.size)
            assertEquals(listOf(1, 2, 3), datasetEvents.map { it.index })
            assertTrue(datasetEvents.all { it.total == 3 && it.success })
            val terminal = assertIs<FitEvent.BatchFitCompleted>(events.last())

            val result = withTimeout(20_000) { handle.result.await() }
            val completed = assertIs<FitResult.BatchCompleted>(result)
            assertEquals(completed.report, terminal.report)
        }
    }

    @Test
    fun `per-dataset failure is captured and reported as unsuccessful progress`(): Unit = runBlocking {
        DistributionModelingSession().use { session ->
            val good = continuousEntry("good", 11)
            // A missing file passes static validation (file resolvability is a
            // run-time concern) but fails at import time -> captured per-dataset.
            val bad = NamedFitConfiguration(
                name = "bad",
                config = FitConfiguration(
                    dataSource = DataSourceReference.DelimitedFile(path = "/no/such/batch-file.csv"),
                    estimatorIds = estimators
                )
            )
            val handle = session.submit(FitSpec.Batch(listOf(good, bad)))
            val result = withTimeout(20_000) { handle.result.await() }
            val report = assertIs<FitResult.BatchCompleted>(result).report
            assertEquals(listOf("good"), report.results.map { it.datasetName })
            assertEquals(1, report.failures.size)
            assertEquals("bad", report.failures[0].name)
        }
    }

    @Test
    fun `awaited batch report is JSON round-trippable`() {
        DistributionModelingSession().use { session ->
            val report = assertIs<FitResult.BatchCompleted>(
                session.submitAndAwaitBlocking(goodBatch())
            ).report
            val json = Json { encodeDefaults = true }
            val encoded = json.encodeToString(BatchFitResultData.serializer(), report)
            assertEquals(report, json.decodeFromString(BatchFitResultData.serializer(), encoded))
        }
    }

    @Test
    fun `awaited batch report renders the cross-dataset summary`() {
        DistributionModelingSession().use { session ->
            val report = assertIs<FitResult.BatchCompleted>(
                session.submitAndAwaitBlocking(goodBatch())
            ).report
            val text = report.toDocument().toText(tempContext())
            assertTrue(text.contains("Cross-Dataset Summary"))
            assertTrue(text.contains("a") && text.contains("b") && text.contains("c"))
        }
    }

    @Test
    fun `cancelling a batch resolves without deadlock`(): Unit = runBlocking {
        val session = DistributionModelingSession()
        val handle = session.submit(goodBatch())
        launch { handle.cancel("test") }
        val result = withTimeout(20_000) { handle.result.await() }
        assertTrue(result is FitResult.Cancelled || result is FitResult.BatchCompleted)
        if (result is FitResult.Cancelled) assertEquals("test", result.reason)
        session.close()
    }
}
