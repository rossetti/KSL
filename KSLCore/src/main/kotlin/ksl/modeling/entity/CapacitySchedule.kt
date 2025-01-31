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

import ksl.modeling.entity.CapacitySchedule.CapacityItem
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement

interface CapacityChangeListenerIfc {
    fun scheduleStarted(schedule: CapacitySchedule)
    fun scheduleEnded(schedule: CapacitySchedule)
    fun capacityChange(item: CapacitySchedule.CapacityItem)
}

/** A CapacityItemData represents the data for an item on a CapacitySchedule. CapacityItems are placed
 * on a CapacitySchedule in the order that they will execute. The first item will start
 * when the schedule starts and each item will be scheduled sequentially based on the durations
 * of the previous items.  At the specified start time, the capacity is to be set to the supplied
 * value.
 * @param capacity the value of the capacity to be specified at the start time
 * @param duration the duration of the change
 * @param priority the priority of the change
 */
data class CapacityItemData(
    val capacity: Int,
    val duration: Double,
    val priority: Int = KSLEvent.DEFAULT_PRIORITY
) {
    init {
        require(duration > 0.0) { "The start time must be >= 0.0" }
        require(capacity >= 0) { "The capacity must be >= 0" }
    }
}

interface CapacityScheduleCIfc {

    val eventPriority: Int

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
     * Indicates whether the schedule should be started automatically upon initialization, default is true
     */
    var isAutoStartFlag: Boolean

    /**
     * The time from the beginning of the replication to the time that the schedule is to start
     */
    var initialStartTime: Double

    /**
     * The same listener cannot be added more than once. Listeners are
     * notified of schedule changes in the sequence by which they were added.
     *
     * @param listener the listener to add to the schedule
     */
    fun addCapacityChangeListener(listener: CapacityChangeListenerIfc)

    /**
     *
     * @param listener the listener to delete from the schedule
     */
    fun deleteCapacityChangeListener(listener: CapacityChangeListenerIfc)

    /**
     * Deletes all listeners
     */
    fun deleteCapacityChangeListeners()

    /**
     *
     * @return the number of listeners
     */
    fun countScheduleChangeListeners(): Int

    /**
     * Adds an item to the schedule
     * @param duration the duration of the item
     * @param capacity a message or datum to attach to the item
     * @return the created CapacityItem
     * */
    fun addItem(
        capacity: Int,
        duration: Double,
        itemPriority: Int = eventPriority
    ) : CapacityItem

    /**
     *  Allow the creation of capacity schedule items via a data class
     */
    fun addItemData(itemData: CapacityItemData) : CapacityItem {
        return addItem(itemData.capacity, itemData.duration, itemData.priority)
    }

    /**
     *  Allow the creation of capacity schedule items via a data class
     */
    fun addItemData(itemDataList: List<CapacityItemData>) : List<CapacityItem> {
        val list = mutableListOf<CapacityItem>()
        for(item in itemDataList){
            list.add(addItemData(item))
        }
        return list
    }

    /**
     * Removes all capacity items from the schedule
     */
    fun clearSchedule()
}

/** A CapacitySchedule represents a known set of capacity specifications that last for a duration of time.
 *
 * A CapacitySchedule has an auto start flag, which controls whether the schedule should start automatically
 * upon initialization (at the start of the replication). The default is to start automatically.
 *
 * A CapacitySchedule has an initial start time, which represents the amount of time after the beginning of
 * the replication that the schedule is to start. The default start time is zero (at the beginning of the replication).
 *
 * A CapacitySchedule as a length (or duration) that represents the total time associated with the schedule. After this
 * time has elapsed the entire schedule can repeat if the repeat option is on. The length of the schedule
 * is determined by the durations of the items placed on it.
 *
 * A CapacitySchedule has a repeat flag that controls whether it will repeat after its duration has elapsed. The
 * default is to repeat the schedule and is only relevant if the schedule duration (length) is finite.
 *
 * A CapacitySchedule has a cycle start time that represents when the schedule started its current cycle. Again, this
 * is only relevant if the repeat flag is true and the schedule duration is finite. If there is only one cycle, it is
 * the time that the schedule started.
 *
 * To make a CapacitySchedule useful, instances of the CapacityChangeListenerIfc interface should be added to
 * listen for changes in the schedule.  Instances of CapacityChangeListenerIfc are notified in the order
 * in which they are added to the schedule.  Instances of CapacityChangeListenerIfc are notified when the
 * schedule starts, when it ends, and when any CapacityItem starts.  It is up to the instance
 * of CapacityChangeListenerIfc to react to the schedule changes that it needs to react to and ignore those
 * that it does not care about.
 *
 * @param parent the parent model element holding the schedule
 * @param startTime the time after the start of the replication that the schedule should start. The default is 0.0
 * @param autoStartOption whether the schedule should start automatically. The default is true.
 * @param repeatable whether the schedule will automatically repeat. The default is false.
 * @param eventPriority the default priority of the events used to start each item on the schedule
 * @param name an optional name for the schedule
 * @author rossetti
 */
