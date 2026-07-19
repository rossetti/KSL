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

/** One recorded tool invocation. `client` is a coarse agent id when known (no arguments, no PII). */
@Serializable
data class UsageEvent(
    val tool: String,
    val capability: String,
    val timestampMillis: Long,
    val durationMs: Long,
    val ok: Boolean,
    val client: String? = null,
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
 * Records one tool invocation. Implementations MUST be thread-safe — concurrent SSE sessions call
 * tools in parallel. [NONE] is the no-op used by the standalone servers and by tests.
 */
fun interface ToolUsageRecorder {
    fun record(tool: String, durationMs: Long, ok: Boolean)

    companion object {
        val NONE: ToolUsageRecorder = ToolUsageRecorder { _, _, _ -> }
    }
}

/**
 * A LOCAL, append-only record of tool invocations for a usage study (which tools students actually
 * use, how often, and how often they fail). One JSONL file under the server workspace; nothing is
 * transmitted, and only the tool name, capability, timing, ok/error, and a coarse client id are
 * stored — never arguments or results. Writes append under a lock so concurrent sessions are safe;
 * reads (recent / summary) scan the file.
 */
class UsageStore(private val dir: Path) {

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

    /** Append one event to the durable log and the current-run view (best-effort: a write failure never propagates into a tool call). */
    fun record(event: UsageEvent) {
        synchronized(lock) {
            runCatching {
                Files.createDirectories(dir)
                Files.writeString(
                    file,
                    json.encodeToString(UsageEvent.serializer(), event) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                )
            }
            ring.addLast(event)
            while (ring.size > RING_CAPACITY) ring.removeFirst()
            runTotal++
            if (event.ok) runOk++
            runByTool.merge(event.tool, 1, Int::plus)
            runByCapability.merge(event.capability, 1, Int::plus)
            runLastActivityMillis = event.timestampMillis
        }
    }

    /**
     * A recorder scoped to one capability id (e.g. "sim"), stamping the wall-clock time at record.
     * Hand this to a tool surface's registration so every call it serves is recorded uniformly.
     */
    fun recorderFor(capability: String): ToolUsageRecorder =
        ToolUsageRecorder { tool, durationMs, ok ->
            record(UsageEvent(tool, capability, System.currentTimeMillis(), durationMs, ok))
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
