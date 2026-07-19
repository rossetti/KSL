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

import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import ksl.app.config.RunConfiguration
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.capability.run.dto.mapping.toDto
import ksl.service.capability.run.dto.mapping.withArtifacts
import ksl.service.job.JobAtCapacityException
import ksl.service.job.JobManager
import ksl.service.job.JobStatus
import ksl.service.store.ArtifactStore
import ksl.service.store.CachedResult
import ksl.service.store.ResultKind
import ksl.service.store.ResultStore
import ksl.service.store.StoredResult
import java.util.concurrent.ConcurrentHashMap

/** The outcome of an async run submission. */
sealed interface RunSubmitOutcome {
    /** An identical run was already retained; its content key is the resultId. */
    data class AlreadyCached(val resultId: String) : RunSubmitOutcome

    /** A run was started; poll [RunApplicationService.getRunResult] with [jobId]. */
    data class Started(val jobId: String, val resultId: String, val reusedReplications: Int?) : RunSubmitOutcome

    /** The job manager is at its concurrency [limit]. */
    data class AtCapacity(val limit: Int) : RunSubmitOutcome

    /** The run could not be started (with a diagnostic [message]). */
    data class Failed(val message: String) : RunSubmitOutcome
}

/** The outcome of fetching an async run's result. */
sealed interface RunResultOutcome {
    /** Still in flight; [status] is the live job status name. */
    data class Running(val status: String) : RunResultOutcome

    /** Finished; the stored/combined result (its [CachedResult.fromCache] distinguishes live vs store). */
    data class Ready(val cached: CachedResult) : RunResultOutcome

    /** No such job and nothing stored under that id. */
    data object Unknown : RunResultOutcome

    /** The job finished but its result is unavailable. */
    data object Unavailable : RunResultOutcome

    /** An incremental base run was evicted before the top-up completed, so the full result cannot be assembled. */
    data object EvictedBase : RunResultOutcome
}

/** The outcome of a cancel request. */
sealed interface RunCancelOutcome {
    data object Cancelled : RunCancelOutcome
    data class NotRunning(val why: String) : RunCancelOutcome
}

/** A non-blocking snapshot of a run's journaled events. */
data class EventsSnapshot(
    val jobId: String,
    val fromOffset: Int,
    val nextOffset: Int,
    val status: String,
    val events: List<RunEvent>,
)

/**
 * The async run-lifecycle orchestrator: submit a run, poll its journaled events and terminal result,
 * and cancel it — with cache short-circuiting, incremental top-up (reuse a cached shorter run and run
 * only the missing replications), and store-on-completion (the first terminal fetch persists the
 * result under its content key and records it in the run-identity family). This is the machine that
 * was duplicated in each transport (the MCP `KslMcpTools` and the REST `KslRestService`); it now lives
 * once in the service layer and returns plain outcomes/DTOs — no transport (MCP/HTTP) types.
 *
 * It shares the [runService] and [runJobs] the transport already holds (they also back the blocking
 * run/experiment/optimization paths); this service owns only the async bookkeeping (the pending-run
 * map + store-on-completion + top-up planning).
 */
