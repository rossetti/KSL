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

package ksl.service.usage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * One recorded tool invocation. The first six fields are always present; the rest are optional, filled
 * selectively by each capability's recording wrapper (U2), and gated by the study's [UsageLevel] — the
 * free-text fields (`query`, `paramsDigest`, `errorSummary`) are written only at [UsageLevel.FULL]. All
 * additions are nullable so existing `usage.jsonl` lines decode unchanged. Never arguments or results
 * beyond what a field explicitly captures; nothing is transmitted off the machine.
 */
@Serializable
data class UsageEvent(
    val tool: String,
    val capability: String,
    val timestampMillis: Long,
    val durationMs: Long,
    val ok: Boolean,
    val client: String? = null,          // coarse agent id (claude-desktop / codex) — populated in U2
    val sessionId: String? = null,       // per-connection id grouping a working session — U2
    val errorClass: String? = null,      // NOT_FOUND | INVALID_INPUT | TIMEOUT | UNAVAILABLE | INTERNAL
    val errorSummary: String? = null,    // short reason (FULL only)
    val target: String? = null,          // the call's subject: section id / class fqn / model name — U2
    val resultCount: Int? = null,        // hits (search) or 1/0 found (fetch) — U2
    val topScore: Double? = null,        // top relevance score of a search — U2
    val query: String? = null,           // raw search terms (FULL only) — U2
    val paramsDigest: String? = null,    // compact, PII-free run config (FULL only) — U2
    val intent: String? = null,          // the student's originating question, if the assistant passes it (FULL only) — U4
)

/** An aggregate view of recorded usage for the console / export. */
@Serializable
data class UsageSummary(
    val total: Int,
    val ok: Int,
    val byTool: Map<String, Int>,
    val byCapability: Map<String, Int>,
    val lastActivityMillis: Long? = null,
) {
    /** Fraction of recorded calls that did not error (1.0 when there are none). */
    val successRate: Double get() = if (total == 0) 1.0 else ok.toDouble() / total
}

/**
 * Privacy/detail level for the usage study, applied centrally in [UsageStore] so lowering it provably
 * cannot leak text. `OFF` records nothing (the opt-out); `COUNTS` records everything except free text;
 * `FULL` records all fields including the raw query.
 */
enum class UsageLevel {
    OFF, COUNTS, FULL;

    companion object {
        /** Parse "off"/"counts"/"full" (case-insensitive); unknown ⇒ [FULL]. */
        fun fromString(s: String?): UsageLevel = when (s?.trim()?.lowercase()) {
            "off" -> OFF
            "counts" -> COUNTS
            else -> FULL
        }
    }
}

/**
 * The optional per-call detail a capability's recording wrapper contributes (U2). All nullable; the
 * wrapper fills what it knows and [UsageStore] applies the active [UsageLevel] before writing.
 */
data class UsageDetails(
    val client: String? = null,
    val sessionId: String? = null,
    val errorClass: String? = null,
    val errorSummary: String? = null,
    val target: String? = null,
    val resultCount: Int? = null,
    val topScore: Double? = null,
    val query: String? = null,
    val paramsDigest: String? = null,
    val intent: String? = null,
)

/**
 * Per-connection context threaded to each tool surface's registration (U2), so every recorded event of
 * one SSE session shares a [sessionId] (to reconstruct a student's workflow) and the connecting client.
 * The suite mints one per session in `buildAggregatedServer`. [client] is a supplier resolved at record
 * time (the client's name is known only after the MCP `initialize` handshake, which precedes any tool
 * call), kept as a plain `() -> String?` so this stays free of any MCP-SDK type.
 */
class ToolCallSession(val sessionId: String, val client: () -> String? = { null })

/** Maps a thrown error to a coarse [UsageEvent.errorClass] bucket; null for cancellation (not an error). */
object UsageErrors {
    fun classify(t: Throwable): String? = when (t) {
        is kotlin.coroutines.cancellation.CancellationException -> null
        is java.util.NoSuchElementException, is java.io.FileNotFoundException -> "NOT_FOUND"
        is IllegalArgumentException -> "INVALID_INPUT"
        is java.util.concurrent.TimeoutException -> "TIMEOUT"
        is java.io.IOException -> "UNAVAILABLE"
        else -> "INTERNAL"
    }
}

/**
 * Records one tool invocation. Implementations MUST be thread-safe — concurrent SSE sessions call tools
 * in parallel. The 4-arg form is the SAM; the 3-arg form is a convenience so existing call sites (which
 * pass no [UsageDetails]) keep compiling. [NONE] is the no-op used by the standalone servers and tests.
 */
