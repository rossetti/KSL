/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.modeling.queue

import ksl.utilities.GetValueIfc
import ksl.utilities.NameIfc
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateAccessorIfc
import java.util.*

/**
 * incremented to give a running total of the number of model QObject
 * created
 */
private var qObjCounter: Long = 0

/**
 * QObject can be used as a base class for objects that need to be placed in
 * queues on a regular basis.  A QObject can be in one and only one Queue at a time.
 * An arbitrary object can be associated with the QObject. The user is
 * responsible for managing the type of the attached object.
 *
 * Creates an QObject with the given name and the creation time set to the
 * supplied value
 *
 * @param creationTime the time created
 * @param name The name of the QObject
*/
class QObject (theCreateTime: Double, aName: String? = null) : NameIfc, Comparable<QObject> {
    init{
        require(theCreateTime >= 0.0) {"The creation time must be >= 0.0"}
        qObjCounter++
    }
    /**
     * Gets a uniquely assigned identifier for this QObject. This
     * identifier is assigned when the QObject is created. It may vary if the
     * order of creation changes.
     */
    val id: Long = qObjCounter

    /**
     * The name of the QObject
     */
    override val name: String = aName ?: ("ID_${id}")

    /**
     * A state representing that the QObject was created
     */
    private var createTime = theCreateTime

    /**
     * A state representing when the QObject was queued
     */
    private var myQueuedState: State = State(name = "{$name}_State")

    /**
     * A priority for use in queueing
     */
    private var myPriority = 1

    /**
     * The current queue that the QObject is in, null if not in a queue
     */
    private var myQueue: Queue? = null

    /**
     * A reference to an object that can be attached to the QObject when queued
     */
    var attachedObject: Any? = null

    /**
     * can be used to time stamp the qObject
     */
    protected var myTimeStamp = 0.0

    /**
     * Causes the QObject to look like new, gets a new name, number, priority is
     * reset to 1, states are initialized, and starts in created state. As if
     * newly, created. Useful if reusing QObjects
     *
     * @param time used to set the creation time of the QObject
     * @param name the name
     */
//    protected fun initialize(time: Double, name: String?) {
//        require(time >= 0) { "The creation time must be > 0.0" }
//        qObjCounter = qObjCounter + 1
//        myId = qObjCounter
//        this.name = name
//        myPriority = 1
//        queue = null
//        myAttachedObject = null
//        myValue = null
//        myCreationTime = time
//        if (myQueuedState == null) {
//            myQueuedState = State(name = this.name + "Queued")
//        } else {
//            myQueuedState.initialize()
//        }
//    }
    /**
     * Returns the value determined by the object supplied from setValue(). The
     * object returned may be null
     *
     * @return an implementation of GetValueIfc or null
     */
    /**
     * Allows for a generic value to be held by the QObject whose value will be
     * return by getValue() It can be null, in which case, getValue() will
     * return null
     *
     * @param value the value object
     */
    var valueObject: GetValueIfc? = null

    override fun toString(): String {
        return "ID= $id, name= $name"
    }

    /**
     * Gets the queueing priority associated with this QObject
     *
     * @return The priority as an int
     */
    /**
     * Sets the priority to the supplied value If the QObject is queued, the
     * queue's changePriority() method is called (possibly causing a reordering
     * of the queue) which may cause significant reordering overhead otherwise
     * the priority is directly changed Changing this value only changes how the
     * QObjects are compared and may or may not change how they are ordered in
     * the queue, depending on the queue discipline used
     *
     * @param priority An integer representing the priority of the QObject
     */
    var priority: Int
        get() = myPriority
        set(priority) {
            if (isQueued) {
                myQueue.changePriority(this, priority)
            } else {
                setPriority_(priority)
            }
        }
    /**
     * Returns the queue that the QObject was last enqueued within
     *
     * @return The Queue, or null if no queue
     */
    /**
     * Sets the queue that the QObject is enqueued
     *
     * @param queue The Queue that the object is enqueued
     */
    var queue: Queue?
        get() = myQueue
        protected set(queue) {
            myQueue = queue
        }

    /**
     * Gets the time the QObject was LAST enqueued
     *
     * @return A double representing the time the QObject was enqueued
     */
    val timeEnteredQueue: Double
        get() = myQueuedState.timeStateEntered

