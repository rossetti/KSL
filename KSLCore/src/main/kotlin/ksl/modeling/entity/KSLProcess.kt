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

import ksl.modeling.entity.ProcessModel.Entity
import ksl.modeling.queue.Queue
import ksl.modeling.spatial.*
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.coroutines.*

const val PRIORITY = KSLEvent.DEFAULT_PRIORITY

val alwaysTrue: (T: ModelElement.QObject) -> Boolean = { _ -> true }

/**
 * Used to exit (terminate) a currently executing ProcessCoroutine.
 *
 *  @param afterTermination a function to invoke after the process is successfully terminated
 */
class ProcessTerminatedException(
    val afterTermination: ((entity: ProcessModel.Entity) -> Unit)? = null,
    m: String = "Process Terminated!"
) : RuntimeException(m)

interface KSLProcess {
    val id: Int
    val name: String
    val isCreated: Boolean
    val isSuspended: Boolean
    val isTerminated: Boolean
    val isCompleted: Boolean
    val isRunning: Boolean
    val isActivated: Boolean
    val currentStateName: String
    val entity: ProcessModel.Entity
}

enum class SuspendType {
    NONE,
    SUSPEND,
    WAIT_FOR_SIGNAL,
    HOLD,
    WAIT_FOR_ITEMS,
    WAIT_FOR_ANY_ITEMS,
    WAIT_FOR_PROCESS,
    BLOCK_UNTIL_COMPLETION,
    SEND,
    SEIZE,
    DELAY,
    ACCESS,
    RIDE,
    EXIT
}

///**
// *  Because an entity that is executing a process cannot resume itself
// *  after suspending itself, we need a mechanism to allow the entity
// *  to register itself before suspending.
// *
// *  Allows entities to suspend themselves by providing a callback mechanism
// *  to allow the entity to register (attach) itself before suspending, with
// *  the assurance that the SuspensionObserver will (eventually) tell the entity
// *  to resume.
// */
//interface SuspensionObserver {
//
//    val name: String
//
//    fun attach(entity: ProcessModel.Entity)
//    fun detach(entity: ProcessModel.Entity)
//}

///**
// *  An abstraction that represents a general suspension point within a process. Suspensions are
// *  one-shot. That is, once resumed they cannot be used again unless passed through
// *  the suspend(suspension: Suspension) function for a KSLProcess.
// *
// *  To be useful, a suspension must be used as an argument of the suspend(suspension: Suspension) function for a KSLProcess.
// *  The main purpose of this class is to better facilitate process interaction coordination between
// *  entities that must suspend and resume each other to try to make the interaction less error-prone.
// *
// *  @param name the name of the suspension. Useful for debugging and
// *  tracking of suspensions. Defaults to null. If null, a useful name is created based on its identity.
// *  @param type the type of suspension. By default, this is the general type, SuspendType.SUSPEND.
// */
//class Suspension(
//    internal val entity: Entity,
//    name: String? = null,
//    val type: SuspendType = SuspendType.SUSPEND
//) : IdentityIfc by Identity(name) {
//
//    /**
//     * The entity that is suspended. This property is set by
//     * the suspend(suspension: Suspension) function before the entity suspends
//     */
//    private var suspendedEntity: Entity? = null
//
//    internal fun suspending(suspendingEntity: Entity){
//        require(suspendingEntity == entity) {"The suspension $this is not associated with the suspending entity: ${suspendingEntity.id}"}
//        isResumed = false
//        suspendedEntity = suspendingEntity
//    }
//
//    /**
//     *  True indicates that the suspension is suspending for the associated entity.
//     */
//    val isSuspending: Boolean
//        get() = suspendedEntity != null
//
//    /**
//     *  A suspension is once only. Once done it cannot be reused.
//     *  This flag indicates if the suspension has occurred and been resumed.
//     *  True means that the resumption has occurred. False means that
//     *  the resumption has not yet occurred.  This flag is set to false
//     *  internally by the suspend(suspension: Suspension) function when
//     *  the suspension is used.  Once the suspension has been resumed, this property
//     *  remains true, unless the suspension is passed again through the suspend(suspension: Suspension) function
//     */
//    var isResumed: Boolean = false
//        private set
//
//    /**
//     *  Causes the suspension to be resumed at the current time (i.e. without any delay).
//     *  Errors will result if the suspension is not associated with a suspending entity
//     *  via the suspend(suspension: Suspension) function or if the suspension has already
//     *  been resumed.
//     *
//     * @param priority the priority associated with the resume. Can be used
//     * to order resumptions that occur at the same time.
//     */
//    fun resume(priority: Int = KSLEvent.DEFAULT_PRIORITY) {
//        require(!isResumed) { "The suspension with label $label and type $type associated with entity ${entity.name} has already been resumed." }
//        require(suspendedEntity != null) { "The suspension with label $label and type $type associated with entity ${entity.name} is not associated with a suspended entity." }
//        suspendedEntity?.resumeProcess(priority = priority)
//        isResumed = true
//        suspendedEntity = null
//    }
//
//    override fun toString(): String {
//        val sb = StringBuilder()
//        sb.appendLine("Suspension: id = $id, label = $label, type = $type, done = $isResumed for entity (id = ${entity.id}, name = ${entity.name}")
//        return sb.toString()
//    }
//}

/**
 * KSLProcessBuilder provides the functionality for describing a process.  A process is an instance
 * of a coroutine that can be suspended and resumed.  The methods of the KSLProcessBuilder are the suspending
 * methods that are allowed within the process modeling paradigm of the KSL.  The various suspend methods have
 * an optional string name parameter to identify the name of the suspension point.  While not required for basic
 * modeling, identifying the suspension point can be useful for more advanced modeling involving the
 * cancellation or interrupting of a process.  The name can be used to determine which suspension point
 * is suspending the process when the process has many suspension points.
 */
@RestrictsSuspension
interface KSLProcessBuilder {

    val entity: ProcessModel.Entity

