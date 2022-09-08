package ksl.modeling.entity

import ksl.modeling.queue.Queue
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
    name: String?,
    discipline: Queue.Discipline = Queue.Discipline.FIFO
) : ModelElement(parent, name) {

    private val holdQueue = HoldQueue(this, "${name}:HoldQ", discipline)

    /**
     *  Used within the process implementation to hold the entities
     */
    internal fun hold(entity: EntityType.Entity, queuePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        holdQueue.enqueue(entity, queuePriority)
    }

    /** Use this to signal a specific entity to move in its process.
     *  The entity removes itself from the waiting condition.
     *  @param entity the entity to signal
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signal(entity: EntityType.Entity, resumePriority: Int = KSLEvent.DEFAULT_PRIORITY) {
        entity.resumeProcess(resumePriority)
    }

    /** The entity removes itself from the waiting condition.
     *  If there are no entities, or the rank is out of range, then nothing happens (no signal)
     * @param rank the rank goes from 0 to size
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
    internal fun release(entity: EntityType.Entity, waitStats: Boolean = true) {
        holdQueue.remove(entity, waitStats)
    }
}