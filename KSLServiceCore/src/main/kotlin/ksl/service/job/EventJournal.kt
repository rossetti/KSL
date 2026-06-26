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

package ksl.service.job

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/**
 * An append-only, replayable log of one job's progress events.
 *
 * This is the mitigation for gap #6 in the strategic plan (§2.2): the
 * substrate's `RunHandle.events` / `FitHandle.events` are hot `SharedFlow`s
 * with a *bounded* replay (128), so a client that subscribes late — a
 * reconnecting SSE consumer, an agent that polls after a fast run already
 * finished — can miss early events. The `JobManager` collects each job's events
 * into a journal *once*, live and unbounded; any number of consumers then
 * [stream] from any offset, with full replay, regardless of when they arrive.
 *
 * A journal is single-writer (the manager's per-job collector) and
 * many-reader. [append] / [complete] are called only from the manager's own
 * coroutines; [stream] is safe to collect concurrently from many consumers.
 */
class EventJournal<E> {

    private val lock = Any()
    private val recorded = ArrayList<E>()
    @Volatile private var done = false

    /** Bumped on every [append] and on [complete] so [stream] wakes promptly.
     *  The absolute value is meaningless — only that it changes. */
    private val version = MutableStateFlow(0L)

    /** Number of events recorded so far. */
    val size: Int get() = synchronized(lock) { recorded.size }

    /** True once no further events will be appended. */
    val isComplete: Boolean get() = done

    /** Records one event. Called by the manager's per-job collector. */
    fun append(event: E) {
        synchronized(lock) {
            recorded.add(event)
            version.value++
        }
    }

    /** Marks the journal closed; [stream] consumers complete once drained. */
    fun complete() {
        synchronized(lock) {
            if (!done) {
                done = true
                version.value++
            }
        }
    }

    /**
     * A non-blocking snapshot of the events recorded from [fromOffset] onward —
     * everything available *now*, without waiting for more. This is the polling
     * counterpart to [stream], used by request/response transports (e.g. an MCP
     * `get_run_events` tool) where each call returns the currently-journaled
     * tail; the caller advances its own offset and polls again. Replay from any
     * offset is always available because the journal retains every event.
     */
    fun available(fromOffset: Int = 0): List<E> = synchronized(lock) {
        val start = fromOffset.coerceIn(0, recorded.size)
        if (start >= recorded.size) emptyList() else ArrayList(recorded.subList(start, recorded.size))
    }

    /**
     * A cold flow that replays recorded events from [fromOffset], then delivers
     * subsequent events live, completing once the journal is [complete] and the
     * consumer has drained every recorded event.
     */
    fun stream(fromOffset: Int = 0): Flow<E> = flow {
        var i = fromOffset.coerceAtLeast(0)
        while (true) {
            val seen = version.value
            val batch = synchronized(lock) {
                if (i < recorded.size) ArrayList(recorded.subList(i, recorded.size)) else ArrayList()
            }
            for (e in batch) {
                emit(e)
                i++
            }
            if (done && i >= size) break
            // Wait for the next append or completion. StateFlow is conflated and
            // always holds the latest value, so a change that raced ahead of this
            // call is observed immediately — no missed wake-up.
            version.first { it != seen }
        }
    }
}
