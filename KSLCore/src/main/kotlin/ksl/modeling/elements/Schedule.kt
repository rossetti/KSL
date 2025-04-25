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

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.io.ToJSONIfc

//TODO: provide ability to easily create non-overlapping sequence of durations


@Serializable
data class ScheduleItemData(
    val name: String,
    var startTime: Double = 0.0,
    var duration: Double,
    var priority: Int = KSLEvent.DEFAULT_PRIORITY - 4,
    var message: String? = null,
    var datum: Double? = null
) {
    init {
        require(name.isNotBlank()) { "The name cannot be blank" }
        require(duration > 0.0) { "The duration must be > 0.0" }
        require(startTime >= 0.0) { "The start time must be >= 0.0" }
    }
}

@Serializable
data class ScheduleData(
    var startTime: Double = 0.0,
    var length: Double = Double.POSITIVE_INFINITY,
    var autoStartOption: Boolean = true,
    var repeatable: Boolean = true,
    var startPriority: Int = KSLEvent.DEFAULT_PRIORITY - 5,
    var itemPriority: Int = KSLEvent.DEFAULT_PRIORITY - 4,
    val items: List<ScheduleItemData>
) : ToJSONIfc {
    init {
        require(startTime >= 0.0) { "The start time must be >= 0.0" }
        require(length > 0.0) { "The length must be > 0" }
    }

    override fun toJson(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(this)
    }
}

interface ScheduleCIfc {
    /**
     * Indicates whether the schedule should be started automatically upon initialization, default is true
     */
    var isAutoStartFlag: Boolean

    /**
     * The time from the beginning of the replication to the time that the schedule is to start
     */
    var initialStartTime: Double

    /**
     * Represents the total length of time of the schedule.
     * The total of the durations added to the schedule cannot exceed this
     * amount.
     * After this time has elapsed the entire schedule can repeat if the
     * schedule repeat flag is true. The default is infinite.
     */
    var scheduleLength: Double

    /**
     * The schedule repeat flag controls whether
     * the entire schedule will repeat after its entire duration
     * has elapsed. The default is to repeat the schedule. The
     * use of this flag only makes sense if a finite schedule length is
     * specified
     *
     */
    var isScheduleRepeatable: Boolean

    /**
     *
     * the priority associated with the item's start event
     */
    var itemStartEventPriority: Int

    /**
     *
     * the priority associated with the schedule's start event
     */
    var startEventPriority: Int

    /**
     * The same listener cannot be added more than once. Listeners are
     * notified of schedule changes in the sequence by which they were added.
     *
     * @param listener the listener to add to the schedule
     */
    fun addScheduleChangeListener(listener: ScheduleChangeListenerIfc)

    /**
     *
     * @param listener the listener to delete from the schedule
     */
    fun deleteScheduleChangeListener(listener: ScheduleChangeListenerIfc)

    /**
     * Deletes all listeners
     */
    fun deleteScheduleChangeListeners()

    /**
     *
     * @param listener the listener to check
     * @return true if the listener is already added
     */
    operator fun contains(listener: ScheduleChangeListenerIfc): Boolean

    /**
     *
     * @return the number of listeners
     */
    fun countScheduleChangeListeners(): Int

    /**
     *  Adds the item to the schedule
     *  @param item the item to add. The item's end time must be less than or equal to
     *  the schedule's initial start time plus the schedule length
     */
    fun addItem(scheduleItemData: ScheduleItemData): Schedule.ScheduleItem

    /** Adds all the items to the schedule.
     *
     *  @param items the items to add
     */
    fun addItems(items: List<ScheduleItemData>)

    /** Removes the item from the schedule. If the item is null or not on this
     * schedule nothing happens.
     *
     * @param item the item to remove
     */
    fun removeItem(item: Schedule.ScheduleItem?)

