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

package ksl.app.moda

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Something that happened while a study was being run, reported as it happens.
 *
 *  Studies submitted to a service run out of sight, and a caller that can only wait for the end has
 *  no way to tell slow progress from no progress. These say where a study has got to, and end with
 *  exactly one event saying how it finished.
 */
sealed interface ModaEvent {

    /** The study these concern. */
    val studyId: String

    data class Started(
        override val studyId: String,
        val studyName: String,
        val startTime: Instant
    ) : ModaEvent

    /** The document was checked, before anything was run. */
    data class Checked(
        override val studyId: String,
        val errorCount: Int,
        val warningCount: Int
    ) : ModaEvent

    /** The scores were read, and this many alternatives came back complete. */
    data class ScoresRead(
        override val studyId: String,
        val alternativesScored: Int,
        val missingScores: Int
    ) : ModaEvent

    data class Completed(
        override val studyId: String,
        val recommendation: String,
        val endTime: Instant
    ) : ModaEvent

    /** The study was not run, because the document said something that could not be carried out. */
    data class Refused(
        override val studyId: String,
        val errorCount: Int,
        val endTime: Instant
    ) : ModaEvent

    /** The study stopped because something went wrong that it did not anticipate. */
    data class Failed(
        override val studyId: String,
        val message: String,
        val endTime: Instant
    ) : ModaEvent

    data class Cancelled(
        override val studyId: String,
        val reason: String,
        val endTime: Instant
    ) : ModaEvent
}

/**
 *  How a submitted study ended.
 *
 *  A study that was refused and one that broke are told apart, because they call for different
 *  things: a refusal is a document to correct, and a failure is a fault to report. Neither is
 *  raised to the caller, since a study ending badly is an outcome of asking rather than an error in
 *  the asking.
 */
sealed interface ModaStudyOutcome {

    val studyId: String

    /**
     *  The study reached a conclusion about the document, whether or not the document turned out to
     *  be runnable. See [ModaRunResult].
     */
    data class Finished(
        override val studyId: String,
        val run: ModaRunResult
    ) : ModaStudyOutcome {

        /** The result, when the study ran, or null when the document was refused. */
        val completed: ModaRunResult.Completed?
            get() = run as? ModaRunResult.Completed
    }

    /** Something went wrong that the study did not anticipate. */
    data class Failed(
        override val studyId: String,
        val message: String,
        val cause: Throwable? = null
    ) : ModaStudyOutcome

    data class Cancelled(
        override val studyId: String,
        val reason: String
    ) : ModaStudyOutcome
}

/**
 *  A submitted study, to watch or to wait for.
 */
interface ModaHandle {

    val studyId: String

    /** What has happened so far, and what happens next. */
    val events: SharedFlow<ModaEvent>

    /** How it ended. Resolves rather than raising, whatever the outcome. */
    val result: Deferred<ModaStudyOutcome>

    /** Asks the study to stop. Has no effect once it has finished. */
    fun cancel(reason: String = DEFAULT_CANCEL_REASON)

    /** Waits for the study to end, for callers that are not themselves suspending. */
    fun awaitResultBlocking(): ModaStudyOutcome = runBlocking { result.await() }

    companion object {
        const val DEFAULT_CANCEL_REASON: String = "Cancelled by user"
    }
}

/**
 *  Holds one study's events and outcome, and makes sure it ends exactly once.
 *
 *  Ending once matters because a study can be cancelled at the moment it finishes, and a caller
 *  that saw it complete must not then see it cancelled, nor wait forever because two endings raced
 *  and neither settled the outcome.
 */