    /**
     * Gets the time the QObject LAST exited a queue
     *
     * @return A double representing the time the QObject last exited a QObject
     */
    val timeExitedQueue: Double
        get() = myQueuedState.timeStateExited

    /**
     * Gets the time the QObject spent in the Queue based on the LAST time
     * dequeued
     *
     * @return the most recent time spend in a queue
     */
    val timeInQueue: Double
        get() = myQueuedState.totalTimeInState

    /**
     * This method can be used to get direct access to the State that represents
     * when the object was queued. This allows access to the total time in the
     * queued state as well as other statistical accumulation of state
     * statistics
     *
     * @return Returns the QueuedState.
     */
    val queuedState: StateAccessorIfc
        get() = myQueuedState  //TODO clone it

    /**
     * Checks if the QObject is queued
     *
     * @return true if it is queued
     */
    val isQueued: Boolean
        get() = myQueuedState.isEntered

    /**
     * Used to make the QObject not have any references, e.g. to a Queue and to
     * an Object that was queued
     *
     *
     */
    protected fun setNulls() {
        attachedObject = null
        valueObject = null
        queue = null
    }

    /**
     * Causes all references of objects from this QObject to be set to null.
     *
     * Meant primarily to facilitate garbage collection. After this call, the
     * object should not be used.
     *
     */
    fun nullify() {
        setNulls()
    }

    /**
     * Used by Queue to indicate that the QObject has entered the queue
     *
     * @param queue the queue entered
     * @param time the time
     * @param priority the priority
     * @param obj an object to attach
     */
    protected fun enterQueue(queue: Queue, time: Double, priority: Int, obj: Any?) {
        check(!myQueuedState.isEntered) { "The QObject was already queued!" }
        myQueuedState.enter(time)
        this.queue = queue
        setPriority_(priority)
        attachedObject = obj
    }

    /**
     * Indicates that the QObject exited the queue
     *
     * @param time The time QObject exited the queue
     */
    protected fun exitQueue(time: Double) {
        check(myQueuedState.isEntered) { "The QObject was not in a queue!" }
        myQueuedState.exit(time)
        queue = null
    }

    /**
     * Sets the queueing priority for this QObject Changing the priority while
     * the object is in a queue has no effect on the ordering of the queue. This
     * priority is only used to determine the ordering in the queue when the
     * item enters the queue.
     *
     * @param priority lower priority implies earlier ranking in the queue
     */
    protected fun setPriority_(priority: Int) {
        myPriority = priority
    }

    //  ===========================================
    //        Comparable Interface
    //  ===========================================
    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     *
     * Throws ClassCastException if the specified object's type prevents it from
     * begin compared to this object.
     *
     *
     * Throws RuntimeException if the id's of the objects are the same, but the
     * references are not when compared with equals.
     *
     *
     * Note: This class may have a natural ordering that is inconsistent with
     * equals.
     *
     * @param other The object to compare to
     * @return Returns a negative integer, zero, or a positive integer if this
     * object is less than, equal to, or greater than the specified object.
     */
    override operator fun compareTo(other: QObject): Int {
        val qObj = other

        // compare the priorities
        if (myPriority < qObj.priority) {
            return -1
        }
        if (myPriority > qObj.priority) {
            return 1
        }

        // priorities are equal, compare time stamps
        if (timeEnteredQueue < qObj.timeEnteredQueue) {
            return -1
        }
        if (timeEnteredQueue > qObj.timeEnteredQueue) {
            return 1
        }

        // time stamps are equal, compare ids
        if (id < qObj.id) // lower id, implies created earlier
        {
            return -1
        }
        if (id > qObj.id) {
            return 1
        }

        // if the id's are equal then the object references must be equal
        // if this is not the case there is a problem
        return if (this == other) {
            0
        } else {
            throw RuntimeException("Id's were equal, but references were not, in QObject compareTo")
        }
    }
    /**
     * @return Returns the TimeStamp.
     */
    /**
     * @param timeStamp The timeStamp to set.
     */
    var timeStamp: Double
        get() = myTimeStamp
        set(timeStamp) {
            require(timeStamp >= 0.0) { "The time stamp was less than 0.0" }
            myTimeStamp = timeStamp
        }
}