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

package ksl.service.capability.moda

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import ksl.app.moda.MetricSpec
import ksl.app.moda.ModaDocument
import ksl.app.moda.ModaEvent
import ksl.app.moda.ModaRunResult
import ksl.app.moda.ModaSourceIfc
import ksl.app.moda.ModaSourceProviderIfc
import ksl.app.moda.ModaSourceReference
import ksl.app.moda.ModaSourceResolver
import ksl.app.moda.ModaStudyOutcome
import ksl.app.moda.ScoreTable
import ksl.app.moda.Severity
import ksl.service.job.JobAtCapacityException
import ksl.service.job.JobManager
import ksl.service.job.JobStatus
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 *  Tests that a decision study behaves like any other job the server runs.
 *
 *  The point of putting studies on the shared spine rather than giving them a scheme of their own
 *  is that everything around running them — a limit on how many run at once, a journal of what
 *  happened, status, cancellation, retention — already exists and already works. These tests check
 *  that a study actually gets all of it, since a capability that quietly opts out of the spine
 *  looks fine until the day the server is busy.
 */
class ModaServiceTest {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val service = ModaService()

    @AfterTest
    fun cleanUp() {
        service.close()
        scope.cancel("test finished")
    }

    private fun document(name: String = "Siting"): ModaDocument = ModaDocument(
        name = name,
        metrics = listOf(
            MetricSpec("Cost", weight = 2.0, upperLimit = 1000.0),
            MetricSpec("Delay", weight = 1.0, upperLimit = 1000.0)
        ),
        alternatives = listOf("North", "South", "East"),
        source = ModaSourceReference.InlineScores(
            mapOf(
                "North" to mapOf("Cost" to 100.0, "Delay" to 900.0),
                "South" to mapOf("Cost" to 300.0, "Delay" to 500.0),
                "East" to mapOf("Cost" to 500.0, "Delay" to 100.0)
            )
        )
    )

    private fun manager(
        maxConcurrent: Int = 4,
        retention: kotlin.time.Duration = 30.minutes
    ) = JobManager<ModaEvent, ModaStudyOutcome>(scope, maxConcurrent, retention)

    // ------------------------------------------------------------------------------------------
    // Submitting through the spine
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study submitted as a job runs and comes back through the spine`() = runBlocking {
        val jobs = manager()
        val record = jobs.register { service.submit(document()) }
        val outcome = withTimeout(30_000) { jobs.result(record.jobId) }
        val finished = assertIs<ModaStudyOutcome.Finished>(outcome)
        val completed = assertIs<ModaRunResult.Completed>(finished.run)
        assertTrue(completed.snapshot.primaryRecommendation in completed.snapshot.alternatives)
        assertEquals(record.jobId, finished.studyId, "the job and the study disagree about the identifier")
    }

    @Test
    fun `a study's progress is journalled and can be replayed from any point`() = runBlocking {
        val jobs = manager()
        val record = jobs.register { service.submit(document()) }
        withTimeout(30_000) { jobs.result(record.jobId) }

        val all = assertNotNull(jobs.eventsNow(record.jobId), "the job kept no journal")
        assertTrue(all.any { it is ModaEvent.Started }, "the journal has no start: $all")
        assertTrue(all.any { it is ModaEvent.Completed }, "the journal has no ending: $all")
        assertTrue(all.all { it.studyId == record.jobId }, "the journal mixed in another study's events")

