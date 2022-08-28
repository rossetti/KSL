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
 * @param theCreateTime the time created
 * @param aName The name of the QObject
*/
open class QObject(theCreateTime: Double, aName: String? = null) : NameIfc, Comparable<QObject> {
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
    val createTime = theCreateTime

    /**
     * A state representing when the QObject was queued
     */
    private val myQueuedState: State = State(name = "{$name}_State")

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
     * Sets the priority to the supplied value If the QObject is queued, the
     * queue's changePriority() method is called (possibly causing a reordering
     * of the queue) which may cause significant reordering overhead otherwise
     * the priority is directly changed Changing this value only changes how the
     * QObjects are compared and may or may not change how they are ordered in
     * the queue, depending on the queue discipline used
     */
    var priority: Int = 1
        set(value) {
            field = value // always make the change
            if (isQueued) {
                //change the priority here
                // then just tell the queue that there was a change that needs handling
                //myQueue.priorityChanged(this)
                queue?.priorityChanged()
            }
        }

    /**
     * The current queue that the QObject is in, null if not in a queue
     */
    var queue: Queue<*>? = null //TODO why do I need the type parameter and why can't it be T: QObject
        internal set

    /**
     * A reference to an object that can be attached to the QObject when queued
     */
    var attachedObject: Any? = null

    /**
     * can be used to time stamp the qObject
     */
    var timeStamp: Double = theCreateTime
        set(value) {
            require(value >= 0.0) { "The time stamp was less than 0.0" }
            field = value
        }

    /**
     * Allows for a generic value to be held by the QObject
     */
    var valueObject: GetValueIfc? = null

    override fun toString(): String {
        return "ID= $id, name= $name"
    }

    /**
     * The time that the QObject was LAST enqueued
     */
    val timeEnteredQueue: Double
        get() = myQueuedState.timeStateEntered

    /**
     *  The time that the QObject LAST exited a queue
     */
    val timeExitedQueue: Double
        get() = myQueuedState.timeStateExited

    /**
     * The time that the QObject spent in the Queue based on the LAST time dequeued
     */
    val timeInQueue: Double
        get() = myQueuedState.totalTimeInState

    /**
     * Checks if the QObject is queued
     */
    val isQueued: Boolean
        get() = myQueuedState.isEntered

    val isNotQueued: Boolean
        get() = !isQueued

    /**
     * Causes all references of objects from this QObject to be set to null.
     *
     * Meant primarily to facilitate garbage collection. After this call, the
     * object should not be used.
     *
     */
    fun nullify() {
        attachedObject = null
        valueObject = null
        queue = null
    }

    /**
     * Used by Queue to indicate that the QObject has entered the queue
     *
     * @param queue the queue entered
     * @param time the time
     * @param priority the priority
     * @param obj an object to attach
     */
    internal fun <T:QObject> enterQueue (queue: Queue<T>, time: Double, priority: Int, obj: Any?) {
        //TODO why do I need the type parameter and why can't it be T: QObject
        check(isNotQueued) { "The QObject was already queued!" }
        myQueuedState.enter(time)
        this.queue = queue //TODO
        this.priority = priority
        attachedObject = obj
    }

    /**
     * Used by Queue to indicate that the QObject exited the queue
     *
     * @param time The time QObject exited the queue
     */
    internal fun exitQueue(time: Double) {
        check(isQueued) { "The QObject was not in a queue!" }
        myQueuedState.exit(time)
        queue = null
    }

    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     *
     * Throws ClassCastException if the specified object's type prevents it from
     * being compared to this object.
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

        // compare the priorities
        if (priority < other.priority) {
            return -1
        }
        if (priority > other.priority) {
            return 1
        }

        // priorities are equal, compare time stamps
        if (timeEnteredQueue < other.timeEnteredQueue) {
            return -1
        }
        if (timeEnteredQueue > other.timeEnteredQueue) {
            return 1
        }

        // time stamps are equal, compare ids
        if (id < other.id) // lower id, implies created earlier
        {
            return -1
        }
        if (id > other.id) {
            return 1
        }

        // if the ids are equal then the object references must be equal
        // if this is not the case there is a problem
        return if (this == other) {
            0
        } else {
            throw RuntimeException("Id's were equal, but references were not, in QObject compareTo")
        }
    }

}