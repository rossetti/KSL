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

package ksl.service.store

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** The capability that produced a stored result. */
@Serializable
enum class ResultKind { RUN, BATCH, OPTIMIZATION, FIT }

/** A retained, content-addressed result: the request that produced it and the
 *  serialized result payload (a `RunResultDto` / `FitResultData`). */
@Serializable
data class StoredResult(
    val resultId: String,
    val kind: ResultKind,
    val createdAt: Instant,
    val request: JsonElement,
    val payload: JsonElement,
)

/**
 * The outcome of a cache-aware run: the stored result, whether it was a full
 * cache hit ([fromCache]), and how many replications were reused from a cached
 * shorter run when this was an incremental top-up ([reusedReplications], 0 when
 * not incremental).
 */
data class CachedResult(
    val stored: StoredResult,
    val fromCache: Boolean,
    val reusedReplications: Int = 0,
)

/**
 * A persistent, content-addressed store of completed results — the keystone of
 * the result side (Phase 8 plan §4). It is **both** the cache (look up a result
 * by its content key before re-running) **and** the retention backbone for
 * fine-grained projection ([get] by id, used by Phase 8.5).
 *
 * Two tiers: Caffeine in memory (size-bounded LRU, the part worth not
 * reinventing) over a thin JSON-on-disk store under `~/.ksl/result-cache/`
 * (mirroring `BundleDescriptorCache`: inspectable, survives restart,
 * best-effort — an I/O error is swallowed, never breaking a request).
 */