        // Replaying from part way through gives the remainder, which is what lets a caller that
        // dropped its connection pick up where it left off rather than start again.
        val fromSecond = assertNotNull(jobs.eventsNow(record.jobId, fromOffset = 1))
        assertEquals(all.drop(1), fromSecond)
    }

    @Test
    fun `a study reports as running and then as finished`() = runBlocking {
        val jobs = manager()
        val record = jobs.register { service.submit(document()) }
        assertNotNull(jobs.status(record.jobId))
        withTimeout(30_000) { jobs.result(record.jobId) }
        // Terminating is recorded just after the result settles, so allow it to catch up.
        withTimeout(10_000) {
            while (jobs.status(record.jobId) != JobStatus.TERMINAL) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(JobStatus.TERMINAL, jobs.status(record.jobId))
        assertNotNull(jobs.list().first { it.jobId == record.jobId }.terminatedAt)
    }

    @Test
    fun `a study nobody submitted is not known to the spine`() = runBlocking {
        val jobs = manager()
        assertNull(jobs.status("moda-nonexistent"))
        assertNull(jobs.eventsNow("moda-nonexistent"))
        assertNull(jobs.result("moda-nonexistent"))
    }

    // ------------------------------------------------------------------------------------------
    // Capacity, cancellation, retention
    // ------------------------------------------------------------------------------------------

    /**
     *  A source that does not answer until it is told to, so a study can be held part way through.
     *
     *  Studies over data already in hand finish in well under a millisecond, so a test that submits
     *  one and then checks whether it is still running is really checking which of two things the
     *  machine did first. Holding the study open makes the questions about capacity and cancelling
     *  answerable rather than a matter of timing.
     */
    private class HeldSource : ModaSourceProviderIfc {
        private val released = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)

        fun release() = released.countDown()

        override fun create(parameters: Map<String, String>): ModaSourceIfc =
            ModaSourceIfc { alternatives, metrics ->
                entered.countDown()
                released.await()
                ScoreTable(
                    alternatives.associateWith { name -> metrics.associateWith { name.length * 100.0 } },
                    emptyList()
                )
            }
    }

    private fun heldDocument(name: String) = document(name).copy(
        source = ModaSourceReference.RegisteredProvider("held")
    )

    /**
     *  A server that accepted unlimited work would fall over under it. Studies queue under the same
     *  limit as every other kind of job rather than a limit of their own.
     */
    @Test
    fun `studies are held to the same limit on how many run at once`() {
        val held = HeldSource()
        val resolver = ModaSourceResolver(providers = mapOf("held" to held))
        val jobs = JobManager<ModaEvent, ModaStudyOutcome>(scope, maxConcurrent = 1, retention = 30.minutes)
        try {
            val running = jobs.register { service.submit(heldDocument("Holding"), resolver) }
            // Wait until it really is in flight rather than merely submitted.
            assertTrue(held.entered.await(30, java.util.concurrent.TimeUnit.SECONDS), "the study never started")
            assertEquals(JobStatus.RUNNING, jobs.status(running.jobId))

            val error = assertFailsWith<JobAtCapacityException> {
                jobs.register { service.submit(heldDocument("Rejected"), resolver) }
            }
            assertEquals(1, error.limit, "the refusal reports the wrong limit")

            held.release()
            runBlocking { withTimeout(30_000) { jobs.result(running.jobId) } }

            // With the place free again, another study is taken.
            val next = jobs.register { service.submit(document("After")) }
            assertNotNull(runBlocking { withTimeout(30_000) { jobs.result(next.jobId) } })
        } finally {
            held.release()
        }
    }

    /**
     *  Cancelling a study that is genuinely part way through, rather than one that may already have
     *  finished, is the case that matters: it has to settle rather than leave a caller waiting.
     */
    @Test
    fun `a study in flight can be stopped and settles`() {
        val held = HeldSource()
        val resolver = ModaSourceResolver(providers = mapOf("held" to held))
        val jobs = manager()
        try {
            val record = jobs.register { service.submit(heldDocument("Long"), resolver) }
            assertTrue(held.entered.await(30, java.util.concurrent.TimeUnit.SECONDS), "the study never started")

            jobs.cancel(record.jobId, "no longer needed")
            val outcome = runBlocking { withTimeout(30_000) { jobs.result(record.jobId) } }
            val cancelled = assertIs<ModaStudyOutcome.Cancelled>(
                outcome, "a study stopped mid-flight should report as cancelled: $outcome"
            )
            assertEquals("no longer needed", cancelled.reason)
            assertEquals(record.jobId, cancelled.studyId)
        } finally {
            held.release()
        }
    }

    @Test
    fun `the limit is reported with the refusal so a caller knows what it hit`() {
        val full = JobManager<ModaEvent, ModaStudyOutcome>(scope, maxConcurrent = 0, retention = 30.minutes)
        val error = assertFailsWith<JobAtCapacityException> {
            full.register { service.submit(document()) }
        }
        assertEquals(0, error.limit)
        assertTrue(error.message!!.contains("capacity"), "the refusal does not say what happened")
    }

    @Test
    fun `a study can be stopped through the spine`() = runBlocking {
        val jobs = manager()
        val record = jobs.register { service.submit(document()) }
        jobs.cancel(record.jobId, "no longer needed")
        val outcome = withTimeout(30_000) { jobs.result(record.jobId) }
        assertTrue(
            outcome is ModaStudyOutcome.Cancelled || outcome is ModaStudyOutcome.Finished,
            "a cancelled study was left unsettled: $outcome"
        )
        // However it landed, it stays landed.
        assertEquals(outcome, withTimeout(30_000) { jobs.result(record.jobId) })
    }

    @Test
    fun `a finished study is forgotten once it is no longer worth keeping`() = runBlocking {
        val jobs = JobManager<ModaEvent, ModaStudyOutcome>(
            scope, maxConcurrent = 4, retention = kotlin.time.Duration.ZERO
        )
        val record = jobs.register { service.submit(document()) }
        withTimeout(30_000) { jobs.result(record.jobId) }
        withTimeout(10_000) {
            while (jobs.status(record.jobId) != null) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertNull(jobs.status(record.jobId), "a study was kept past its retention")
        assertNull(jobs.eventsNow(record.jobId))
    }

    // ------------------------------------------------------------------------------------------
    // Studies at the same time
    // ------------------------------------------------------------------------------------------

    /**
     *  Each study builds its own model inside its own run, so several going through the spine at
     *  once must each come back with its own answer and its own journal.
     */
    @Test
    fun `studies running through the spine at once stay separate`() = runBlocking {
        val jobs = JobManager<ModaEvent, ModaStudyOutcome>(scope, maxConcurrent = 16, retention = 30.minutes)
        val names = (1..12).map { "Study $it" }
        val records = names.map { name -> name to jobs.register { service.submit(document(name)) } }

        for ((name, record) in records) {
            val outcome = withTimeout(30_000) { jobs.result(record.jobId) }
            val completed = assertIs<ModaRunResult.Completed>(
                assertIs<ModaStudyOutcome.Finished>(outcome).run
            )
            assertEquals(name, completed.snapshot.name, "a job came back as the wrong study")
            val journal = assertNotNull(jobs.eventsNow(record.jobId))
            assertTrue(
                journal.all { it.studyId == record.jobId },
                "a journal mixed in another study's events"
            )
        }
        assertEquals(records.size, records.map { it.second.jobId }.toSet().size, "job identifiers repeated")
    }

    // ------------------------------------------------------------------------------------------
    // Checking without running
    // ------------------------------------------------------------------------------------------

    @Test
    fun `a study can be checked without being run`() {
        val issues = service.check(document())
        assertTrue(issues.none { it.severity == Severity.ERROR }, "a sound study was faulted: $issues")

        val broken = document().copy(
            metrics = listOf(MetricSpec("Cost", valueFunctionId = "nope", upperLimit = 100.0))
        )
        val problems = service.check(broken)
        assertTrue(problems.any { it.severity == Severity.ERROR }, "a broken study was passed")
        assertTrue(problems.any { it.message.contains("nope") }, "the problem does not name the cause")
    }

    @Test
    fun `a closed service refuses further studies rather than breaking`() = runBlocking {
        val closing = ModaService()
        closing.close()
        val handle = closing.submit(document())
        val outcome = withTimeout(30_000) { handle.result.await() }
        val failed = assertIs<ModaStudyOutcome.Failed>(outcome, "a closed service should refuse: $outcome")
        assertTrue(failed.message.contains("closed"))
    }
}
