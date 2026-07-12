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
import kotlinx.serialization.json.encodeToJsonElement
import ksl.app.config.RunConfiguration
import ksl.app.config.RunConfigurationJson
import ksl.service.capability.run.dto.RunResultDto
import ksl.service.store.CachedResult
import ksl.service.store.ResultKind
import ksl.service.store.ResultStore
import ksl.service.store.StoredResult

/**
 * Cache-aware execution of a single-scenario [RunConfiguration] that reuses a
 * cached *shorter* run when only the replication count grew — incremental-
 * replication caching (Option B). When the exact document is cached it is served
 * directly; otherwise, if a run with the same *identity* (the document minus its
 * replication count) but fewer replications is retained, it runs only the missing
 * `M − N` replications (resetting the streams and advancing `N` substreams so they
 * reproduce reps `N+1..M`) and combines via [IncrementalCombine]. The result is
 * exact — equal to a monolithic M-rep run (proven by `IncrementalEquivalenceTest`).
 *
 * Gated to the cases where that equivalence holds: a single scenario with an
 * explicit replication count, default sub-stream advancement, no antithetic
 * variates, and no pre-run stream advance in the original request.
 */
object IncrementalRunCache {

    /** The replication count an incremental decision keys on, or null if not explicit. */
    fun replications(config: RunConfiguration): Int? =
        config.scenarios.singleOrNull()?.runOverrides?.numberOfReplications

    /** Whether [config] is shaped so an incremental top-up is sound (see class KDoc). */
    fun eligible(config: RunConfiguration): Boolean {
        val overrides = config.scenarios.singleOrNull()?.runOverrides ?: return false
        if (overrides.numberOfReplications == null) return false
        if (overrides.antitheticOption == true) return false
        if (overrides.advanceNextSubStreamOption == false) return false
        if ((overrides.numberOfStreamAdvancesPriorToRunning ?: 0) != 0) return false
        return true
    }

    /** The run-identity key: the document with replication count (and any pre-advance) normalized out. */
    fun runIdentity(config: RunConfiguration, versionSalt: String = ""): String {
        val scenario = config.scenarios.single()
        val normalized = scenario.runOverrides!!.copy(
            numberOfReplications = null,
            numberOfStreamAdvancesPriorToRunning = null,
        )
        val doc = config.copy(scenarios = listOf(scenario.copy(runOverrides = normalized)))
        return ResultStore.sha256("$versionSalt|run-identity:" + RunConfigurationJson.encode(doc))
    }

    /** The document that runs only reps `cachedReps+1..M`, reproducing them via a stream advance. */
    fun topUpConfig(config: RunConfiguration, cachedReps: Int): RunConfiguration {
        val scenario = config.scenarios.single()
        val overrides = scenario.runOverrides!!
        val topUp = overrides.copy(
            numberOfReplications = overrides.numberOfReplications!! - cachedReps,
            resetStartStreamOption = true,
            advanceNextSubStreamOption = true,
            numberOfStreamAdvancesPriorToRunning = cachedReps,
        )
        return config.copy(scenarios = listOf(scenario.copy(runOverrides = topUp)))
    }

    /**
     * Runs [config] cache-aware: exact hit, incremental top-up over a cached
     * shorter run, or a full run — storing the result and recording it in the
     * run-identity family index. [produce] runs a (possibly modified) document
     * and returns its DTO.
     */
    suspend fun run(
        store: ResultStore,
        json: Json,
        config: RunConfiguration,
        useCache: Boolean,
        versionSalt: String = "",
        produce: suspend (RunConfiguration) -> RunResultDto,
    ): CachedResult {
        val exactKey = ResultKeys.forRunConfig(config, versionSalt)
        // A stored non-success (Failed/Cancelled) is retained for diagnostics but never served —
        // treat it as a miss so the identical request re-runs instead of returning a stale failure.
        if (useCache) store.get(exactKey)?.takeIf { it.holdsServableResult() }
            ?.let { return CachedResult(it, fromCache = true) }

        val target = replications(config)
        val identity = if (useCache && target != null && eligible(config)) runIdentity(config, versionSalt) else null

        if (identity != null && target != null) {
            val best = store.familyMembers(identity).filterKeys { it < target }.maxByOrNull { it.key }
            val cached = best?.let { store.get(it.value)?.payload }
                ?.let { runCatching { json.decodeFromJsonElement(RunResultDto.serializer(), it) }.getOrNull() }
            if (best != null && cached is RunResultDto.Completed && cached.responses.all { it.sum != null && it.deviationSumOfSquares != null }) {
                val topUp = produce(topUpConfig(config, best.key))
                if (topUp is RunResultDto.Completed) {
                    val stored = persist(store, json, exactKey, config, IncrementalCombine.completed(cached, topUp))
                    store.indexFamily(identity, target, exactKey)
                    return CachedResult(stored, fromCache = false, reusedReplications = best.key)
                }
            }
        }

        // Full run.
        val dto = produce(config)
        val stored = persist(store, json, exactKey, config, dto)
        if (identity != null && target != null && dto is RunResultDto.Completed) {
            store.indexFamily(identity, target, exactKey)
        }
        return CachedResult(stored, fromCache = false)
    }

    private fun persist(
        store: ResultStore,
        json: Json,
        key: String,
        config: RunConfiguration,
        dto: RunResultDto,
    ): StoredResult {
        val stored = StoredResult(
            resultId = key,
            kind = ResultKind.RUN,
            createdAt = Clock.System.now(),
            request = json.parseToJsonElement(RunConfigurationJson.encode(config)),
            payload = json.encodeToJsonElement(RunResultDto.serializer(), dto),
        )
        // Retain every outcome (successes and failures) for diagnostics; the read side
        // (holdsServableResult) refuses to serve a stored failure, so a retry re-runs.
        store.put(stored)
        return stored
    }
}