class ResultStore(
    private val dir: Path = defaultDir(),
    maxMemoryBytes: Long = DEFAULT_MAX_MEMORY_BYTES,
    private val maxDiskEntries: Int = 500,
    private val maxDiskBytes: Long = DEFAULT_MAX_DISK_BYTES,
) {
    private val json = Json {
        encodeDefaults = true
        allowSpecialFloatingPointValues = true // ControlData bounds can be ±∞
    }
    // Bound the in-memory tier by payload *bytes*, not entry count: a few large
    // batch/experiment results (per-design-point snapshots) won't dominate RAM,
    // and a single result heavier than the budget is simply never memory-cached —
    // it stays on disk, still retrievable for projection (Phase 8 §9). The disk
    // tier is the durable store; the retention cap bounds its growth.
    private val memory: Cache<String, StoredResult> =
        Caffeine.newBuilder()
            .maximumWeight(maxMemoryBytes)
            .weigher<String, StoredResult> { _, value -> value.payload.toString().length }
            .build()
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    // Secondary index for incremental-replication caching: a "family" key (a run
    // identity = the document minus its replication count) -> the retained result
    // ids keyed by their replication count. Kept in its own subdirectory so the
    // retention sweep ignores it.
    private val familyDir = dir.resolve("_family")
    private val familyLock = Any()

    init {
        runCatching { Files.createDirectories(dir) }
    }

    /** Looks up a result by id (memory → disk → null), promoting a disk hit. */
    fun lookup(resultId: String): StoredResult? {
        memory.getIfPresent(resultId)?.let { return it }
        return readDisk(resultId)?.also { memory.put(resultId, it) }
    }

    /** Retrieves a retained result for projection (Phase 8.5). */
    fun get(resultId: String): StoredResult? = lookup(resultId)

    /** Stores a result in both tiers, then enforces the disk retention cap. */
    fun put(stored: StoredResult) {
        memory.put(stored.resultId, stored)
        writeDisk(stored)
        evictDiskIfNeeded()
    }

    /**
     * Runs [produce] only on a cache miss (or when [useCache] is false), storing
     * the result under [key]. On a hit, returns the stored result without
     * running — the "don't re-run an identical document" guarantee. Soundness
     * rests on [key] capturing everything that affects output (the normalized
     * document; see Phase 8 plan §7).
     */
    suspend fun cachedRun(
        key: String,
        kind: ResultKind,
        request: JsonElement,
        useCache: Boolean,
        produce: suspend () -> JsonElement,
    ): CachedResult {
        if (useCache) lookup(key)?.let { return CachedResult(it, fromCache = true) }
        val stored = StoredResult(
            resultId = key,
            kind = kind,
            createdAt = Clock.System.now(),
            request = request,
            payload = produce(),
        )
        put(stored)
        return CachedResult(stored, fromCache = false)
    }

    /**
     * Records that [resultId] is the retained result for run-identity [familyKey]
     * at replication count [size] — the index an incremental top-up consults to
     * find a shorter cached run to extend. Best-effort.
     */
    fun indexFamily(familyKey: String, size: Int, resultId: String) {
        synchronized(familyLock) {
            runCatching {
                Files.createDirectories(familyDir)
                val file = familyDir.resolve("$familyKey.json")
                val current = readFamily(file).toMutableMap()
                current[size.toString()] = resultId
                Files.writeString(file, json.encodeToString(mapSerializer, current))
            }
        }
    }

    /**
     * The `replicationCount -> resultId` members recorded for run-identity
     * [familyKey], filtered to those still retained (a member whose result was
     * evicted is dropped).
     */
    fun familyMembers(familyKey: String): Map<Int, String> {
        val raw = synchronized(familyLock) { readFamily(familyDir.resolve("$familyKey.json")) }
        return raw.mapNotNull { (size, id) ->
            val n = size.toIntOrNull() ?: return@mapNotNull null
            if (lookup(id) != null) n to id else null
        }.toMap()
    }

    private fun readFamily(file: Path): Map<String, String> = runCatching {
        if (!Files.exists(file)) emptyMap() else json.decodeFromString(mapSerializer, Files.readString(file))
    }.getOrDefault(emptyMap())

    private fun readDisk(resultId: String): StoredResult? = runCatching {
        val file = dir.resolve(resultId).resolve("result.json")
        if (!Files.exists(file)) null else json.decodeFromString(StoredResult.serializer(), Files.readString(file))
    }.getOrNull()

    private fun writeDisk(stored: StoredResult) {
        runCatching {
            val entry = dir.resolve(stored.resultId)
            Files.createDirectories(entry)
            Files.writeString(entry.resolve("result.json"), json.encodeToString(StoredResult.serializer(), stored))
        }
    }

    /**
     * Bounds disk growth (Phase 8 plan §9): evicts the oldest result directories
     * (by last-modified time) until BOTH the entry-count cap [maxDiskEntries] and
     * the total-byte cap [maxDiskBytes] hold, invalidating each from memory. A cap
     * of `0` disables that bound. When anything is evicted, orphaned `_family`
     * index files (no surviving member) are pruned too. Best-effort — an I/O error
     * never breaks a request.
     */
    private fun evictDiskIfNeeded() {
        if (maxDiskEntries <= 0 && maxDiskBytes <= 0) return
        runCatching {
            val entries = Files.list(dir).use { stream ->
                // Only real result entries (a dir with result.json); never the _family index.
                stream.filter { Files.isRegularFile(it.resolve("result.json")) }.toList()
            }
            // Oldest first, each paired with its on-disk size.
            val sized = entries
                .sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
                .map { it to dirBytes(it) }
            var count = sized.size
            var total = sized.sumOf { it.second }
            var evicted = false
            for ((entry, bytes) in sized) {
                val overCount = maxDiskEntries > 0 && count > maxDiskEntries
                val overBytes = maxDiskBytes > 0 && total > maxDiskBytes
                if (!overCount && !overBytes) break
                deleteRecursively(entry)
                memory.invalidate(entry.fileName.toString())
                count--
                total -= bytes
                evicted = true
            }
            if (evicted) pruneOrphanedFamilies()
        }
    }

    /** Total bytes of the regular files under [entry] (its `result.json` and any siblings). */
    private fun dirBytes(entry: Path): Long = runCatching {
        Files.walk(entry).use { walk ->
            walk.filter { Files.isRegularFile(it) }.toList()
        }.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
    }.getOrDefault(0L)

    /** Deletes `_family` index files that no longer reference any retained result. */
    private fun pruneOrphanedFamilies() {
        synchronized(familyLock) {
            runCatching {
                if (!Files.isDirectory(familyDir)) return
                val files = Files.list(familyDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }.toList()
                }
                for (file in files) {
                    val anyLive = readFamily(file).values.any {
                        Files.exists(dir.resolve(it).resolve("result.json"))
                    }
                    if (!anyLive) runCatching { Files.delete(file) }
                }
            }
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { runCatching { Files.delete(it) } }
        }
    }

    companion object {
        /** Default in-memory budget for retained result payloads: 64 MB. */
        const val DEFAULT_MAX_MEMORY_BYTES: Long = 64L * 1024 * 1024

        /** Default on-disk budget for the result cache: 128 MB. */
        const val DEFAULT_MAX_DISK_BYTES: Long = 128L * 1024 * 1024

        fun defaultDir(): Path =
            Path.of(System.getProperty("user.home")).resolve(".ksl").resolve("result-cache")

        /** Hex SHA-256 of [text] — the content key for a normalized document. */
        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
