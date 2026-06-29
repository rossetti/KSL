/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.animation

/**
 * A filtering [AnimationSink] that forwards events and lifecycle callbacks to
 * [delegate] only during the replications named in [capturedReplications].
 *
 * Animating an entire multi-replication experiment is rarely wanted; a modeler
 * typically animates one replication. This decorator restricts capture to the
 * chosen replication number(s). During any non-selected replication [isActive] is
 * `false`, so — by the usual `if (sink.isActive) sink.emit(...)` rule — no event
 * objects are constructed and that replication costs nothing.
 *
 * The [delegate] sees a clean lifecycle for the selected replications only: its
 * `onReplicationStart`/`onReplicationEnd` fire just for those, so a buffering
 * delegate flushes exactly one batch per selected replication and is never asked
 * to flush an empty non-selected one. [onExperimentEnd] is always forwarded so a
 * delegate that owns an experiment-scoped resource (e.g. an open file) can close it.
 *
 * @param delegate the downstream sink that records events for selected replications
 * @param capturedReplications the 1-based replication numbers to capture (non-empty)
 */
class ReplicationSelectingSink(
    private val delegate: AnimationSink,
    capturedReplications: Set<Int>
) : AnimationSink {

    private val capturedReplications: Set<Int> = capturedReplications.toSet()

    init {
        require(this.capturedReplications.isNotEmpty()) { "at least one replication must be selected" }
        require(this.capturedReplications.all { it >= 1 }) { "replication numbers must be >= 1" }
    }

    /** Convenience constructor for capturing a single replication. */
    constructor(delegate: AnimationSink, replication: Int) : this(delegate, setOf(replication))

    private var capturing: Boolean = false

    override val isActive: Boolean
        get() = capturing && delegate.isActive

    override fun emit(event: AnimationEvent) {
        when (event) {
            // Experiment markers frame the whole trace (they fire outside any replication, when
            // `capturing` is false), so forward them always.
            is AnimationEvent.ExperimentStarted, is AnimationEvent.ExperimentEnded -> delegate.emit(event)
            // Replication markers delimit a replication's block; keep them iff that replication is captured
            // (the marker carries its own number, so this holds even when `capturing` has been reset).
            is AnimationEvent.ReplicationStarted -> if (event.replicationNumber in capturedReplications) delegate.emit(event)
            is AnimationEvent.ReplicationEnded -> if (event.replicationNumber in capturedReplications) delegate.emit(event)
            // Content events are captured only during a selected replication.
            else -> if (capturing) delegate.emit(event)
        }
    }

    override fun onReplicationStart(replicationNumber: Int) {
        capturing = replicationNumber in capturedReplications
        if (capturing) delegate.onReplicationStart(replicationNumber)
    }

    override fun onReplicationEnd(replicationNumber: Int) {
        if (capturing) delegate.onReplicationEnd(replicationNumber)
        capturing = false
    }

    override fun onExperimentEnd(): Unit = delegate.onExperimentEnd()
}