    /**
     * Removes all schedule items from the schedule
     */
    fun clearSchedule()

}

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
) : ModelElement(parent, name), ScheduleCIfc {

    init {
        require(startTime >= 0.0) { "The start time must be >= 0.0" }
        require(length > 0.0) { "The length must be > 0" }
    }

    constructor(
        parent: ModelElement,
        scheduleData: ScheduleData,
        name: String? = null
    ) : this(
        parent, scheduleData.startTime, scheduleData.length, scheduleData.autoStartOption,
        scheduleData.repeatable, scheduleData.startPriority, scheduleData.itemPriority, name
    ) {
        addItems(scheduleData.items)
    }

    private var idCounter: Int = 0

    /**
     * Indicates whether the schedule should be started automatically upon initialization, default is true
     */
    override var isAutoStartFlag: Boolean = autoStartOption
        set(value) {
            require(model.isNotRunning) { "The model must not be running when configuring the schedule" }
            field = value
        }

    /**
     * The time from the beginning of the replication to the time that the schedule is to start
     */
    override var initialStartTime: Double = startTime
        set(value) {
            require(model.isNotRunning) { "The model must not be running when configuring the schedule" }
            require(value >= 0.0) { "The initial start time must be greater than or equal to zero" }
            field = value
        }

    /**
     * Represents the total length of time of the schedule.
     * The total of the durations added to the schedule cannot exceed this
     * amount.
     * After this time has elapsed the entire schedule can repeat if the
     * schedule repeat flag is true. The default is infinite.
     */
    override var scheduleLength: Double = length
        set(value) {
            require(model.isNotRunning) { "The model must not be running when configuring the schedule" }
            require(value > 0) { "The schedule length must be greater than zero" }
            field = value
        }

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
    override var isScheduleRepeatable: Boolean = repeatable
        set(value) {
            require(model.isNotRunning) { "The model must not be running when configuring the schedule" }
            field = value
        }

    /**
     *
     * the priority associated with the item's start event
     */
    override var itemStartEventPriority: Int = itemPriority
        set(value) {
            require(model.isNotRunning) { "The model must not be running when configuring the schedule" }
            field = value
        }

    /**
     *
     * the priority associated with the schedule's start event
     */
    override var startEventPriority: Int = startPriority
        set(value) {
            require(model.isNotRunning) { "The model must not be running when configuring the schedule" }
            field = value
        }

    private val myItems: MutableList<ScheduleItem> = mutableListOf()
    private val myItemNames = mutableSetOf<String>()
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
    override fun addScheduleChangeListener(listener: ScheduleChangeListenerIfc) {
        require(!myChangeListeners.contains(listener)) { "The supplied listener is already attached" }
        myChangeListeners.add(listener)
    }

    /**
     *
     * @param listener the listener to delete from the schedule
     */
    override fun deleteScheduleChangeListener(listener: ScheduleChangeListenerIfc) {
        myChangeListeners.remove(listener)
    }

    /**
     * Deletes all listeners
     */
    override fun deleteScheduleChangeListeners() {
        myChangeListeners.clear()
    }

    /**
     *
     * @param listener the listener to check
     * @return true if the listener is already added
     */
    override operator fun contains(listener: ScheduleChangeListenerIfc): Boolean {
        return myChangeListeners.contains(listener)
    }

    /**
     *
     * @return the number of listeners
     */
    override fun countScheduleChangeListeners(): Int {
        return myChangeListeners.size
    }

    /**
     *  Adds the item to the schedule
     *  @param item the item to add. The item's end time must be less than or equal to
     *  the schedule's initial start time plus the schedule length
     */
    private fun addItem(item: ScheduleItem) {
        require(item.endTime <= initialStartTime + scheduleLength) { "The item's end time is past the schedule's end." }
        require(!myItemNames.contains(item.name)) { "The supplied item name is already on the schedule." }

        // nothing in the list, just add to beginning
        if (myItems.isEmpty()) {
            myItems.add(item)
            myItemNames.add(item.name)
            return
        }
        // might as well check for worse case, if larger than the largest
        // then put it at the end and return
        if (item.compareTo(myItems[myItems.size - 1]) >= 0) {
            myItems.add(item)
            myItemNames.add(item.name)
            return
        }

        // now iterate through the list
        val i: ListIterator<ScheduleItem> = myItems.listIterator()
        while (i.hasNext()) {
            if (item.compareTo(i.next()) < 0) {
                // next() move the iterator forward, if it is < what was returned by next(), then it
                // must be inserted at the previous index
                myItems.add(i.previousIndex(), item)
                myItemNames.add(item.name)
                break
            }
        }
    }

    /**
     * Adds an item to the schedule
     *
     * @param startTime the time past the start of the schedule to start the
     * item
     * @param duration the duration of the item
     * @param name the unique name of the item on the schedule
     * @param priority the priority, (among items) if items start at the same
     * time
     * @param message a message to attach to the item
     * @param datum a datum to attach to the item
     * @return the created ScheduleItem
     * */
    fun addItem(
        startTime: Double,
        duration: Double,
        name: String = "Item:${idCounter}",
        priority: Int = itemStartEventPriority,
        message: String? = null,
        datum: Double? = null
    ): ScheduleItem {
        val aItem: ScheduleItem = ScheduleItem(name, startTime, duration, priority, message, datum)
        addItem(aItem)
        return aItem
    }

    /**
     *  Adds the item to the schedule
     *  @param scheduleItemData the item to add. The item's end time must be less than or equal to
     *  the schedule's initial start time plus the schedule length
     */
    override fun addItem(scheduleItemData: ScheduleItemData): ScheduleItem {
        return addItem(
            scheduleItemData.startTime, scheduleItemData.duration, scheduleItemData.name,
            scheduleItemData.priority, scheduleItemData.message, scheduleItemData.datum
        )
    }

    /** Adds all the items to the schedule.
     *
     *  @param items the items to add
     */
    override fun addItems(items: List<ScheduleItemData>) {
        for (item in items) {
            addItem(item)
        }
    }

    /** Removes the item from the schedule. If the item is null or not on this
     * schedule nothing happens.
     *
     * @param item the item to remove
     */
    override fun removeItem(item: ScheduleItem?) {
        if (item == null) {
            return
        }
        myItems.remove(item)
        myItemNames.remove(item.name)
    }

    /**
     * Removes all schedule items from the schedule
     */
    override fun clearSchedule() {
        myItems.clear()
        myItemNames.clear()
    }

    override fun toString(): String {
        return asString()
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.appendLine("Schedule: $name")
        sb.appendLine("Initial Start Time = $initialStartTime")
        sb.appendLine("Length = $scheduleLength")
        sb.appendLine("Auto start = $isAutoStartFlag")
        sb.appendLine("Repeats = $isScheduleRepeatable")
        sb.appendLine("Start event priority = $startEventPriority")
        sb.appendLine("Item Start event priority = $itemStartEventPriority")
        sb.appendLine("Items:")
        sb.appendLine("-------------------------------------------------------------------------------")
        for (i in myItems) {
            sb.appendLine(i)
        }
        sb.appendLine("-------------------------------------------------------------------------------")
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

    private fun notifyScheduleChangeListenersScheduleItemEnded(item: ScheduleItem) {
        for (listener in myChangeListeners) {
            listener.scheduleItemEnded(item)
        }
    }

    private fun notifyScheduleChangeListenersScheduleItemStarted(item: ScheduleItem) {
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

    private fun startItem(event: KSLEvent<ScheduleItem>) {
        val item = event.message as ScheduleItem
        notifyScheduleChangeListenersScheduleItemStarted(item)
        scheduleItemEnd(item)
    }

    private fun endItem(event: KSLEvent<ScheduleItem>) {
        val item = event.message as ScheduleItem
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

    private fun scheduleItemStart(item: ScheduleItem) {
        // if the item's start time is 0.0 relative to the start of 
        // the schedule its priority must be after the start of schedule
        var priority = item.priority
        if (item.startTime == 0.0) {
            // check priority
            if (item.priority <= startEventPriority) {
                priority = startEventPriority + 1
            }
        }
        val e: KSLEvent<ScheduleItem> = schedule(this::startItem, item.startTime, item, priority)
        //e.setMessage(item);
        item.myStartEvent = e
    }

    private fun scheduleItemEnd(item: ScheduleItem) {
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
        val event: KSLEvent<ScheduleItem> = schedule(this::endItem, item.duration, item, priority - 1)
        //event.setMessage(item);
        item.myEndEvent = event
    }

    /** A ScheduleItem represents an item on a Schedule. It has a start time, relative to the
     * start of the Schedule and a duration. If more than one schedule item needs to start at
     * the same time, then a priority can be provided to determine the ordering (i.e. the smallest priority goes first).
     *
     * @param <T> a general message or other object that can be associated with the ScheduleItem
    </T> */
    open inner class ScheduleItem(
        val name: String = "Item:${idCounter + 1}",
        val startTime: Double,
        val duration: Double,
        val priority: Int = itemStartEventPriority,
        val message: String? = null,
        val datum: Double? = null
    ) :
        Comparable<ScheduleItem> {
        init {
            require(name.isNotBlank()) { "The name must not be blank." }
            require(startTime >= 0.0) { "The start time must be >= 0.0" }
            require(duration > 0.0) { "The duration must be > 0.0" }
            idCounter += 1
        }

        val id: Int = idCounter

        val schedule: Schedule = this@Schedule
        internal var myStartEvent: KSLEvent<ScheduleItem>? = null
        internal var myEndEvent: KSLEvent<ScheduleItem>? = null

        val endTime: Double
            get() = startTime + duration

        override fun toString(): String {
            val sb = StringBuilder()
            sb.append("ID = $id")
            sb.append(" | name = $name")
            sb.append(" | Priority = $priority")
            sb.append(" | Start time = $startTime")
            sb.append(" | Duration = $duration")
            if (myStartEvent != null) {
                sb.append(" | Start event priority = ${myStartEvent?.priority}")
            }
            if (myEndEvent != null) {
                sb.append(" | End event priority = ${myEndEvent?.priority}")
            }
            if (datum != null) {
                sb.append(" | datum = $datum")
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
        override operator fun compareTo(other: ScheduleItem): Int {

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
    s.addItem(startTime = 60.0 * 2.0, 15.0, message = "break1")
    s.addItem(startTime = (60.0 * 4.0), 30.0, message = "lunch")
    s.addItem(startTime = (60.0 * 6.0), 15.0, message = "break2")
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

    override fun scheduleItemStarted(item: Schedule.ScheduleItem) {
        println("time = ${item.schedule.time} scheduled item ${item.name} started with message ${item.message}")
    }

    override fun scheduleItemEnded(item: Schedule.ScheduleItem) {
        println("time = ${item.schedule.time} scheduled item ${item.name} ended with message ${item.message}")
    }

}