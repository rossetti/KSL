package ksl.app.session

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.IterativeProcessIfc.EndingStatus
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Acceptance tests for Phase 1: ksl.app.session run driver and event bus.
 *
 * Model: GIGcQueue M/M/1 (arrivals ExponentialRV(1.0), service ExponentialRV(0.5)).
 */
class RunnerTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Builds a finite M/M/1 model ready to pass to Runner. */
    private fun mm1Model(name: String, reps: Int, repLength: Double): Model {
        val model = Model(name, autoCSVReports = false)
        model.numberOfReplications = reps
        model.lengthOfReplication = repLength
        GIGcQueue(model, numServers = 1, name = "MM1")
        return model
    }

    /**
     * Schedules a call to `model.endSimulation()` at [stopAtTime] so that a
     * replication with an infinite (or very long) length terminates cleanly.
     * Used in the infinite-horizon warning test to avoid a hanging simulation.
     */
    /**
     * Stops the current replication at [stopAtTime] by calling [stopReplication].
     *
     * [stopReplication] signals the Executive's inner event loop to halt the
     * current replication immediately.  It is the correct call when stopping
     * from within model code (event handlers, process steps).
     *
     * Do NOT use [Model.endSimulation] here.  [endSimulation] operates at the
     * experiment (outer) level and is only checked between replications by the
     * iterative process loop.  When called from inside an event handler while
     * the Executive is running, [endSimulation] has no effect on the current
     * replication and the simulation hangs indefinitely.
     */
    private class SimulationStopper(
        parent: ModelElement,
        private val stopAtTime: Double
    ) : ModelElement(parent, name = "Stopper") {
        override fun initialize() {
            schedule(::stop, stopAtTime)
        }
        private fun stop(e: KSLEvent<Nothing>) {
            stopReplication("terminated by SimulationStopper at simTime=$stopAtTime")
        }
    }

    /**
     * Collects [RunEvent]s from [handle] into a list, stopping after the first
     * terminal event ([RunEvent.RunCompleted], [RunEvent.RunCancelled], or
     * [RunEvent.RunFailed]) is added.
     */
    private suspend fun collectUntilTerminal(handle: RunHandle): List<RunEvent> {
        val events = mutableListOf<RunEvent>()
        handle.events
            .takeWhile { event ->
                events.add(event)
                event !is RunEvent.RunCompleted &&
                event !is RunEvent.RunCancelled &&
                event !is RunEvent.RunFailed
            }
            .collect {}
        return events
    }

    // ── Test 1: normal full run ───────────────────────────────────────────────

    /**
     * A 30-rep finite M/M/1 must emit the complete event sequence in order and
     * resolve as [RunResult.Completed] with a correct [RunSummary].
     */
    @Test
    fun `normal 30-rep run emits correct event sequence and completes`() = runBlocking {
        val reps = 30
        val model = mm1Model("NormalRunTest", reps, repLength = 500.0)

        val runner = Runner()
        val handle = runner.submit(RunRequest.SingleRun(model), scope = this)

        val events = collectUntilTerminal(handle)
        val result = handle.result.await()

        // result type
        assertIs<RunResult.Completed>(result)
        val summary = (result as RunResult.Completed).summary

        // summary values
        assertEquals(reps, summary.requestedReplications)
        assertEquals(reps, summary.completedReplications)
        assertEquals(EndingStatus.COMPLETED_ALL_STEPS, summary.endingStatus)
        assertTrue(summary.wallClockDuration > Duration.ZERO)

        // exactly one RunStarted with correct total
        val runStartedEvents = events.filterIsInstance<RunEvent.RunStarted>()
        assertEquals(1, runStartedEvents.size)
        assertEquals(reps, runStartedEvents.first().totalReplications)

        // one ReplicationStarted and one ReplicationEnded per rep, in order
        val repStarted = events.filterIsInstance<RunEvent.ReplicationStarted>()
        val repEnded   = events.filterIsInstance<RunEvent.ReplicationEnded>()
        assertEquals(reps, repStarted.size)
        assertEquals(reps, repEnded.size)
        repStarted.forEachIndexed { i, e -> assertEquals(i + 1, e.repNumber) }
        repEnded.forEachIndexed   { i, e -> assertEquals(i + 1, e.repNumber) }

        // RunCompleted is the last event and carries the same summary
        assertIs<RunEvent.RunCompleted>(events.last())
        assertEquals(summary, (events.last() as RunEvent.RunCompleted).summary)

        // no warnings for a finite-horizon model
        assertTrue(events.filterIsInstance<RunEvent.RunWarning>().isEmpty())
    }

    // ── Test 2: mid-run cancellation ─────────────────────────────────────────

    /**
     * Calling [RunHandle.cancel] after a specific replication must stop the run,
     * emit [RunEvent.RunCancelled] as the terminal event, and resolve
     * [RunHandle.result] as [RunResult.Cancelled].
     *
     * Because cancellation is cooperative (checked between replications) and the
     * runner executes on a different thread from the cancellation watcher, the
     * run may complete one additional replication after the trigger before the
     * cancel flag is seen.  The assertion therefore checks:
     *   - at least 5 replications completed (the trigger rep did finish), and
     *   - fewer than all 30 completed (it was genuinely cancelled).
     */
    @Test
    fun `cancel after 5th replication emits RunCancelled and result is Cancelled`() = runBlocking {
        val totalReps = 30
        val cancelAfterRep = 5
        val model = mm1Model("CancelTest", reps = totalReps, repLength = 500.0)

        val runner = Runner()
        val handle = runner.submit(RunRequest.SingleRun(model), scope = this)

        // Watch the flow on the runBlocking event loop; fire cancel when rep 5 ends.
        val cancelJob = launch {
            handle.events
                .filterIsInstance<RunEvent.ReplicationEnded>()
                .first { it.repNumber == cancelAfterRep }
            handle.cancel("test cancel after rep $cancelAfterRep")
        }

        val events = collectUntilTerminal(handle)
        val result = handle.result.await()
        cancelJob.join()

        // result type and reason
        assertIs<RunResult.Cancelled>(result)
        assertEquals("test cancel after rep $cancelAfterRep", (result as RunResult.Cancelled).reason)

        // terminal event
        assertIs<RunEvent.RunCancelled>(events.last())
        assertEquals("test cancel after rep $cancelAfterRep", (events.last() as RunEvent.RunCancelled).reason)

        // replication count: at least the trigger rep completed, but not all 30
        val completedReps = events.filterIsInstance<RunEvent.ReplicationEnded>().size
        assertTrue(completedReps >= cancelAfterRep,
            "Expected at least $cancelAfterRep completed reps; got $completedReps")
        assertTrue(completedReps < totalReps,
            "Expected fewer than $totalReps completed reps (run should have been cancelled); got $completedReps")

        // no RunCompleted on the cancelled path
        assertTrue(events.filterIsInstance<RunEvent.RunCompleted>().isEmpty())
    }

    // ── Test 3: infinite-horizon warning ─────────────────────────────────────

    /**
     * A model with [Model.lengthOfReplication] == [Double.POSITIVE_INFINITY] and
     * no wall-clock timeout must emit [RunEvent.RunWarning] before [RunEvent.RunStarted].
     *
     * The replication is kept finite by [SimulationStopper], which schedules a
     * call to [ModelElement.stopReplication] at simulation time 100.  This ends
     * the replication via the Executive's inner event loop, allowing the run to
     * terminate normally.  The result is [RunResult.Completed] with
     * [EndingStatus.COMPLETED_ALL_STEPS]: [stopReplication] is a replication-level
     * stop, so after that one replication finishes the experiment has completed
     * all of its planned replications (1 of 1).
     */
