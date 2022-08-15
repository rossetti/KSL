package ksl.process

import kotlin.coroutines.intrinsics.*
import ksl.simulation.JSLEvent
import kotlin.coroutines.*

// just place holders
//class Entity {} // entities are supposed to experience processes
// maybe need an EntityType class that is a model element, it knows how to create and track its entities, process its entities
class Resource {} // entities may use resources during their processes, must have some kind of queueing
class Task {} // a task is what the entity asks resources to do during the process
class Signal {} // represents a signal to hold a process for, must have some kind of queue

@RestrictsSuspension
interface ProcessScope {

    /**
     *  Activates the process. Causes the process to be scheduled to start at the present time or some time
     *  into the future. This schedules an event
     *
     *  @param atTime the time into the future at which the process should be activated (started) for
     *  the supplied entity
     *  @param priority used to indicate priority of activation if there are activations at the same time.
     *  Lower priority goes first.
     *  @return JSLEvent the event used to schedule the activation
     */
    fun activate(atTime: Double = 0.0, priority: Int = JSLEvent.DEFAULT_PRIORITY) : JSLEvent<Entity>
// maybe activate should take in a process and not be in this scope?

    /**
     *  Suspends the execution of the process
     */
    suspend fun suspend()

    /**
     *  Resumes the process after it was halted (suspended).
     */
    suspend fun resume() //TODO I don't think it needs to be a suspending function

    /**
     *  Causes the process to halt, waiting for the signal to be on.  If the signal if off, when the process
     *  executes this method, it should halt until the signal becomes on. If the signal is on, when the process
     *  executes this method, the process simply continues executing.
     *
     *  @param signal a general on/off indicator for controlling the process
     *  @param priority a priority indicator to inform ordering when there is more than one process waiting for
     *  the same signal
     */
    suspend fun waitFor(signal: Signal, priority: Int = JSLEvent.DEFAULT_PRIORITY)

    /**
     *  Requests a number of units of the indicated resource.
     *
     *  @param numRequested the number of units of the resource needed for the request.
     *   The default is 1 unit.
     *  @param resource the resource from which the units are being requested.
     *  @param taskTime the amount of time associated with the request. By default, this is infinite. The task time
     *  may be used to inform any allocation mechanism for requests that may be competing for the resource.
     *  @param priority the priority of the request. This is meant to inform any allocation mechanism for
     *  requests that may be competing for the resource.
     *  @return the Task representing the request for the Resource. After returning, the task indicates that the units
     *  of the resource have been allocated to the entity making the request. A task should not be returned until
     *  all requested units of the resource have been allocated.
     */
    suspend fun seize( resource: Resource, numRequested: Int = 1,
                        taskTime: Double = Double.POSITIVE_INFINITY,
                        priority: Int = JSLEvent.DEFAULT_PRIORITY) : Task

    /**
     *  Causes the process to delay (suspend execution) for the specified amount of time.
     *
     *  @param time, the length of time required before the process continues executing, must not be negative and
     *  must be finite.
     *  @param priority, since the delay is scheduled, a priority can be used to determine the order of events for
     *  delays that might be scheduled to complete at the same time.
     */
    suspend fun delay(time: Double, priority: Int = JSLEvent.DEFAULT_PRIORITY)

    /**
     *  Releases a number of units of the indicated resource.
     *
     *
     *  @param numReleased the number of units of the resource needed for the request.
     *   The default is 1 unit. Cannot be more than the number of units
     *  @param resource the resource from which the units are being requested
     */
    suspend fun release(resource: Resource, numReleased: Int = 1) //TODO I don't think it needs to be a suspending function

    /**
     *  A method for delaying with a task time.
     *
     *  @param task The supplied task must have a finite task time.
     */
    suspend fun work(task: Task) //TODO not sure if work should be a suspending function of task
    suspend fun complete(task: Task) //TODO not sure if complete should be method on task and if it should be suspending

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

fun main(){

    val p = ProcessCoroutine()// does not make a coroutine, yet

    val s = Something("manuel")
    println(s.name)
}
interface Person {
    val name : String
}
public fun Something(str: String): Person = object : Person {
    override val name: String
        get() = str
}

/* Sequence takes in a function that makes an iterator, returns an instance of a Sequence
 by creating an object instance that implements Sequence and uses the argument parameter
 to define the iterator function of the object that is returned.
*/
public inline fun <T> Sequence(crossinline iteratorParameter: () -> Iterator<T>): Sequence<T> = object : Sequence<T> {
    override fun iterator(): Iterator<T> = iteratorParameter()
}

/*
The function sequence() takes in a suspending function with receiver (SequenceScope<T>) and returns an instance
of the interface Sequence by defining a function declaration with equals that calls the function
Sequence and passing in a lambda expression that calls the iterator function that uses the suspending function parameter
"block" to create an Iterator
 */
public fun <T> sequence(block: suspend SequenceScope<T>.() -> Unit): Sequence<T> = Sequence { iterator(block) }

public fun <T> iterator(block: suspend SequenceScope<T>.() -> Unit): Iterator<T> {
    val iterator = SequenceBuilderIterator<T>()

    iterator.nextStep = block.createCoroutineUnintercepted<SequenceBuilderIterator<T>, Unit>(receiver = iterator, completion = iterator)
    return iterator
}
@RestrictsSuspension
public abstract class SequenceScope<in T> internal constructor() {
    /**
     * Yields a value to the [Iterator] being built and suspends
     * until the next value is requested.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     * @sample samples.collections.Sequences.Building.buildFibonacciSequence
     */
    public abstract suspend fun yield(value: T)