    /**
     *  Suspends the execution of the process.  Since the process cannot resume itself, the client
     *  must provide a mechanism that resumes the process. The most basic strategy would be to
     *  store a "globally" available reference to the entity that is suspending. The reference
     *  can then be used to resume the process via the entity's resumeProcess() function.
     *
     *  The method suspend() is used for implementing higher level functionality.
     *
     * @param suspensionName a name for the suspension point. Can be used to determine which suspension point
     * the entity is in when there are multiple suspension points, assuming that every suspension point
     * has a unique suspension name..
     */
    @Deprecated(
        "The general suspend function is error prone and may be replaced with other constructs in future releases",
        level = DeprecationLevel.WARNING
    )
    suspend fun suspend(suspensionName: String? = null)

    /**
     *  An abstraction that represents a general suspension point within a process. Suspensions are
     *  one-shot. That is, once resumed they cannot be used again unless passed through
     *  the suspend(suspension: Suspension) function for a KSLProcess.
     *
     *  To be useful, a suspension must be used as an argument of the suspend(suspension: Suspension) function for a KSLProcess.
     *  The main purpose of this function is to set up the suspension for being associated
     *  with the suspension of the current entity and then suspending the execution at the
     *  current suspend function until the associated suspension is resumed.
     *
     *  @param suspension the suspension to be associated with this suspend call
     */
    suspend fun suspend(suspension: Entity.Suspension)

    /** Causes the current process to suspend (immediately) until the blockage has been completed.
     *  If the blockage is active, the entity will be suspended until the blockage is ended.
     *  If the blockage is not active, then no suspension occurs. Some other entity
     *  must cause the blockage to be completed. An entity cannot block itself.
     *
     * @param blockage the blockage to wait for
     * @param queue an optional queue to hold the entity while it is blocked.
     *  @param suspensionName the name of the waitFor. can be used to identify which waitFor the entity is experiencing if there
     *   are more than one waitFor suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun waitFor(
        blockage: Entity.Blockage,
        queue: Queue<Entity>? = null,
        suspensionName: String? = null
    )

    /** Causes the blockage to be active. The blockage can only be started by
     * the entity that created it.
     *
     *  @param blockage The blockage to start
     */
    fun startBlockage(blockage: Entity.Blockage)

    /** Causes the blockage to be cleared and any entities suspended because of the blockage
     * to be resumed. The blockage can only be cleared by the entity that created it.
     *
     *  @param blockage the blockage to clear
     *  @param priority the priority for the resumption of suspended entities associated
     *  with the blockage
     */
    fun clearBlockage(blockage: Entity.Blockage, priority: Int = KSLEvent.DEFAULT_PRIORITY)

    /** Causes the current process to suspend (immediately) until the specified process has run to completion.
     *  This is like run blocking.  It activates the specified process and then waits for it
     *  to complete before proceeding. Since the current process suspends (immediately) the time until
     *  activation of the specified process will be included in the total time until the specified
     *  process completes.
     *
     * @param process the process to start for an entity. The supplied process must be in the created state.
     * @param timeUntilActivation the time until the start of the process being activated.
     * @param priority the priority associated with the event to activate the process
     *  @param suspensionName the name of the waitFor. can be used to identify which waitFor the entity is experiencing if there
     *   are more than one waitFor suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun waitFor(
        process: KSLProcess,
        timeUntilActivation: Double = 0.0,
        priority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes the process to halt, waiting for the signal to be announced.  Some other process/event
     *  is necessary to cause the signal. The entities stop waiting for the signal and resume their
     *  processes when signaled.
     *
     *  @param signal a general indicator for controlling the process
     *  @param waitPriority a priority indicator to inform ordering when there is more than one process waiting for
     *  the same signal
     *  @param waitStats Indicates whether waiting time statistics should be
     * collected on waiting items, true means collect statistics
     *  @param suspensionName the name of the waitFor. can be used to identify which waitFor the entity is experiencing if there
     *   are more than one waitFor suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun waitFor(
        signal: Signal,
        waitPriority: Int = PRIORITY,
        waitStats: Boolean = true,
        suspensionName: String? = null
    )

    /**
     *  Causes the current process to block until the specified process completes. This function does not
     *  activate the specified process. The specified process must have been previously activated. If the specified process has
     *  already completed, then no blocking occurs.  In other words, the call immediately returns.
     *  If the specified process has terminated, then an exception occurs.
     *
     *  @param process the process to block for. The supplied process must not be terminated and cannot be
     *  the same as the current process.
     *  @param resumptionPriority a priority indicator to inform ordering when there is more than one process blocking
     *  for the specified process
     *  @param suspensionName the name of the blockUntilCompletion. can be used to identify which blockUntilCompletion the entity is experiencing if there
     *   are more than one blockUntilCompletion suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun blockUntilCompleted(
        process: KSLProcess,
        resumptionPriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes the current process to block until all the specified processes completes. This function does not
     *  activate the specified processes. The specified processes must have been previously activated. If all specified processes have
     *  already completed, then no blocking occurs.  In other words, the call immediately returns.
     *  If any of the specified processes have terminated, then an exception occurs.
     *
     *  @param processes the processes to block for. The supplied processes must not be terminated and cannot be
     *  the same as the current process.
     *  @param resumptionPriority a priority indicator to inform ordering when there is more than one process blocking
     *  for the specified processes
     *  @param suspensionName the name of the blockUntilCompletion. can be used to identify which blockUntilCompletion the entity is experiencing if there
     *   are more than one blockUntilCompletion suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun blockUntilAllCompleted(
        processes: Set<KSLProcess>,
        resumptionPriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes the process to hold indefinitely within the supplied queue.  Some other process or event
     *  is responsible for removing the entities and causing them to proceed with their processes
     *  NOTE:  The entity is not signaled to resume its process unless you signal it.  The
     *  entity cannot remove itself.  Some other construct must do the removal and resumption.
     *
     *  @param queue a queue to hold the waiting entities
     *  @param priority a priority for the queue discipline if needed
     *  @param suspensionName the name of the hold. can be used to identify which hold the entity is experiencing if there
     *   are more than one hold suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun hold(queue: HoldQueue, priority: Int = PRIORITY, suspensionName: String? = null)

    /**
     * This method will block (suspend) until the required number of items that meet the criteria
     * become available within the blocking queue.
     *
     * @param blockingQ the blocking queue channel that has the items
     * @param amount the number of items needed from the blocking queue that match the criteria
     * @param predicate a functional predicate that tests items in the queue for the criteria
     * @param blockingPriority the priority associated with the entity if it has to wait to receive
     * @param suspensionName the name of the suspension point. can be used to identify which receive suspension point
     * the entity is experiencing if there are more than one receive suspension points within the process.
     * The user is responsible for uniqueness.
     */
    suspend fun <T : ModelElement.QObject> waitForItems(
        blockingQ: BlockingQueue<T>,
        amount: Int = 1,
        predicate: (T) -> Boolean = alwaysTrue,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    ): List<T>