fun interface ToolUsageRecorder {
    fun record(tool: String, durationMs: Long, ok: Boolean, details: UsageDetails?)

    fun record(tool: String, durationMs: Long, ok: Boolean): Unit = record(tool, durationMs, ok, null)

    companion object {
        val NONE: ToolUsageRecorder = ToolUsageRecorder { _, _, _, _ -> }
    }
}

/**
 * A LOCAL, append-only record of tool invocations for a usage study (which tools students actually use,
 * how often, how often they fail, and — per the active [UsageLevel] — what they searched for). One JSONL
 * file under the server workspace; nothing is transmitted. The [level] gates what is written: `OFF`
 * records nothing, `COUNTS` records everything except free text, `FULL` records all fields. Writes append
 * under a lock so concurrent sessions are safe; reads (recent / summary) use the in-memory current-run
 * view, and `all()` reads the durable file for export.
 */
class UsageStore(private val dir: Path, initialLevel: UsageLevel = UsageLevel.FULL) {

    /** The active detail level. Mutable (volatile) so the console can toggle the study live. */
    @Volatile
    var level: UsageLevel = initialLevel

    private val file: Path = dir.resolve(FILE)
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    // A bounded, current-run view kept in memory, so the console reads O(1) from here and never
    // re-scans the growing file — and nothing accumulates unbounded in RAM (the ring is capped). The
    // file (record / all) remains the durable, all-time study log.
    private val ring = ArrayDeque<UsageEvent>()
    private var runTotal = 0
    private var runOk = 0
    private val runByTool = LinkedHashMap<String, Int>()
    private val runByCapability = LinkedHashMap<String, Int>()
    private var runLastActivityMillis: Long? = null

    /**
     * Append one event to the durable log and the current-run view, applying the active [level]: `OFF`
     * records nothing; `COUNTS` drops the free-text fields (`query`/`paramsDigest`/`errorSummary`) before
     * anything is written or counted, so a lower level provably cannot leak text. Best-effort: a write
     * failure never propagates into a tool call.
     */
    fun record(event: UsageEvent) {
        val lvl = level
        if (lvl == UsageLevel.OFF) return
        val e = if (lvl == UsageLevel.COUNTS) {
            event.copy(query = null, paramsDigest = null, errorSummary = null, intent = null)
        } else {
            event
        }
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dir)
                Files.writeString(
                    file,
                    json.encodeToString(UsageEvent.serializer(), e) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
            ring.addLast(e)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
            runTotal++
            if (e.ok) runOk++
            runByTool.merge(e.tool, 1, Int::plus)
            runByCapability.merge(e.capability, 1, Int::plus)
            runLastActivityMillis = e.timestampMillis
        }
    }

    /**
     * A recorder scoped to one capability id (e.g. "sim"), stamping the wall-clock time at record.
     * Hand this to a tool surface's registration so every call it serves is recorded uniformly.
     */
    fun recorderFor(capability: String): ToolUsageRecorder =
        ToolUsageRecorder { tool, durationMs, ok, details ->
            record(
                UsageEvent(
                    tool = tool, capability = capability, timestampMillis = System.currentTimeMillis(),
                    durationMs = durationMs, ok = ok,
                    client = details?.client, sessionId = details?.sessionId,
                    errorClass = details?.errorClass, errorSummary = details?.errorSummary,
                    target = details?.target, resultCount = details?.resultCount, topScore = details?.topScore,
                    query = details?.query, paramsDigest = details?.paramsDigest, intent = details?.intent,
                ),
            )
        }

    /** ALL events ever recorded (all-time), read from the durable file — the CSV-export / study path. */
    fun all(): List<UsageEvent> = synchronized(lock) {
        if (!Files.isRegularFile(file)) return emptyList()
        runCatching {
            Files.readAllLines(file)
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString(UsageEvent.serializer(), it) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    /** The most recent [limit] events of THIS run, newest first (from the bounded in-memory ring). */
    fun recent(limit: Int): List<UsageEvent> = synchronized(lock) { ring.toList() }.takeLast(limit).asReversed()

    /** Aggregate counts for THIS run, from in-memory counters (O(1); no file scan). */
    fun summary(): UsageSummary = synchronized(lock) {
        UsageSummary(
            total = runTotal,
            ok = runOk,
            byTool = LinkedHashMap(runByTool),
            byCapability = LinkedHashMap(runByCapability),
            lastActivityMillis = runLastActivityMillis,
        )
    }

    companion object {
        const val FILE: String = "usage.jsonl"

        /** Cap on the in-memory current-run ring — the console shows the last ~10; this is headroom. */
        const val RING_CAPACITY: Int = 256
    }
}
