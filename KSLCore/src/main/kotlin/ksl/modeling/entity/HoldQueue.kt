package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

class HoldQueue(
    parent: ModelElement,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) :
    Queue<EntityType.Entity>(parent, name, discipline) {

    fun removeAndResume(
        entity: EntityType.Entity,
        resumePriority: Int = KSLEvent.DEFAULT_PRIORITY,
        waitStats: Boolean = true
    ) {
        remove(entity, waitStats)
        entity.resumeProcess(resumePriority)
    }
}