    /**
     * Permits simpler calling syntax when using a blocking queue within
     * a KSLProcess
     * This method will block (suspend) until the required number of items that meet the criteria
     * become available within the blocking queue.
     *
     * @param amount the number of items needed from the blocking queue that match the criteria
     * @param predicate a functional predicate that tests items in the queue for the criteria
     * @param blockingPriority the priority associated with the entity if it has to wait to receive
     * @param suspensionName the name of the suspension point. can be used to identify which receive suspension point
     * the entity is experiencing if there are more than one receive suspension points within the process.
     * The user is responsible for uniqueness.
     */
    suspend fun <T : ModelElement.QObject> BlockingQueue<T>.waitFor(
        amount: Int = 1,
        predicate: (T) -> Boolean = alwaysTrue,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    ): List<T> {
        return waitForItems(this, amount, predicate, blockingPriority, suspensionName)
    }

    /**
     * This method will block (suspend) until at least 1 item that meets the criteria is available
     * within the blocking queue.  If more than one is available, all items meeting the criteria will
     * be returned.
     *
     * @param blockingQ the blocking queue channel that has the items
     * @param predicate a functional predicate that tests items in the queue for the criteria
     * @param blockingPriority the priority associated with the entity if it has to wait to receive
     * @param suspensionName the name of the suspension point. can be used to identify which receive suspension point
     * the entity is experiencing if there are more than one receive suspension points within the process.
     * The user is responsible for uniqueness.
     */
    suspend fun <T : ModelElement.QObject> waitForAnyItems(
        blockingQ: BlockingQueue<T>,
        predicate: (T) -> Boolean,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    ): List<T>

    /**
     * Permits simpler calling syntax when using a blocking queue within a KSLProcess
     * This method will block (suspend) until at least 1 item that meets the criteria is available
     * within the blocking queue.  If more than one is available, all items meeting the criteria will
     * be returned.
     *
     * @param predicate a functional predicate that tests items in the queue for the criteria
     * @param blockingPriority the priority associated with the entity if it has to wait to receive
     * @param suspensionName the name of the suspension point. can be used to identify which receive suspension point
     * the entity is experiencing if there are more than one receive suspension points within the process.
     * The user is responsible for uniqueness.
     */
    suspend fun <T : ModelElement.QObject> BlockingQueue<T>.waitForAny(
        predicate: (T) -> Boolean,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    ): List<T> {
        return waitForAnyItems(this, predicate, blockingPriority, suspensionName)
    }