    /**
     * Yields all values from the `iterator` to the [Iterator] being built
     * and suspends until all these values are iterated and the next one is requested.
     *
     * The sequence of values returned by the given iterator can be potentially infinite.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     */
    public abstract suspend fun yieldAll(iterator: Iterator<T>)

    /**
     * Yields a collections of values to the [Iterator] being built
     * and suspends until all these values are iterated and the next one is requested.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     */
    public suspend fun yieldAll(elements: Iterable<T>) {
        if (elements is Collection && elements.isEmpty()) return
        return yieldAll(elements.iterator())
    }

    /**
     * Yields potentially infinite sequence of values  to the [Iterator] being built
     * and suspends until all these values are iterated and the next one is requested.
     *
     * The sequence can be potentially infinite.
     *
     * @sample samples.collections.Sequences.Building.buildSequenceYieldAll
     */
    public suspend fun yieldAll(sequence: Sequence<T>) = yieldAll(sequence.iterator())
}


private typealias State = Int

private const val State_NotReady: State = 0
private const val State_ManyNotReady: State = 1
private const val State_ManyReady: State = 2
private const val State_Ready: State = 3
private const val State_Done: State = 4
private const val State_Failed: State = 5

private class SequenceBuilderIterator<T> : SequenceScope<T>(), Iterator<T>, Continuation<Unit> {
    // I think that this extends SequenceScope and Continuation so that it can be used as both receiver and completion parameters
    private var state = State_NotReady
    private var nextValue: T? = null
    private var nextIterator: Iterator<T>? = null
    var nextStep: Continuation<Unit>? = null // used to capture the Continuation for resumption after suspension or creation

    override fun hasNext(): Boolean {
        while (true) {
            when (state) {
                State_NotReady -> {}
                State_ManyNotReady ->
                    if (nextIterator!!.hasNext()) {
                        state = State_ManyReady
                        return true
                    } else {
                        nextIterator = null
                    }
                State_Done -> return false
                State_Ready, State_ManyReady -> return true
                else -> throw exceptionalState()
            }

            state = State_Failed
            val step = nextStep!!
            nextStep = null
            step.resume(Unit)
        }
    }

    override fun next(): T {
        when (state) {
            State_NotReady, State_ManyNotReady -> return nextNotReady()
            State_ManyReady -> {
                state = State_ManyNotReady
                return nextIterator!!.next()
            }
            State_Ready -> {
                state = State_NotReady
                @Suppress("UNCHECKED_CAST")
                val result = nextValue as T
                nextValue = null
                return result
            }
            else -> throw exceptionalState()
        }
    }

    private fun nextNotReady(): T {
        if (!hasNext()) throw NoSuchElementException() else return next()
    }

    private fun exceptionalState(): Throwable = when (state) {
        State_Done -> NoSuchElementException()
        State_Failed -> IllegalStateException("Iterator has failed.")
        else -> IllegalStateException("Unexpected state of the iterator: $state")
    }


    override suspend fun yield(value: T) {
        nextValue = value
        state = State_Ready
        return suspendCoroutineUninterceptedOrReturn { c ->
            nextStep = c
            COROUTINE_SUSPENDED
        }
    }

    override suspend fun yieldAll(iterator: Iterator<T>) {
        if (!iterator.hasNext()) return
        nextIterator = iterator
        state = State_ManyReady
        return suspendCoroutineUninterceptedOrReturn { c ->
            nextStep = c
            COROUTINE_SUSPENDED
        }
    }

    // Completion continuation implementation
    override fun resumeWith(result: Result<Unit>) {
        result.getOrThrow() // just rethrow exception if it is there
        state = State_Done
    }

    override val context: CoroutineContext
        get() = EmptyCoroutineContext
}



internal class ProcessCoroutine : ProcessScope, ProcessContinuation() {
    var continuation : Continuation<Unit>? = null //set with suspending

    override fun activate(atTime: Double, priority: Int): JSLEvent<Entity> {
        TODO("Not yet implemented")
    }

    override suspend fun resume() {
        // what to do if the process is not suspended
        continuation?.resume(Unit)
        //TODO("Not yet implemented")
    }

//    suspend fun halt() {
//       return suspendCoroutineUninterceptedOrReturn { cont -> COROUTINE_SUSPENDED }
//
////        TODO("Not yet implemented")
//    }

    override suspend fun suspend() {
        // whenever suspended this creates a new continuation, which must be captured for resumption
        return suspendCoroutineUninterceptedOrReturn<Unit> { cont ->
            continuation = cont
            COROUTINE_SUSPENDED }
    }

//    suspend fun halt() = suspendCoroutineUninterceptedOrReturn {
//        cont: Continuation<Unit> -> COROUTINE_SUSPENDED
//    }

    override suspend fun waitFor(signal: Signal, priority: Int) {
        // if signal is on/true then just return
        // if signal is off/false then suspend
        TODO("Not yet implemented")
    }

    override suspend fun seize(resource: Resource, numRequested: Int, taskTime: Double, priority: Int): Task {
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

    override suspend fun release(resource: Resource, numReleased: Int) {
        // this is not really a suspending function
        TODO("Not yet implemented")
    }

    override suspend fun work(task: Task) {
        TODO("Not yet implemented")
    }

    override suspend fun complete(task: Task) {
        TODO("Not yet implemented")
    }

}