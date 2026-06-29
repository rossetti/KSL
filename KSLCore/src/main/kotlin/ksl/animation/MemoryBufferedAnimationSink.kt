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
 * An [AnimationSink] that holds every emitted event in memory for the duration of
 * a replication, then hands the whole batch to [flush] when the replication ends.
 *
 * This sink does no input/output itself: it is deliberately decoupled from how
 * events are written. The [flush] function decides what "write" means (append to
 * a `.atf` file, push to a renderer, accumulate in a test), which keeps this class
 * trivial to test and free of any file dependency. Because all events for a
 * replication are gathered before [flush] runs, there is zero I/O on the
 * simulation thread during the replication — the trade-off is memory proportional
 * to the number of events in one replication.
 *
 * Threading: like every sink, [emit] is called on the simulation thread. This
 * implementation is single-threaded and performs no synchronization.
 *
 * @param flush invoked once at the end of each replication with the replication
 *        number and the events collected during it. The list passed to [flush] is
 *        the sink's live buffer and is cleared as soon as [flush] returns, so the
 *        callback must consume it synchronously (copy it if it needs to keep it).
 */
class MemoryBufferedAnimationSink(
    private val flush: (replicationNumber: Int, events: List<AnimationEvent>) -> Unit
) : AnimationSink {

    private val buffer: MutableList<AnimationEvent> = mutableListOf()
    private var lastReplicationNumber: Int = 0

    /** The number of events currently held for the in-progress replication. */
    val bufferedCount: Int
        get() = buffer.size

    /** Always active: this sink is installed only when animation is enabled. */
    override val isActive: Boolean
        get() = true

    override fun emit(event: AnimationEvent) {
        buffer.add(event)
    }

    /**
     * Flushes the events collected during [replicationNumber] and empties the
     * buffer so the next replication starts clean.
     */
    override fun onReplicationEnd(replicationNumber: Int) {
        lastReplicationNumber = replicationNumber
        flushAndClear(replicationNumber)
    }

    /**
     * Flushes any events emitted after the last replication ended (e.g. a final
     * experiment-level marker), so nothing is left unwritten. Attributed to the
     * last replication that ended.
     */
    override fun onExperimentEnd() {
        if (buffer.isNotEmpty()) flushAndClear(lastReplicationNumber)
    }

    /** Hands the buffer to [flush] and empties it, clearing even if [flush] throws. */
    private fun flushAndClear(replicationNumber: Int) {
        try {
            flush(replicationNumber, buffer)
        } finally {
            buffer.clear()
        }
    }
}