    /** This method will block (suspend) if the blocking queue is full. That is, if the blocking queue
     * has reached its capacity.  When space for the item
     * becomes available, then the item is placed within the blocking queue.
     *
     * @param item the item being placed into the blocking queue
     * @param blockingQ the blocking queue channel that holds the items
     * @param blockingPriority the priority for the entity that must wait to send if the blocking queue is full
     * @param suspensionName the name of the possible suspension point. This can be used by the entity to
     * determine which send blocking it might be experiencing when blocked.  It is up to the client to
     * ensure the name is meaningful and possibly unique.
     */
    suspend fun <T : ModelElement.QObject> send(
        item: T,
        blockingQ: BlockingQueue<T>,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /** This method will block (suspend) if the blocking queue is full. That is, if the blocking queue
     * has reached its capacity.  When space for the item
     * becomes available, then the item is placed within the blocking queue.
     *
     * @param collection the items being placed into the blocking queue
     * @param blockingQ the blocking queue channel that holds the items
     * @param blockingPriority the priority for the entity that must wait to send if the blocking queue is full
     * @param suspensionName the name of the possible suspension point. This can be used by the entity to
     * determine which send blocking it might be experiencing when blocked.  It is up to the client to
     * ensure the name is meaningful and possibly unique.
     */
    suspend fun <T : ModelElement.QObject> sendItems(
        collection: Collection<T>,
        blockingQ: BlockingQueue<T>,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        for (item in collection) {
            send(item, blockingQ, blockingPriority, suspensionName)
        }
    }

    /**
     * Permits simpler calling syntax when using a blocking queue within a KSLProcess
     * This method will block (suspend) if the blocking queue is full. That is, if the blocking queue
     * has reached its capacity.  When space for the item
     * becomes available, then the item is placed within the blocking queue.
     *
     * @param item the item being placed into the blocking queue
     * @param blockingPriority the priority for the entity that must wait to send if the blocking queue is full
     * @param suspensionName the name of the possible suspension point. This can be used by the entity to
     * determine which send blocking it might be experiencing when blocked.  It is up to the client to
     * ensure the name is meaningful and possibly unique.
     */
    suspend fun <T : ModelElement.QObject> BlockingQueue<T>.send(
        item: T,
        blockingPriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        send(item, this, blockingPriority, suspensionName)
    }

    /**
     *  Requests a number of units of the indicated resource.
     *
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resource: Resource,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        queue: RequestQ,
        suspensionName: String? = null
    ): Allocation

    /**
     *  Requests a number of units of the indicated resource.
     *  The queue that will hold the entity is internal to the resource.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resource: ResourceWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): Allocation {
        return seize(resource, amountNeeded, seizePriority, resource.myWaitingQ, suspensionName)
    }

    /**
     *  Requests a number of units of the indicated movable resource.
     *
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resource: MovableResource,
        seizePriority: Int = PRIORITY,
        queue: RequestQ,
        suspensionName: String? = null
    ): Allocation {
        return seize(resource, amountNeeded = 1, seizePriority, queue, suspensionName)
    }

    /**
     *  Requests a number of units of the indicated movable resource.
     *
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resource: MovableResourceWithQ,
        seizePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): Allocation {
        return seize(resource, amountNeeded = 1, seizePriority, resource.myWaitingQ, suspensionName)
    }

    /**
     *  Requests a number of units from the indicated pool of resources
     *
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resourcePool the resource pool from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resourcePool: ResourcePool,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        queue: RequestQ,
        suspensionName: String? = null
    ): ResourcePoolAllocation

    /**
     *  Requests a number of units from the indicated pool of resources
     *  The queue that will hold the entity is internal to the resource pool.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resourcePool the resource pool from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resourcePool: ResourcePoolWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): ResourcePoolAllocation {
        return seize(resourcePool, amountNeeded, seizePriority, resourcePool.myWaitingQ, suspensionName)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     */
    suspend fun use(
        resource: Resource,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: Double,
        delayPriority: Int = PRIORITY,
        queue: RequestQ
    ) {
        val a = seize(resource, amountNeeded, seizePriority, queue)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *  The queue that will hold the entity is internal to the resource.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun use(
        resource: ResourceWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: Double,
        delayPriority: Int = PRIORITY,
    ) {
        val a = seize(resource, amountNeeded, seizePriority, resource.myWaitingQ)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *  The queue that will hold the entity is internal to the resource.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun use(
        resource: ResourceWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = PRIORITY,
    ) {
        val a = seize(resource, amountNeeded, seizePriority)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resourcePool the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     */
    suspend fun use(
        resourcePool: ResourcePool,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: Double,
        delayPriority: Int = PRIORITY,
        queue: RequestQ
    ) {
        val a = seize(resourcePool, amountNeeded, seizePriority, queue)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *  The queue that will hold the entity is internal to the resource pool.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resourcePool the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun use(
        resourcePool: ResourcePoolWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: Double,
        delayPriority: Int = PRIORITY,
    ) {
        val a = seize(resourcePool, amountNeeded, seizePriority)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *  The queue that will hold the entity is internal to the resource pool.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resourcePool the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun use(
        resourcePool: ResourcePoolWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = PRIORITY,
    ) {
        val a = seize(resourcePool, amountNeeded, seizePriority)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release()
     *
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
     */
    suspend fun use(
        resource: Resource,
        amountNeeded: Int = 1,
        seizePriority: Int = PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = PRIORITY,
        queue: RequestQ
    ) {
        val a = seize(resource, amountNeeded, seizePriority, queue)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Causes the process to delay (suspend execution) for the specified amount of time.
     *
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun delay(
        delayDuration: Double,
        delayPriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun delay(
        delayDuration: GetValueIfc,
        delayPriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        delay(delayDuration.value, delayPriority, suspensionName)
    }

    /**
     *  Causes movement of the entity from the specified location to the specified location at
     *  the supplied velocity.  If the entity is not currently at [fromLoc] then its
     *  current location is quietly set to [fromLoc], without movement before the move commences.
     *  To move directly from the current location, use moveTo().
     *  @param fromLoc, the location from which the entity is supposed to move
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun move(
        fromLoc: LocationIfc,
        toLoc: LocationIfc,
        velocity: Double = entity.velocity.value,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the entity from the specified location to the specified location at
     *  the supplied velocity.  If the entity is not currently at [fromLoc] then its
     *  current location is quietly set to [fromLoc], without movement before the move commences.
     *  To move directly from the current location, use moveTo().
     *  @param fromLoc, the location from which the entity is supposed to move
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun move(
        fromLoc: LocationIfc,
        toLoc: LocationIfc,
        velocity: GetValueIfc = entity.velocity,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        move(fromLoc, toLoc, velocity.value, movePriority, suspensionName)
    }

    /**
     *  Causes movement of the spatial element from its current location to the specified location at
     *  the supplied velocity.
     *  @param spatialElement, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun move(
        spatialElement: SpatialElementIfc,
        toLoc: LocationIfc,
        velocity: Double,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the movable resource from its current location to the specified location at
     *  the supplied velocity.
     *  @param movableResource, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun move(
        movableResource: MovableResource,
        toLoc: LocationIfc,
        velocity: Double = movableResource.velocity.value,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        movableResource.isMovingEmpty = true
        move(movableResource as SpatialElementIfc, toLoc, velocity, movePriority, suspensionName)
        movableResource.isMovingEmpty = false
    }

    /**
     *  Causes movement of the movable resource from its current location to the specified location at
     *  the supplied velocity.
     *  @param movableResourceWithQ, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun move(
        movableResourceWithQ: MovableResourceWithQ,
        toLoc: LocationIfc,
        velocity: Double = movableResourceWithQ.velocity.value,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        movableResourceWithQ.isMovingEmpty = true
        move(movableResourceWithQ as SpatialElementIfc, toLoc, velocity, movePriority, suspensionName)
        movableResourceWithQ.isMovingEmpty = false
    }

    /**
     *  Causes movement of the entity and the spatial element from the current location to the specified location at
     *  the supplied velocity. The entity and the spatial element must be at the same location to start the movement.
     *  If not specified, the default velocity of the spatial element is used for the movement.
     *  @param spatialElement, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun moveWith(
        spatialElement: SpatialElementIfc,
        toLoc: LocationIfc,
        velocity: Double,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the entity and the movable resource from the current location to the specified location at
     *  the supplied velocity. The entity and the movable resource must be at the same location to start the movement.
     *  If not specified, the default velocity of the spatial element is used for the movement.
     *  @param movableResource, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun moveWith(
        movableResource: MovableResource,
        toLoc: LocationIfc,
        velocity: Double = movableResource.velocity.value,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the entity and the movable resource from the current location to the specified location at
     *  the supplied velocity. The entity and the movable resource must be at the same location to start the movement.
     *  If not specified, the default velocity of the spatial element is used for the movement.
     *  @param movableResourceWithQ, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun moveWith(
        movableResourceWithQ: MovableResourceWithQ,
        toLoc: LocationIfc,
        velocity: Double = movableResourceWithQ.velocity.value,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes transport of the entity via the movable resource from the entity's current location to the specified location at
     *  the supplied velocities.
     *  If not specified, the default velocity of the movable resource is used for the movement.
     *  @param movableResource, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param transportQ the queue that the entity waits in if the resource is busy
     *  @param requestPriority, a priority can be used to determine the order of events for
     *  requests for transport
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     */
    suspend fun transportWith(
        movableResource: MovableResource,
        toLoc: LocationIfc,
        emptyVelocity: Double = movableResource.velocity.value,
        transportVelocity: Double = movableResource.velocity.value,
        transportQ: RequestQ,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = PRIORITY,
        emptyMovePriority: Int = PRIORITY,
        loadingPriority: Int = PRIORITY,
        transportPriority: Int = PRIORITY,
        unLoadingPriority: Int = PRIORITY
    ) {
        val a = seize(movableResource, seizePriority = requestPriority, queue = transportQ)
        move(movableResource, entity.currentLocation, emptyVelocity, emptyMovePriority)
        if (loadingDelay != ConstantRV.ZERO) {
            delay(loadingDelay, loadingPriority)
        }
        moveWith(movableResource, toLoc, transportVelocity, transportPriority)
        if (unLoadingDelay != ConstantRV.ZERO) {
            delay(unLoadingDelay, unLoadingPriority)
        }
        if (movableResource.homeBase != null) {
            move(movableResource, movableResource.homeBase!!, emptyVelocity, emptyMovePriority)
        }
        release(a)
    }

    /**
     *  Causes transport of the entity via the movable resource from the entity's current location to the specified location at
     *  the supplied velocities.
     *  If not specified, the default velocity of the movable resource is used for the movement.
     *  @param movableResourceWithQ, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param requestPriority, a priority can be used to determine the order of events for
     *  requests for transport
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     */
    suspend fun transportWith(
        movableResourceWithQ: MovableResourceWithQ,
        toLoc: LocationIfc,
        emptyVelocity: Double = movableResourceWithQ.velocity.value,
        transportVelocity: Double = movableResourceWithQ.velocity.value,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = PRIORITY,
        emptyMovePriority: Int = PRIORITY,
        loadingPriority: Int = PRIORITY,
        transportPriority: Int = PRIORITY,
        unLoadingPriority: Int = PRIORITY
    ) {
        val a = seize(movableResourceWithQ, seizePriority = requestPriority)
        move(movableResourceWithQ, entity.currentLocation, emptyVelocity, emptyMovePriority)
        if (loadingDelay != ConstantRV.ZERO) {
            delay(loadingDelay, loadingPriority)
        }
        moveWith(movableResourceWithQ, toLoc, transportVelocity, transportPriority)
        if (unLoadingDelay != ConstantRV.ZERO) {
            delay(unLoadingDelay, unLoadingPriority)
        }
        if (movableResourceWithQ.homeBase != null) {
            move(movableResourceWithQ, movableResourceWithQ.homeBase!!, emptyVelocity, emptyMovePriority)
        }
        release(a)
    }

    /**
     *  Causes transport of the entity via a movable resource within the fleet from the entity's current location to the specified location at
     *  the supplied velocities.
     *  If not specified, the default velocity of the movable resource is used for the movement.
     *  @param fleet, the pool of movable resources
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param requestPriority, a priority can be used to determine the order of events for
     *  requests for transport
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     */
    suspend fun transportWith(
        fleet: MovableResourcePool,
        toLoc: LocationIfc,
        emptyVelocity: Double = fleet.velocity.value,
        transportVelocity: Double = fleet.velocity.value,
        transportQ: RequestQ,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = PRIORITY,
        emptyMovePriority: Int = PRIORITY,
        loadingPriority: Int = PRIORITY,
        transportPriority: Int = PRIORITY,
        unLoadingPriority: Int = PRIORITY
    ) {
        val a = seize(fleet, seizePriority = requestPriority, queue = transportQ)
        // must be 1 allocation for 1 unit seized
        val movableResource = a.allocations[0].resource as MovableResource
        move(movableResource, entity.currentLocation, emptyVelocity, emptyMovePriority)
        if (loadingDelay != ConstantRV.ZERO) {
            delay(loadingDelay, loadingPriority)
        }
        moveWith(movableResource, toLoc, transportVelocity, transportPriority)
        if (unLoadingDelay != ConstantRV.ZERO) {
            delay(unLoadingDelay, unLoadingPriority)
        }
        if (movableResource.homeBase != null) {
            move(movableResource, movableResource.homeBase!!, emptyVelocity, emptyMovePriority)
        }
        release(a)
    }

    /**
     *  Causes transport of the entity via a movable resource within the fleet from the entity's current location to the specified location at
     *  the supplied velocities.
     *  If not specified, the default velocity of the movable resource is used for the movement.
     *  @param fleet, the pool of movable resources
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param requestPriority, a priority can be used to determine the order of events for
     *  requests for transport
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     */
    suspend fun transportWith(
        fleet: MovableResourcePoolWithQ,
        toLoc: LocationIfc,
        emptyVelocity: Double = fleet.velocity.value,
        transportVelocity: Double = fleet.velocity.value,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = PRIORITY,
        emptyMovePriority: Int = PRIORITY,
        loadingPriority: Int = PRIORITY,
        transportPriority: Int = PRIORITY,
        unLoadingPriority: Int = PRIORITY
    ) {
        val a = seize(fleet, seizePriority = requestPriority, queue = fleet.myWaitingQ)
        // must be 1 allocation for 1 unit seized because there is only 1 unit in each
        val movableResource = a.allocations[0].resource as MovableResource
        move(movableResource, entity.currentLocation, emptyVelocity, emptyMovePriority)
        if (loadingDelay != ConstantRV.ZERO) {
            delay(loadingDelay, loadingPriority)
        }
        moveWith(movableResource, toLoc, transportVelocity, transportPriority)
        if (unLoadingDelay != ConstantRV.ZERO) {
            delay(unLoadingDelay, unLoadingPriority)
        }
        if (movableResource.homeBase != null) {
            move(movableResource, movableResource.homeBase!!, emptyVelocity, emptyMovePriority)
        }
        release(a)
    }

    /**
     *  Causes movement of the entity from its current location to the specified location at
     *  the supplied velocity.
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun moveTo(
        toLoc: LocationIfc,
        velocity: Double,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        move(entity.currentLocation, toLoc, velocity, movePriority, suspensionName)
    }

    /**
     *  Causes movement of the entity from its current location to the specified location at
     *  the supplied velocity.
     *  @param toLoc the location to which the entity is supposed to move
     *  @param velocity the velocity associated with the movement
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun moveTo(
        toLoc: LocationIfc,
        velocity: GetValueIfc = entity.velocity,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        moveTo(toLoc, velocity.value, movePriority, suspensionName)
    }

    /**
     *  Causes movement of the entity from its current location to the specified location
     *  using the entity's default velocity.
     *
     *  @param toLoc the location to which the entity is supposed to move
     *  @param movePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun moveTo(
        toLoc: LocationIfc,
        movePriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        move(entity.currentLocation, toLoc, entity.velocity, movePriority, suspensionName)
    }

    /**
     *  Releases the allocation of the resource
     *
     *  @param allocation represents an allocation of so many units of a resource to an entity
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun release(allocation: Allocation, releasePriority: Int = PRIORITY)

    /**
     *  Releases ANY(ALL) allocations related to the resource that are allocated
     *  to the entity currently executing this process
     *
     *  @param resource the resource to release
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun release(resource: Resource, releasePriority: Int = PRIORITY)

    /**
     *  Releases ALL the resources that the entity has currently allocated to it
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun releaseAllResources(releasePriority: Int = PRIORITY)

    /**
     * Releases the allocations associated with using a ResourcePool
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun release(pooledAllocation: ResourcePoolAllocation, releasePriority: Int = PRIORITY)

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted for the specified interrupt time.  After the interruption
     * time has elapsed, the process experiencing the original delay continues a delay for the specified
     * post interruption delay time.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptTime the length of time associated with the interrupt
     * @param interruptPriority the priority associated with the interrupt event
     * @param postInterruptDelayTime the time to be associated with the original delay, after the
     * interruption is completed
     */
    suspend fun interruptDelay(
        process: KSLProcess,
        delayName: String,
        interruptTime: Double,
        interruptPriority: Int = PRIORITY,
        postInterruptDelayTime: Double
    )

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted for the specified interrupt time.  After the interruption
     * time has elapsed, the process experiencing the original delay continues a delay for the specified
     * post interruption delay time.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptTime the length of time associated with the interrupt
     * @param interruptPriority the priority associated with the interrupt event
     * @param postInterruptDelayTime the time to be associated with the original delay, after the
     * interruption is completed
     */
    suspend fun interruptDelay(
        process: KSLProcess,
        delayName: String,
        interruptTime: GetValueIfc,
        interruptPriority: Int = PRIORITY,
        postInterruptDelayTime: GetValueIfc
    ) {
        interruptDelay(process, delayName, interruptTime.value, interruptPriority, postInterruptDelayTime.value)
    }

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted for the specified interrupt time.  After the interruption
     * time has elapsed, the process experiencing the original delay continues a delay using its original
     * delay time.  That is, it restarts its original delay.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptTime the length of time associated with the interrupt
     * @param interruptPriority the priority associated with the interrupt event
     */
    suspend fun interruptDelayAndRestart(
        process: KSLProcess,
        delayName: String,
        interruptTime: Double,
        interruptPriority: Int,
    )

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted for the specified interrupt time.  After the interruption
     * time has elapsed, the process experiencing the original delay continues a delay using its original
     * delay time.  That is, it restarts its original delay.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptTime the length of time associated with the interrupt
     * @param interruptPriority the priority associated with the interrupt event
     */
    suspend fun interruptDelayAndRestart(
        process: KSLProcess,
        delayName: String,
        interruptTime: GetValueIfc,
        interruptPriority: Int,
    ) {
        interruptDelayAndRestart(process, delayName, interruptTime.value, interruptPriority)
    }

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted for the specified interrupt time.  After the interruption
     * time has elapsed, the process experiencing the original delay continues with the post
     * interruption time being equal to the time remaining on the original delay at the time
     * of the interruption
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptTime the length of time associated with the interrupt
     * @param interruptPriority the priority associated with the interrupt event
     */
    suspend fun interruptDelayAndContinue(
        process: KSLProcess,
        delayName: String,
        interruptTime: Double,
        interruptPriority: Int,
    )

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted for the specified interrupt time.  After the interruption
     * time has elapsed, the process experiencing the original delay continues with the post
     * interruption time being equal to the time remaining on the original delay at the time
     * of the interruption
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptTime the length of time associated with the interrupt
     * @param interruptPriority the priority associated with the interrupt event
     */
    suspend fun interruptDelayAndContinue(
        process: KSLProcess,
        delayName: String,
        interruptTime: GetValueIfc,
        interruptPriority: Int,
    ) {
        interruptDelayAndContinue(process, delayName, interruptTime.value, interruptPriority)
    }

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted while the interrupting process executes to completion.
     * After the interruption process has completed, the process experiencing the original delay
     * continues a delay for the specified post interruption delay time.  Note that this functionality
     * requires three processes 1) to have the interruptDelay() call, 2) the process being interrupted,
     * and 3) the process that is used as the interruption.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptingProcess the process that will interrupt the delay
     * @param interruptPriority the priority associated with the interrupt event
     * @param postInterruptDelayTime the time to be associated with the original delay, after the
     * interruption is completed
     */
    suspend fun interruptDelayWithProcess(
        process: KSLProcess,
        delayName: String,
        interruptingProcess: KSLProcess,
        interruptPriority: Int = PRIORITY,
        postInterruptDelayTime: Double
    )

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted while the interrupting process executes to completion.
     * After the interruption process has completed, the process experiencing the original delay
     * continues a delay for the specified post interruption delay time.  Note that this functionality
     * requires three processes 1) to have the interruptDelay() call, 2) the process being interrupted,
     * and 3) the process that is used as the interruption.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptingProcess the process that will interrupt the delay
     * @param interruptPriority the priority associated with the interrupt event
     * @param postInterruptDelayTime the time to be associated with the original delay, after the
     * interruption is completed
     */
    suspend fun interruptDelayWithProcess(
        process: KSLProcess,
        delayName: String,
        interruptingProcess: KSLProcess,
        interruptPriority: Int = PRIORITY,
        postInterruptDelayTime: GetValueIfc
    ) {
        interruptDelayWithProcess(
            process,
            delayName,
            interruptingProcess,
            interruptPriority,
            postInterruptDelayTime.value
        )
    }

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted while the interrupting process executes to completion.
     * After the interruption process has completed, the process experiencing the original delay
     * continues a delay for the full original delay time.  Note that this functionality
     * requires three processes 1) to have the interruptDelay() call, 2) the process being interrupted,
     * and 3) the process that is used as the interruption.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptingProcess the process that will interrupt the delay
     * @param interruptPriority the priority associated with the interrupt event
     */
    suspend fun interruptDelayWithProcessAndRestart(
        process: KSLProcess,
        delayName: String,
        interruptingProcess: KSLProcess,
        interruptPriority: Int = PRIORITY,
    )

