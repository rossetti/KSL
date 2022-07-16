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
package ksl.simulation

import jsl.modeling.elements.entity.Entity
import jsl.simulation.ModelElement
import java.text.DecimalFormat

/**
 * This class represents a simulated event.  It allows for the simulation of
 * durations of simulated time. These events are placed on the Executive and ordered by time, priority,
 * and order of creation.
 *
 * The constructor has package scope because only the Executive class can make events.
 *
 * @param theAction the action for the event
 * @param theTime the time that the event will be scheduled to occur
 * @param thePriority the priority associated with the event, smaller is more important
 * @param theMessage an object of type T attached to the event
 * @param theName the name of the event
 * @param theModelElement the model element that scheduled the event
 * @param <T> the type associated with the (optional) message sent with the event
 */
class JSLEvent<T> internal constructor(
    theId: Long,
    theAction: EventActionIfc<T>,
    theTime: Double = 0.0,
    thePriority: Int = DEFAULT_PRIORITY,
    theMessage: T? = null,
    theName: String? = null,
    theModelElement: ModelElement
) : Comparable<JSLEvent<*>> {

    init {
        require(theTime >= 0.0) { "The event time must be >= 0.0" }
    }

    private val action: EventActionIfc<T> = theAction
    val time: Double = theTime
    val priority: Int = thePriority
    val message: T? = theMessage
    var name: String? = theName
        get() {
            return if (field == null) {
                "Event_{$id}"
            } else {
                field
            }
        }
        internal set

    private val modelElement: ModelElement = theModelElement

    /**
     *  Unique number assigned when the event is scheduled
     */
    val id: Long = theId

    /**
     * Gets a flag indicating whether the event is to be canceled or not. True
     * implies that the event is to be canceled. It is up to the executive to
     * handle the cancellation. If the event is canceled, it's execute method
     * will not be called when it becomes the current event. Thus, an event can
     * be canceled or uncanceled at any simulated time prior to when the event
     * is scheduled to occur.
     */
    var cancelled: Boolean = false
        set(value) {
            require(scheduled) { "Cannot cancel an event that is not scheduled" }
            field = value
        }

    /**
     * Whether the event is scheduled.
     * The executive should indicate if the event is scheduled
     */
    var scheduled: Boolean = false
        internal set

    /**
     * Allows the association of an Entity with the event
     */
    var entity: Entity? = null
        internal set

    /**
     * Provides a string representation for the event. Useful for tracing
     *
     * @return A String representing the event
     */
    override fun toString(): String {
        val sb = StringBuilder()
        val df = DecimalFormat("0.####E0")
        sb.append(df.format(time))
        sb.append(" > Event = ")
        sb.append(name)
        sb.append(" : ")
        sb.append("ID = ")
        sb.append(id)
        sb.append(" : ")
        sb.append("Priority = ")
        sb.append(priority)
        sb.append(" : ")
        sb.append("Scheduled by = ")
        sb.append(modelElement.name)
        return sb.toString()
    }

    /**
     * Called by the Executive class to cause the EventAction to have its
     * action method invoked
     */
    internal fun execute() {
        if (!cancelled) {
            action.action(this)
        }
    }

    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     * Natural ordering: time, then priority, then order of creation
     *
     * Lower time, lower priority, lower order of creation goes first
     *
     * Throws ClassCastException if the specified object's type prevents it from
     * being compared to this object.
     *
     * Throws RuntimeException if the id's of the objects are the same, but the
     * references are not when compared with equals.
     *
     * Note: This class may have a natural ordering that is inconsistent with
     * equals.
     *
     * @param other The event to compare this event to
     * @return Returns a negative integer, zero, or a positive integer if this
     * object is less than, equal to, or greater than the specified object.
     */
    override operator fun compareTo(other: JSLEvent<*>): Int {
        // compare time first
        if (time < other.time) {
            return -1
        }
        if (time > other.time) {
            return 1
        }

        // times are equal, check priorities
        if (priority < other.priority) {
            return -1
        }
        if (priority > other.priority) {
            return 1
        }

        // time and priorities are equal, compare ids
        // lower id, implies created earlier
        if (id < other.id) {
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
            throw RuntimeException("Id's were equal, but references were not, in JSLEvent compareTo")
        }
    }

    companion object {
        /**
         * Represents the default priority for events within the Executive
         * DEFAULT_PRIORITY = 10. Lower priority goes first. All integer priority
         * numbers can be used to set the priority of an event.
         */
        const val DEFAULT_PRIORITY = 10

        /**
         * Default event priority for the end replication event
         */
        const val DEFAULT_END_REPLICATION_EVENT_PRIORITY = 10000

        /**
         * A constant for the default warm up event priority
         */
        const val DEFAULT_WARMUP_EVENT_PRIORITY = 9000

        /**
         * A constant for the default batch priority
         */
        const val DEFAULT_BATCH_PRIORITY = 8000

    }
}