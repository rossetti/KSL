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
        resumePriority: Int = KSLEvent.DEFAULT_PRIORITY,
        waitStats: Boolean = true
    ) {
        remove(entity, waitStats)
        entity.resumeProcess(resumePriority)
    }

    /**
     *  Removes and resumes all the entities waiting in the queue
     *
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    fun removeAllAndResume(resumePriority: Int = KSLEvent.DEFAULT_PRIORITY, waitStats: Boolean = true) {
        while (isNotEmpty) {
            val entity = peekNext()
            removeAndResume(entity!!, resumePriority, waitStats)
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