    /**
     * This method allows a process to interrupt another process while that process is
     * experiencing a delay.  If the supplied process is not currently experiencing the named
     * delay, then nothing happens.  That is, the interrupt is ignored and the method just returns
     * at the current time without suspending.  If the supplied process is experiencing the named
     * delay, then its delay is interrupted while the interrupting process executes to completion.
     * After the interruption process has completed, the process experiencing the original delay
     * continues a delay for time remaining on its original delay.  Note that this functionality
     * requires three processes 1) to have the interruptDelay() call, 2) the process being interrupted,
     * and 3) the process that is used as the interruption.
     *
     * @param process the process with the delay that may be interrupted
     * @param delayName the name of the delay within the process that needs to be interrupted
     * @param interruptingProcess the process that will interrupt the delay
     * @param interruptPriority the priority associated with the interrupt event
     */
    suspend fun interruptDelayWithProcessAndContinue(
        process: KSLProcess,
        delayName: String,
        interruptingProcess: KSLProcess,
        interruptPriority: Int = PRIORITY,
    )

    /**
     * This suspending function requests the number of cells indicated at the entry location of the conveyor.
     * If the number of cells are not immediately available the process is suspended until
     * the number of cells can be allocated (in full).  The request for the cells will
     * wait for the allocation in the queue associated with the start of the segment associated
     * with the entry location of the conveyor. After this suspending function
     * returns, the entity holds the cells in the returned cell allocation, but the entity is
     * not on the conveyor. The entity can then decide to ride on the conveyor using the cell allocation or
     * release the cell allocation by exiting the conveyor without riding. The behavior of the
     * conveyor during access is governed by the type of conveyor.  A blockage occurs at the
     * entry point of the segment while the entity has the allocated cells and before exiting or riding.
     *
     * @param conveyor the conveyor to access
     * @param entryLocation the location on the conveyor at which the cells are requested
     * @param numCellsNeeded the number of cells needed (requested)
     * @param requestPriority the priority of the access. If there are multiple entities that
     * access the conveyor at the same time this priority determines which goes first. Similar to
     * the seize resource priority.
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return a representation of the allocated cells on the conveyor. The user should use this as a ticket
     * to ride on the conveyor and to eventually release the allocated cells by exiting the conveyor.
     */
    suspend fun requestConveyor(
        conveyor: Conveyor,
        entryLocation: IdentityIfc,
        numCellsNeeded: Int = 1,
        requestPriority: Int = PRIORITY,
        requestResumePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): ConveyorRequestIfc