//    @Disabled("Requires model with internal stopping mechanism — see SimulationStopper; kept for documentation; enable manually when verifying infinite-horizon warning.")
    @Test
    fun `infinite-horizon model with no timeout emits RunWarning before RunStarted`() = runBlocking {
        val model = Model("InfiniteHorizonTest", autoCSVReports = false)
        model.numberOfReplications = 1
        model.lengthOfReplication = Double.POSITIVE_INFINITY
        // maximumAllowedExecutionTimePerReplication deliberately left at Duration.ZERO
        GIGcQueue(model, numServers = 1, name = "MM1")
        SimulationStopper(model, stopAtTime = 100.0) // ensures replication ends

        val runner = Runner()
        val handle = runner.submit(RunRequest.SingleRun(model), scope = this)

        val events = collectUntilTerminal(handle)
        val result = handle.result.await()

        // run completed normally (model stopped itself, not cancelled)
        assertIs<RunResult.Completed>(result)
        val summary = (result as RunResult.Completed).summary
        // stopReplication() is a replication-level stop: the experiment still
        // ran all 1 planned replications, so the experiment-level status is
        // COMPLETED_ALL_STEPS, not MET_STOPPING_CONDITION.
        assertEquals(EndingStatus.COMPLETED_ALL_STEPS, summary.endingStatus)

        // warning is present
        val warnings = events.filterIsInstance<RunEvent.RunWarning>()
        assertTrue(warnings.isNotEmpty(), "Expected at least one RunWarning")

        val warningType = warnings.first().warning
        assertIs<RunWarningType.InfiniteHorizonNoTimeout>(warningType)
        assertEquals(model.modelIdentifier, warningType.modelIdentifier)

        // warning appears before RunStarted
        val warningIdx = events.indexOfFirst { it is RunEvent.RunWarning }
        val startedIdx = events.indexOfFirst { it is RunEvent.RunStarted }
        assertTrue(warningIdx >= 0 && startedIdx >= 0)
        assertTrue(warningIdx < startedIdx,
            "RunWarning (index $warningIdx) must precede RunStarted (index $startedIdx)")
    }

    // ── Test 4: RunAttachmentIfc lifecycle guarantee ──────────────────────────

    /**
     * [RunAttachmentIfc.onAttach] and [RunAttachmentIfc.onDetach] must each be
     * called exactly once, and [onDetach] must fire even when the run is
     * cancelled before all replications complete.
     */
    @Test
    fun `attachment onAttach and onDetach called exactly once on cancellation`() = runBlocking {
        val model = mm1Model("AttachmentTest", reps = 10, repLength = 200.0)

        var attachCount = 0
        var detachCount = 0
        val attachment = object : RunAttachmentIfc {
            override fun onAttach(model: Model, scope: CoroutineScope) { attachCount++ }
            override fun onDetach() { detachCount++ }
        }

        val runner = Runner()
        val handle = runner.submit(
            RunRequest.SingleRun(model, attachments = listOf(attachment)),
            scope = this
        )

        // Cancel after the first replication completes
        launch {
            handle.events.filterIsInstance<RunEvent.ReplicationEnded>().first()
            handle.cancel("attachment lifecycle test")
        }

        handle.result.await()

        assertEquals(1, attachCount, "onAttach must be called exactly once")
        assertEquals(1, detachCount, "onDetach must be called exactly once even on cancel")
    }

    // ── Test 5: snapshot in RunResult.Completed ───────────────────────────────

    /**
     * A completed run must carry a non-empty [RunResult.Completed.snapshot] with
     * across-replication statistics.  The snapshot is produced by
     * [ksl.simulation.InMemorySnapshotCollector] attached inside [Runner].
     */
    @Test
    fun `completed result carries non-empty experiment snapshot`() = runBlocking {
        val reps = 5
        val model = mm1Model("SnapshotTest", reps, repLength = 200.0)

        val runner = Runner()
        val handle = runner.submit(RunRequest.SingleRun(model), scope = this)
        val result = handle.result.await()

        assertIs<RunResult.Completed>(result)
        val snapshot = (result as RunResult.Completed).snapshot
        assertTrue(
            snapshot.acrossRepStats.isNotEmpty(),
            "Expected non-empty across-rep stats in snapshot after a ${reps}-rep MM1 run"
        )
    }

    // ── Test 6: ReplicationDataAttachment ────────────────────────────────────

    /**
     * [ReplicationDataAttachment] must provide per-replication arrays whose length
     * equals the number of replications requested.
     */
    @Test
    fun `ReplicationDataAttachment provides per-replication arrays of correct length`() = runBlocking {
        val reps = 5
        val model = mm1Model("RepDataTest", reps, repLength = 200.0)
        val attachment = ReplicationDataAttachment()

        val runner = Runner()
        val handle = runner.submit(
            RunRequest.SingleRun(model, attachments = listOf(attachment)),
            scope = this
        )
        handle.result.await()

        val data = attachment.replicationData
        assertTrue(data.isNotEmpty(), "Expected non-empty replication data map from MM1")
        data.values.forEach { arr ->
            assertEquals(reps, arr.size,
                "Expected each replication data array to have length $reps")
        }
    }
}
