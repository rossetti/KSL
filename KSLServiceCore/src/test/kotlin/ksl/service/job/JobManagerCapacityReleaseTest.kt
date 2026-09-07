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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

/**
 *  A finished job must stop occupying a concurrency slot, and it must do so
 *  without waiting on the manager's own termination coroutine to be scheduled.
 *
 *  These tests run on a real dispatcher on purpose. The sibling
 *  [JobManagerTest] runs under `runTest`, whose virtual scheduler plus
 *  `advanceUntilIdle()` serialises the two parties awaiting a job's result, and
 *  its capacity test never re-registers after a completion — so that class is
 *  structurally blind to this defect and should not be mistaken for coverage of
 *  it.
 *
 *  The defect these guard against: capacity used to be counted from
 *  `terminatedAt`, which the manager publishes last, after draining and closing
 *  the journal. A caller awaiting the job's result awaits the very same
 *  deferred, nothing orders the two, and when the caller won it saw a finished
 *  job still counted as running and its next registration refused.
 */
class JobManagerCapacityReleaseTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    /**
     *  The caller completes the deferred on its own thread, so the manager's
     *  continuation has to be dispatched while this thread walks straight into
     *  an already-complete await. That is the losing schedule for the manager,
     *  and it is what made the old capacity read fail here every time.
     */
    @Test
    @DisplayName("a slot is free as soon as the result settles, however the completion was scheduled")
    fun slotIsFreeOnceTheResultSettles() {
        val attempts = 200
        var refusals = 0
        repeat(attempts) { i ->
            val manager = JobManager<String, String>(scope, maxConcurrent = 1, retention = 30.minutes)
            val first = FakeJob<String, String>("a$i")
            manager.register { first }
            first.finish("done")
            runBlocking { withTimeout(30_000) { manager.result("a$i") } }
            try {
                manager.register { FakeJob<String, String>("b$i") }
            } catch (e: JobAtCapacityException) {
                refusals++
            }
        }
        assertEquals(0, refusals, "$refusals of $attempts registrations were refused after result() returned")
    }

    /**
     *  The slot and the journal answer different questions, and the point of the
     *  fix is that they are now allowed to answer at different times. Freeing the
     *  slot early must not weaken what TERMINAL promises.
     */
    @Test
    @DisplayName("freeing the slot early does not make status read TERMINAL early")
    fun statusStillTrailsTheResult() {
        val manager = JobManager<String, String>(scope, maxConcurrent = 1, retention = 30.minutes)
        val first = FakeJob<String, String>("a")
        manager.register { first }
        first.finish("done")
        runBlocking { withTimeout(30_000) { manager.result("a") } }

        // The slot is free: this is the registration the defect used to refuse.
        manager.register { FakeJob<String, String>("b") }

        // And TERMINAL still means what its documentation says, arriving only
        // once the manager's own bookkeeping has caught up.
        runBlocking {
            withTimeout(30_000) {
                while (manager.status("a") != JobStatus.TERMINAL) {
                    delay(1)
                }
            }
        }
        assertEquals(JobStatus.TERMINAL, manager.status("a"), "the finished job never reached TERMINAL")
        assertEquals(JobStatus.RUNNING, manager.status("b"), "the newly accepted job should be running")
    }
}