    /** This suspending function causes the entity to be associated with an item that occupies the allocated
     * cells on the conveyor. The item will move on the conveyor until it reaches the supplied destination.
     * After this suspending function returns, the item associated with the entity will be occupying the
     * cells it requires at the exit location of the segment associated with the destination. The item
     * will remain on the conveyor until the entity indicates that the cells are to be released by using
     * the exit function. The behavior of the conveyor during the ride and when the item reaches its
     * destination is governed by the type of conveyor. A blockage occurs at the destination location of the segment
     * while the entity occupies the final cells before exiting or riding again.  If the destination implements
     * the LocationIfc then the current location property of the entity will be updated to this value at the
     * completion of the ride.
     *
     * @param conveyorRequest the permission to ride on the conveyor in the form of a valid cell allocation
     * @param destination the location to which to ride
     * @param ridePriority the priority associated with ride request
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the time that it took to reach the destination. This may include time spent on the conveyor waiting
     * due to blockages and the time moving through cells on the conveyor during the ride
     */
    suspend fun rideConveyor(
        conveyorRequest: ConveyorRequestIfc,
        destination: IdentityIfc,
        ridePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): Double

    /** This suspending function causes the entity to be associated with an item that occupies the allocated
     * cells on the conveyor. The item will move on the conveyor until it reaches the supplied destination.
     * After this suspending function returns, the item associated with the entity will be occupying the
     * cells it requires at the exit location of the segment associated with the destination. The item
     * will remain on the conveyor until the entity indicates that the cells are to be released by using
     * the exit function. The behavior of the conveyor during the ride and when the item reaches its
     * destination is governed by the type of conveyor. A blockage occurs at the destination location of the segment
     * while the entity occupies the final cells before exiting or riding again. If the destination implements
     * the LocationIfc then the current location property of the entity will be updated to this value at the
     * completion of the ride.
     *
     * @param destination the location to which to ride
     * @param ridePriority the priority associated with ride request
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the time that it took to reach the destination. This may include time spent on the conveyor waiting
     * due to blockages and the time moving through cells on the conveyor during the ride
     */
    suspend fun rideConveyor(
        destination: IdentityIfc,
        ridePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): Double {
        require(entity.conveyorRequest != null) { "The entity attempted to ride without using the conveyor." }
        return rideConveyor(entity.conveyorRequest!!, destination, ridePriority, suspensionName)
    }

