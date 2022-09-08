package ksl.modeling.entity

import ksl.modeling.queue.Queue
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement

class Signal(
    parent: ModelElement,
    name: String?,
    discipline: Queue.Discipline = Queue.Discipline.FIFO
) : ModelElement(parent, name) {

    private val holdQueue = HoldQueue(this, "${name}:HoldQ", discipline)

    fun hold(entity: EntityType.Entity, queuePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        holdQueue.enqueue(entity, queuePriority)
    }

    fun signal(entity: EntityType.Entity, resumePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        entity.resumeProcess(resumePriority)
    }

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

    fun signal(range: IntRange, resumePriority: Int = KSLEvent.DEFAULT_PRIORITY){
        for(i in range){
            signal(i, resumePriority)
        }
    }

    fun signalAll(resumePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        for (entity in holdQueue) {
            signal(entity, resumePriority)
        }
    }

    internal fun release(entity: EntityType.Entity, waitStats: Boolean = true) {
        holdQueue.remove(entity, waitStats)
    }
}