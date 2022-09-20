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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.modeling.variable

/**
 * Represents a ResponseInterval placed on a ResponseSchedule.
 * @param schedule the schedule that the interval should belong to
 * @param theStartTime must be greater than zero. Represents start time relative to start of schedule
 * @param duration the ResponseInterval
 * @param label a string for labeling the item
 * @author rossetti
 */
class ResponseScheduleItem internal constructor(
    schedule: ResponseSchedule,
    theStartTime: Double,
    duration: Double,
    label: String?
) {
    private val mySchedule: ResponseSchedule = schedule

    init {
        require(theStartTime >= 0) { "The start time must be >= 0" }
        require(duration.isFinite()) { "The duration must be finite." }
        require(duration > 0) { "The duration must be > 0." }
    }

    /**
     * the time to start the schedule
     */
    val startTime: Double = theStartTime

    internal val responseInterval: ResponseInterval = ResponseInterval(schedule, duration, label)

//    init {
//        responseInterval.responseSchedule = schedule
//    }

    /**
     * Adds a ResponseVariable to the item for data collection over the
     * interval
     *
     * @param theResponse the response to collect interval statistics on
     * @return a ResponseVariable for the interval
     */
    fun addResponseToInterval(theResponse: Response): Response {
        return responseInterval.addResponseToInterval(theResponse)
    }

    /**
     * Adds a Counter to the interval for data collection over the interval
     *
     * @param theCounter the counter to collect interval statistics on
     * @return a ResponseVariable for the interval
     */
    fun addCounterToInterval(theCounter: Counter): Response {
        return responseInterval.addCounterToInterval(theCounter)
    }

    /**
     *
     * @return true if the interval has been scheduled
     */
    val isScheduled: Boolean
        get() = responseInterval.isScheduled

    /**
     * When the interval was last started
     *
     * @return When the interval was last started
     */
    val timeLastStarted: Double
        get() = responseInterval.timeLastStarted

    /**
     * When the interval was last ended
     *
     * @return When the interval was last ended
     */
    val timeLastEnded: Double
        get() = responseInterval.timeLastEnded

    /**
     * The duration (length) of the interval
     *
     * @return The duration (length) of the interval
     */
    val duration: Double
        get() = responseInterval.duration

    /**
     * Causes the response interval to be scheduled at the start time for the item.
     */
    internal fun scheduleResponseInterval() {
        responseInterval.scheduleInterval(startTime)
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Schedule starts at time: ").append(mySchedule.startTime)
        sb.append("\t Item starts at time: ").append(mySchedule.startTime + startTime)
        sb.append(System.lineSeparator())
        sb.append(responseInterval.toString())
        return sb.toString()
    }
}