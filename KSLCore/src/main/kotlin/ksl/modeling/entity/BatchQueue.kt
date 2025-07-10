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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.modeling.queue.Queue
import ksl.simulation.ModelElement


/**
 * This class is designed to hold entities that are waiting within a process for the
 * creation of a batch of entities via the waitedForBatch() suspending function.
 * The main purpose of this class is to hold the entities that are waiting to be batched in
 * a similar manner as a HoldQueue. The entities held within this queue are suspended until
 * the batch is formed.  The parameters of this class permit the specification
 * of a default batch size and a default predicate that will be used to form the batch
 * via the waitForBatch() suspending function; however, the user can supply their own
 * batch size and predicate at the suspending function call site.
 *
 * Notice that batches are formed from the **same** type (subclass) of BatchingEntity.
 * Thus, this queue is limited to holding and participating in the forming of batches of the
 * same type of entity.
 *
 * @param parent the element's parent model element
 * @param defaultBatchSize the default batch size for this queue
 * @param defaultPredicate the default predicate to use when selecting elements for the batch.
 * The default is an alwaysTrueFunction, such that every element in the queue satisfies the
 * selection criterion.
 * @param name the elements name
 * @param discipline the queue discipline for the queue
 * @param T the type of batching entity being held in the queue. A batching entity has
 * additional functionality to hold the formed batches.
 */
class BatchQueue<T : ProcessModel.BatchingEntity<T>> @JvmOverloads constructor(
    parent: ModelElement,
    defaultBatchSize: Int = 1,
    defaultPredicate: (T) -> Boolean = Companion::alwaysTrueFunction,
    name: String? = null,
    discipline: Discipline = Discipline.FIFO
) : Queue<T>(parent, name, discipline) {

    init {
        require(defaultBatchSize >= 1) { "The batch size must be >= 1" }
    }

    /**
     *  The default initial predicate for all replications. The batching
     *  predicate will be set to this default at the beginning of each replication
     *  to ensure that each replication starts with the same conditions.
     */
    var initialDefaultPredicate = defaultPredicate
        set(value) {
            require(model.isNotRunning) {"The model must not be running when changing the initial default predicate"}
            field = value
        }

    /**
     *  The predicate used to form batches during a replication
     */
    var batchingPredicate = defaultPredicate

    /**
     *  The default batch size for the queue for each replication. This
     *  property cannot be changed during a replication.
     */
    @set:KSLControl(
        controlType = ControlType.INTEGER,
        lowerBound = 1.0
    )
    var initialDefaultBatchSize = defaultBatchSize
        set(value) {
            require(value >= 1) { "The batch size must be >= 1" }
            require(model.isNotRunning) { "You cannot change the default batch size while the model is running" }
            field = value
        }

    /**
     *  The batch size used during a replication. Set to the initial default batch size
     *  when model initialization occurs to ensure that replications start with the same conditions.
     */
    var batchSize = defaultBatchSize
        set(value) {
            require(value >= 1) { "The batch size must be >= 1" }
            field = value
        }

    override fun initialize() {
        super.initialize()
        batchSize = initialDefaultBatchSize
        batchingPredicate = initialDefaultPredicate
    }

    /**
     *  The returned list represents a potential batch.
     *  The returned list may have less than [batchingSize] elements
     *  or may even be empty if a batch cannot be formed.
     *  The entities in the returned list are suspended.
     */
    internal fun selectBatch(
        batchingSize: Int,
        predicate: (T) -> Boolean
    ): List<T> {
        return this.myList.filter(predicate).take(batchingSize)
    }

    /** Removes the entity from the queue and tells it to resume its process
     *
     * @param entity the entity to remove from the queue
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    internal fun removeAndResume(
        entity: T,
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
    internal fun removeAndImmediateResume(
        entity: T,
        waitStats: Boolean = true
    ) {
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > HoldQueue : removeAndImmediateResume() : removing entity_id = ${entity.id}" }
        remove(entity, waitStats)
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > HoldQueue : removeAndImmediateResume() : after remove, before immediateResume()" }
        entity.immediateResume()
        ProcessModel.logger.trace { "r = ${model.currentReplicationNumber} : $time > HoldQueue : removeAndImmediateResume() : after remove, after immediateResume()" }
    }

    /**
     *  Removes and resumes all the entities waiting in the queue
     *
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     */
    internal fun removeAllAndResume(resumePriority: Int = ProcessModel.RESUME_PRIORITY, waitStats: Boolean = true) {
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
    internal fun removeAllWithImmediateResume(
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
    internal fun removeAndTerminate(
        entity: T,
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
    internal fun removeAllAndTerminate(waitStats: Boolean = false) {
        while (isNotEmpty) {
            val entity = peekNext()
            removeAndTerminate(entity!!, waitStats)
        }
    }

    companion object{

        val alwaysTruePredicate: (T : ProcessModel.Entity) -> Boolean = { true }

        @Suppress("UNUSED_PARAMETER")
        fun <T : ProcessModel.Entity> alwaysTrueFunction(input: T): Boolean {
            return true
        }

    }
}