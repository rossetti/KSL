package ksl.modeling.entity

import ksl.simulation.KSLEvent
import ksl.utilities.GetValueIfc
import kotlin.coroutines.*

/**
 * Used to exit (terminate) a currently executing ProcessCoroutine.
 */
class ProcessTerminatedException(m: String = "Process Terminated!") : RuntimeException(m)

enum class ProcessType {
    MAIN, SUB
}

interface KSLProcess {
    val id: Int
    val name: String
    val isCreated: Boolean
    val isSuspended: Boolean
    val isTerminated: Boolean
    val isCompleted: Boolean
    val isRunning: Boolean
    val isActivated: Boolean
    val entity: ProcessModel.Entity
    val processType: ProcessType
}

/**
 *  Because an entity that is executing a process cannot resume itself
 *  after suspending itself, we need a mechanism to allow the entity
 *  to register itself before suspending.
 *
 *  Allows entities to suspend themselves by providing a callback mechanism
 *  to allow the entity to register (attach) itself before suspending, with
 *  the assurance that the SuspensionObserver will (eventually) tell the entity
 *  to resume.
 */
interface SuspensionObserver {

    val name: String

    fun attach(entity: ProcessModel.Entity)
    fun detach(entity: ProcessModel.Entity)
}

@RestrictsSuspension
interface KSLProcessBuilder {

    /**
     *  Suspends the execution of the process.  Since the process cannot resume itself, the client
     *  must provide an object that will resume the process.
     *
     * @param suspensionObserver the thing that promises to resume the process
     * @param suspensionName a name for the suspension point. Can be used to determine which suspension point
     * the entity is in when there are multiple suspension points.
     */
    suspend fun suspend(suspensionObserver: SuspensionObserver, suspensionName: String?)

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
     *  @param waitForName the name of the waitFor. can be used to identify which waitFor the entity is experiencing if there
     *   are more than one waitFor suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun waitFor(
        signal: Signal,
        waitPriority: Int = KSLEvent.DEFAULT_PRIORITY,
        waitStats: Boolean = true,
        waitForName: String? = null
    )

    /**
     *  Causes the process to hold indefinitely within the supplied queue.  Some other process or event
     *  is responsible for removing the entities and causing them to proceed with their processes
     *  NOTE:  The entity is not signaled to resume its process unless you signal it.  The
     *  entity cannot remove itself.  Some other construct must do the removal and resumption.
     *
     *  @param queue a queue to hold the waiting entities
     *  @param priority a priority for the queue discipline if needed
     *  @param holdName the name of the hold. can be used to identify which hold the entity is experiencing if there
     *   are more than one hold suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun hold(queue: HoldQueue, priority: Int = KSLEvent.DEFAULT_PRIORITY, holdName: String? = null)

    /**
     *  Requests a number of units of the indicated resource.
     *
     *  @param amountNeeded the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param seizeName the name of the seize suspension point. can be used to identify which seize the entity is experiencing if there
     *   are more than one seize suspension points within the process. The user is responsible for uniqueness.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize(
        resource: Resource, amountNeeded: Int = 1,
        seizePriority: Int = KSLEvent.DEFAULT_PRIORITY, seizeName: String? = null
    ): Allocation

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
     */
    suspend fun use(
        resource: Resource,
        amountNeeded: Int = 1,
        seizePriority: Int = KSLEvent.DEFAULT_PRIORITY,
        delayDuration: Double,
        delayPriority: Int = KSLEvent.DEFAULT_PRIORITY,
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
     *  @param resource the resource from which the units are being requested.
     *  @param seizePriority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun use(
        resource: Resource,
        amountNeeded: Int = 1,
        seizePriority: Int = KSLEvent.DEFAULT_PRIORITY,
        delayDuration: GetValueIfc,
        delayPriority: Int = KSLEvent.DEFAULT_PRIORITY,
    ) {
        val a = seize(resource, amountNeeded, seizePriority)
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
     *  @param delayName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun delay(delayDuration: Double, delayPriority: Int = KSLEvent.DEFAULT_PRIORITY, delayName: String? = null)

    /**
     *  @param delayDuration, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param delayPriority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     *  @param delayName the name of the delay. can be used to identify which delay the entity is experiencing if there
     *   are more than one delay suspension points within the process. The user is responsible for uniqueness.
     */
    suspend fun delay(
        delayDuration: GetValueIfc,
        delayPriority: Int = KSLEvent.DEFAULT_PRIORITY,
        delayName: String? = null
    ) {
        delay(delayDuration.value, delayPriority, delayName)
    }

    /**
     *  Releases the allocation of the resource
     *
     *  @param allocation represents an allocation of so many units of a resource to an entity
     */
    fun release(allocation: Allocation)

    /**
     *  Releases ANY(ALL) allocations related to the resource that are allocated
     *  to the entity currently executing this process
     *
     *  @param resource the resource to release
     */
    fun release(resource: Resource)

    /**
     *  Releases ALL the resources that the entity has currently allocated to it
     */
    fun releaseAllResources()

    fun runSubProcess(subProcess: KSLProcess)
}
