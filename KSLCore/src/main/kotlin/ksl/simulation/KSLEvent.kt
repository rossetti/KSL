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
package ksl.simulation

import ksl.modeling.entity.ProcessModel.Entity //TODO not sure if this is needed anymore, why should event have an entity?
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
class KSLEvent<out T> internal constructor(
    theId: Long,
    theAction: ModelElement.EventActionIfc<T>,
    theTime: Double = 0.0,
    thePriority: Int = DEFAULT_PRIORITY,
    theMessage: T? = null,
    theName: String? = null,
    theModelElement: ModelElement
) : Comparable<KSLEvent<*>> {

    init {
        require(theTime >= 0.0) { "The event time must be >= 0.0" }
    }

    private val myAction: ModelElement.EventActionIfc<T> = theAction

    /**
     *  The time that the event will occur
     */
    val time: Double = theTime

    /**
     *  The priority associated with the event
     */
    val priority: Int = thePriority

    /**
     * An optional message attached to the event
     */
    val message: T? = theMessage

    /**
     *  The difference between when the event will occur and the time that it was created (placed on the calendar)
     */
    val interEventTime : Double
        get() = time - timeCreated

    /**
     *  The time from the current time until time that the event will occur
     */
    val timeRemaining : Double
        get() = time - modelElement.time

    var name: String? = theName
        get() {
            return if (field == null) {
                "Event_$id"
            } else {
                field
            }
        }
        internal set

    internal val modelElement: ModelElement = theModelElement

    /**
     * The time that the event was created and placed on the calendar
     */
    val timeCreated : Double = theModelElement.time

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
     * is scheduled to occur. It is an error to attempt to cancel an event that
     * is not scheduled.
     */
    var cancel: Boolean = false
        set(value) {
            require(isScheduled) { "Cannot cancel an event that is not scheduled" }
            field = value
        }

    /**
     * Whether the event is scheduled.
     * The executive should indicate if the event is scheduled
     */
    var isScheduled: Boolean = false
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
        //val df = DecimalFormat("####.####E0")
        sb.append("Replication = ${modelElement.myModel.currentReplicationNumber} : ")
        sb.append("Name = ")
        sb.append(name)
        sb.append(" : ")
        sb.append(" time = ").append(time)
        sb.append(" : ")
        sb.append("event_id = ")
        sb.append(id)
        sb.append(" : ")
        sb.append("Priority = ")
        sb.append(priority)
        sb.append(" : ")
        sb.append("is scheduled = ")
        sb.append(isScheduled)
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
        if (!cancel) {
            myAction.action(this)
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
    override operator fun compareTo(other: KSLEvent<*>): Int {
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
            throw RuntimeException("Id's were equal, but references were not, in KSLEvent compareTo")
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

        /**
         * A constant for the default timed update priority
         */
        const val DEFAULT_TIMED_EVENT_PRIORITY = 3

    }
}