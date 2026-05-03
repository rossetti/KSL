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

package ksl.app.session

import kotlinx.datetime.Instant

/**
 * Sealed lifecycle event hierarchy emitted on [RunHandle.events] during a
 * simulation run managed by [Runner].
 *
 * ## Event sequence guarantees
 *
 * For every call to [Runner.submit] exactly one *terminal* event will be emitted
 * as the final event on the flow: [RunCompleted], [RunCancelled], or [RunFailed].
 * No further events are emitted after a terminal event.
 *
 * A typical successful run produces:
 * ```
 * RunStarted
 * [RunWarning]?           (zero or more, emitted before the experiment begins)
 * ReplicationStarted(1)
 * ReplicationEnded(1)
 * ReplicationStarted(2)
 * ReplicationEnded(2)
 * ...
 * RunCompleted(summary)   ← terminal
 * ```
 *
 * ## What these events do NOT carry
 *
 * These events are **lifecycle-only** and carry no statistical observations.
 * Per-replication and across-replication statistics live in the output sinks
 * the user configured on the model (database, CSV, in-memory responses) and
 * are accessed through normal KSL APIs after [RunCompleted] is received.
 */
sealed class RunEvent {

    /**
     * Emitted once, immediately after [Runner] calls `model.initializeReplications()`.
     *
     * @property runId the unique run identifier assigned by [Runner]
     * @property modelIdentifier `model.modelIdentifier` at submission time
     * @property totalReplications `model.numberOfReplications` at submission time
     * @property startTime wall-clock instant the experiment was initialized
     */
    data class RunStarted(
        val runId: String,
        val modelIdentifier: String,
        val totalReplications: Int,
        val startTime: Instant
    ) : RunEvent()

    /**
     * Emitted when [Runner] detects a potentially problematic configuration
     * **before** the experiment begins.  Does not prevent the run from
     * proceeding; the receiving GUI or test driver should surface it to the user.
     *
     * Multiple warnings may be emitted for a single run.  All [RunWarning]
     * events are emitted before [RunStarted].
     */
    data class RunWarning(val warning: RunWarningType) : RunEvent()

    /**
     * Emitted by [Runner] immediately before `model.runNextReplication()` is
     * called for replication [repNumber].
     *
     * @property repNumber 1-based index of the replication about to execute
     * @property totalReplications total replications requested for this run
     */
    data class ReplicationStarted(
        val repNumber: Int,
        val totalReplications: Int
    ) : RunEvent()

    /**
     * Emitted by [Runner] immediately after `model.runNextReplication()` returns
     * for replication [repNumber].
     *
     * @property repNumber 1-based index of the replication that just completed
     * @property totalReplications total replications requested for this run
     */
    data class ReplicationEnded(
        val repNumber: Int,
        val totalReplications: Int
    ) : RunEvent()

    /**
     * Carries the current simulation clock value and event execution count.
     *
     * Defined here so the sealed hierarchy is stable, but **not emitted in
     * Phase 1**.  Emission requires a mechanism to bridge the Executive's
     * synchronous event loop to the coroutine flow during a replication;
     * that mechanism is deferred to a later phase.
     *
     * @property simTime current value of the simulation clock
     * @property eventsExecuted cumulative events executed since experiment start
     */
    data class SimTimeAdvanced(
        val simTime: Double,
        val eventsExecuted: Long
    ) : RunEvent()

    /**
     * Terminal event — emitted when an unexpected exception is thrown during
     * replication execution.  Always the last event on the flow.
     *
     * @property error typed description of the failure
     */
    data class RunFailed(val error: KSLRuntimeError) : RunEvent()

    /**
     * Terminal event — emitted when the run is stopped by an explicit call to
     * [RunHandle.cancel].  Always the last event on the flow.
     *
     * @property reason the message passed to [RunHandle.cancel]
     */
    data class RunCancelled(val reason: String) : RunEvent()

    /**
     * Terminal event — emitted when the run ends normally (all replications
     * completed, execution-time limit reached, or the model stopped itself via
     * `endSimulation()`).  Always the last event on the flow.
     *
     * @property summary lightweight post-run summary; see [RunSummary]
     */
    data class RunCompleted(val summary: RunSummary) : RunEvent()
}
