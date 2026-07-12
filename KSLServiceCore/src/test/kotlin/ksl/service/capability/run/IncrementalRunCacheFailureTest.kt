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
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.app.config.RunConfiguration
import ksl.app.config.ScenarioSpec
import ksl.service.capability.run.dto.BatchItemDto
import ksl.service.capability.run.dto.OrchestratorSummaryDto
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.capability.run.dto.RunSummaryDto
import ksl.service.capability.run.dto.SolutionDto
import ksl.service.store.CachedResult
import ksl.service.store.ResultStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A run whose `produce` returns a non-success result (`Failed` / `Cancelled`) must NOT be cached:
 * a transient or since-fixed failure must not be frozen under the document key and re-served on a
 * retry of the identical request. Successful results still cache and serve as before.
 *
 * Regression for the live-observed behavior where a `run_config` scenario batch that failed with
 * `SQLITE_CANTOPEN` kept returning that cached failure after the server-side cause was fixed.
 */
class IncrementalRunCacheFailureTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun store() = ResultStore(Files.createTempDirectory("rc-fail"))

    private fun config(name: String) = RunConfiguration(
        scenarios = listOf(ScenarioSpec(name = name, modelReference = ModelReference.ByProviderId(name))),
        outputConfig = OutputConfig(reports = emptySet()),
    )

    private fun completed() = RunResultDto.Completed(
        summary = RunSummaryDto(
            runId = "r", modelIdentifier = "M", experimentName = "exp",
            requestedReplications = 1, completedReplications = 1, endingStatus = "COMPLETED_ALL_STEPS",
            beginTime = Clock.System.now(), endTime = Clock.System.now(),
        ),
        responses = emptyList(),
    )

    private fun decode(r: CachedResult): RunResultDto =
        json.decodeFromJsonElement(RunResultDto.serializer(), r.stored.payload)

    @Test
    fun `a failed run is not cached and re-runs on retry`() = runBlocking {
        val store = store()
        val cfg = config("FailMM1")
        val key = ResultKeys.forRunConfig(cfg, "")
        var calls = 0

        val first = IncrementalRunCache.run(store, json, cfg, useCache = true) {
            calls++; RunResultDto.Failed("ExecutiveError", "boom")
        }
        // The failure is returned to the caller, but never persisted.
        assertIs<RunResultDto.Failed>(decode(first))
        assertNull(store.get(key), "a Failed result must not be persisted in the cache")

        // Retry the identical request: the stale failure must NOT be served — produce runs again.
        val second = IncrementalRunCache.run(store, json, cfg, useCache = true) {
            calls++; RunResultDto.Failed("ExecutiveError", "boom")
        }
        assertEquals(2, calls, "the identical failed request must re-execute, not serve a cached failure")
        assertFalse(second.fromCache, "a re-run must not be reported as a cache hit")
    }

    @Test
    fun `a cancelled run is not cached`() = runBlocking {
        val store = store()
        val cfg = config("CancelMM1")
        val key = ResultKeys.forRunConfig(cfg, "")
        IncrementalRunCache.run(store, json, cfg, useCache = true) { RunResultDto.Cancelled("user") }
        assertNull(store.get(key), "a Cancelled result must not be persisted in the cache")
    }

    @Test
    fun `a successful run is cached and served on retry`() = runBlocking {
        val store = store()
        val cfg = config("OkMM1")
        val key = ResultKeys.forRunConfig(cfg, "")
        var calls = 0

        IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; completed() }
        assertNotNull(store.get(key), "a Completed result must be cached")

        val second = IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; completed() }
        assertEquals(1, calls, "the identical successful request must be served from cache, not re-run")
        assertTrue(second.fromCache, "the second identical request must be a cache hit")
    }

    @Test
    fun `isCacheable classifies every result variant`() {
        // The property both persist gates (sync IncrementalRunCache + async persistRun) rely on:
        // only the three successful terminal results are cacheable.
        val now = Clock.System.now()
        val runSummary = RunSummaryDto("r", "M", "exp", 1, 1, "COMPLETED_ALL_STEPS", now, now)
        val orchSummary = OrchestratorSummaryDto("r", "orch", 1, 1, 0, now, now)

        assertTrue(RunResultDto.Completed(runSummary, emptyList()).isCacheable)
        assertTrue(RunResultDto.BatchCompleted(orchSummary, listOf(BatchItemDto("item", emptyList()))).isCacheable)
        assertTrue(
            RunResultDto.OptimizationCompleted(orchSummary, SolutionDto(emptyMap(), 0.0, 0.0, isValid = true), emptyList()).isCacheable,
        )
        assertFalse(RunResultDto.Failed("ExecutiveError", "boom").isCacheable)
        assertFalse(RunResultDto.Cancelled("user").isCacheable)
    }
}
