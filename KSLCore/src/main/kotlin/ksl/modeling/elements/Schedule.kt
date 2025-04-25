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
package ksl.modeling.elements

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

/** A Schedule represents a known set of events that can occur according to a pattern.
 * A schedule contains one or more instances of ScheduleItem.  A ScheduleItem represents an item on a
 * Schedule. It has a start time, relative to the start of the Schedule and a duration.
 * If more than one schedule item needs to start at
 * the same time, then a priority can be provided to determine the ordering (i.e. the smallest priority goes first).
 * ScheduleItems are not scheduled to occur until the Schedule actually starts.
 *
 * A Schedule has an auto start flag, which controls whether the schedule should start automatically
 * upon initialization (at the start of the simulation). The default is to start automatically.
 *
 * A Schedule has an initial start time, which represents the amount of time after the beginning of
 * the simulation that the schedule is to start. The default start time is zero (at the beginning of the simulation).
 *
 * A Schedule as a length (or duration) that represents the total time associated with the schedule. After this
 * time has elapsed the entire schedule can repeat if the repeat option is on. The default length of a schedule
 * is infinite.  The total or maximum duration of scheduled items cannot exceed the schedule duration if it is finite.
 *
 * A Schedule has a repeat flag that controls whether it will repeat after its duration has elapsed. The
 * default is to repeat the schedule and is only relevant if the schedule duration (length) is finite.
 *
 * A Schedule has a cycle start time that represents when the schedule started its current cycle. Again, this
 * is only relevant if the repeat flag is true and the schedule duration is finite. If there is only one cycle, it is
 * the time that the schedule started.
 *
 * To make a Schedule useful, instances of the ScheduleChangeListenerIfc interface should be added to
 * listen for changes in the schedule.  Instances of ScheduleChangeListenerIfc are notified in the order
 * in which they are added to the schedule.  Instances of ScheduleChangeListenerIfc are notified when the
 * schedule starts, when it ends, and when any ScheduleItem starts and ends.  It is up to the instance
 * of ScheduleChangeListenerIfc to react to the schedule changes that it needs to react to and ignore those
 * that it does not care about.
 *
 * @author rossetti
 */
