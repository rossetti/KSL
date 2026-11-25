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
class HoldQueue @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) :
    Queue<ProcessModel.Entity>(parent, name, discipline) {

    /** Removes the entity from the queue and tells it to resume its process
     *
     * @param entity the entity to remove from the queue. It must be in the queue; check contains(entity) first.
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAndResume(
        entity: ProcessModel.Entity,
        resumePriority: Int = ProcessModel.RESUME_PRIORITY,
        waitStats: Boolean = true
    ) {
        require(contains(entity)) { "The entity ${entity.name} (id = ${entity.id}) is not in the hold queue $name, so it cannot be removed from it and resumed. Check contains(entity) first." }
        remove(entity, waitStats)
        entity.scheduleResumeProcess(0.0, resumePriority, ResumeSource.HOLD_QUEUE, "queue=$name")
    }

    /** Removes the entity from the queue and tells it to resume its process immediately,
     *  without being scheduled through the event loop. This may be useful to coordinate
     *  processes that occur at the same time.
     *
     * @param entity the entity to remove from the queue. It must be in the queue; check contains(entity) first.
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAndImmediateResume(
        entity: ProcessModel.Entity,
        waitStats: Boolean = true
    ) {
        require(contains(entity)) { "The entity ${entity.name} (id = ${entity.id}) is not in the hold queue $name, so it cannot be removed from it and resumed. Check contains(entity) first." }
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
     *  An entity is in the queue while its process still runs, from when it is placed until the process suspends,
     *  and the queue's listeners can see it then. A running process cannot be terminated, so this throws, before
     *  anything is removed; check that the entity's `currentProcess` is not running first.
     *
     * @param entity the entity to remove from the queue
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue. The default is false.
     */
    fun removeAndTerminate(
        entity: ProcessModel.Entity,
        waitStats: Boolean = false
    ) {
        check(entity.currentProcess?.isRunning != true) {
            "The entity ${entity.name} in $name cannot be removed and terminated: its process is running now, as " +
                    "when this is called from code run while it is placed, and a running process cannot be " +
                    "terminated. Check that the entity's currentProcess is not running first."
        }
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
