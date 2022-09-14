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

    fun removeAndResume(
        entity: ProcessModel.Entity,
        resumePriority: Int = KSLEvent.DEFAULT_PRIORITY,
        waitStats: Boolean = true
    ) {
        remove(entity, waitStats)
        entity.resumeProcess(resumePriority)
    }

    fun removeAllAndResume(resumePriority: Int = KSLEvent.DEFAULT_PRIORITY, waitStats: Boolean = true) {
        while (isNotEmpty) {
            val entity = peekNext()
            removeAndResume(entity!!, resumePriority, waitStats)
        }
    }
    //TODO need to work on removing suspended items after a replication
}