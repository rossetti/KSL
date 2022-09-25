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
package ksl.modeling.variable

import ksl.observers.ModelElementObserver
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.io.D2FORMAT
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateFrequency

/**
 * Collects statistics on whether a specific level associated with a variable is
 * maintained.
 * @param theResponse the response to observe
 * @param theLevel    the level to associate with the response
 * @param stats    whether detailed state change statistics are collected
 * @param theName     the name of the response
 */
class LevelResponse(theResponse: TWResponse, theLevel: Double, stats: Boolean = true, theName: String? = null) :
    ModelElement(theResponse, theName) {
    init {
        require(theResponse.limits.contains(theLevel))
        { "The supplied level $theLevel was outside the range of the variable's limits ${theResponse.limits}" }
    }

    private val myObserver: ModelElementObserver = TheObserver()
    private val myResponse: TWResponse = theResponse
    val level: Double = theLevel

    /**
     * true if detailed state change statistics are collected
     */
    val statisticsOption: Boolean = stats
    private val myStateFreq: StateFrequency = StateFrequency(2)
    private val myAbove: State = myStateFreq.states[0]
    private val myBelow: State = myStateFreq.states[1]
    private var myCurrentState: State = if (myResponse.initialValue >= level) {
        myAbove
    } else {
        myBelow
    }

    init {
        myAbove.label = ("${myResponse.name}:$name:+")
        myBelow.label = ("${myResponse.name}:$name:-")
        myAbove.sojournTimeCollectionFlag = true
        myBelow.sojournTimeCollectionFlag = true
        myResponse.attachModelElementObserver(myObserver)
    }

    // collected during the replication
//TODO what are the proper limits

    private val myDistanceAbove = Response(this, "${myResponse.name}:$name:DistAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myDistanceBelow = Response(this, "${myResponse.name}:$name:DistBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myDevAboveLevel = TWResponse(this, "${myResponse.name}:$name:DevAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myDevBelowLevel = TWResponse(this, "${myResponse.name}:$name:DevBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myDeviationFromLevel =
        TWResponse(this, "${myResponse.name}:$name:DevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myMaxDistanceAbove =
        Response(this, "${myResponse.name}:$name:MaxDistAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myMaxDistanceBelow =
        Response(this, "${myResponse.name}:$name:MaxDistBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myPctTimeAbove = Response(this, "${myResponse.name}:$name:PctTimeAbove:${D2FORMAT.format(theLevel)}")
    private val myPctTimeBelow = Response(this, "${myResponse.name}:$name:PctTimeBelow:${D2FORMAT.format(theLevel)}")
    private val myTotalTimeAbove = Response(this, "${myResponse.name}:$name:TotalTimeAbove:${D2FORMAT.format(theLevel)}")
    private val myTotalTimeBelow = Response(this, "${myResponse.name}:$name:TotalTimeBelow:${D2FORMAT.format(theLevel)}")
    private val myTotalAbsDeviationFromLevel =
        Response(this, "${myResponse.name}:$name:TotalAbsDevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myProportionDevFromAboveLevel =
        Response(this, "${myResponse.name}:$name:PctDevAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myProportionDevFromBelowLevel =
        Response(this, "${myResponse.name}:$name:PctDevBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myRelDevFromLevel = Response(this, "${myResponse.name}:$name:RelDevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myRelPosDevFromLevel =
        Response(this, "${myResponse.name}:$name:RelPosDevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myRelNegDevFromLevel =
        Response(this, "${myResponse.name}:$name:RelNegDevFromLevel:${D2FORMAT.format(theLevel)}")

    // all these are collected within replicationEnded()
    private var myAvgTimeAbove: Response? = null
    private var myAvgTimeBelow: Response? = null
    private var myMaxTimeAbove: Response? = null
    private var myMaxTimeBelow: Response? = null
    private var myPAA: Response? = null
    private var myPAB: Response? = null
    private var myPBB: Response? = null
    private var myPBA: Response? = null
    private var myNAA: Response? = null
    private var myNAB: Response? = null
    private var myNBB: Response? = null
    private var myNBA: Response? = null

    init {
        // collected after the replication ends
        if (statisticsOption) {
            myAvgTimeAbove = Response(this, "${myResponse.name}:$name:AvgTimeAboveLevel:${D2FORMAT.format(theLevel)}")
            myAvgTimeBelow = Response(this, "${myResponse.name}:$name:AvgTimeBelowLevel:${D2FORMAT.format(theLevel)}")
            myMaxTimeAbove = Response(this, "${myResponse.name}:$name:MaxTimeAboveLevel:${D2FORMAT.format(theLevel)}")
            myMaxTimeBelow = Response(this, "${myResponse.name}:$name:MaxTimeBelowLevel:${D2FORMAT.format(theLevel)}")
            myPAA = Response(this, "${myResponse.name}:$name:P(AboveToAbove)")
            myPAB = Response(this, "${myResponse.name}:$name:P(AboveToBelow)")
            myPBB = Response(this, "${myResponse.name}:$name:P(BelowToBelow)")
            myPBA = Response(this, "${myResponse.name}:$name:P(BelowToAbove)")
            myNAA = Response(this, "${myResponse.name}:$name:#(AboveToAbove)")
            myNAB = Response(this, "${myResponse.name}:$name:#(AboveToBelow)")
            myNBB = Response(this, "${myResponse.name}:$name:#(BelowToBelow)")
            myNBA = Response(this, "${myResponse.name}:$name:#(BelowToAbove)")
        }
    }

    private var myInitTime = 0.0
    private var myObservationIntervalStartTime = 0.0
    private var myObservationIntervalDuration = 0.0
    private var myObservationIntervalStartEvent: KSLEvent<Nothing>? = null
    private var myObservationIntervalEndEvent: KSLEvent<Nothing>? = null
    private var myHasObservationIntervalFlag = false
    private var myIntervalEndedFlag = false
    private var myIntervalStartedFlag = false

    /**
     * Causes an observation interval to be specified. An observation interval is
     * an interval of time over which the response statistics will be collected.  This
     * method will cause events to be scheduled (at the start of the simulation) that
     * represent the interval.
     *
     * @param startTime the time to start the interval, must be greater than or equal to 0.0
     * @param duration  the duration of the observation interval, must be greater than 0.0
     */
    fun scheduleObservationInterval(startTime: Double, duration: Double) {
        require(startTime >= 0.0) { "Start time must be non-negative" }
        require(duration > 0.0) { "Duration must be greater than zero" }
        myObservationIntervalStartTime = startTime
        myObservationIntervalDuration = duration
        myHasObservationIntervalFlag = true
    }

    /**
     * @return true if scheduleObservationInterval() has been previously called
     */
    fun hasObservationInterval(): Boolean {
        return myHasObservationIntervalFlag
    }

    /**
     * Causes the cancellation of the observation interval events
     */
    fun cancelObservationInterval() {
        if (myObservationIntervalStartEvent != null) {
            myObservationIntervalStartEvent!!.cancelled = true
        }
        if (myObservationIntervalEndEvent != null) {
            myObservationIntervalEndEvent!!.cancelled = true
        }
    }

    private inner class StartObservationIntervalAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            // clear any previous statistics prior to start of the interval
            warmUp()
            myIntervalStartedFlag = true
        }
    }

    private inner class EndObservationIntervalAction : EventAction<Nothing>() {
        override fun action(event: KSLEvent<Nothing>) {
            myIntervalEndedFlag = true
        }
    }

    private inner class TheObserver : ModelElementObserver() {
        override fun initialize(modelElement: ModelElement) {
            // variableInitialized();
        }

        override fun warmUp(modelElement: ModelElement) {
            //variableWarmedUp();
        }

        override fun update(modelElement: ModelElement) {
            variableUpdated()
        }

        override fun replicationEnded(modelElement: ModelElement) {
            variableReplicationEnded()
        }
    }

    private fun variableUpdated() {
        if (hasObservationInterval()) {
            // has observation interval, only capture during the interval
            if (myIntervalStartedFlag && !myIntervalEndedFlag) {
                // interval has started but not yet ended
                stateUpdate()
            }
        } else {
            // no interval, always capture
            stateUpdate()
        }
    }

    private fun stateUpdate() {
        myDeviationFromLevel.value = (myResponse.value - level)
        val nextState: State = if (myResponse.value >= level) {
            myDevAboveLevel.value = myResponse.value - level
            myDevBelowLevel.value = 0.0
            myDistanceAbove.value = myResponse.value - level
            myAbove
        } else {
            // below level
            myDevAboveLevel.value = 0.0
            myDevBelowLevel.value = level - myResponse.value
            myDistanceBelow.value = level - myResponse.value
            myBelow
        }
        nextState.enter(time)
        // now check if exit is required
        if (myCurrentState !== nextState) {
            myCurrentState.exit(time)
        }
        myCurrentState = nextState
        myStateFreq.collect(myCurrentState)
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed to initialize prior to a replication. It is called once before
     * each replication occurs if the model element wants initialization. It is
     * called after beforeReplication() is called
     */
    override fun initialize() {
        variableInitialized()
    }

    /**
     * This method should be overridden by subclasses that need actions
     * performed at the warm-up event during each replication. It is called once
     * during each replication if the model element reacts to warm up actions.
     */
    override fun warmUp() {
        variableWarmedUp()
    }

    private fun variableInitialized() {
        myIntervalEndedFlag = false
        myIntervalStartedFlag = false
        myInitTime = time
        myAbove.initialize()
        myBelow.initialize()
        myStateFreq.reset()
        myCurrentState = if (myResponse.initialValue >= level) {
            myAbove
        } else {
            myBelow
        }
        myCurrentState.enter(time)
        if (hasObservationInterval()) {
            myObservationIntervalStartEvent =
                schedule(StartObservationIntervalAction(), myObservationIntervalStartTime)
            myObservationIntervalEndEvent = schedule(
                EndObservationIntervalAction(),
                myObservationIntervalStartTime + myObservationIntervalDuration
            )
        }
    }

    private fun variableWarmedUp() {
        myInitTime = time
        myAbove.initialize()
        myBelow.initialize()
        myStateFreq.reset()
        myCurrentState = if (myResponse.value >= level) {
            myAbove
        } else {
            myBelow
        }
        myCurrentState.enter(time)
    }

    private fun variableReplicationEnded() {
        myCurrentState.exit(time)
        // need to get statistics to the end of the simulation, act like exiting current state
        myMaxDistanceAbove.value = (myDistanceAbove.withinReplicationStatistic.max)
        myMaxDistanceBelow.value = (myDistanceBelow.withinReplicationStatistic.max)
        val avgDevAbove = myDevAboveLevel.withinReplicationStatistic.weightedAverage
        val avgDevBelow = myDevBelowLevel.withinReplicationStatistic.weightedAverage
        val avgTotalDev = avgDevAbove + avgDevBelow
        myTotalAbsDeviationFromLevel.value = avgTotalDev
        if (avgTotalDev > 0.0) {
            myProportionDevFromAboveLevel.value = (avgDevAbove / avgTotalDev)
            myProportionDevFromBelowLevel.value = (avgDevBelow / avgTotalDev)
        }
        if (level != 0.0) {
            myRelDevFromLevel.value = (myDeviationFromLevel.withinReplicationStatistic.weightedAverage / level)
            myRelPosDevFromLevel.value = (avgDevAbove / level)
            myRelNegDevFromLevel.value = (avgDevBelow / level)
        }
        if (myAbove.sojournTimeStatistic != null) {
            val totalTimeInState: Double = myAbove.totalTimeInState
            myPctTimeAbove.value = (totalTimeInState / (time - myInitTime))
            myTotalTimeAbove.value = totalTimeInState
        }
        if (myBelow.sojournTimeStatistic != null) {
            val totalTimeInState: Double = myBelow.totalTimeInState
            myPctTimeBelow.value = (totalTimeInState / (time - myInitTime))
            myTotalTimeBelow.value = totalTimeInState
        }
        // collect state statistics
        if (statisticsOption) {
            if (myAbove.sojournTimeStatistic != null) {
                myAvgTimeAbove!!.value = myAbove.sojournTimeStatistic!!.average
                myMaxTimeAbove!!.value = myAbove.sojournTimeStatistic!!.max
            }
            if (myBelow.sojournTimeStatistic != null) {
                myAvgTimeBelow!!.value = myBelow.sojournTimeStatistic!!.average
                myMaxTimeBelow!!.value = myBelow.sojournTimeStatistic!!.max
            }
            val p: Array<DoubleArray> = myStateFreq.transitionProportions
            myPAA!!.value = p[0][0]
            myPAB!!.value = p[0][1]
            myPBB!!.value = p[1][1]
            myPBA!!.value = p[1][0]
            val n: Array<IntArray> = myStateFreq.transitionCounts
            myNAA!!.value = n[0][0].toDouble()
            myNAB!!.value = n[0][1].toDouble()
            myNBB!!.value = n[1][1].toDouble()
            myNBA!!.value = n[1][0].toDouble()
        }
    }
}