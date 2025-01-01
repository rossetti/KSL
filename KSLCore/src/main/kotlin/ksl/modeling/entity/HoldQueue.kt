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
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

/**
 * This class is designed to hold entities that are waiting within a process.
 *
 */
class HoldQueue(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) :
    Queue<ProcessModel.Entity>(parent, name, discipline) {

    /** Removes the entity from the queue and tells it to resume its process
     *
     * @param entity the entity to remove from the queue
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAndResume(
        entity: ProcessModel.Entity,
        resumePriority: Int = ProcessModel.RESUME_PRIORITY,
        waitStats: Boolean = true
    ) {
        remove(entity, waitStats)
        entity.resumeProcess(0.0, resumePriority)
    }

    /** Removes the entity from the queue and tells it to resume its process immediately,
     *  without being scheduled through the event loop. This may be useful to coordinate
     *  processes that occur at the same time.
     *
     * @param entity the entity to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAndImmediateResume(
        entity: ProcessModel.Entity,
        waitStats: Boolean = true
    ) {
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > HoldQueue : removeAndImmediateResume() : removing entity_id = ${entity.id}"}
        remove(entity, waitStats)
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > HoldQueue : removeAndImmediateResume() : after remove, before immediateResume()"}
        entity.immediateResume()
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > HoldQueue : removeAndImmediateResume() : after remove, after immediateResume()"}
    }

    /**
     *  Removes and resumes all the entities waiting in the queue
     *
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAllAndResume(resumePriority: Int = ProcessModel.RESUME_PRIORITY, waitStats: Boolean = true) {
        while (isNotEmpty) {
            val entity = peekNext()
            removeAndResume(entity!!, resumePriority, waitStats)
        }
    }

    /** Removes the entities from the queue and tells it to resume its process immediately,
     *  without being scheduled through the event loop. This may be useful to coordinate
     *  processes that occur at the same time.
     *
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAllWithImmediateResume(
        waitStats: Boolean = true
    ) {
        while (isNotEmpty) {
            val entity = peekNext()
            removeAndImmediateResume(entity!!, waitStats)
        }
    }

    /** Removes the entity from the queue and tells it to terminate its process.  The process
     *  that was suspended because the entity was placed in the queue is immediately terminated.
     *
     * @param entity the entity to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue. The default is false.
     */
    fun removeAndTerminate(
        entity: ProcessModel.Entity,
        waitStats: Boolean = false
    ) {
        remove(entity, waitStats)
        entity.terminateProcess()
    }

    /**
     *  Removes and terminates all the entities waiting in the queue
     *
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue.
     * The default is false.
     */
    fun removeAllAndTerminate(waitStats: Boolean = false) {
        while (isNotEmpty) {
            val entity = peekNext()
            removeAndTerminate(entity!!, waitStats)
        }
    }
}