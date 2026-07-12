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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
import ksl.service.store.ResultKind
import ksl.service.store.ResultStore
import ksl.service.store.StoredResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Retain-and-mark, never serve: a run whose result is `Failed`/`Cancelled` is RETAINED in the store
 * for diagnostics (`get_result` / `list_results`), but must never be served as a cache hit — a retry
 * of the identical request re-runs, and a later success overwrites the failure. Successful results
 * cache and serve as before.
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

    private fun stored(dto: RunResultDto) = StoredResult(
        resultId = "x", kind = ResultKind.RUN, createdAt = Clock.System.now(),
        request = JsonNull, payload = json.encodeToJsonElement(RunResultDto.serializer(), dto),
    )

    private fun decode(r: CachedResult): RunResultDto =
        json.decodeFromJsonElement(RunResultDto.serializer(), r.stored.payload)

    @Test
    fun `a failed run is retained but never served on retry`() = runBlocking {
        val store = store()
        val cfg = config("FailMM1")
        val key = ResultKeys.forRunConfig(cfg, "")
        var calls = 0

        val first = IncrementalRunCache.run(store, json, cfg, useCache = true) {
            calls++; RunResultDto.Failed("ExecutiveError", "boom")
        }
        assertIs<RunResultDto.Failed>(decode(first))
        // Retained for diagnostics ...
        assertNotNull(store.get(key), "a Failed result must be retained in the store for review")
        assertFalse(store.get(key)!!.holdsServableResult(), "... but marked non-servable")

        // ... and never served: the identical request re-runs.
        val second = IncrementalRunCache.run(store, json, cfg, useCache = true) {
            calls++; RunResultDto.Failed("ExecutiveError", "boom")
        }
        assertEquals(2, calls, "the identical failed request must re-execute, not serve a cached failure")
        assertFalse(second.fromCache, "a re-run must not be reported as a cache hit")
    }

    @Test
    fun `a cancelled run is retained but never served`() = runBlocking {
        val store = store()
        val cfg = config("CancelMM1")
        val key = ResultKeys.forRunConfig(cfg, "")
        var calls = 0
        IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; RunResultDto.Cancelled("user") }
        assertNotNull(store.get(key), "a Cancelled result is retained")
        IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; RunResultDto.Cancelled("user") }
        assertEquals(2, calls, "a cancelled result must not be served on retry")
    }

    @Test
    fun `a successful run is cached and served on retry`() = runBlocking {
        val store = store()
        val cfg = config("OkMM1")
        var calls = 0
        IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; completed() }
        val second = IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; completed() }
        assertEquals(1, calls, "the identical successful request must be served from cache, not re-run")
        assertTrue(second.fromCache, "the second identical request must be a cache hit")
    }

    @Test
    fun `a success overwrites a prior retained failure and is then served`() = runBlocking {
        val store = store()
        val cfg = config("HealMM1")
        val key = ResultKeys.forRunConfig(cfg, "")
        var calls = 0

        // First attempt fails (retained, non-servable).
        IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; RunResultDto.Failed("E", "boom") }
        assertFalse(store.get(key)!!.holdsServableResult())

        // Retry succeeds: it re-runs (the failure is not served) and overwrites it with the success.
        val healed = IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; completed() }
        assertEquals(2, calls, "the retry must re-run since the failure was not served")
        assertFalse(healed.fromCache)
        assertTrue(store.get(key)!!.holdsServableResult(), "the success overwrites the retained failure")

        // Now identical requests are served from cache.
        val third = IncrementalRunCache.run(store, json, cfg, useCache = true) { calls++; completed() }
        assertEquals(2, calls, "the healed result is now a cache hit")
        assertTrue(third.fromCache)
    }

    @Test
    fun `holdsServableResult marks only failures non-servable`() {
        val now = Clock.System.now()
        val runSummary = RunSummaryDto("r", "M", "exp", 1, 1, "COMPLETED_ALL_STEPS", now, now)
        val orchSummary = OrchestratorSummaryDto("r", "orch", 1, 1, 0, now, now)

        assertTrue(stored(RunResultDto.Completed(runSummary, emptyList())).holdsServableResult())
        assertTrue(stored(RunResultDto.BatchCompleted(orchSummary, listOf(BatchItemDto("i", emptyList())))).holdsServableResult())
        assertTrue(
            stored(RunResultDto.OptimizationCompleted(orchSummary, SolutionDto(emptyMap(), 0.0, 0.0, isValid = true), emptyList()))
                .holdsServableResult(),
        )
        assertFalse(stored(RunResultDto.Failed("E", "boom")).holdsServableResult())
        assertFalse(stored(RunResultDto.Cancelled("user")).holdsServableResult())

        // A non-run payload (no run `type` discriminator, e.g. a fit report) is servable.
        val fitLike = StoredResult("f", ResultKind.FIT, now, JsonNull, buildJsonObject { put("datasetName", "svc") })
        assertTrue(fitLike.holdsServableResult(), "a non-run payload has no failure type and is servable")
    }
}