    /** This suspending function causes the item associated with the allocated cells to exit the conveyor.
     * If there is no item associated with the allocated cells, the cells are immediately released without
     * a time delay.  If there is an item occupying the associated cells there will be a delay while
     * the item moves through the deallocated cells and then the cells are deallocated.  After
     * exiting the conveyor, the cell allocation is deallocated and cannot be used for further interaction
     * with the conveyor.
     *
     * @param conveyorRequest the cell allocation that will be released during the exiting process
     * @param exitPriority the priority associated with the exit
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the ride and contains information about
     * the origin point, the destination, etc.
     */
    suspend fun exitConveyor(
        conveyorRequest: ConveyorRequestIfc,
        exitPriority: Int = PRIORITY,
        suspensionName: String? = null
    )

    /** This suspending function causes the item associated with the allocated cells to exit the conveyor.
     * If there is no item associated with the allocated cells, the cells are immediately released without
     * a time delay.  If there is an item occupying the associated cells there will be a delay while
     * the item moves through the deallocated cells and then the cells are deallocated.  After
     * exiting the conveyor, the cell allocation is deallocated and cannot be used for further interaction
     * with the conveyor.
     *
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @param exitPriority the priority associated with the exit
     * @return the returned item encapsulates what happened during the ride and contains information about
     * the origin point, the destination, etc.
     */
    suspend fun exitConveyor(
        exitPriority: Int = PRIORITY,
        suspensionName: String? = null
    ) {
        require(entity.conveyorRequest != null) { "The entity attempted to ride without using the conveyor." }
        exitConveyor(entity.conveyorRequest!!, exitPriority, suspensionName)
    }

