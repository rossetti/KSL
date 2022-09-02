package ksl.modeling.entity

import kotlin.coroutines.intrinsics.*
import ksl.simulation.KSLEvent
import kotlin.coroutines.*

// just placeholders
class Signal {} // represents a signal to hold a process for, must have some kind of queue

@RestrictsSuspension
interface ProcessScope {

//    /**
//     *  Activates the process. Causes the process to be scheduled to start at the present time or some time
//     *  into the future. This schedules an event
//     *
//     *  @param atTime the time into the future at which the process should be activated (started) for
//     *  the supplied entity
//     *  @param priority used to indicate priority of activation if there are activations at the same time.
//     *  Lower priority goes first.
//     *  @return JSLEvent the event used to schedule the activation
//     */
//    fun activate(atTime: Double = 0.0, priority: Int = KSLEvent.DEFAULT_PRIORITY) : KSLEvent<Entity>
// maybe activate should take in a process and not be in this scope?

    /**
     *  Suspends the execution of the process
     */
    suspend fun suspend()

    /**
     *  Resumes the process after it was halted (suspended).
     */
    fun resume() //TODO I don't think it needs to be a suspending function

    /**
     *  Causes the process to halt, waiting for the signal to be on.  If the signal if off, when the process
     *  executes this method, it should halt until the signal becomes on. If the signal is on, when the process
     *  executes this method, the process simply continues executing.
     *
     *  @param signal a general on/off indicator for controlling the process
     *  @param priority a priority indicator to inform ordering when there is more than one process waiting for
     *  the same signal
     */
//    suspend fun waitFor(signal: Signal, priority: Int = KSLEvent.DEFAULT_PRIORITY)

    /**
     *  Requests a number of units of the indicated resource.
     *
     *  @param numRequested the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param priority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @return the Allocation representing the request for the Resource. After returning, the allocation indicates that the units
     *  of the resource have been allocated to the entity making the request. An allocation should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize( resource: Resource, numRequested: Int = 1,
                        priority: Int = KSLEvent.DEFAULT_PRIORITY) : Allocation

    /**
     *  Causes the process to delay (suspend execution) for the specified amount of time.
     *
     *  @param time, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param priority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun delay(time: Double, priority: Int = KSLEvent.DEFAULT_PRIORITY)

    /**
     *  Releases a number of units of the indicated resource.
     *
     *
     *  @param numReleased the number of units of the resource needed for the request.
     *   The default is 1 unit. Cannot be more than the number of units
     *  @param resource the resource from which the units are being requested
     */
     fun release(allocation: Allocation) //TODO I don't think it needs to be a suspending function

}

internal open class ProcessContinuation : Continuation<Unit> {
    override val context: CoroutineContext get() = EmptyCoroutineContext

    override fun resumeWith(result: Result<Unit>) {
        //not sure what to do with this
        println("before result.getOrThrow()")
        result.getOrThrow()
        println("after result.getOrThrow()")
    }
}

// need to be able to just create the coroutine
/* issues:
    how to schedule events
    how to capture/resume the continuation
    clearly a process can only have one suspension point "suspended" at time
    maybe a Process should be a model element that uses a ProcessCoroutine and
    delegates the suspend/resume work to it
    maybe we should just start with the basic suspend/resume primitive
    within a model element a process builder should be used
 */


internal class ProcessCoroutine : ProcessScope, ProcessContinuation() {
    var continuation : Continuation<Unit>? = null //set with suspending

    override fun resume() {
        // what to do if the process is not suspended
        continuation?.resume(Unit)
        //TODO("Not yet implemented")
    }

    override suspend fun suspend() {
        // whenever suspended this creates a new continuation, which must be captured for resumption
        return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            continuation = cont
            COROUTINE_SUSPENDED }
    }

//    override suspend fun waitFor(signal: Signal, priority: Int) {
//        // if signal is on/true then just return
//        // if signal is off/false then suspend
//        // need to register with the signal before suspending
//        TODO("Not yet implemented")
//    }

    override suspend fun seize(resource: Resource, numRequested: Int, priority: Int): Allocation {
        // if the request/task has been allocated then just return
        // otherwise suspend
        TODO("Not yet implemented")
    }

    override suspend fun delay(time: Double, priority: Int) {
        // if time < 0 throw error
        // if time = 0 don't delay, just return
        // if time > 0, then schedule a resume after the delay, and then suspend
        // need to think about what happens if the event associated with this delay is cancelled
        // probably needs to return the event
        TODO("Not yet implemented")
    }

    //TODO consider scheduleResumeAfterDelay()
    // https://github.com/Kotlin/kotlinx.coroutines/blob/3cb61fc44bec51f85abde11f83bc5f556e5e313a/kotlinx-coroutines-core/common/src/Delay.kt

    override fun release(allocation: Allocation) {
        // this is not really a suspending function
        TODO("Not yet implemented")
    }

}