class CapacitySchedule(
    parent: ModelElement,
    startTime: Double = 0.0,
    autoStartOption: Boolean = true,
    repeatable: Boolean = false,
    override val eventPriority: Int = KSLEvent.DEFAULT_PRIORITY,
    name: String? = null
) : ModelElement(parent, name), CapacityScheduleCIfc {

    init {
        require(startTime >= 0.0) {"The initial start time must be >= 0.0"}
    }

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
            require(model.isNotRunning) {"The model must not be running when configuring the schedule"}
            field = value
        }

    private var idCounter: Long = 0

    /**
     * Indicates whether the schedule should be started automatically upon initialization, default is true
     */
    override var isAutoStartFlag: Boolean = autoStartOption
        set(value) {
            require(model.isNotRunning) {"The model must not be running when configuring the schedule"}
            field = value
        }

    /**
     * The time from the beginning of the replication to the time that the schedule is to start
     */
    override var initialStartTime: Double = startTime
        set(value) {
            require(value >= 0.0) {"The initial start time must be >= 0.0"}
            require(model.isNotRunning) {"The model must not be running when configuring the schedule"}
            field = value
        }

    /**
     * Represents the total length of time of the schedule.
     * The total of the durations determines the length of the schedule.
     * After this time has elapsed the entire schedule can repeat if the
     * schedule repeat flag is true. The default is infinite.
     */
    var scheduleLength: Double = 0.0
        private set

    /**
     * The time that the schedule started for its current cycle
     *
     */
    var cycleStartTime = 0.0
        private set

    private val myItems: MutableList<CapacityItem> = mutableListOf()
    val items: List<CapacityItem>
        get() = myItems

    private val myChangeListeners: MutableList<CapacityChangeListenerIfc> = mutableListOf()
    private var myStartScheduleEvent: KSLEvent<Nothing>? = null

    private var myEndEventPriority = Int.MIN_VALUE
    private var myStartEventPriority = Int.MAX_VALUE
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
    override fun addCapacityChangeListener(listener: CapacityChangeListenerIfc) {
        require(!myChangeListeners.contains(listener)) { "The supplied listener is already attached" }
        myChangeListeners.add(listener)
    }

    /**
     *
     * @param listener the listener to delete from the schedule
     */
    override fun deleteCapacityChangeListener(listener: CapacityChangeListenerIfc) {
        myChangeListeners.remove(listener)
    }

    /**
     * Deletes all listeners
     */
    override fun deleteCapacityChangeListeners() {
        myChangeListeners.clear()
    }

    /**
     *
     * @param listener the listener to check
     * @return true if the listener is already added
     */
    operator fun contains(listener: CapacityChangeListenerIfc): Boolean {
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
     * Adds an item to the schedule
     * @param duration the duration of the item
     * @param capacity a message or datum to attach to the item
     * @return the created CapacityItem
     * */
    override fun addItem(
        capacity: Int,
        duration: Double,
        itemPriority: Int
    ) : CapacityItem {
        require(model.isNotRunning) {"The model must not be running when configuring the schedule"}
        require(scheduleLength.isFinite()) {"Cannot add items once the schedule length becomes infinite!"}
        if (myItems.isEmpty()){
            // first item being added, end event priority must be < first item's priority
            myEndEventPriority = itemPriority - 5
            // start event priority must be > end event priority
            myStartEventPriority = myEndEventPriority + 10
        }
        val item = CapacityItem(capacity, duration, itemPriority)
        item.startTime = scheduleLength
        scheduleLength = scheduleLength + duration
        myItems.add(item)
        return item
    }

    /**
     * Removes all capacity items from the schedule
     */
    override fun clearSchedule() {
        require(model.isNotRunning) {"The model must not be running when configuring the schedule"}
        myItems.clear()
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
        sb.appendLine("Schedule event priority = $eventPriority")
        sb.appendLine("Items:")
        sb.appendLine("-----------------------------------------------------------------------------")
        for (i in myItems) {
            sb.appendLine(i)
        }
        sb.appendLine("-----------------------------------------------------------------------------")
        return sb.toString()
    }

    private fun notifyChangeListenersScheduleStarted() {
        for (listener in myChangeListeners) {
            listener.scheduleStarted(this)
        }
    }

    private fun notifyChangeListenersScheduleEnded() {
        for (listener in myChangeListeners) {
            listener.scheduleEnded(this)
        }
    }

    private fun notifyChangeListenersScheduleItemStarted(item: CapacityItem) {
        for (listener in myChangeListeners) {
            listener.capacityChange(item)
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
            myStartScheduleEvent = schedule(this::startSchedule, initialStartTime, priority = myStartEventPriority)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun startSchedule(event: KSLEvent<Nothing>) {
        cycleStartTime = time
        // logic for what to do when schedule is started
        notifyChangeListenersScheduleStarted()
        // schedule the first item if there is one
        for(item in myItems){
            item.startEvent = schedule(this::startItem, item.startTime, item, item.priority)
        }
        // if the length of the schedule is finite, schedule the end event
        if (scheduleLength.isFinite() && (scheduleLength != 0.0)){
            // priority for end the schedule must be lower than the start of the schedule to ensure it goes first
            schedule(this::endSchedule, scheduleLength, priority = myEndEventPriority)
        }
    }

    private fun endSchedule(event: KSLEvent<Nothing>) {
        notifyChangeListenersScheduleEnded()
        if (isScheduleRepeatable) {
            startSchedule(event)
        }
    }

    private fun startItem(event: KSLEvent<CapacityItem>) {
        val item = event.message as CapacityItem
        notifyChangeListenersScheduleItemStarted(item)
    }

    /** A CapacityItem represents an item on a CapacitySchedule. CapacityItems are placed
     * on a CapacitySchedule in the order that they will execute. The first item will start
     * when the schedule starts and each item will be scheduled sequentially based on the durations
     * of the previous items.  At the specified start time, the capacity is to be set to the supplied
     * value.
     * @param capacity the value of the capacity to be specified at the start time
     * @param duration the duration of the change
     * @param priority the priority of the change
    */
    inner class CapacityItem(
        val capacity: Int = 0,
        val duration: Double,
        val priority: Int = eventPriority
    ) {
        init {
            require(duration > 0.0) { "The start time must be >= 0.0" }
            require(capacity >= 0) { "The capacity must be >= 0" }
            idCounter += 1
        }

        val id: Long = idCounter
        var name: String = "Item:$id"
        internal val schedule: CapacitySchedule = this@CapacitySchedule
        internal var startEvent: KSLEvent<CapacityItem>? = null

        var startTime: Double = 0.0
            internal set

        fun cancelStart(){
            startEvent?.cancel = true
        }

        override fun toString(): String {
            return ("ID = $id | name = $name | start time = $startTime | duration = $duration | priority $priority")
        }
    }
}

fun main() {
    val m = Model()
    val s = CapacitySchedule(m, startTime = 0.0)
    s.addItem(capacity = 2, duration = 15.0)
    s.addItem(capacity = 1, duration = 30.0)
    s.addItem(capacity = 2, duration = 15.0)
    s.addCapacityChangeListener(CapacityListener())

    println(s)
    println()
    m.lengthOfReplication = 100.0
    m.numberOfReplications = 2

    m.simulate()

}

class CapacityListener : CapacityChangeListenerIfc {
    override fun scheduleStarted(schedule: CapacitySchedule) {
        val n = schedule.model.currentReplicationNumber
        println("Replication: $n")
        println("time = ${schedule.time} Schedule Started")
    }

    override fun scheduleEnded(schedule: CapacitySchedule) {
        println("time = ${schedule.time} Schedule Ended")
    }

    override fun capacityChange(item: CapacitySchedule.CapacityItem) {
        println("time = ${item.schedule.time}  : ${item.name} started with capacity ${item.capacity} for duration ${item.duration}")
    }

}
