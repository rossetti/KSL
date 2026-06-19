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

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Exercises the generic [JobManager] + [EventJournal] against a controllable
 * fake job. The headline guarantee is gap #6: a consumer that subscribes *after*
 * the job has finished still replays every event from offset 0 — the bounded
 * `SharedFlow` replay no longer governs delivery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JobManagerTest {

    /** A directly-driven [JobHandleView]; the spine is an interface, so no real
     *  session is needed to test the manager. */
    private class FakeJob<E, R>(override val jobId: String) : JobHandleView<E, R> {
        private val _events = MutableSharedFlow<E>(replay = 64, extraBufferCapacity = 64)
        override val events: SharedFlow<E> = _events
        private val _result = CompletableDeferred<R>()
        override val result: Deferred<R> = _result
        var cancelledWith: String? = null
        override fun cancel(reason: String) { cancelledWith = reason }
        suspend fun emit(event: E) = _events.emit(event)
        fun finish(result: R) = _result.complete(result)
    }

    @Test
    fun `late subscriber replays the full journal from offset zero`() = runTest {
        val mgr = JobManager<String, String>(backgroundScope, maxConcurrent = 4)
        val job = FakeJob<String, String>("j1")
        mgr.register { job }

        listOf("a", "b", "c").forEach { job.emit(it) }
        advanceUntilIdle()
        job.finish("done")
        advanceUntilIdle()

        // Subscribe only *after* completion — still get everything.
        assertEquals(listOf("a", "b", "c"), mgr.events("j1", 0)!!.toList())
        assertEquals(listOf("b", "c"), mgr.events("j1", 1)!!.toList())
        assertEquals("done", mgr.result("j1"))
        assertEquals(JobStatus.TERMINAL, mgr.status("j1"))
    }

    @Test
    fun `concurrency limit rejects submissions over capacity`() = runTest {
        val mgr = JobManager<String, String>(backgroundScope, maxConcurrent = 1)
        mgr.register { FakeJob("a") }   // running, never finished -> holds the one slot

        assertFailsWith<JobAtCapacityException> { mgr.register { FakeJob("b") } }
    }

    @Test
    fun `cancel routes through to the underlying handle`() = runTest {
        val mgr = JobManager<String, String>(backgroundScope, maxConcurrent = 4)
        val job = FakeJob<String, String>("j1")
        mgr.register { job }

        mgr.cancel("j1", "stop it")
        assertEquals("stop it", job.cancelledWith)
    }

    @Test
    fun `unknown job ids return null, not errors`() = runTest {
        val mgr = JobManager<String, String>(backgroundScope)
        assertNull(mgr.status("nope"))
        assertNull(mgr.events("nope"))
        assertNull(mgr.result("nope"))
    }
}
