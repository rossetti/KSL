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

import ksl.observers.ModelElementObserver
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.Interval
import ksl.utilities.io.D2FORMAT
import ksl.utilities.statistic.State
import ksl.utilities.statistic.StateFrequency

/**
 * Collects statistics on whether a specific level associated with a variable is
 * maintained.
 * @param variable the response to observe
 * @param theLevel    the level to associate with the response
 * @param stats    whether detailed state change statistics are collected
 * @param theName     the name of the response
 */
class LevelResponse(variable: Variable,
                    theLevel: Double,
                    stats: Boolean = true,
                    theName: String? = null) :
    ModelElement(variable, theName) {
    init {
        require(variable.domain.contains(theLevel))
        { "The supplied level $theLevel was outside the range of the variable's limits ${variable.domain}" }
    }

    private val myObserver: ModelElementObserver = TheObserver()

    //TODO should be VariableIfc
    private val myVariable: Variable = variable
    val level: Double = theLevel

    /**
     * true if detailed state change statistics are collected
     */
    val statisticsOption: Boolean = stats

    private val myStateFreq: StateFrequency = StateFrequency(2)
    private val myAbove: State = myStateFreq.states[0]
    private val myBelow: State = myStateFreq.states[1]
    private var myCurrentState: State = if (myVariable.initialValue >= level) {
        myAbove
    } else {
        myBelow
    }

    init {
        myAbove.label = ("${myVariable.name}:$name:+")
        myBelow.label = ("${myVariable.name}:$name:-")
        myAbove.sojournTimeCollectionFlag = true
        myBelow.sojournTimeCollectionFlag = true
        myVariable.attachModelElementObserver(myObserver)
    }

    // collected during the replication
    private val myDistanceAbove = Response(this, "${myVariable.name}:$name:DistAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myDistanceBelow = Response(this, "${myVariable.name}:$name:DistBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myDevAboveLevel =
        TWResponse(this, name = "${myVariable.name}:$name:DevAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myDevBelowLevel =
        TWResponse(this, name = "${myVariable.name}:$name:DevBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myDeviationFromLevel =
        TWResponse(
            this,
            allowedDomain = Interval(),
            name = "${myVariable.name}:$name:DevFromLevel:${D2FORMAT.format(theLevel)}"
        )
    private val myMaxDistanceAbove =
        Response(this, "${myVariable.name}:$name:MaxDistAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myMaxDistanceBelow =
        Response(this, "${myVariable.name}:$name:MaxDistBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myPctTimeAbove = Response(this, "${myVariable.name}:$name:PctTimeAbove:${D2FORMAT.format(theLevel)}")
    private val myPctTimeBelow = Response(this, "${myVariable.name}:$name:PctTimeBelow:${D2FORMAT.format(theLevel)}")
    private val myTotalTimeAbove =
        Response(this, "${myVariable.name}:$name:TotalTimeAbove:${D2FORMAT.format(theLevel)}")
    private val myTotalTimeBelow =
        Response(this, "${myVariable.name}:$name:TotalTimeBelow:${D2FORMAT.format(theLevel)}")
    private val myTotalAbsDeviationFromLevel =
        Response(this, "${myVariable.name}:$name:TotalAbsDevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myProportionDevFromAboveLevel =
        Response(this, "${myVariable.name}:$name:PctDevAboveLevel:${D2FORMAT.format(theLevel)}")
    private val myProportionDevFromBelowLevel =
        Response(this, "${myVariable.name}:$name:PctDevBelowLevel:${D2FORMAT.format(theLevel)}")
    private val myRelDevFromLevel =
        Response(this, "${myVariable.name}:$name:RelDevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myRelPosDevFromLevel =
        Response(this, "${myVariable.name}:$name:RelPosDevFromLevel:${D2FORMAT.format(theLevel)}")
    private val myRelNegDevFromLevel =
        Response(this, "${myVariable.name}:$name:RelNegDevFromLevel:${D2FORMAT.format(theLevel)}")

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
            myAvgTimeAbove = Response(this, "${myVariable.name}:$name:AvgTimeAboveLevel:${D2FORMAT.format(theLevel)}")
            myAvgTimeBelow = Response(this, "${myVariable.name}:$name:AvgTimeBelowLevel:${D2FORMAT.format(theLevel)}")
            myMaxTimeAbove = Response(this, "${myVariable.name}:$name:MaxTimeAboveLevel:${D2FORMAT.format(theLevel)}")
            myMaxTimeBelow = Response(this, "${myVariable.name}:$name:MaxTimeBelowLevel:${D2FORMAT.format(theLevel)}")
            myPAA = Response(this, "${myVariable.name}:$name:P(AboveToAbove)")
            myPAB = Response(this, "${myVariable.name}:$name:P(AboveToBelow)")
            myPBB = Response(this, "${myVariable.name}:$name:P(BelowToBelow)")
            myPBA = Response(this, "${myVariable.name}:$name:P(BelowToAbove)")
            myNAA = Response(this, "${myVariable.name}:$name:#(AboveToAbove)")
            myNAB = Response(this, "${myVariable.name}:$name:#(AboveToBelow)")
            myNBB = Response(this, "${myVariable.name}:$name:#(BelowToBelow)")
            myNBA = Response(this, "${myVariable.name}:$name:#(BelowToAbove)")
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
        myDeviationFromLevel.value = (myVariable.value - level)
        val nextState: State = if (myVariable.value >= level) {
            myDevAboveLevel.value = myVariable.value - level
            myDevBelowLevel.value = 0.0
            myDistanceAbove.value = myVariable.value - level
            myAbove
        } else {
            // below level
            myDevAboveLevel.value = 0.0
            myDevBelowLevel.value = level - myVariable.value
            myDistanceBelow.value = level - myVariable.value
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
        myCurrentState = if (myVariable.initialValue >= level) {
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
        myCurrentState = if (myVariable.value >= level) {
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