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

package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.modeling.queue.QueueCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * The purpose of this class is to hold entities that are waiting for a signal while executing
 * a KSLProcess.  A reference to this class can be used to signal specific, any, or all the
 * entities waiting to proceed within their process.
 *
 * @param parent the parent (containing) model element
 * @param name the name of this signal
 * @param discipline the queue discipline for the internal queue that holds the waiting entities
 */
class Signal(
    parent: ModelElement,
    name: String? = null,
    discipline: Queue.Discipline = Queue.Discipline.FIFO
) : ModelElement(parent, name) {

    private val holdQueue = HoldQueue(this, "${name}:HoldQ", discipline)

    val waitingQ: QueueCIfc<ProcessModel.Entity>
        get() = holdQueue

    /**
     *  An immutable list representation of the holding queue. While the list is
     *  read-only, the elements can be changed. Thus, some care may be needed to
     *  understand the effects of such changes.  This view is mainly to provide the abilities
     *  available in the Kotlin collection classes to working with the waiting entities,
     *  which may be used to determine which entities to signal.
     */
    fun holdingQueueAsList() : List<ProcessModel.Entity>{
        return holdQueue.toList()
    }

    /**
     *  Used within the process implementation to hold the entities
     */
    internal fun hold(entity: ProcessModel.Entity, queuePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        holdQueue.enqueue(entity, queuePriority)
    }

    /** Use this to signal a specific entity to move in its process.
     *  The entity removes itself from the waiting condition.
     *  @param entity the entity to signal
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signal(entity: ProcessModel.Entity, resumePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        entity.resumeProcess(0.0, resumePriority)
    }

    /** The entity removes itself from the waiting condition.
     *  If there are no entities, or the rank is out of range, then nothing happens (no signal)
     * @param rank the rank goes from 0 to size-1
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signal(rank: Int = 0, resumePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        require(rank >= 0) { "The rank of the desired entity must be >= 0" }
        if (holdQueue.isEmpty) {
            return
        }
        if (rank > holdQueue.size - 1) {
            return
        }
        val entity = holdQueue[rank]
        signal(entity, resumePriority)
    }

    /** The entities remove themselves from the waiting condition.
     * @param range the range associated with the signal
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signal(range: IntRange, resumePriority: Int = KSLEvent.DEFAULT_PRIORITY){
        for(i in range){
            signal(i, resumePriority)
        }
    }

    /**
     *  All the entities are signaled to proceed in their process. The entities
     *  remove themselves from the waiting condition.
     *
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signalAll(resumePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        for (entity in holdQueue) {
            signal(entity, resumePriority)
        }
    }

    /**
     *  Used by the entity to call back and remove itself after the signal
     */
    internal fun release(entity: ProcessModel.Entity, waitStats: Boolean = true) {
        holdQueue.remove(entity, waitStats)
    }
}