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
import ksl.simulation.ModelElement
import org.jetbrains.kotlinx.dataframe.Predicate

/**
 * The purpose of this class is to hold entities that are waiting for a signal while executing
 * a KSLProcess.  A reference to this class can be used to signal specific, any, or all the
 * entities waiting to proceed within their process.
 *
 * For a broadcast to agent statecharts, which has no suspended entities and cannot be waited on
 * with `waitFor`, see `ksl.modeling.agent.AgentSignal`.
 *
 * @param parent the parent (containing) model element
 * @param name the name of this signal
 * @param discipline the queue discipline for the internal queue that holds the waiting entities
 */
class Signal @JvmOverloads constructor(
    parent: ModelElement,
    name: String? = null,
    discipline: Queue.Discipline = Queue.Discipline.FIFO
) : ModelElement(parent, name) {

    private val holdQueue = HoldQueue(this, "${name}:HoldQ", discipline)

    /** The entities in the hold queue through a signal trigger, each with its place in its wait. */
    private val myTimedHolds: MutableMap<ProcessModel.Entity, SignalRegistration> = mutableMapOf()

    override fun initialize() {
        super.initialize()
        // A safeguard: every registration leaves the map when it is released, and the waits of a
        // replication end with it.
        myTimedHolds.clear()
    }

    /**
     *  A trigger for this signal, for use in a wait for the first of several things
     *  (`waitForFirst`). The trigger fires when the waiting entity is signalled while the wait is
     *  open; it never fires at once. While the wait is open, the entity waits in this signal's
     *  queue as an entity in `waitFor(signal)` does, so it is counted, ranked and signalled in the
     *  same way. A wait may include at most one signal trigger, because an entity can wait in only
     *  one queue.
     *
     *  A trigger holds no state of any wait, so it can be kept and used in any number of waits.
     *
     *  @param waitPriority the entity's priority in this signal's queue; null (the default) means
     *  the entity's own priority when the wait starts
     *  @param waitStats whether the time the entity waits in this signal's queue is recorded,
     *  whether the trigger wins or loses
     */
    fun trigger(waitPriority: Int? = null, waitStats: Boolean = true): WaitTrigger<Unit> =
        SignalTrigger(this, waitPriority, waitStats)

    val waitingQ: QueueCIfc<ProcessModel.Entity>
        get() = holdQueue

    /**
     *  An immutable list representation of the holding queue. While the list is
     *  read-only, the elements can be changed. Thus, some care may be needed to
     *  understand the effects of such changes.  This view is mainly to provide the abilities
     *  available in the Kotlin collection classes to working with the waiting entities,
     *  which may be used to determine which entities to signal.
     */
    fun holdingQueueAsList(): List<ProcessModel.Entity> {
        return holdQueue.toList()
    }

    /**
     *  Used within the process implementation to hold the entities
     */
    internal fun hold(entity: ProcessModel.Entity, queuePriority: Int = ProcessModel.QUEUE_PRIORITY) {
        holdQueue.enqueue(entity, queuePriority)
    }

    /** Holds an entity for a signal trigger's wait. */
    internal fun holdTimed(entity: ProcessModel.Entity, queuePriority: Int, registration: SignalRegistration) {
        hold(entity, queuePriority)
        myTimedHolds[entity] = registration
    }

    /** Removes an entity held for a signal trigger's wait, if it is still held. */
    internal fun releaseTimed(entity: ProcessModel.Entity, waitStats: Boolean) {
        myTimedHolds.remove(entity)
        if (holdQueue.contains(entity)) {
            holdQueue.remove(entity, waitStats)
        }
    }

    /**
     *  Signals the entities that match the predicate to resume their processes if they are waiting for the signal.
     *  @param predicate the list of entities to signal
     *  @param resumePriority the priority associated with their resumption.
     */
    fun signal(predicate: Predicate<ProcessModel.Entity>, resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        signal(holdQueue.filter(predicate), resumePriority)
    }

    /**
     *  Signals the entities in the list to resume their processes if they are waiting for the signal.
     *  @param entities the list of entities to signal
     *  @param resumePriority the priority associated with their resumption.
     */
    fun signal(entities: List<ProcessModel.Entity>, resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        for (entity in entities) {
            if (holdQueue.contains(entity)) {
                signal(entity, resumePriority)
            }
        }
    }

    /** Use this to signal a specific entity to move in its process.
     *  The entity removes itself from the waiting condition.
     *  @param entity the entity to signal. The entity must be waiting for the signal.
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signal(entity: ProcessModel.Entity, resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        if (!holdQueue.contains(entity)) {
            return
        }
        // An entity already signalled stays in the hold queue until its resumed process leaves it,
        // so a repeated signal at the same instant finds it here. Signalling is naturally repeated,
        // so the repeat does nothing rather than schedule a second resume.
        if (entity.hasPendingResume) {
            return
        }
        val registration = myTimedHolds[entity]
        if (registration != null) {
            // The entity waits for the first of several things, one of which is this signal. The
            // claim releases the wait's other registrations and schedules the resume; a wait
            // already claimed (a second signal at this instant) does nothing.
            registration.group.claim(registration, Unit, ResumeSource.SIGNAL, resumePriority)
            return
        }
        entity.scheduleResumeProcess(0.0, resumePriority, ResumeSource.SIGNAL, "signal=$name")
    }

    /**
     *  If the queue is not empty, the first entity in the queue is signaled to proceed.
     */
    fun signalFirst(resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        if (holdQueue.isEmpty) {
            return
        }
        signal(holdQueue.first(), resumePriority)
    }

    /**
     *  If the queue is not empty, the last entity in the queue is signaled to proceed.
     */
    fun signalLast(resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        if (holdQueue.isEmpty) {
            return
        }
        signal(holdQueue.last(), resumePriority)
    }

    /** The entity removes itself from the waiting condition.
     *  If there are no entities, or the rank is out of range, then nothing happens (no signal)
     * @param rank the rank goes from 0 to size-1
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signal(rank: Int = 0, resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
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
    fun signal(range: IntRange, resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        // The entities at the ranks are found first: a signal can end a wait whose other
        // registrations leave this queue, which would shift the ranks while signalling.
        val targets = mutableListOf<ProcessModel.Entity>()
        for (i in range) {
            require(i >= 0) { "The rank of the desired entity must be >= 0" }
            if (i < holdQueue.size) targets.add(holdQueue[i])
        }
        signal(targets, resumePriority)
    }

    /**
     *  All the entities are signaled to proceed in their process. The entities
     *  remove themselves from the waiting condition.
     *
     *  @param resumePriority to use to order resumptions that occur at the same time
     */
    fun signalAll(resumePriority: Int = ProcessModel.RESUME_PRIORITY) {
        // Over a copy: a signal can end a wait whose other registrations leave this queue.
        signal(holdQueue.toList(), resumePriority)
    }

    /**
     *  Used by the entity to call back and remove itself after the signal
     */
    internal fun release(entity: ProcessModel.Entity, waitStats: Boolean = true) {
        holdQueue.remove(entity, waitStats)
    }
}
