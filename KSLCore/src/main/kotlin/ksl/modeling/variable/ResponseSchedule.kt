/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.modeling.variable

import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement


/**
 * This class allows the creation of a schedule that represents a list of
 * intervals of time. The starting length of a schedule is
 * 0.0. The length of a schedule depends upon the intervals added to it.
 * The schedule's length encompasses the furthest interval added. If no
 * intervals are added, then the schedule only has its start time and no
 * response collection will occur.
 *
 *
 * The user adds intervals and responses for which statistics need to be collected during the intervals.
 * The intervals within the cycle may overlap in time. The start time
 * of an interval is specified relative to the beginning of the cycle.
 * The length of any interval must be finite.
 *
 *
 * The schedule can be started any time after the start of the simulation.
 * The default starting time of the schedule is time 0.0.
 * The schedule will start automatically at the designated
 * start time.
 *
 *
 * The schedule can be repeated after the cycle length of the schedule is
 * reached. The default is for the schedule to automatically repeat.
 * Note that depending on the simulation run length only a portion of the
 * scheduled intervals may be executed.
 *
 *
 * The classic use case of this class is to collect statistics for each hour of the day.
 * In this case, the user would use the addIntervals() method to add 24 intervals of 1 hour duration.
 * Then responses (response variables, time weighted variables, and counters) can be added
 * to the schedule. In which case, they will be added to each interval. Thus, interval statistics
 * for each of the 24 intervals will be collected for everyone of the added responses.  If more
 * than one day is simulated and the schedule is allowed to repeat, then statistics are collected
 * across the days.  That is, the statistics of hour 1 on day 1 are averaged with the
 * statistics of hour 1 on all subsequent days.
 *
 *
 * This functionality is built on the ResponseInterval class, which can be used separately. In
 * other words, response intervals do not have to be on a schedule. The schedule facilitates
 * the collection of many responses across many intervals.
 *
 * @param parent         the parent model element
 * @param theScheduleStartTime the time to start the schedule, must be finite.
 * @param repeatSchedule Whether the schedule will repeat
 * @param name           the name of the model element
 */