internal class ModaLifecycle(
    val studyId: String,
    replay: Int = DEFAULT_REPLAY
) {

    private val mutableEvents = MutableSharedFlow<ModaEvent>(
        replay = replay,
        extraBufferCapacity = EXTRA_BUFFER
    )

    private val resultDeferred = CompletableDeferred<ModaStudyOutcome>()

    private val terminated = AtomicBoolean(false)

    val events: SharedFlow<ModaEvent> get() = mutableEvents
    val result: Deferred<ModaStudyOutcome> get() = resultDeferred

    /** True while the study has not yet ended. */
    val isActive: Boolean get() = !terminated.get()

    fun emit(event: ModaEvent): Boolean {
        if (terminated.get()) return false
        return mutableEvents.tryEmit(event)
    }

    fun complete(outcome: ModaStudyOutcome, terminalEvent: ModaEvent): Boolean {
        // Whoever gets here first decides the outcome; everyone else is too late.
        if (!terminated.compareAndSet(false, true)) return false
        mutableEvents.tryEmit(terminalEvent)
        return resultDeferred.complete(outcome)
    }

    fun completeCancelled(reason: String): Boolean = complete(
        ModaStudyOutcome.Cancelled(studyId, reason),
        ModaEvent.Cancelled(studyId, reason, Clock.System.now())
    )

    fun completeFailed(message: String, cause: Throwable? = null): Boolean = complete(
        ModaStudyOutcome.Failed(studyId, message, cause),
        ModaEvent.Failed(studyId, message, Clock.System.now())
    )

    companion object {
        /**
         *  Enough replay that a caller attaching just after submitting still sees the whole story.
         *  A study emits a handful of events, so keeping them all costs nothing.
         */
        const val DEFAULT_REPLAY: Int = 16
        const val EXTRA_BUFFER: Int = 16
    }
}

internal class ModaHandleImpl(
    private val lifecycle: ModaLifecycle,
    private val job: Job
) : ModaHandle {

    override val studyId: String get() = lifecycle.studyId
    override val events: SharedFlow<ModaEvent> get() = lifecycle.events
    override val result: Deferred<ModaStudyOutcome> get() = lifecycle.result

    override fun cancel(reason: String) {
        // Settle the outcome first, so a caller awaiting the result is never left waiting on a
        // coroutine that has already been torn down.
        if (lifecycle.completeCancelled(reason)) {
            job.cancel(CancellationException(reason))
        }
    }
}

/**
 *  Runs studies on behalf of a caller, one at a time or many at once.
 *
 *  Each submitted study gets its own model, built inside its own run and never handed out except as
 *  a recorded result. Nothing about a study is shared with another, so studies running at the same
 *  time cannot disturb one another and the engine needs no locking to make that true. It is the
 *  reason this is safe to put behind a service.
 *
 *  Closing a session stops whatever is still running rather than leaving it going, and a session
 *  that has been closed refuses further studies with a result saying so rather than by raising.
 *
 *  @param registry the value functions studies may name
 *  @param resolver how a study's source reference becomes something to read scores from, when the
 *  submission does not supply one of its own
 *  @param scope where studies run. When none is supplied the session makes its own and shuts it
 *  down on close; when one is supplied the caller keeps that responsibility.
 */
