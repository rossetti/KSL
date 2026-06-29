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
 * A filtering [AnimationSink] that forwards events to [delegate] only while the
 * simulated clock lies within the closed time window `[startTime, endTime]`.
 *
 * This is a *decorator*: it wraps another sink and adds time filtering without
 * that sink knowing anything about windows. It exists so a modeler can animate a
 * bounded horizon of a long run (e.g. `[500.0, 600.0]` of a 10,000-hour run)
 * while paying essentially nothing for the rest of the run — because every
 * emission site checks [isActive] before constructing an event, and [isActive] is
 * `false` outside the window, no event objects are created outside the window.
 *
 * Note on the opening frame: filtering raw events to the window does not, by
 * itself, reproduce the model's *state* at [startTime] (entities already queued,
 * resources already busy, moves already in flight). A correct opening frame
 * requires a state snapshot emitted at the window boundary; that snapshot is
 * produced later by the animation controller and its emitters (it is not this
 * decorator's responsibility).
 *
 * @param delegate the downstream sink that actually records in-window events
 * @param startTime inclusive lower bound of the capture window (>= 0)
 * @param endTime inclusive upper bound of the capture window (>= startTime)
 * @param currentTime supplies the model's current simulated time when queried
 */
class WindowedAnimationSink(
    private val delegate: AnimationSink,
    private val startTime: Double,
    private val endTime: Double,
    private val currentTime: () -> Double
) : AnimationSink {

    init {
        require(startTime >= 0.0) { "startTime ($startTime) must be >= 0.0" }
        require(startTime <= endTime) { "startTime ($startTime) must be <= endTime ($endTime)" }
    }

    private val withinWindow: Boolean
        get() = currentTime() in startTime..endTime

    override val isActive: Boolean
        get() = delegate.isActive && withinWindow

    override fun emit(event: AnimationEvent) {
        // Lifecycle markers describe the experiment/replication framing, which is true regardless of the
        // capture window; only *content* events are time-filtered. Without this, a windowed trace would
        // lose the ReplicationStarted/Ended (t=0 / t=runLength) markers a non-windowed trace keeps.
        if (event.isLifecycleMarker || withinWindow) delegate.emit(event)
    }

    override fun onReplicationStart(replicationNumber: Int): Unit =
        delegate.onReplicationStart(replicationNumber)

    override fun onReplicationEnd(replicationNumber: Int): Unit =
        delegate.onReplicationEnd(replicationNumber)

    override fun onExperimentEnd(): Unit = delegate.onExperimentEnd()
}
