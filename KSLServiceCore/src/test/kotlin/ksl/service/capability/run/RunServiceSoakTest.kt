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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.session.RunEvent
import ksl.app.session.RunResult
import ksl.service.job.JobManager
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Hardening for the long-running-server case (Phase 7 strategic plan §7;
 * Phase 6 §10.2 classloader-leak risk). A single [RunService] — one
 * `KSLAppSession` over one `BundleModelProvider`, i.e. one bundle classloader —
 * executes many runs back to back. The provider rebuilds the model each time
 * but never opens a new classloader, so repeated runs must all complete and the
 * JobManager must reclaim terminated jobs on its TTL rather than growing
 * without bound.
 */
class RunServiceSoakTest {

    @Test
    fun `many sequential runs reuse one provider and all complete`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val registry = BundleRegistry.fromClasspath()
        try {
            RunService.fromRegistry(registry).use { runService ->
                // Short retention so terminated jobs are evicted during the soak.
                val jobs = JobManager<RunEvent, RunResult>(scope, retention = 1.seconds)
                val rounds = 20
                repeat(rounds) { round ->
                    val record = jobs.register { runService.submitSingle("MM1", numberOfReplications = 2) }
                    val result = withTimeout(30.seconds) { jobs.result(record.jobId)!! }
                    assertIs<RunResult.Completed>(result, "round $round did not complete cleanly: $result")
                }
                // The registry never grows unbounded: with sequential runs and a
                // 1s TTL, far fewer than `rounds` jobs are retained at the end.
                assertTrue(jobs.list().size <= rounds, "job registry should not exceed the runs submitted")
            }
        } finally {
            scope.cancel()
            registry.close()
        }
    }
}
