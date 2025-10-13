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

import ksl.modeling.entity.ProcessModel.BatchingEntity
import ksl.modeling.entity.ProcessModel.Companion.BLOCKAGE_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.CONVEYOR_EXIT_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.CONVEYOR_REQUEST_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.DELAY_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.INTERRUPT_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.MOVE_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.QUEUE_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.RELEASE_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.RESUME_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.SEIZE_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.TRANSPORT_REQUEST_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.WAIT_FOR_PRIORITY
import ksl.modeling.entity.ProcessModel.Companion.YIELD_PRIORITY
import ksl.modeling.entity.ProcessModel.Entity
import ksl.modeling.queue.Queue
import ksl.modeling.spatial.*
import ksl.simulation.ModelElement
import ksl.utilities.GetValueIfc
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.coroutines.*

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

    /**
     *  The simulation time that the process first started (activated) after being created.
     *  If the process was never started, this returns Double.NaN
     */
    val processStartTime: Double

    /**
     *  The simulation time that the process completed.
     *  If the process was never completed, this returns Double.NaN
     */
    val processCompletionTime: Double

    /**
     *  The elapsed time from start to completion.
     */
    val processElapseTime: Double
        get() = processCompletionTime - processStartTime
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
    BATCHING,
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

    /**
     *  The entity associated with the process
     */
    val entity: ProcessModel.Entity

    /**
     *  The simulation time that the process first started (activated) after being created.
     *  If the process was never started, this returns Double.NaN
     */
    val processStartTime: Double

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
    suspend fun suspendFor(suspension: Entity.Suspension)

    /**
     *  Causes the suspension to be resumed at the current time (i.e. without any delay).
     *  Errors will result if the suspension is not associated with a suspending entity
     *  via the suspend(suspension: Suspension) function or if the suspension has already
     *  been resumed.
     *
     * @param priority the priority associated with the resume. Can be used
     * to order resumptions that occur at the same time.
     */
    fun resume(suspension: Entity.Suspension, priority: Int = RESUME_PRIORITY) {
        suspension.resume(priority)
    }

    /**
     *  Causes the process to yield (suspend execution) at the current time, for zero time units, and return
     *  control back to the event executive. This permits other events scheduled for the current time
     *  to proceed due to the ordering of events.
     *
     *  @param yieldPriority, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time. Lower yield priorities go first.
     *  @param suspensionName the name of the yield. can be used to identify which yield the entity is experiencing if there
     *   are more than one suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun yield(
        yieldPriority: Int = YIELD_PRIORITY,
        suspensionName: String? = null
    )

    /** Causes the current process to suspend (immediately) until the blockage has been completed.
     *  If the blockage is active, the entity will be suspended until the blockage is completed (cleared).
     *  If the blockage is not active, then no suspension occurs. Some other entity
     *  must cause the blockage to be started and completed. An entity cannot block itself.
     *
     * @param blockage the blockage to wait for
     * @param queue an optional queue to hold the entity while it is blocked.
     * @param yieldBeforeWaiting indicates that the process should yield (instantaneously) control back to the
     * event executive prior to starting to wait for the blockage. The default is true. This yield allows blockages that
     * need to occur prior to but at the same time as the beginning of the wait.
     *  @param yieldPriority, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time. Lower yield priorities go first.
     *  @param suspensionName the name of the waitFor. can be used to identify which waitFor the entity is experiencing if there
     *   are more than one waitFor suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun waitFor(
        blockage: Entity.Blockage,
        queue: Queue<Entity>? = null,
        yieldBeforeWaiting: Boolean = true,
        yieldPriority: Int = YIELD_PRIORITY,
        suspensionName: String? = null
    )

    /** Causes the current process to suspend (immediately) until the blocking task has been completed.
     *  If the blocking task is active (in progress), the entity will be suspended until the task is completed (cleared).
     *  If the blockage is not active, then no suspension occurs. Some other entity
     *  must cause the task to be started and completed. An entity cannot block itself.
     *
     *  There are many different kinds of blocking tasks represented by the classes: BlockingActivity,
     *  BlockingResourceUsage, BlockingResourcePoolUsage, BlockingMovement, etc.
     *
     * @param blockingTask the blocking activity to wait for
     * @param queue an optional queue to hold the entity while it is blocked for statistical collection purposes.
     * @param yieldBeforeWaiting indicates that the process should yield (instantaneously) control back to the
     * event executive prior to starting to wait for the blockage. The default is true. This yield allows blockages that
     * need to occur prior to but at the same time as the beginning of the wait.
     *  @param yieldPriority, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time. Lower yield priorities go first.
     *  @param suspensionName the name of the waitFor. can be used to identify which waitFor the entity is experiencing if there
     *   are more than one waitFor suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun waitFor(
        blockingTask: Entity.BlockingTask,
        queue: Queue<Entity>? = null,
        yieldBeforeWaiting: Boolean = true,
        yieldPriority: Int = YIELD_PRIORITY,
        suspensionName: String? = null
    ) {
        waitFor(blockingTask.blockage, queue, yieldBeforeWaiting, yieldPriority, suspensionName)
    }

    /**
     *  Causes the entity's process to perform the activity. The activity (and its blockage)
     *  are started, the activity delay occurs, and then the activity is completed such that
     *  the blockage is cleared.  Entities that are waiting on the activity to complete will
     *  be resumed.
     * @param blockingActivity the activity to perform
     */
    suspend fun perform(
        blockingActivity: Entity.BlockingActivity
    ) {
        startBlockage(blockingActivity.blockage)
        delay(blockingActivity.activityTime, blockingActivity.activityPriority, blockingActivity.name)
        clearBlockage(blockingActivity.blockage)
    }

    /**
     *  Causes the entity's process to perform an activity involving the usage of a resource.
     *  The usage (and its blockage)
     *  are started, the resource is seized, activity delay occurs, the resource is released
     *   and then the activity is completed such that
     *  the blockage is cleared.  Entities that are waiting on the usage to complete will
     *  be resumed.
     * @param blockingUsage the usage of the resource to perform
     */
    suspend fun perform(
        blockingUsage: Entity.BlockingResourceUsage
    ) {
        startBlockage(blockingUsage.blockage)
        use(
            resource = blockingUsage.resource,
            amountNeeded = blockingUsage.amountNeeded,
            seizePriority = blockingUsage.seizePriority,
            delayDuration = blockingUsage.activityTime,
            delayPriority = blockingUsage.activityPriority,
            queue = blockingUsage.queue
        )
        clearBlockage(blockingUsage.blockage)
    }

    /**
     *  Causes the entity's process to perform an activity involving the usage of a resource pool.
     *  The usage (and its blockage)
     *  are started, the resource is seized, activity delay occurs, the resource is released
     *   and then the activity is completed such that
     *  the blockage is cleared.  Entities that are waiting on the usage to complete will
     *  be resumed.
     * @param blockingUsage the usage of the resource to perform
     */
    suspend fun perform(
        blockingUsage: Entity.BlockingResourcePoolUsage
    ) {
        startBlockage(blockingUsage.blockage)
        use(
            resourcePool = blockingUsage.resourcePool,
            amountNeeded = blockingUsage.amountNeeded,
            seizePriority = blockingUsage.seizePriority,
            delayDuration = blockingUsage.activityTime,
            delayPriority = blockingUsage.activityPriority,
            queue = blockingUsage.queue
        )
        clearBlockage(blockingUsage.blockage)
    }

    /**
     *  Causes the entity's process to perform a movement from one location to another.
     *  The movement (and its blockage)
     *  are started, the movement is completed such that
     *  the blockage is cleared.  Entities that are waiting on the movement to complete will
     *  be resumed.
     * @param blockingMovement the usage of the resource to perform
     */
    suspend fun perform(
        blockingMovement: Entity.BlockingMovement
    ) {
        startBlockage(blockingMovement.blockage)
        move(
            fromLoc = blockingMovement.fromLoc,
            toLoc = blockingMovement.toLoc,
            velocity = blockingMovement.velocity,
            movePriority = blockingMovement.movePriority,
            suspensionName = blockingMovement.name
        )
        clearBlockage(blockingMovement.blockage)
    }

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
    fun clearBlockage(blockage: Entity.Blockage, priority: Int = BLOCKAGE_PRIORITY)

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
        priority: Int = WAIT_FOR_PRIORITY,
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
        waitPriority: Int = WAIT_FOR_PRIORITY,
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
        resumptionPriority: Int = RESUME_PRIORITY,
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
        resumptionPriority: Int = RESUME_PRIORITY,
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
    suspend fun hold(queue: HoldQueue, priority: Int = QUEUE_PRIORITY, suspensionName: String? = null)

    /**
     *  Causes the entity to place itself in the hold queue if the entity to sync with is not within
     *  the hold queue.  If the entity to sync with is in the hold queue, then the active entity will
     *  cause the held entity to resume at the curren time. This function facilitates a common use case
     *  between entities to allow them to sync in time.
     *
     *  @param queue a queue to hold the waiting entities
     *  @param priority a priority for the queue discipline if needed
     * @param resumePriority the priority for the resumption of the suspended process
     * @param waitStats if true the waiting time statistics are collected on the usage of the queue
     *  @param suspensionName the name of the hold. can be used to identify which hold the entity is experiencing if there
     *   are more than one hold suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun syncWith(
        entityToSyncWith: Entity,
        queue: HoldQueue,
        priority: Int = QUEUE_PRIORITY,
        resumePriority: Int = RESUME_PRIORITY,
        waitStats: Boolean = true,
        suspensionName: String? = null
    ) {
        require(!queue.contains(entity)) {"Entity: ${entity.name} was already in HoldQueue: ${queue.name}"}
        require(entity != entityToSyncWith) { "The entity ${entityToSyncWith.name} cannot sync with itself" }
        if (queue.contains(entityToSyncWith)) {
            queue.removeAndResume(entityToSyncWith, resumePriority, waitStats)
        } else {
            hold(queue, priority, suspensionName)
        }
    }

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
     * @return the items that meet the criteria in a list
     */
    suspend fun <T : ModelElement.QObject> waitForItems(
        blockingQ: BlockingQueue<T>,
        amount: Int = 1,
        predicate: (T) -> Boolean = alwaysTrue,
        blockingPriority: Int = QUEUE_PRIORITY,
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
     * @return the items that meet the criteria in a list
     */
    suspend fun <T : ModelElement.QObject> BlockingQueue<T>.waitFor(
        amount: Int = 1,
        predicate: (T) -> Boolean = alwaysTrue,
        blockingPriority: Int = QUEUE_PRIORITY,
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
     * @return the items that meet the criteria in a list
     */
    suspend fun <T : ModelElement.QObject> waitForAnyItems(
        blockingQ: BlockingQueue<T>,
        predicate: (T) -> Boolean,
        blockingPriority: Int = QUEUE_PRIORITY,
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
     * @return the items that meet the criteria in a list
     */
    suspend fun <T : ModelElement.QObject> BlockingQueue<T>.waitForAny(
        predicate: (T) -> Boolean,
        blockingPriority: Int = QUEUE_PRIORITY,
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
        blockingPriority: Int = QUEUE_PRIORITY,
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
        blockingPriority: Int = QUEUE_PRIORITY,
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
        blockingPriority: Int = QUEUE_PRIORITY,
        suspensionName: String? = null
    ) {
        send(item, this, blockingPriority, suspensionName)
    }

    /**
     * The purpose of this suspending function is to allow a batching entity
     * to wait for a batch to be formed.  If a batch can be formed using the
     * candidate entity, then the batch is formed and the function returns false.
     * If the batch cannot be formed, the candidate entity waits in the batch queue
     * and suspends. If the batch can be formed, the batch is attached to the batching
     * entity using the supplied batch name. The batching entity will hold the batched
     * entities in a list associated with the supplied batch name via a map. The entities entering
     * the batch queue wait until the number of entities satisfying the supplied predicate
     * is equal to the supplied batch size. The returned Boolean value associated with the
     * function indicates if the entity is part of the batch and if so, it must exit
     * from the process. The entity that caused the batch to form, will cause the function
     * to return false, when it returns from its suspension.  All entities that enter
     * this suspending function will suspend. The resumption of the suspension is predicated
     * on the formation of the batch.
     *
     * Notice that the associated BatchingQueue must hold entities that are the same
     * type (subclass) of BatchingEntity. Only entities of the same type can be batched
     * together via this functionality.
     *
     * **WARNING**
     * The proper use of this method must have the following form:
     * ```
     *  // within a process()
     *
     *  if (waitedForBatch(candidate, batchingQ, batchName, batchSize, predicate, suspensionName)) {
     *      return@process
     *  }
     *  // the continuing entity is the one that caused the batch to be formed
     * ```
     * That is, you **must** wrap the usage of this suspending function within an if-statement construct.
     *
     * Note the use of the [explicitly labeled return](https://kotlinlang.org/docs/returns.html#return-to-labels) to
     * exit from the process. This is essential.
     * The entities that waited for the batch because the if statement was true, will be resumed when
     * the batch is formed and must be allowed to return from the process and not continue with the current process.
     *
     * @param candidateForBatch this must be the current entity, and it must be a batching entity
     * @param batchingQ the queue from which to form the batch
     * @param batchName the name to associate with the batch. By default, this will be the
     * name of the batch queue.
     * @param batchSize the number of elements to form into a batch. The default is specified by
     * the batch queue's batch size property.
     * @param predicate the predicate governing which entities from the queue are selected for
     * the batch. By default, this is the specified by the batching predicate from the batch queue,
     * which is an always true predicate. Thus, the default is to form a batch of the specified size
     * from whatever is in the queue.
     * @param suspensionName the name of the possible suspension point. This can be used by the entity to
     * determine which send blocking it might be experiencing when blocked.  It is up to the client to
     * ensure the name is meaningful and possibly unique.
     * @return the returned Boolean indicates whether the suspending entity should exit from its
     * process as a result of the call to the suspending function. The caller must cause the exit
     * from the process for the entities within the batch.
     */
    suspend fun <T: BatchingEntity<T>> waitedForBatch(
        candidateForBatch: T,
        batchingQ: BatchQueue<T>,
        batchName: String = batchingQ.name,
        batchSize: Int = batchingQ.batchSize,
        predicate: (T) -> Boolean = batchingQ.batchingPredicate,
        suspensionName: String? = null
    ) : Boolean

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
        seizePriority: Int = SEIZE_PRIORITY,
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
        seizePriority: Int = SEIZE_PRIORITY,
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
        seizePriority: Int = SEIZE_PRIORITY,
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
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resource: MovableResourceWithQ,
        seizePriority: Int = SEIZE_PRIORITY,
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
     *  @param resourceSelectionRule The rule to use to select resources to allocate from. By default, the pool's default rule is used.
     *  @param resourceAllocationRule The rule to use to determine the resources to allocate from given the selected resources.
     *  By default, the pool's default rule is used.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resourcePool: ResourcePool,
        amountNeeded: Int = 1,
        seizePriority: Int = SEIZE_PRIORITY,
        queue: RequestQ,
        resourceSelectionRule: ResourceSelectionRuleIfc = resourcePool.defaultResourceSelectionRule,
        resourceAllocationRule: ResourceAllocationRuleIfc = resourcePool.defaultResourceAllocationRule,
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
     *  @param resourceSelectionRule The rule to use to select resources to allocate from. By default, the pool's default rule is used.
     *  @param resourceAllocationRule The rule to use to determine the resources to allocate from given the selected resources.
     *  By default, the pool's default rule is used.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resourcePool: ResourcePoolWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = SEIZE_PRIORITY,
        resourceSelectionRule: ResourceSelectionRuleIfc = resourcePool.defaultResourceSelectionRule,
        resourceAllocationRule: ResourceAllocationRuleIfc = resourcePool.defaultResourceAllocationRule,
        suspensionName: String? = null
    ): ResourcePoolAllocation {
        return seize(resourcePool, amountNeeded, seizePriority, resourcePool.myWaitingQ,
            resourceSelectionRule, resourceAllocationRule, suspensionName)
    }

    /**
     *  Requests a movable resource from the indicated pool of resources
     *  The queue that will hold the entity is supplied to the movable resource pool.  The
     *  movable resources in the pool will select requests from the queue. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority
     *  attribute for use in ranking the queue prior to the calling seize.
     *
     *  @param movableResourcePool the resource pool from which the units are being requested.
     *  @param requestLocation The location of the request. The default is the entity's currentLocation
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param queue the queue that will hold the entity if the amount needed cannot immediately be supplied by the resource. If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling seize.
     *  @param resourceSelectionRule The rule to use to select resources to allocate from. If null is supplied, then
     *  the pool's default rule is used.
     *  @param resourceAllocationRule The rule to use to determine the resources to allocate from given the selected resources.
     *  By default, the pool's default rule is used.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        movableResourcePool: MovableResourcePool,
        queue: RequestQ,
        requestLocation: LocationIfc = entity.currentLocation,
        seizePriority: Int = SEIZE_PRIORITY,
        resourceSelectionRule: MovableResourceSelectionRuleIfc = movableResourcePool.defaultMovableResourceSelectionRule,
        resourceAllocationRule: MovableResourceAllocationRuleIfc = movableResourcePool.defaultMovableResourceAllocationRule,
        suspensionName: String? = null
    ): Allocation

    /**
     *  Requests a movable resource from the indicated pool of resources
     *  The queue that will hold the entity is internal to the movable resource pool.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority
     *  attribute for use in ranking the queue prior to the calling seize.
     *
     *  @param movableResourcePoolWithQ the resource pool from which the units are being requested.
     *  @param requestLocation The location of the request. The default is the entity's currentLocation
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param resourceSelectionRule The rule to use to select resources to allocate from. By default, the pool's default rule is used.
     *  @param resourceAllocationRule The rule to use to determine the resources to allocate from given the selected resources.
     *  By default, the pool's default rule is used.
     *  @param suspensionName the name of the suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        movableResourcePoolWithQ: MovableResourcePoolWithQ,
        requestLocation: LocationIfc = entity.currentLocation,
        seizePriority: Int = SEIZE_PRIORITY,
        resourceSelectionRule: MovableResourceSelectionRuleIfc = movableResourcePoolWithQ.defaultMovableResourceSelectionRule,
        resourceAllocationRule: MovableResourceAllocationRuleIfc = movableResourcePoolWithQ.defaultMovableResourceAllocationRule,
        suspensionName: String? = null
    ): Allocation {
        return seize(movableResourcePoolWithQ, movableResourcePoolWithQ.myWaitingQ, requestLocation, seizePriority,
            resourceSelectionRule, resourceAllocationRule, suspensionName)
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: Double,
        delayPriority: Int = DELAY_PRIORITY,
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: Double,
        delayPriority: Int = DELAY_PRIORITY,
    ) {
        val a = seize(resource, amountNeeded, seizePriority, resource.myWaitingQ)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release(). The queue that will hold the entity is internal to the resource.  If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
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
     */
    suspend fun use(
        resource: ResourceWithQ,
        amountNeeded: Int = 1,
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = DELAY_PRIORITY,
    ) {
        val a = seize(resource, amountNeeded, seizePriority)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release(). If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: Double,
        delayPriority: Int = DELAY_PRIORITY,
        queue: RequestQ
    ) {
        val a = seize(resourcePool, amountNeeded, seizePriority, queue)
        delay(delayDuration, delayPriority)
        release(a)
    }

    /**
     *  Uses the resource with the amount of units for the delay and then releases it.
     *  Equivalent to: seize(), delay(), release(). If the queue
     *  is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute for use in ranking the queue
     *  prior to the calling use.
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = DELAY_PRIORITY,
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: Double,
        delayPriority: Int = DELAY_PRIORITY,
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = DELAY_PRIORITY,
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
        seizePriority: Int = SEIZE_PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = DELAY_PRIORITY,
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
        delayPriority: Int = DELAY_PRIORITY,
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
        delayPriority: Int = DELAY_PRIORITY,
        suspensionName: String? = null
    ) {
        delay(delayDuration.value, delayPriority, suspensionName)
    }

    /**
     *  Causes the entity to take a trip from the origin location to the destination location using
     *  the provided movement controller.  A trip consists of a sequence of movements that ultimately
     *  result in one of three cases:
     *
     *  1. A successful trip to the destination, with the current location of the entity at the destination location.
     *  2. A partial trip to some (intermediate) location because the trip was cancelled.
     *  3. A partial trip to some (intermediate) location because a collision occurred during the trip.
     *
     *  The returned class Trip encapsulates the result of the trip and can be tested to determine which case occurred
     *  during the trip.
     *
     *  The movements that occur during a trip are considered atomic. That is, they cannot be interrupted once started.
     *  A collision will be detected prior to the movement that will cause the collision. A detected collision will
     *  be handled by the movement controller, which may or may not permit the collision to occur. If the collision occurs
     *  then, this is noted in the resulting Trip.  If a trip is cancelled
     *  during a movement, the current movement will complete, but subsequent movements will not occur.
     *
     *  @param origin the origin of the trip. This represents the starting location of the trip.
     *  @param destination the destination for the trip. This represents the desired location for ending the trip.
     *  @param movementController the controller to use to control the individual movements that occur during the trip.
     *  The default controller is supplied by the entity taking the trip.
     *  @param autoMoveToOrigin If the current location is not the same as the origin location for the trip, then this
     *  option (if true, the default) will automatically set the entity's current location to the origin before the trip
     *  commences.  If this option is false, an illegal state exception will occur if the current location is not the
     *  origin of the trip.
     *  @param suspensionName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     *  @return the result of the trip, which should be checked before proceeding
     */
    suspend fun trip(
        origin: LocationIfc,
        destination: LocationIfc,
        movementController: MovementControllerIfc = entity.movementController,
        autoMoveToOrigin: Boolean = true,
        suspensionName: String? = null
    ) : Trip

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
        movePriority: Int = MOVE_PRIORITY,
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
        movePriority: Int = MOVE_PRIORITY,
        suspensionName: String? = null
    ) {
        move(fromLoc, toLoc, velocity.value, movePriority, suspensionName)
    }

    /**
     *  Causes movement of the spatial element from its current location to the specified location at
     *  the supplied velocity.  The entity will experience the time needed to move the spatial element.
     *
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
        movePriority: Int = MOVE_PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the movable resource from its current location to the specified location at
     *  the supplied velocity. The entity experiences the time needed for the movable resource to move
     *  to the specified location.
     *
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
        movePriority: Int = MOVE_PRIORITY,
        suspensionName: String? = null
    ) {
        movableResource.isMovingEmpty = true
        move(movableResource as SpatialElementIfc, toLoc, velocity, movePriority, suspensionName)
        movableResource.isMovingEmpty = false
    }

    /**
     *  Causes movement of the movable resource from its current location to the specified location at
     *  the supplied velocity. The entity experiences the time needed for the movable resource to move
     *  to the specified location.
     *
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
        movePriority: Int = MOVE_PRIORITY,
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
     *
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
        movePriority: Int = MOVE_PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the entity and the movable resource from the current location to the specified location at
     *  the supplied velocity. The entity and the movable resource must be at the same location to start the movement.
     *  If not specified, the default velocity of the spatial element is used for the movement.
     *
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
        movePriority: Int = MOVE_PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes movement of the entity and the movable resource from the current location to the specified location at
     *  the supplied velocity. The entity and the movable resource must be at the same location to start the movement.
     *  If not specified, the default velocity of the spatial element is used for the movement.
     *
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
        movePriority: Int = MOVE_PRIORITY,
        suspensionName: String? = null
    )

    /**
     *  Causes transport of the entity via the movable resource from the entity's current location to the specified location at
     *  the supplied velocities.  The entity experiences the time for the movable resource to move to its current location.
     *  If not specified, the default velocity of the movable resource is used for the movement.  If the home base option is
     *  specified, the entity does not experience the time for the movable resource to proceed to its home base after
     *  the entity releases the movable resource.
     *
     *  If the queue is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute \
     *  for use in ranking the queue prior to the calling for transport.
     *
     *  @param movableResource, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param transportQ the queue that the entity waits in if the resource is busy
     *  @param requestPriority, a priority can be used to determine the priority of the underlying (seize) call
     *  associated with the transport request. This is not the priority associated with waiting in a queue.
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param sendToHomeBaseOption If true, and there are no pending requests, and the selected movable resource has a home base,
     *  it will be sent to the home base after the transport. The default is false.
     */
    suspend fun transportWith(
        movableResource: MovableResource,
        toLoc: LocationIfc,
        emptyVelocity: Double = movableResource.velocity.value,
        transportVelocity: Double = movableResource.velocity.value,
        transportQ: RequestQ,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = TRANSPORT_REQUEST_PRIORITY,
        emptyMovePriority: Int = MOVE_PRIORITY,
        loadingPriority: Int = DELAY_PRIORITY,
        transportPriority: Int = MOVE_PRIORITY,
        unLoadingPriority: Int = DELAY_PRIORITY,
        sendToHomeBaseOption: Boolean = false
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
        release(a)
        if (sendToHomeBaseOption){
            if (movableResource.hasHomeBase){
                if (transportQ.isEmpty){
                    movableResource.sendToHomeBase()
                    yield()
                }
            }
        }
    }

    /**
     *  Causes transport of the entity via the movable resource from the entity's current location to the specified location at
     *  the supplied velocities.  The entity experiences the time for the movable resource to move to its current location.
     *  If not specified, the default velocity of the movable resource is used for the movement.  If the home base option is
     *  specified, the entity does not experience the time for the movable resource to proceed to its home base after
     *  the entity releases the movable resource.
     *
     *  If the queue is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute \
     *  for use in ranking the queue prior to the calling for transport.
     *
     *  @param movableResourceWithQ, the spatial element that will be moved
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param requestPriority, a priority can be used to determine the priority of the underlying (seize) call
     *  associated with the transport request. This is not the priority associated with waiting in a queue.
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param sendToHomeBaseOption If true, and there are no pending requests, and the selected movable resource has a home base,
     *  it will be sent to the home base after the transport. The default is false.
     */
    suspend fun transportWith(
        movableResourceWithQ: MovableResourceWithQ,
        toLoc: LocationIfc,
        emptyVelocity: Double = movableResourceWithQ.velocity.value,
        transportVelocity: Double = movableResourceWithQ.velocity.value,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = TRANSPORT_REQUEST_PRIORITY,
        emptyMovePriority: Int = MOVE_PRIORITY,
        loadingPriority: Int = DELAY_PRIORITY,
        transportPriority: Int = MOVE_PRIORITY,
        unLoadingPriority: Int = DELAY_PRIORITY,
        sendToHomeBaseOption: Boolean = false
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
        release(a)
        if (sendToHomeBaseOption){
            if (movableResourceWithQ.hasHomeBase){
                if (movableResourceWithQ.waitingQ.isEmpty){
                    movableResourceWithQ.sendToHomeBase()
                    yield()
                }
            }
        }
    }

    /**
     *  Causes transport of the entity via a movable resource within the fleet from the entity's current location to the specified location at
     *  the supplied velocities.
     *  If not specified, the default velocity of the movable resource is used for the movement.
     *
     *  If the queue is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute \
     *  for use in ranking the queue prior to the calling for transport.
     *
     *  @param fleet, the pool of movable resources
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param requestPriority, a priority can be used to determine the priority of the underlying (seize) call
     *  associated with the transport request. This is not the priority associated with waiting in a queue.
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param sendToHomeBaseOption If true, and there are no pending requests, and the selected movable resource has a home base,
     *  it will be sent to the home base after the transport. The default is false.
     */
    suspend fun transportWith(
        fleet: MovableResourcePool,
        toLoc: LocationIfc,
        emptyVelocity: Double = fleet.velocity.value,
        transportVelocity: Double = fleet.velocity.value,
        transportQ: RequestQ,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = TRANSPORT_REQUEST_PRIORITY,
        emptyMovePriority: Int = MOVE_PRIORITY,
        loadingPriority: Int = DELAY_PRIORITY,
        transportPriority: Int = MOVE_PRIORITY,
        unLoadingPriority: Int = DELAY_PRIORITY,
        resourceSelectionRule: MovableResourceSelectionRuleIfc = fleet.defaultMovableResourceSelectionRule,
        resourceAllocationRule: MovableResourceAllocationRuleIfc = fleet.defaultMovableResourceAllocationRule,
        sendToHomeBaseOption: Boolean = false
    ) {
        val a = seize(fleet, seizePriority = requestPriority, queue = transportQ,
            resourceSelectionRule = resourceSelectionRule, resourceAllocationRule = resourceAllocationRule)
        // must be 1 allocation for 1 unit seized
        val movableResource = a.myResource as MovableResource
        move(movableResource, entity.currentLocation, emptyVelocity, emptyMovePriority)
        if (loadingDelay != ConstantRV.ZERO) {
            delay(loadingDelay, loadingPriority)
        }
        moveWith(movableResource, toLoc, transportVelocity, transportPriority)
        if (unLoadingDelay != ConstantRV.ZERO) {
            delay(unLoadingDelay, unLoadingPriority)
        }
        release(a)
        if (sendToHomeBaseOption){
            if (movableResource.hasHomeBase){
                if (transportQ.isEmpty){
                    movableResource.sendToHomeBase()
                    yield()
                }
            }
        }
    }

    /**
     *  Causes transport of the entity via a movable resource within the fleet from the entity's current location to the specified location at
     *  the supplied velocities.
     *  If not specified, the default velocity of the movable resource is used for the movement.
     *
     *  If the queue is priority based (i.e. uses a ranked queue discipline) the user should set the entity's priority attribute \
     *  for use in ranking the queue prior to the calling for transport.
     *
     *  @param fleet, the pool of movable resources
     *  @param toLoc the location to which the entity is supposed to move
     *  @param emptyVelocity the velocity associated with the movement to the entity's location
     *  @param transportVelocity the velocity associated with the movement to the desired location
     *  @param requestPriority, a priority can be used to determine the priority of the underlying (seize) call
     *  associated with the transport request. This is not the priority associated with waiting in a queue.
     *  @param emptyMovePriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param transportPriority, since the move is scheduled, a priority can be used to determine the order of events for
     *  moves that might be scheduled to complete at the same time.
     *  @param sendToHomeBaseOption If true, and there are no pending requests, and the selected movable resource has a home base,
     *  it will be sent to the home base after the transport
     */
    suspend fun transportWith(
        fleet: MovableResourcePoolWithQ,
        toLoc: LocationIfc,
        emptyVelocity: Double = fleet.velocity.value,
        transportVelocity: Double = fleet.velocity.value,
        loadingDelay: GetValueIfc = ConstantRV.ZERO,
        unLoadingDelay: GetValueIfc = ConstantRV.ZERO,
        requestPriority: Int = TRANSPORT_REQUEST_PRIORITY,
        emptyMovePriority: Int = MOVE_PRIORITY,
        loadingPriority: Int = DELAY_PRIORITY,
        transportPriority: Int = MOVE_PRIORITY,
        unLoadingPriority: Int = DELAY_PRIORITY,
        resourceSelectionRule: MovableResourceSelectionRuleIfc = fleet.defaultMovableResourceSelectionRule,
        resourceAllocationRule: MovableResourceAllocationRuleIfc = fleet.defaultMovableResourceAllocationRule,
        sendToHomeBaseOption: Boolean = false
    ) {
        val a = seize(fleet, seizePriority = requestPriority, queue = fleet.myWaitingQ,
            resourceSelectionRule = resourceSelectionRule, resourceAllocationRule = resourceAllocationRule)
        // must be 1 allocation for 1 unit seized because there is only 1 unit in each
        val movableResource = a.myResource as MovableResource
        move(movableResource, entity.currentLocation, emptyVelocity, emptyMovePriority)
        if (loadingDelay != ConstantRV.ZERO) {
            delay(loadingDelay, loadingPriority)
        }
        moveWith(movableResource, toLoc, transportVelocity, transportPriority)
        if (unLoadingDelay != ConstantRV.ZERO) {
            delay(unLoadingDelay, unLoadingPriority)
        }
        release(a)
        if (sendToHomeBaseOption){
            if (movableResource.hasHomeBase){
                if (fleet.myWaitingQ.isEmpty){
                    movableResource.sendToHomeBase()
                    yield()
                }
            }
        }
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
        movePriority: Int = MOVE_PRIORITY,
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
        movePriority: Int = MOVE_PRIORITY,
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
        movePriority: Int = MOVE_PRIORITY,
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
    fun release(allocation: Allocation, releasePriority: Int = RELEASE_PRIORITY)

    /**
     *  Releases ANY(ALL) allocations related to the resource that are allocated
     *  to the entity currently executing this process
     *
     *  @param resource the resource to release
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun release(resource: Resource, releasePriority: Int = RELEASE_PRIORITY)

    /**
     *  Releases ALL the resources that the entity has currently allocated to it
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun releaseAllResources(releasePriority: Int = RELEASE_PRIORITY)

    /**
     * Releases the allocations associated with using a ResourcePool
     *  @param releasePriority the priority associated with this release. This priority is used
     *  to order the resumption events associated with the release. If multiple releases occur at the same
     *  simulated time, this priority can be used to order the associated resumption of dependent processes.
     */
    fun release(pooledAllocation: ResourcePoolAllocation, releasePriority: Int = RELEASE_PRIORITY)

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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
        interruptPriority: Int = INTERRUPT_PRIORITY,
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
     * access the conveyor at the same time, this priority determines which goes first. Similar to
     * the seize resource priority.
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return a representation of the allocated cells on the conveyor. The user should use this as a ticket
     * to ride on the conveyor and to eventually release the allocated cells by exiting the conveyor.
     */
    suspend fun requestConveyor(
        conveyor: Conveyor,
        entryLocation: String,
        numCellsNeeded: Int = 1,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
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
     * the LocationIfc, then the current location property of the entity will be updated to this value at the
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
        destination: String,
        ridePriority: Int = CONVEYOR_REQUEST_PRIORITY,
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
     * the LocationIfc, then the current location property of the entity will be updated to this value at the
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
        destination: String,
        ridePriority: Int = CONVEYOR_REQUEST_PRIORITY,
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
        exitPriority: Int = CONVEYOR_EXIT_PRIORITY,
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
        exitPriority: Int = CONVEYOR_EXIT_PRIORITY,
        suspensionName: String? = null
    ) {
        require(entity.conveyorRequest != null) { "The entity attempted to exit without using the conveyor." }
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
        entryLocation: String,
        destination: String,
        numCellsNeeded: Int = 1,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
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
        entryLocation: String,
        loadingTime: Double = 0.0,
        destination: String,
        unloadingTime: Double = 0.0,
        numCellsNeeded: Int = 1,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
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
     * the `seize()` function resource priority.
     * @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the ride and contains information about
     * the origin point, the destination, etc.
     */
    suspend fun convey(
        conveyor: Conveyor,
        entryLocation: String,
        loadingTime: GetValueIfc = ConstantRV.ZERO,
        destination: String,
        unloadingTime: GetValueIfc = ConstantRV.ZERO,
        numCellsNeeded: Int = 1,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
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

    /**
     *  Causes the entity to transfer from one conveyor to another. The entity will suspend while
     *  accessing the required cells at the next conveyor. Once the desired cells are obtained
     *  the entity will exit its current conveyor and be positioned to ride on the next conveyor.
     *  After executing this function, the entity will be blocking the entry cell of the next conveyor
     *  and must then execute a rideConveyor() function to move on the new conveyor. The transfer
     *  will include the time needed for the entity to move through the exit cells of the current
     *  conveyor, any waiting to access the next conveyor, and be positioned to ride on the
     *  next conveyor.
     *
     *  Pre-conditions for this function:
     *  - the entity must currently be on a different conveyor than the one to which it is transferring
     *  - the entity must currently be occupying an exit cell of its current conveyor
     *  - the exit location of the entity's current conveyor must correspond to the entry location for the next conveyor
     *
     * @param conveyorRequest the entity's current conveyor request. This is required to exit the current conveyor.
     *  @param nextConveyor the conveyor to transfer to
     *  @param entryLocation the entry location on the conveyor to access
     *  @param requestPriority the priority associated with the conveyor access request
     *  @param requestResumePriority the priority for resuming after accessing the conveyor
     *  @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the transfer and contains information about
     * the origin point, and can be used by the entity to control its movement on the new conveyor.
     */
    suspend fun transferTo(
        conveyorRequest: ConveyorRequestIfc,
        nextConveyor: Conveyor,
        entryLocation: String,
        exitPriority: Int = CONVEYOR_EXIT_PRIORITY,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
        suspensionName: String? = null
    ) : ConveyorRequestIfc

    /**
     *  Causes the entity to transfer from its current conveyor to another. The entity will suspend while
     *  accessing the required cells at the next conveyor. Once the desired cells are obtained
     *  the entity will exit its current conveyor and be positioned to ride on the next conveyor.
     *  After executing this function, the entity will be blocking the entry cell of the next conveyor
     *  and must then execute a rideConveyor() function to move on the new conveyor. The transfer
     *  will include the time needed for the entity to move through the exit cells of the current
     *  conveyor, any waiting to access the next conveyor, and be positioned to ride on the
     *  next conveyor.
     *
     *  Pre-conditions for this function:
     *  - the entity must currently be on a different conveyor than the one to which it is transferring
     *  - the entity must currently be occupying an exit cell of its current conveyor
     *  - the exit location of the entity's current conveyor must correspond to the entry location for the next conveyor
     *
     *  @param nextConveyor the conveyor to transfer to
     *  @param entryLocation the entry location on the conveyor to access
     *  @param requestPriority the priority associated with the conveyor access request
     *  @param requestResumePriority the priority for resuming after accessing the conveyor
     *  @param suspensionName the name of the suspension point the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     * @return the returned item encapsulates what happened during the transfer and contains information about
     * the origin point, and can be used by the entity to control its movement on the new conveyor.
     */
    suspend fun transferTo(
        nextConveyor: Conveyor,
        entryLocation: String,
        exitPriority: Int = CONVEYOR_EXIT_PRIORITY,
        requestPriority: Int = CONVEYOR_REQUEST_PRIORITY,
        requestResumePriority: Int = RESUME_PRIORITY,
        suspensionName: String? = null
    ) : ConveyorRequestIfc {
        require(entity.conveyorRequest != null) { "The entity attempted to exit without using the conveyor." }
        return transferTo(entity.conveyorRequest!!, nextConveyor,
            entryLocation, exitPriority, requestPriority, requestResumePriority, suspensionName)
    }

}