class ResponseSchedule(
    parent: ModelElement,
    theScheduleStartTime: Double = 0.0,
    repeatSchedule: Boolean = true,
    name: String? = null
) : ModelElement(parent, name) {
    init {
        require(theScheduleStartTime >= 0.0) { "The start time cannot be negative" }
        require(theScheduleStartTime.isFinite()) { "The start time cannot be infinity" }
    }

    companion object {
        /**
         * Need to ensure that start event happens before interval responses
         */
        const val START_EVENT_PRIORITY: Int = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY - 1
    }

    /**
     * The time that the schedule should start
     */
    var startTime: Double = theScheduleStartTime
        private set

    /**
     * The time that the schedule started for its current cycle
     */
    var cycleStartTime: Double = Double.NaN
        private set

    /**
     * The schedule repeat flag controls whether the entire schedule will
     * repeat after its entire cycle has elapsed.  The default is true.
     */
    var scheduleRepeatFlag = repeatSchedule

    /**
     * Represents the length of time of the schedule based on the intervals added
     */
    var length: Double = 0.0
        private set

    /**
     * Holds the intervals to be invoked on schedule
     */
    private val myScheduleItems: MutableList<ResponseScheduleItem> = mutableListOf()

    /**
     * Holds the set of intervals that have been scheduled
     */
    private val myScheduledIntervals: MutableSet<ResponseInterval> = mutableSetOf()

    /**
     * Represents the event scheduled to start the schedule
     */
    private var myStartEvent: KSLEvent<Nothing>? = null

    private val myStartAction: StartScheduleAction = StartScheduleAction()

    /**
     * Indicates if the schedule has been scheduled to start
     */
    var isScheduled: Boolean = false
        private set

    fun cancelScheduleStart() {
        if (isScheduled) {
            myStartEvent!!.cancelled = true
        }
    }

    /**
     * The time that has elapsed into the current cycle
     */
    val elapsedCycleTime: Double
        get() = time - cycleStartTime

    /**
     * The time remaining within the current cycle
     */
    val remainingCycleTime: Double
        get() = cycleStartTime + length - time

    /**
     * The number of intervals in the schedule
     */
    val numberOfIntervals: Int
        get() = myScheduleItems.size

    /**
     * An unmodifiable list of the ResponseScheduleItems
     */
    val responseScheduleItems: List<ResponseScheduleItem>
        get() = myScheduleItems

    /**
     * Causes interval statistics to be collected for the response for every
     * interval in the schedule
     *
     * @param response the response to add
     */
    fun addResponseToAllIntervals(response: Response) {
        for (item in myScheduleItems) {
            item.addResponseToInterval(response)
        }
    }

    /**
     * There must not be any duplicates in the collection or null values. Causes
     * interval statistics to be collected for all the responses for every
     * interval in the schedule.
     *
     * @param responses a collection of unique Response instances
     */
    fun addResponsesToAllIntervals(responses: Collection<Response>) {
        for (c in responses) {
            addResponseToAllIntervals(c)
        }
    }

    /**
     * Causes interval statistics to be collected for the counter for every
     * interval in the schedule
     *
     * @param counter the counter to add
     */
    fun addCounterToAllIntervals(counter: Counter) {
        for (item in myScheduleItems) {
            item.addCounterToInterval(counter)
        }
    }

    /**
     * There must not be any duplicates in the collection or null values.
     * Causes interval statistics to be collected for all the counters for every
     * interval in the schedule.
     *
     * @param counters a collection of unique Counter instances
     */
    fun addCountersToAllIntervals(counters: Collection<Counter>) {
        for (c in counters) {
            addCounterToAllIntervals(c)
        }
    }

    /**
     * Add an interval for collecting responses to the schedule.  If the start time plus the
     * duration reaches past the current schedule length, the schedule length is extended to
     * include the interval.
     *
     * @param startTime must be greater than or equal to zero. Represents start time relative to start of schedule
     * @param theLabel     the label associated with the interval, must not be null
     * @param duration  duration of the interval, must be finite and strictly positive
     * @return the ResponseScheduleItem
     */
    fun addResponseInterval(
        startTime: Double,
        duration: Double, theLabel: String
    ): ResponseScheduleItem {
        require(startTime >= 0.0) { "The start time must be greater than or equal to zero" }
        require(duration.isFinite()) { "The duration must be finite" }
        require(duration > 0.0) { "The duration must be strictly positive" }
        var label = theLabel
        val n = myScheduleItems.size + 1
        label = String.format("Interval:%02d", n) + ":" + label
        val item = ResponseScheduleItem(this, startTime, duration, "$name:$label")
        if (startTime + item.duration > length) {
            length = startTime + item.duration
        }
        myScheduleItems.add(item)
        return item
    }

    /**
     * Add non-overlapping, sequential intervals to the schedule, each having
     * the provided duration
     *
     * @param startTime    must not be negative. Represents start time of first interval
     * relative to the start time of the schedule
     * @param numIntervals the number of intervals
     * @param duration     the duration of each interval
     * @param label        a base label for each interval, if null a label is created
     */
    fun addIntervals(
        startTime: Double = 0.0,
        numIntervals: Int,
        duration: Double,
        label: String? = null
    ) {
        require(startTime >= 0) { "The start time must be >= 0" }
        require(numIntervals >= 1) { "The number of intervals must be >=1" }
        require(duration.isFinite()) { "The duration must be finite" }
        require(duration > 0.0) { "The duration must be strictly positive" }
        var t = startTime
        var s: String
        for (i in 1..numIntervals) {
            s = if (label == null) {
                val t1 = t + this.startTime
                String.format("[%.1f,%.1f]", t1, t1 + duration)
            } else {
                "$label:$i"
            }
            addResponseInterval(t, duration, s)
            t = t + duration
        }
    }

    /**
     * Includes the model name, the id, the model element name, the parent name, and parent id
     *
     * @return a string representing the model element
     */
    override fun toString(): String {
        return asString()
    }

    fun asString(): String {
        val sb = StringBuilder()
        sb.append(name)
        sb.append(System.lineSeparator())
        sb.append("Repeats: ")
        sb.append(scheduleRepeatFlag)
        sb.append(System.lineSeparator())
        sb.append("Start time: ")
        sb.append(startTime)
        sb.append(System.lineSeparator())
        sb.append("Length: ")
        sb.append(length)
        sb.append(System.lineSeparator())
        sb.append("#Intervals: ")
        sb.append(numberOfIntervals)
        sb.append(System.lineSeparator())
        sb.append("------")
        sb.append(System.lineSeparator())
        var i = 1
        for (item in myScheduleItems) {
            sb.append("Item: ")
            sb.append(i)
            sb.append(System.lineSeparator())
            sb.append(item)
            sb.append(System.lineSeparator())
            i++
        }
        sb.append("------")
        return sb.toString()
    }

    /**
     * Schedules the start of the schedule for t + time the schedule if it has
     * not already been started. This method will do nothing if the event scheduling
     * executive is not available.
     *
     * @param timeToStart the time to start
     */
    private fun scheduleStart(timeToStart: Double) {
        check(!isScheduled) { "The schedule as already been scheduled to start" }
        isScheduled = true
        myStartEvent = myStartAction.schedule(timeToStart, priority = START_EVENT_PRIORITY)
    }

    override fun initialize() {
        if (startTime >= 0.0) {
            cycleStartTime = Double.NaN
            scheduleStart(startTime)
        }
    }

    override fun afterReplication() {
        super.afterReplication()
        isScheduled = false
        myStartEvent = null
        myScheduledIntervals.clear()
    }

    /**
     * Used to communicate that the response interval ended
     *
     * @param responseInterval the interval that ended
     */
    internal fun responseIntervalEnded(responseInterval: ResponseInterval) {
        // cancel the interval so that it can be used again
        responseInterval.cancelInterval()
        myScheduledIntervals.remove(responseInterval)
        if (scheduleRepeatFlag) {
            if (myScheduledIntervals.isEmpty()) {
                // all response intervals have completed, safe to start again
                isScheduled = false
                scheduleStart(0.0) //zero is time NOW
            }
        }
    }

    private inner class StartScheduleAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            cycleStartTime = time
            for (item in myScheduleItems) {
                item.scheduleResponseInterval()
                myScheduledIntervals.add(item.responseInterval)
            }
        }
    }
}