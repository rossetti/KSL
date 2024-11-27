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
    useStatistic: Boolean = false,
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
         set(value) {
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
     * Total time spent in state based on exits.  Does not
     * include time in state if not exited.
     */
    override var totalTimeInState = 0.0
        protected set

//    override fun toString(): String {
//        val sb = StringBuilder("State{")
//        sb.append("id = $id ")
//        sb.append(", number = $number")
//        sb.append(", name = $name}")
//        return sb.toString()
//    }



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
        if (isEntered && (numberOfTimesEntered > 1.0)) {
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
    protected open fun onEnter() {}

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
        if (!timeStateEntered.isNaN()){
            require(time >= timeStateEntered) { "The exit time = $time was < enter time = $timeStateEntered" }
        }
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
    protected open fun onExit() {}

    /**
     * Initializes the state back to new
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
        resetSojournTimeStatistics()
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
     * This does not affect whether or the state has been entered,
     * the time it was last entered, or the time it was last exited.
     * To reset those quantities and the state counters use initialize()
     */
    fun resetStateCollection() {
        numberOfTimesEntered = 0.0
        numberOfTimesExited = 0.0
        totalTimeInState = 0.0
    }

    override fun toString(): String {
        return "State(number=$number, label=${label} name=$name, isEntered=$isEntered, numberOfTimesEntered=$numberOfTimesEntered, numberOfTimesExited=$numberOfTimesExited, timeStateEntered=$timeStateEntered, timeFirstEntered=$timeFirstEntered, timeStateExited=$timeStateExited, totalTimeInState=$totalTimeInState)"
    }

}