    /**
     * This suspending function combines requestConveyor(), rideConveyor(), and exit() into one suspending function.
     *
     * @param conveyor the conveyor to access
     * @param entryLocation the location on the conveyor at which the cells are requested
     * @param destination the location to which to ride
     * @param numCellsNeeded the number of cells needed (requested)
     * @param requestPriority the priority of the access. If there are multiple entities that
     * access the conveyor at the same time this priority determines which goes first. Similar to
     * the seize resource priority.
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the ride and contains information about
     * the origin point, the destination, etc.
     */
    suspend fun convey(
        conveyor: Conveyor,
        entryLocation: IdentityIfc,
        destination: IdentityIfc,
        numCellsNeeded: Int = 1,
        requestPriority: Int = PRIORITY,
        requestResumePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): ConveyorRequestIfc {
        val ca = requestConveyor(
            conveyor,
            entryLocation,
            numCellsNeeded,
            requestPriority,
            requestResumePriority,
            suspensionName
        )
        rideConveyor(ca, destination)
        exitConveyor(ca)
        return ca
    }

    /**
     * This suspending function combines requestConveyor(), rideConveyor(), and exit() into one suspending function.
     *
     * @param conveyor the conveyor to access
     * @param entryLocation the location on the conveyor at which the cells are requested
     * @param loadingTime the time that it takes to load onto the conveyor
     * @param destination the location to which to ride
     * @param unloadingTime the time that it takes to unload from the conveyor
     * @param numCellsNeeded the number of cells needed (requested)
     * @param requestPriority the priority of the access. If there are multiple entities that
     * access the conveyor at the same time this priority determines which goes first. Similar to
     * the seize resource priority.
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the ride and contains information about
     * the origin point, the destination, etc.
     */
    suspend fun convey(
        conveyor: Conveyor,
        entryLocation: IdentityIfc,
        loadingTime: Double = 0.0,
        destination: IdentityIfc,
        unloadingTime: Double = 0.0,
        numCellsNeeded: Int = 1,
        requestPriority: Int = PRIORITY,
        requestResumePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): ConveyorRequestIfc {
        val ca = requestConveyor(
            conveyor,
            entryLocation,
            numCellsNeeded,
            requestPriority,
            requestResumePriority,
            suspensionName
        )
        delay(loadingTime)
        rideConveyor(ca, destination)
        delay(unloadingTime)
        exitConveyor(ca)
        return ca
    }

    /**
     * This suspending function combines access(), ride(), and exit() into one suspending function.
     *
     * @param conveyor the conveyor to access
     * @param entryLocation the location on the conveyor at which the cells are requested
     * @param loadingTime the time that it takes to load onto the conveyor
     * @param destination the location to which to ride
     * @param unloadingTime the time that it takes to unload from the conveyor
     * @param numCellsNeeded the number of cells needed (requested)
     * @param requestPriority the priority of the access. If there are multiple entities that
     * access the conveyor at the same time this priority determines which goes first. Similar to
     * the seize resource priority.
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the ride and contains information about
     * the origin point, the destination, etc.
     */
    suspend fun convey(
        conveyor: Conveyor,
        entryLocation: IdentityIfc,
        loadingTime: GetValueIfc = ConstantRV.ZERO,
        destination: IdentityIfc,
        unloadingTime: GetValueIfc = ConstantRV.ZERO,
        numCellsNeeded: Int = 1,
        requestPriority: Int = PRIORITY,
        requestResumePriority: Int = PRIORITY,
        suspensionName: String? = null
    ): ConveyorRequestIfc {
        return convey(
            conveyor,
            entryLocation,
            loadingTime.value,
            destination,
            unloadingTime.value,
            numCellsNeeded,
            requestPriority,
            requestResumePriority,
            suspensionName
        )
    }
}
