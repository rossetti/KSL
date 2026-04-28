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

package ksl.simulation

import ksl.utilities.io.dbutil.SimulationSnapshot

/**
 * Buffers every [SimulationSnapshot] emitted by a [SimulationLifeCycleEmitters] into an
 * in-memory list for later batch processing.
 *
 * Attach once before the simulation starts, call [drain] after completion to retrieve
 * all snapshots, then [close] to detach from the emitters.  [AutoCloseable] allows
 * use in a try-with-resources block.
 *
 * Thread-safety: the internal list is guarded by `synchronized(this)` so that multiple
 * concurrent simulations writing to separate collectors do not interfere even if they
 * share a parent scope.
 */
class InMemorySnapshotCollector(emitters: SimulationLifeCycleEmitters) : AutoCloseable {

    private val mySnapshots = mutableListOf<SimulationSnapshot>()

    private val myStartedConnection = emitters.experimentStarted.attach { snapshot ->
        synchronized(this) { mySnapshots.add(snapshot) }
    }
    private val myReplicationConnection = emitters.replicationCompleted.attach { snapshot ->
        synchronized(this) { mySnapshots.add(snapshot) }
    }
    private val myCompletedConnection = emitters.experimentCompleted.attach { snapshot ->
        synchronized(this) { mySnapshots.add(snapshot) }
    }
    private val myFailedConnection = emitters.experimentFailed.attach { snapshot ->
        synchronized(this) { mySnapshots.add(snapshot) }
    }

    private val myEmitters = emitters

    /**
     * Returns all collected snapshots in emission order and clears the internal buffer.
     */
    fun drain(): List<SimulationSnapshot> {
        synchronized(this) {
            val result = mySnapshots.toList()
            mySnapshots.clear()
            return result
        }
    }

    /**
     * Detaches this collector from all emitters.  After closing, no further snapshots
     * are collected.  Any snapshots already buffered remain available via [drain].
     */
    override fun close() {
        myEmitters.experimentStarted.detach(myStartedConnection)
        myEmitters.replicationCompleted.detach(myReplicationConnection)
        myEmitters.experimentCompleted.detach(myCompletedConnection)
        myEmitters.experimentFailed.detach(myFailedConnection)
    }
}
