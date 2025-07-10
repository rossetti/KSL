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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.toDouble
import ksl.utilities.statistic.WeightedStatisticIfc

/**
 * This class represents an interval of time over which statistical collection
 * should be performed. An interval is specified by providing an interval start
 * time and a duration. The duration must be finite and greater than zero.
 *
 * Simulation responses in the form of instances of ResponseVariable,
 * TimeWeighted, and Counter can be added to the interval for observation.
 * New responses are created and associated with each of the supplied responses.
 * The new responses collect observations associated with the supplied responses
 * only during the specified interval. In the case of response variables or
 * time weighted variables, the average response during the interval is observed.
 * In the case of counters, the total count during the interval is observed.
 *
 * If the interval is not associated with a ResponseSchedule, the interval may
 * be repeated. In which case, the statistics are collected across the
 * intervals. A repeated interval starts immediately after the previous
 * duration. Note that for ResponseVariables that are observed, if there
 * are no observations during the interval, then the average response during
 * the interval is undefined (and thus not observed). Therefore, interval
 * statistics for ResponseVariables are conditional on the occurrence of at least
 * one observation.  This is most relevant when the interval is repeated because
 * intervals with no observations are not tabulated.
 *
 * @author rossetti
 */
class ResponseInterval @JvmOverloads constructor(
    parent: ModelElement,
    theDuration: Double,
    label: String?,
    aResponseSchedule: ResponseSchedule? = null
) : ModelElement(parent, label) {
    init {
        require(theDuration.isFinite()) { "The duration must be finite." }
        require(theDuration > 0) { "The duration must be > 0." }
        this.label = label
    }

    companion object {
        /**
         * Need to ensure that start event happens after schedule start
         * and after warm up event
         */
        const val START_EVENT_PRIORITY: Int = KSLEvent.DEFAULT_WARMUP_EVENT_PRIORITY + 1

        /**
         * Need to ensure that end event happens before schedule end
         */
        const val END_EVENT_PRIORITY : Int = START_EVENT_PRIORITY - 5
    }

    /**
     * The action that represents the start of the interval
     */
    private val myStartAction = StartIntervalAction()

    /**
     * The action that represents the end of the interval
     */
    private val myEndAction = EndIntervalAction()

    /**
     * The event that represents the start of the interval
     */
    private var myStartEvent: KSLEvent<Nothing>? = null

    /**
     * The event that represents the end of the interval
     *
     */
    private var myEndEvent: KSLEvent<Nothing>? = null

    /**
     * A map of responses and the data associated with the interval
     */
    private val myResponses: MutableMap<Response, IntervalData> = mutableMapOf()

    /**
     * A map of counters and the data associated with the interval
     */
    private val myCounters: MutableMap<Counter, IntervalData> = mutableMapOf()

    /**
     * An observer to handle the removal of the interval response if the
     * underlying response is removed from the model
     */
    private val myObserver: ResponseObserver = ResponseObserver()

    /**
     *  If the interval is associated with a schedule, this will be set
     */
    private var responseSchedule: ResponseSchedule? = aResponseSchedule

    /**
     * Intervals may be repeated. The represents the time that the interval last
     * started in time;
     *
     */
    var timeLastStarted: Double = 0.0
        private set

    /**
     * Intervals may be repeated. The represents the time that the interval last
     * ended in time;
     *
     */
    var timeLastEnded: Double = 0.0
        private set

    /**
     * Indicates if the interval has been scheduled
     */
    var isScheduled: Boolean = false
        private set

    var duration: Double = theDuration
        private set(value) {
            require(value.isFinite()) { "The duration must be finite." }
            require(value > 0) { "The duration must be > 0." }
            field = value
        }

    /**
     * Specifies when the interval is to start. If negative, then the interval
     * will not be started, must not be infinite
     */
    var startTime: Double = Double.NEGATIVE_INFINITY
        set(value) {
            require(value.isFinite()) { "The start time cannot be infinity" }
            field = value
        }

    /**
     * The repeat flag controls whether the interval will repeat after its duration has elapsed.
     * The default is false.
     */
    var repeatFlag : Boolean = false

    /**
     * Adds a ResponseVariable to the interval for data collection over the
     * interval. By default, interval empty statistics are not collected.
     *
     * @param theResponse the response to collect interval statistics on
     * @param intervalEmptyStatOption true means include statistics on whether
     * the interval is empty when observed
     * @return a Response for the interval
     */
    @JvmOverloads
    fun addResponseToInterval(theResponse: ResponseCIfc, intervalEmptyStatOption: Boolean = false): Response {
        return addResponseToInterval(theResponse as Response, intervalEmptyStatOption)
    }

    /**
     * Adds a ResponseVariable to the interval for data collection over the
     * interval. By default, interval empty statistics are not collected.
     *
     * @param theResponse the response to collect interval statistics on
     * @param intervalEmptyStatOption true means include statistics on whether
     * the interval is empty when observed
     * @return a Response for the interval
     */
    @JvmOverloads
    fun addResponseToInterval(
        theResponse: Response,
        intervalEmptyStatOption: Boolean = false
    ): Response {
        require(!myResponses.containsKey(theResponse)) { "The supplied response was already added." }
        val rv = Response(this, "${theResponse.name}:IntervalAvg:$label")
        val data = IntervalData(rv)
        if (theResponse is TWResponse) {
            val rv2 = Response(this, "${theResponse.name}:ValueAtStart:$label")
            data.myValueAtStart = rv2
        }
        if (intervalEmptyStatOption) {
            val rv3 = Response(this, "${theResponse.name}:$label:P(Empty)")
            data.myEmptyResponse = rv3
        }
        myResponses[theResponse] = data
        theResponse.attachModelElementObserver(myObserver)
        return rv
    }

    /**
     * Adds a Counter to the interval for data collection over the interval
     *
     * @param theCounter the counter to collect interval statistics on
     * @return a Response for the interval
     */
    fun addCounterToInterval(theCounter: CounterCIfc): Response {
        return addCounterToInterval(theCounter as Counter)
    }

    /**
     * Adds a Counter to the interval for data collection over the interval
     *
     * @param theCounter the counter to collect interval statistics on
     * @return a Response for the interval
     */
    fun addCounterToInterval(theCounter: Counter): Response {
        require(!myCounters.containsKey(theCounter)) { "The supplied counter was already added." }
        val rv = Response(this, "${theCounter.name}:$label")
        val data = IntervalData(rv)
        myCounters[theCounter] = data
        theCounter.attachModelElementObserver(myObserver)
        return rv
    }


    override fun initialize() {
        super.initialize()
        if (startTime >= 0.0) {
            scheduleInterval(startTime)
        }
    }

    override fun afterReplication() {
        super.afterReplication()
        timeLastStarted = 0.0
        timeLastEnded = 0.0
        cancelInterval()
        for (d in myResponses.values) {
            d.reset()
        }
        for (d in myCounters.values) {
            d.reset()
        }
    }

    /**
     * Schedules the interval to occur at current time + start time
     *
     * @param startTime the time to start the interval
     */
    internal fun scheduleInterval(startTime: Double) {
        check(!isScheduled) { "Attempted to schedule an already scheduled interval" }
        isScheduled = true
//        val t = time + startTime
//        println("$time > **${this@ResponseInterval.name}** scheduling the start action for time $t")
        myStartEvent = myStartAction.schedule(startTime, priority = START_EVENT_PRIORITY)
//        println("$myStartEvent")
    }

    /**
     * Cancels the scheduling of the interval. Any statistical collection will not occur.
     */
    fun cancelInterval() {
        isScheduled = false
        if (myStartEvent != null) {
            if (myStartEvent!!.isScheduled) {
                myStartEvent!!.cancel = true
            }
        }
        if (myEndEvent != null) {
            if (myEndEvent!!.isScheduled) {
                myEndEvent!!.cancel = true
            }
        }
        myStartEvent = null
        myEndEvent = null
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
        sb.append("Interval: ")
        sb.append(label)
        sb.append(", ")
        sb.append("Start time: ")
        sb.append(startTime)
        sb.append(", ")
        sb.append("Time last started: ")
        sb.append(timeLastStarted)
        sb.append(", ")
        sb.append("Duration: ")
        sb.append(duration)
        sb.append(", ")
        sb.append("Time last ended: ")
        sb.append(timeLastEnded)
        sb.append(", ")
        sb.append("Is Scheduled: ")
        sb.append(isScheduled)
        sb.append(", ")
        sb.append("#Responses: ")
        sb.append(myResponses.size)
        sb.append(", ")
        sb.append("#Counters: ")
        sb.append(myCounters.size)
        return sb.toString()
    }

    /**
     * Represents data collected at the start of an interval for use at the end
     * of the interval
     */
    internal inner class IntervalData(response: Response) {
        val myResponse: Response = response
        var myEmptyResponse: Response? = null
        var myValueAtStart: Response? = null
        var mySumAtStart = 0.0
        var mySumOfWeightsAtStart = 0.0
        var myTotalAtStart = 0.0
        var myNumObsAtStart = 0.0
        fun reset() {
            mySumAtStart = 0.0
            mySumOfWeightsAtStart = 0.0
            myTotalAtStart = 0.0
            myNumObsAtStart = 0.0
        }
    }

    private inner class StartIntervalAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            for ((key, data) in myResponses) {
                timeLastStarted = time
                val w: WeightedStatisticIfc = key.withinReplicationStatistic
                data.mySumAtStart = w.weightedSum
                data.mySumOfWeightsAtStart = w.sumOfWeights
                data.myNumObsAtStart = w.count
                if (key is TWResponse) {
                    data.myValueAtStart!!.value = key.value
                }
            }
            for ((key, data) in myCounters) {
                data.myTotalAtStart = key.value
            }
            myStartEvent = null
//            println("$time > ${this@ResponseInterval.name} start action")
//            val t = time + duration
//            println("$time > **${this@ResponseInterval.name}** scheduling the end action for time $t")
            myEndEvent = myEndAction.schedule(duration, priority = END_EVENT_PRIORITY)
//            println("$myEndEvent")
        }
    }

    private inner class EndIntervalAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            for ((key, data) in myResponses) {
                timeLastEnded = time
                val w: WeightedStatisticIfc = key.withinReplicationStatistic
                val sum: Double = w.weightedSum - data.mySumAtStart
                val denom: Double = w.sumOfWeights - data.mySumOfWeightsAtStart
                val numObs: Double = w.count - data.myNumObsAtStart
                if (data.myEmptyResponse != null) {
                    data.myEmptyResponse!!.value = (numObs == 0.0).toDouble()
                }
                if (numObs == 0.0) {
                    // there were no changes of the variable during the interval
                    // cannot observe Response but can observe TWResponse
                    if (key is TWResponse) {
                        //no observations, value did not change during interval
                        // average = area/time = height*width/width = height
                        data.myResponse.value = key.value
                    }
//                    val r = data.myResponse.model.currentReplicationNumber
//                    println("R = $r  ${data.myResponse.name}  sum = $sum  denom = $denom")
                } else {
                    // there were observations, denominator cannot be 0, but just in case
                    if (denom != 0.0) {
                        val avg = sum / denom
//                        val r = data.myResponse.model.currentReplicationNumber
//                        KSL.out.println("R = $r  ${data.myResponse.name}  sum = $sum  denom = $denom avg = $avg")
                        data.myResponse.value = avg
                    }
                }
            }
            for ((key, data) in myCounters) {
                val intervalCount: Double = key.value - data.myTotalAtStart
                data.myResponse.value = intervalCount
            }
            if (responseSchedule != null) {
                responseSchedule!!.responseIntervalEnded(this@ResponseInterval)
            } else {
                // not on a schedule, check if it can repeat
                if (repeatFlag) {
                    isScheduled = false
                    scheduleInterval(0.0) // schedule it to start again, right now
                }
            }
            myEndEvent = null
//            println("$time > ${this@ResponseInterval.name} end action")
        }
    }

    private inner class ResponseObserver : ModelElementObserver() {
        override fun removedFromModel(modelElement: ModelElement) {
            // m is the model element that is being monitored that is
            // being removed from the model.
            // first remove the monitored object from the maps
            // then remove the associated response from the model
            if (modelElement is Counter) {
                val data = myCounters.remove(modelElement)
                data!!.myResponse.removeFromModel()
            } else if (modelElement is Response) {
                val data = myResponses.remove(modelElement)
                data!!.myResponse.removeFromModel()
            }
        }
    }
}