class ModaSession(
    private val registry: ValueFunctionRegistry = ValueFunctionRegistry.Default,
    private val resolver: ModaSourceResolver = ModaSourceResolver(),
    scope: CoroutineScope? = null
) : AutoCloseable {

    private val ownsScope: Boolean = scope == null
    private val scope: CoroutineScope = scope ?: CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val handles = CopyOnWriteArrayList<ModaHandle>()

    @Volatile
    private var closed = false

    /** Whether this session has been closed. */
    val isClosed: Boolean get() = closed

    /** The studies submitted to this session that have not been cleared by closing it. */
    val submittedCount: Int get() = handles.size

    /**
     *  Submits [document] to be run.
     *
     *  @param sourceResolver where this study's scores come from, when they come from somewhere
     *  particular to it, such as the replications of a simulation held in memory. Falls back to the
     *  session's own.
     *  @return a handle to watch or wait on. A closed session returns one that has already finished.
     */
    fun submit(
        document: ModaDocument,
        sourceResolver: ModaSourceResolver? = null
    ): ModaHandle {
        val studyId = newStudyId()
        if (closed) {
            return finishedHandle(
                studyId,
                ModaStudyOutcome.Failed(studyId, "The MODA session is closed."),
                ModaEvent.Failed(studyId, "The MODA session is closed.", Clock.System.now())
            )
        }
        val lifecycle = ModaLifecycle(studyId)
        val effectiveResolver = sourceResolver ?: resolver

        val job = scope.launch {
            try {
                lifecycle.emit(ModaEvent.Started(studyId, document.name, Clock.System.now()))
                // Running a study is arithmetic over data already in hand rather than waiting on
                // anything, so it belongs on a thread meant for work.
                val outcome = withContext(Dispatchers.Default) {
                    runStudy(studyId, document, effectiveResolver, lifecycle)
                }
                lifecycle.complete(outcome.first, outcome.second)
            } catch (cancellation: CancellationException) {
                // Cancelling settles the outcome before tearing the coroutine down, so by the time
                // this arrives the outcome is already decided and this is only unwinding.
                lifecycle.completeCancelled(cancellation.message ?: ModaHandle.DEFAULT_CANCEL_REASON)
                throw cancellation
            } catch (failure: Throwable) {
                lifecycle.completeFailed(
                    failure.message ?: "The study stopped: ${failure::class.simpleName}.",
                    failure
                )
            }
        }

        val handle = ModaHandleImpl(lifecycle, job)
        handles += handle
        return handle
    }

    /** Submits [document] and waits for it, for callers that are not themselves suspending. */
    fun submitAndAwaitBlocking(
        document: ModaDocument,
        sourceResolver: ModaSourceResolver? = null
    ): ModaStudyOutcome = submit(document, sourceResolver).awaitResultBlocking()

    /**
     *  Runs the study and works out both how it ended and what to say about it.
     *
     *  The runner is made here rather than held, so that nothing about one study can reach another.
     */
    private fun runStudy(
        studyId: String,
        document: ModaDocument,
        sourceResolver: ModaSourceResolver,
        lifecycle: ModaLifecycle
    ): Pair<ModaStudyOutcome, ModaEvent> {
        val run = ModaRunner(registry, sourceResolver).run(document)
        val now = Clock.System.now()
        return when (run) {
            is ModaRunResult.Invalid -> {
                lifecycle.emit(ModaEvent.Checked(studyId, run.errors.size, run.issues.size - run.errors.size))
                ModaStudyOutcome.Finished(studyId, run) to
                        ModaEvent.Refused(studyId, run.errors.size, now)
            }
            is ModaRunResult.Completed -> {
                lifecycle.emit(ModaEvent.Checked(studyId, 0, run.issues.size))
                lifecycle.emit(
                    ModaEvent.ScoresRead(studyId, run.snapshot.alternatives.size, run.missing.size)
                )
                ModaStudyOutcome.Finished(studyId, run) to
                        ModaEvent.Completed(studyId, run.snapshot.primaryRecommendation, now)
            }
        }
    }

    /**
     *  Stops anything still running and, if this session made its own place to run them, shuts that
     *  down too. Closing twice does nothing the second time.
     */
    override fun close() {
        if (closed) return
        closed = true
        val outstanding = handles.toList()
        handles.clear()
        for (handle in outstanding) {
            handle.cancel(SESSION_CLOSED_REASON)
        }
        if (ownsScope) {
            scope.cancel("ModaSession.close")
        }
    }

    private fun newStudyId(): String = "moda-" + UUID.randomUUID().toString().take(8)

    /** A handle for a study that never started, already carrying its outcome. */
    private fun finishedHandle(
        studyId: String,
        outcome: ModaStudyOutcome,
        terminalEvent: ModaEvent
    ): ModaHandle {
        val lifecycle = ModaLifecycle(studyId)
        lifecycle.complete(outcome, terminalEvent)
        return object : ModaHandle {
            override val studyId: String get() = lifecycle.studyId
            override val events: SharedFlow<ModaEvent> get() = lifecycle.events
            override val result: Deferred<ModaStudyOutcome> get() = lifecycle.result
            override fun cancel(reason: String) = Unit
        }
    }

    companion object {
        const val SESSION_CLOSED_REASON: String = "The MODA session was closed."
    }
}
