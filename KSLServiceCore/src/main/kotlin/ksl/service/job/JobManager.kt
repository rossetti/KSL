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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** Thrown by [JobManager.register] when the concurrent-job limit is reached. */
class JobAtCapacityException(val limit: Int) :
    RuntimeException("Job manager at capacity ($limit concurrent jobs)")

/** Coarse, capability-agnostic lifecycle state the manager tracks. */
enum class JobStatus { RUNNING, TERMINAL }

/** A snapshot of one tracked job for listings and status queries. */
data class JobRecord(
    val jobId: String,
    val status: JobStatus,
    val submittedAt: Instant,
    val terminatedAt: Instant?,
)

/**
 * Owns the run lifecycle for any submit-and-stream capability: it registers a
 * job ([JobHandleView]), enforces a concurrency limit, journals the job's
 * events for unbounded replay (gap #6, §2.2), exposes status/result/cancel by
 * id, and evicts terminated jobs on a TTL.
 *
 * Generic over the spine's `<E, R>`, so the *same implementation* serves model
 * runs (`JobManager<RunEvent, RunResult>`) and distribution fits
 * (`JobManager<FitEvent, FitResult>`) — one lifecycle, every job-shaped
 * capability. (A global cross-capability concurrency cap, if ever needed, can
 * be layered by injecting a shared semaphore; the local single-user posture
 * does not require it.)
 *
 * The manager launches its per-job collector and result-waiter coroutines in
 * the supplied [scope]; the host owns that scope's lifetime.
 */
class JobManager<E, R>(
    private val scope: CoroutineScope,
    private val maxConcurrent: Int = Runtime.getRuntime().availableProcessors(),
    private val retention: Duration = 30.minutes,
) {

    private class Entry<E, R>(
        val handle: JobHandleView<E, R>,
        val journal: EventJournal<E>,
        val submittedAt: Instant,
        @Volatile var terminatedAt: Instant? = null,
    )

    private val entries = ConcurrentHashMap<String, Entry<E, R>>()

    /**
     * Submits and begins tracking a job. [submit] is invoked only after the
     * capacity check passes, so a rejected submission never starts work.
     *
     * @throws JobAtCapacityException if [maxConcurrent] jobs are already running
     */
    fun register(submit: () -> JobHandleView<E, R>): JobRecord {
        val running = entries.values.count { it.terminatedAt == null }
        if (running >= maxConcurrent) throw JobAtCapacityException(maxConcurrent)

        val handle = submit()
        val journal = EventJournal<E>()
        val entry = Entry(handle, journal, Clock.System.now())
        entries[handle.jobId] = entry

        // Collect the job's events into the journal, live and unbounded.
        val collector = scope.launch { handle.events.collect { journal.append(it) } }

        // When the job terminates, recover any tail events the live collector
        // had not yet appended (the bounded replayCache always holds the most
        // recent events, including the terminal one), then close the journal.
        scope.launch {
            handle.result.await()
            val cache = handle.events.replayCache
            for (k in journal.size until cache.size) journal.append(cache[k])
            collector.cancel()
            journal.complete()
            entry.terminatedAt = Clock.System.now()
            scope.launch {
                delay(retention)
                entries.remove(handle.jobId)
            }
        }

        return entry.toRecord()
    }

    /** Replayable event stream for [jobId] from [fromOffset], or null if unknown. */
    fun events(jobId: String, fromOffset: Int = 0): Flow<E>? =
        entries[jobId]?.journal?.stream(fromOffset)

    /** A non-blocking snapshot of [jobId]'s journaled events from [fromOffset]
     *  (everything available now), or null if the job is unknown. The polling
     *  counterpart to [events] for request/response transports. */
    fun eventsNow(jobId: String, fromOffset: Int = 0): List<E>? =
        entries[jobId]?.journal?.available(fromOffset)

    /** Awaits the terminal result of [jobId], or null if unknown. Never throws
     *  on the result itself — the capability's result types resolve normally. */
    suspend fun result(jobId: String): R? = entries[jobId]?.handle?.result?.await()

    /** Requests cancellation of [jobId]; no-op if unknown. */
    fun cancel(jobId: String, reason: String = "Cancelled by user") {
        entries[jobId]?.handle?.cancel(reason)
    }

    /** Coarse status of [jobId], or null if unknown / evicted. */
    fun status(jobId: String): JobStatus? = entries[jobId]?.let {
        if (it.terminatedAt == null) JobStatus.RUNNING else JobStatus.TERMINAL
    }

    /** All currently tracked jobs (terminated ones until TTL eviction). */
    fun list(): List<JobRecord> = entries.values.map { it.toRecord() }

    private fun Entry<E, R>.toRecord() = JobRecord(
        jobId = handle.jobId,
        status = if (terminatedAt == null) JobStatus.RUNNING else JobStatus.TERMINAL,
        submittedAt = submittedAt,
        terminatedAt = terminatedAt,
    )
}
