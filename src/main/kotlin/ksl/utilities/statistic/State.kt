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
package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

/**
 * incremented to give a running total of the
 * number of states created
 */
private var stateCounter: Int = 0

/**
 * Create a state with given name and
 * indicate usage of a Statistic object to
 * collect additional statistics
 *
 * @param name         The name of the state
 * @param theStateNumber       a number assigned to the state for labeling purposes
 * @param useStatistic True means collect sojourn time statistics
 */
open class State(
    theStateNumber: Int = stateCounter + 1,
    name: String = "State:${theStateNumber}",
    useStatistic: Boolean = false
) : IdentityIfc by Identity(name), StateAccessorIfc {

    /**
     * statistical collector
     */
    override var sojournTimeStatistic: Statistic? = null
        protected set

    /**
     * Indicates whether statistics should be collected on
     * time spent in the state. The default is false
     */
    var sojournTimeCollectionFlag = useStatistic
        protected set(value) {
            if (value) {
                if (sojournTimeStatistic == null) {
                    sojournTimeStatistic = Statistic("$name:SojournTime")
                }
            }
            field = value
        }

    init {
        stateCounter = stateCounter + 1
        if(useStatistic){
            sojournTimeCollectionFlag = true
        }
        initialize()
    }

    /**
     * A user defined integer label for the state
     */
    val number: Int = theStateNumber

    /**
     * indicates whether currently in the state
     */
    override var isEntered = false
        protected set

    /**
     * number of times the state was entered
     */
    override var numberOfTimesEntered = 0.0
        protected set

    /**
     * number of times the state was exited
     */
    override var numberOfTimesExited = 0.0
        protected set

    /**
     * time the state was last entered
     */
    override var timeStateEntered = Double.NaN
        protected set

    /**
     * time that the state was entered for the first time
     */
    override var timeFirstEntered = Double.NaN
        protected set

    /**
     * time the state was last exited
     */
    override var timeStateExited = Double.NaN
        protected set

    /**
     * Total time spent in state
     */
    override var totalTimeInState = 0.0
        protected set

    override fun toString(): String {
        val sb = StringBuilder("State{")
        sb.append("id = ").append(id)
        sb.append(", number = ").append(number)
        sb.append(", name = '").append(name).append("\'")
        sb.append("}")
        return sb.toString()
    }

    /**
     * Causes the state to be entered
     * If the state has already been entered then nothing happens.
     * Preconditions: time must be &gt;= 0, must not be Double.NaN and must not
     * be Double.Infinity
     *
     * @param time The time that the state is being entered
     */
    fun enter(time: Double) {
        require(!time.isNaN()) { "The supplied time was Double.NaN" }
        require(time.isFinite()) { "The supplied time was Double.Infinity" }
        require(time >= 0.0) { "The supplied time was less than 0.0" }
        if (isEntered) {
            return
        }
        numberOfTimesEntered = numberOfTimesEntered + 1.0
        if (numberOfTimesEntered == 1.0) {
            timeFirstEntered = time
        }
        timeStateEntered = time
        isEntered = true
        onEnter()
    }

    /**
     * can be overwritten by subclasses to
     * perform work when the state is entered
     */
    protected fun onEnter() {}

    /**
     * Causes the state to be exited
     *
     * @param time the time that the state was exited, must
     * be &gt;= time entered, &gt;= 0, not Double.NaN not Double.Infinity
     * @return the time spent in the  state as a double
     */
    fun exit(time: Double): Double {
        require(!time.isNaN()) { "The supplied time was Double.NaN" }
        require(time.isFinite()) { "The supplied time was Double.Infinity" }
        require(time >= 0.0) { "The supplied time was less than 0.0" }
        require(time >= timeStateEntered) { "The exit time = $time was < enter time = $timeStateEntered" }
        if (!isEntered) {
            throw IllegalStateException("Attempted to exit a state when not in the state:$this")
        }
        numberOfTimesExited = numberOfTimesExited + 1.0
        timeStateExited = time
        val timeInState = timeStateExited - timeStateEntered
        totalTimeInState = totalTimeInState + timeInState
        isEntered = false
        if (sojournTimeStatistic != null) {
            sojournTimeStatistic!!.collect(timeInState)
        }
        onExit()
        return timeInState
    }

    /**
     * can be overwritten by subclasses to
     * perform work when the state is exited
     */
    protected fun onExit() {}

    /**
     * Initializes the state back to new
     * - not in state
     * - enter/exited time/time first entered = Double.NaN
     * - total time in state = 0.0
     * - enter/exited count = 0.0
     * - sojourn statistics reset if turned on
     */
    fun initialize() {
        isEntered = false
        timeStateEntered = Double.NaN
        timeStateExited = Double.NaN
        timeFirstEntered = Double.NaN
        totalTimeInState = 0.0
        numberOfTimesEntered = 0.0
        numberOfTimesExited = 0.0
        if (sojournTimeStatistic != null) {
            sojournTimeStatistic!!.reset()
        }
    }

    /**
     * Resets the statistics collected on the sojourn time in the state
     */
    fun resetSojournTimeStatistics() {
        if (sojournTimeStatistic != null) {
            sojournTimeStatistic!!.reset()
        }
    }

    /**
     * Resets the counters for the number of times a state
     * was entered, exited, and the total time spent in the state
     * This does not effect whether or the state has been entered,
     * the time it was last entered, or the time it was last exited.
     * To reset those quantities and the state counters use initialize()
     */
    fun resetStateCollection() {
        numberOfTimesEntered = 0.0
        numberOfTimesExited = 0.0
        totalTimeInState = 0.0
    }

}