class Schedule(
    parent: ModelElement,
    startTime: Double = 0.0,
    length: Double = Double.POSITIVE_INFINITY,
    autoStartOption: Boolean = true,
    repeatable: Boolean = true,
    startPriority: Int = KSLEvent.DEFAULT_PRIORITY - 5,
    itemPriority: Int = KSLEvent.DEFAULT_PRIORITY - 4,
    name: String? = null
) : ModelElement(parent, name) {

    private var idCounter: Long = 0

    /**
     * Indicates whether the schedule should be started automatically upon initialization, default is true
     */
    val isAutoStartFlag: Boolean = autoStartOption

    /**
     * The time from the beginning of the replication to the time that the schedule is to start
     */
    val initialStartTime: Double = startTime

    /**
     * Represents the total length of time of the schedule.
     * The total of the durations added to the schedule cannot exceed this
     * amount.
     * After this time has elapsed the entire schedule can repeat if the
     * schedule repeat flag is true. The default is infinite.
     */
    val scheduleLength: Double = length

    /**
     * The time that the schedule started for its current cycle
     *
     */
    var cycleStartTime = 0.0
        private set

    /**
     * The schedule repeat flag controls whether
     * the entire schedule will repeat after its entire duration
     * has elapsed. The default is to repeat the schedule. The
     * use of this flag only makes sense if a finite schedule length is
     * specified
     *
     */
    val isScheduleRepeatable: Boolean = repeatable

    /**
     *
     * the priority associated with the item's start event
     */
    val itemStartEventPriority: Int = itemPriority

    /**
     *
     * the priority associated with the schedule's start event
     */
    val startEventPriority: Int = startPriority

    private val myItems: MutableList<ScheduleItem<*>> = mutableListOf()
    private val myChangeListeners: MutableList<ScheduleChangeListenerIfc> = mutableListOf()
    private var myStartScheduleEvent: KSLEvent<Nothing>? = null

    /**
     * If scheduled to start, this cancels the start of the schedule.
     */
    fun cancelScheduleStart() {
        if (myStartScheduleEvent != null) {
            myStartScheduleEvent?.cancel = true
        }
    }

    /**
     * The same listener cannot be added more than once. Listeners are
     * notified of schedule changes in the sequence by which they were added.
     *
     * @param listener the listener to add to the schedule
     */
    fun addScheduleChangeListener(listener: ScheduleChangeListenerIfc) {
        require(!myChangeListeners.contains(listener)) { "The supplied listener is already attached" }
        myChangeListeners.add(listener)
    }

    /**
     *
     * @param listener the listener to delete from the schedule
     */
    fun deleteScheduleChangeListener(listener: ScheduleChangeListenerIfc) {
        myChangeListeners.remove(listener)
    }

    /**
     * Deletes all listeners
     */
    fun deleteScheduleChangeListeners() {
        myChangeListeners.clear()
    }

    /**
     *
     * @param listener the listener to check
     * @return true if the listener is already added
     */
    operator fun contains(listener: ScheduleChangeListenerIfc): Boolean {
        return myChangeListeners.contains(listener)
    }

    /**
     *
     * @return the number of listeners
     */
    fun countScheduleChangeListeners(): Int {
        return myChangeListeners.size
    }

    /** Adds all the items to the schedule.
     *
     *  @param items the items to add
     */
    fun <T> addItems(items: List<ScheduleItem<T>>) {
        for (item in items) {
            addItem(item)
        }
    }

    /**
     *  Adds the item to the schedule
     *  @param item the item to add. The item's end time must be less than or equal to
     *  the schedule's initial start time plus the schedule length
     */
    fun <T> addItem(item: ScheduleItem<T>) {
        require(item.endTime <= initialStartTime + scheduleLength) { "The item's end time is past the schedule's end." }

        // nothing in the list, just add to beginning
        if (myItems.isEmpty()) {
            myItems.add(item)
        }
        // might as well check for worse case, if larger than the largest
        // then put it at the end and return
        if (item.compareTo(myItems[myItems.size - 1]) >= 0) {
            myItems.add(item)
        }

        // now iterate through the list
        val i: ListIterator<ScheduleItem<*>> = myItems.listIterator()
        while (i.hasNext()) {
            if (item.compareTo(i.next()) < 0) {
                // next() move the iterator forward, if it is < what was returned by next(), then it
                // must be inserted at the previous index
                myItems.add(i.previousIndex(), item)
                break
            }
        }
    }

    /**
     * Adds an item to the schedule
     *
     * @param <T> the type of the message
     * @param startTime the time past the start of the schedule to start the
     * item
     * @param duration the duration of the item
     * @param priority the priority, (among items) if items start at the same
     * time
     * @param datum a message or datum to attach to the item
     * @return the created ScheduleItem
    </T> */
    fun <T> addItem(
        startTime: Double = 0.0,
        duration: Double,
        priority: Int = itemStartEventPriority,
        datum: T? = null
    ): ScheduleItem<T> {
        val aItem: ScheduleItem<T> = ScheduleItem(startTime, duration, priority, datum)
        addItem(aItem)
        return aItem
    }

    /** Removes the item from the schedule. If the item is null or not on this
     * schedule nothing happens.
     *
     * @param item the item to remove
     */
    fun removeItem(item: ScheduleItem<*>?) {
        if (item == null) {
            return
        }
        myItems.remove(item)
    }

    /**
     * Removes all schedule items from the schedule
     */
    fun clearSchedule() {
        myItems.clear()
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append("Schedule: ")
        sb.append(name)
        sb.append(System.lineSeparator())
        sb.append("Initial Start Time = ").append(initialStartTime)
        sb.append(System.lineSeparator())
        sb.append("Length = ").append(scheduleLength)
        sb.append(System.lineSeparator())
        sb.append("Auto start = ").append(isAutoStartFlag)
        sb.append(System.lineSeparator())
        sb.append("Repeats = ").append(isScheduleRepeatable)
        sb.append(System.lineSeparator())
        sb.append("Start event priority = ").append(startEventPriority)
        sb.append(System.lineSeparator())
        sb.append("Item Start event priority = ").append(itemStartEventPriority)
        sb.append(System.lineSeparator())
        sb.append("Items:")
        sb.append(System.lineSeparator())
        sb.append("-----------------------------------------------------------------------------")
        sb.append(System.lineSeparator())
        for (i in myItems) {
            sb.append(i).append(System.lineSeparator())
        }
        sb.append("-----------------------------------------------------------------------------")
        sb.append(System.lineSeparator())
        return sb.toString()
    }

    private fun notifyScheduleChangeListenersScheduleStarted() {
        for (listener in myChangeListeners) {
            listener.scheduleStarted(this)
        }
    }

    private fun notifyScheduleChangeListenersScheduleEnded() {
        for (listener in myChangeListeners) {
            listener.scheduleEnded(this)
        }
    }

    private fun notifyScheduleChangeListenersScheduleItemEnded(item: ScheduleItem<*>) {
        for (listener in myChangeListeners) {
            listener.scheduleItemEnded(item)
        }
    }

    private fun notifyScheduleChangeListenersScheduleItemStarted(item: ScheduleItem<*>) {
        for (listener in myChangeListeners) {
            listener.scheduleItemStarted(item)
        }
    }

    override fun initialize() {
        cycleStartTime = Double.NaN
        if (isAutoStartFlag) {
            scheduleStart()
        }
    }

    override fun afterReplication() {
        super.afterReplication()
        myStartScheduleEvent = null
    }

    /**
     * Schedules the start of the schedule for the start time of the schedule
     * if it has not already been started
     */
    fun scheduleStart() {
        if (myStartScheduleEvent == null) {
            // priority for starting the schedule must be lower than the first
            // item on the schedule to ensure it goes first
            var priority = startEventPriority
            if (myItems.isNotEmpty()) {
                val p = myItems[0].priority
                if (p < priority) {
                    priority = p - 1
                }
            }
            myStartScheduleEvent = schedule(this::startSchedule, initialStartTime, priority = priority)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun startSchedule(event: KSLEvent<Nothing>) {
        cycleStartTime = time
        // logic for what to do when schedule is started
        notifyScheduleChangeListenersScheduleStarted()
        for (item in myItems) {
            scheduleItemStart(item)
        }
        scheduleEndOfSchedule()
    }

    private fun endSchedule(event: KSLEvent<Nothing>) {
        notifyScheduleChangeListenersScheduleEnded()
        if (isScheduleRepeatable) {
            startSchedule(event)
        }
    }

    private fun startItem(event: KSLEvent<ScheduleItem<*>>) {
        val item = event.message as ScheduleItem<*>
        notifyScheduleChangeListenersScheduleItemStarted(item)
        scheduleItemEnd(item)
    }

    private fun endItem(event: KSLEvent<ScheduleItem<*>>) {
        val item = event.message as ScheduleItem<*>
        notifyScheduleChangeListenersScheduleItemEnded(item)
    }

    private fun scheduleEndOfSchedule() {
        // priority for end the schedule must be lower than the first
        // item and lower than the start of the schedule to ensure it goes first
        var priority = startEventPriority
        if (myItems.isNotEmpty()) {
            val p = myItems[0].priority
            if (p < priority) {
                priority = p - 2
            }
        }
        schedule(this::endSchedule, scheduleLength, priority = priority)
    }

    private fun scheduleItemStart(item: ScheduleItem<*>) {
        // if the item's start time is 0.0 relative to the start of 
        // the schedule its priority must be after the start of schedule
        var priority = item.priority
        if (item.startTime == 0.0) {
            // check priority
            if (item.priority <= startEventPriority) {
                priority = startEventPriority + 1
            }
        }
        val e: KSLEvent<ScheduleItem<*>> = schedule(this::startItem, item.startTime, item, priority)
        //e.setMessage(item);
        item.myStartEvent = e
    }

    private fun scheduleItemEnd(item: ScheduleItem<*>) {
        // if the item's end time is at the same time as the end of the
        // schedule then it's priority needs to be before the priority
        // of the end of the schedule
        var priority = item.priority
        if (item.endTime == scheduleLength) {
            // need to adjust priority, compute end priority
            var endPriority = startEventPriority
            if (myItems.isNotEmpty()) {
                val p = myItems[0].priority
                if (p < endPriority) {
                    endPriority = p - 2
                }
            }
            priority = endPriority - 1
        }
        val event: KSLEvent<ScheduleItem<*>> = schedule(this::endItem, item.duration, item, priority - 1)
        //event.setMessage(item);
        item.myEndEvent = event
    }

    /** A ScheduleItem represents an item on a Schedule. It has a start time, relative to the
     * start of the Schedule and a duration. If more than one schedule item needs to start at
     * the same time, then a priority can be provided to determine the ordering (i.e. the smallest priority goes first).
     *
     * @param <T> a general message or other object that can be associated with the ScheduleItem
    </T> */
    open inner class ScheduleItem<T>(
        val startTime: Double,
        val duration: Double,
        val priority: Int,
        val message: T? = null
    ) :
        Comparable<ScheduleItem<*>> {
        init {
            require(startTime >= 0.0) { "The start time must be >= 0.0" }
            require(duration > 0.0) { "The duration must be > 0.0" }
            idCounter += 1
        }

        val id: Long = idCounter
        var name: String = "Item:$id"
        val schedule: Schedule = this@Schedule
        internal var myStartEvent: KSLEvent<ScheduleItem<*>>? = null
        internal var myEndEvent: KSLEvent<ScheduleItem<*>>? = null

        val endTime: Double
            get() = startTime + duration

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("ID = ")
            sb.append(id)
            sb.append(" | name = ")
            sb.append(name)
            sb.append(" | Priority = ")
            sb.append(priority)
            sb.append(" | Start time = ")
            sb.append(startTime)
            sb.append(" | Duration = ")
            sb.append(duration)
            if (myStartEvent != null) {
                sb.append(" | Start event priority = ")
                sb.append(myStartEvent?.priority)
            }
            if (myEndEvent != null) {
                sb.append(" | End event priority = ")
                sb.append(myEndEvent?.priority)
            }
            return sb.toString()
        }

        /**
         * Returns a negative integer, zero, or a positive integer if this
         * object is less than, equal to, or greater than the specified object.
         *
         * Natural ordering: time, then priority, then order of creation
         *
         * Lower time, lower priority, lower order of creation goes first
         *
         * Throws ClassCastException if the specified object's type prevents it
         * from being compared to this object.
         *
         * Throws RuntimeException if the id's of the objects are the same, but
         * the references are not when compared with equals.
         *
         * Note: This class may have a natural ordering that is inconsistent with equals.
         *
         * @param other The event to compare this event to
         * @return Returns a negative integer, zero, or a positive integer if
         * this object is less than, equal to, or greater than the specified object.
         */
        override operator fun compareTo(other: ScheduleItem<*>): Int {

            // compare time first
            if (startTime < other.startTime) {
                return -1
            }
            if (startTime > other.startTime) {
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
                throw RuntimeException("Id's were equal, but references were not, in ScheduleItem compareTo")
            }
        }
    }
}

fun main() {
    val m = Model()
    val s = Schedule(m, startTime = 0.0, length = 480.0)
    s.addItem(60.0 * 2.0, 15.0, datum = "break1")
    s.addItem((60.0 * 4.0), 30.0, datum = "lunch")
    s.addItem((60.0 * 6.0), 15.0, datum = "break2")
    s.addScheduleChangeListener(ScheduleListener())

    println(s)
    println()
    m.lengthOfReplication = 500.0
    m.numberOfReplications = 2

    m.simulate()

}

class ScheduleListener() : ScheduleChangeListenerIfc {
    override fun scheduleStarted(schedule: Schedule) {
        println("time = ${schedule.time} Schedule Started")
    }

    override fun scheduleEnded(schedule: Schedule) {
        println("time = ${schedule.time} Schedule Ended")
    }

    override fun scheduleItemStarted(item: Schedule.ScheduleItem<*>) {
        println("time = ${item.schedule.time} scheduled item ${item.name} started with message ${item.message}")
    }

    override fun scheduleItemEnded(item: Schedule.ScheduleItem<*>) {
        println("time = ${item.schedule.time} scheduled item ${item.name} ended with message ${item.message}")
    }

}