class RunApplicationService(
    private val registry: BundleRegistry,
    private val runService: RunService,
    private val runJobs: JobManager<RunEvent, RunResult>,
    private val resultStore: ResultStore,
    private val artifactStore: ArtifactStore,
    private val json: Json,
) {

    // jobId -> the content key / kind / request an async run will be stored under when it terminates
    // (store-on-completion). Bounded by the JobManager's own retention; cleared when the result is stored.
    private val pendingRuns = ConcurrentHashMap<String, PendingRun>()

    private class PendingRun(
        val resultId: String,
        val kind: ResultKind,
        val request: JsonElement,
        // Set for an eligible single-scenario run so the result is recorded in the run-identity family
        // on completion (a producer the incremental path reuses).
        val identity: String? = null,
        val replications: Int? = null,
        // Non-null when the registered job runs only the top-up: its result must be combined with the
        // cached shorter run before being served as the full result.
        val topUp: TopUp? = null,
    )

    private class TopUp(val cachedResultId: String, val reuseN: Int)

    /**
     * Start an async run for [config] (whose content [key] and canonical [request] the caller already
     * built). Returns [RunSubmitOutcome.AlreadyCached] on a cache hit, else registers the run (or the
     * incremental top-up) and returns [RunSubmitOutcome.Started].
     */
    fun submitRun(config: RunConfiguration, key: String, request: JsonElement, useCache: Boolean): RunSubmitOutcome {
        // Serve only a successful cached result. A retained Failed/Cancelled run (kept for diagnostics)
        // is treated as a miss so the identical request re-runs.
        if (useCache && resultStore.get(key)?.holdsServableResult() == true) {
            return RunSubmitOutcome.AlreadyCached(key)
        }

        // Incremental planning: if the rep count grew over a cached shorter run of the same identity,
        // run only the missing replications; the combine happens on result fetch.
        val m = IncrementalRunCache.replications(config)
        val identity = if (useCache && m != null && IncrementalRunCache.eligible(config)) {
            IncrementalRunCache.runIdentity(config, CacheVersion.forRun(registry, config))
        } else {
            null
        }
        val topUp = identity?.let { planTopUp(it, m!!) }
        val runConfig = if (topUp != null) IncrementalRunCache.topUpConfig(config, topUp.reuseN) else config

        val jobId = try {
            runJobs.register { runService.submitRunConfig(runConfig) }.jobId
        } catch (e: JobAtCapacityException) {
            return RunSubmitOutcome.AtCapacity(e.limit)
        } catch (e: Exception) {
            return RunSubmitOutcome.Failed(e.message ?: "unknown error")
        }
        pendingRuns[jobId] = PendingRun(key, ResultKind.RUN, request, identity, m, topUp)
        return RunSubmitOutcome.Started(jobId, key, topUp?.reuseN)
    }

    /** The live job status, or null if there is no live job with that id. */
    fun runStatus(jobId: String): JobStatus? = runJobs.status(jobId)

    /**
     * A non-blocking snapshot of a run's journaled events from [fromOffset], or null when the id is
     * unknown. A cached/content-key id (already completed, no live journal) yields an empty terminal
     * snapshot.
     */
    fun eventsSnapshot(jobId: String, fromOffset: Int): EventsSnapshot? {
        val from = fromOffset.coerceAtLeast(0)
        val events = runJobs.eventsNow(jobId, from)
            ?: return if (resultStore.get(jobId) != null) {
                EventsSnapshot(jobId, from, from, JobStatus.TERMINAL.name, emptyList())
            } else {
                null
            }
        return EventsSnapshot(jobId, from, from + events.size, runJobs.status(jobId)?.name ?: "UNKNOWN", events)
    }

    /**
     * Once the run has finished, its stored/combined result (store-on-completion on the first terminal
     * fetch); a [RunResultOutcome.Running] marker while still in flight. A cached/content-key [jobId]
     * resolves straight from the store.
     */
    suspend fun getRunResult(jobId: String): RunResultOutcome {
        val liveStatus = runJobs.status(jobId)
        if (liveStatus != null) {
            if (liveStatus != JobStatus.TERMINAL) return RunResultOutcome.Running(liveStatus.name)
            val result = runJobs.result(jobId) ?: return RunResultOutcome.Unavailable
            val cached = storeRun(jobId, result) ?: return RunResultOutcome.EvictedBase
            return RunResultOutcome.Ready(cached)
        }
        val stored = resultStore.get(jobId) ?: return RunResultOutcome.Unknown
        return RunResultOutcome.Ready(CachedResult(stored, fromCache = true))
    }

    /** Request cancellation; [RunCancelOutcome.NotRunning] (not an error) for an unknown/terminal job. */
    fun cancelRun(jobId: String, reason: String): RunCancelOutcome {
        val status = runJobs.status(jobId)
        if (status != JobStatus.RUNNING) {
            val why = if (status == JobStatus.TERMINAL) "already finished" else "unknown or evicted"
            return RunCancelOutcome.NotRunning(why)
        }
        runJobs.cancel(jobId, reason)
        return RunCancelOutcome.Cancelled
    }

    /** The largest cached shorter run (with sufficient stats) to extend, or null. */
    private fun planTopUp(identity: String, target: Int): TopUp? {
        val best = resultStore.familyMembers(identity).filterKeys { it < target }.maxByOrNull { it.key } ?: return null
        val dto = resultStore.get(best.value)?.payload
            ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
        val usable = dto is RunResultDto.Completed && dto.responses.all { it.sum != null && it.deviationSumOfSquares != null }
        return if (usable) TopUp(best.value, best.key) else null
    }

    /**
     * Store-on-completion for an async run (idempotent). For a plain run, stores the DTO and (when
     * eligible) records it in the run-identity family. For an incremental top-up, combines the job's
     * (M−N)-rep result with the cached N-rep run into the full M-rep result before storing. Returns
     * null only when the incremental base was evicted before the top-up finished.
     */
    private fun storeRun(jobId: String, result: RunResult): CachedResult? {
        val meta = pendingRuns.remove(jobId)
        val resultId = meta?.resultId ?: jobId
        // Idempotency guard, but only for an already-stored SUCCESS — a retained failure under this key
        // must be overwritten by this run's result (self-healing), not returned in its place.
        resultStore.get(resultId)?.takeIf { it.holdsServableResult() }
            ?.let { return CachedResult(it, fromCache = false) }

        val dto = result.toDto()
        val topUp = meta?.topUp
        if (topUp != null) {
            val cachedDto = resultStore.get(topUp.cachedResultId)?.payload
                ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
            if (cachedDto !is RunResultDto.Completed || dto !is RunResultDto.Completed) return null
            val stored = persistRun(resultId, meta.request, IncrementalCombine.completed(cachedDto, dto))
            indexFamily(meta, resultId)
            return CachedResult(stored, fromCache = false, reusedReplications = topUp.reuseN)
        }
        val stored = persistRun(resultId, meta?.request ?: JsonNull, dto)
        if (dto is RunResultDto.Completed) indexFamily(meta, resultId)
        return CachedResult(stored, fromCache = false)
    }

    private fun indexFamily(meta: PendingRun?, resultId: String) {
        val identity = meta?.identity ?: return
        val replications = meta.replications ?: return
        resultStore.indexFamily(identity, replications, resultId)
    }

    private fun persistRun(resultId: String, request: JsonElement, dto: RunResultDto): StoredResult {
        val enriched = dto.withArtifacts(artifactStore.list(resultId))
        val stored = StoredResult(
            resultId = resultId,
            kind = ResultKind.RUN,
            createdAt = Clock.System.now(),
            request = request,
            payload = json.encodeToJsonElement(RunResultDto.serializer(), enriched),
        )
        // Retain every outcome (successes and failures) for diagnostics; the read side
        // (holdsServableResult) refuses to serve a stored failure, so a retry re-runs.
        resultStore.put(stored)
        return stored
    }
}
