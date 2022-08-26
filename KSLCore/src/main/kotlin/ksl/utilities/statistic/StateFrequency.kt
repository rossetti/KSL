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
import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.random.rvariable.DEmpiricalRV

/**
 *
 * @param numStates the number of states to observe
 */
class StateFrequency(numStates: Int, name: String?= null) : IdentityIfc by Identity(name) {
    private val myFreq: IntegerFrequency
    private val myTransCnts: Array<IntArray>
    private val myStates: MutableSet<State>

    init {
        require(numStates > 1) { "The number of states must be > 1" }
        myStates = LinkedHashSet()
        for (i in 0 until numStates) {
            myStates.add(State(i))
        }
        myFreq = IntegerFrequency(0, numStates - 1, name)
        myTransCnts = Array(numStates) { IntArray(numStates) }
    }
    /**
     *
     * @return the last state number observed
     */
    var lastValue = 0
        private set

    /**
     *
     * @return the last state observed
     */
    var lastState: State? = null
        private set

    /**
     *
     * @return a copy of the list of states
     */
    val states: List<State>
        get() = ArrayList(myStates)

    /**
     * Resets the statistical collection
     */
    fun reset() {
        myFreq.reset()
        for (i in myTransCnts.indices) {
            myTransCnts[i].fill(0)
        }
        lastValue = Int.MIN_VALUE
    }

    /**
     *
     * @param states an array of states to collect on, must not be null
     */
    fun collect(states: Array<State>) {
        collect(states.asList())
    }

    /**
     *
     * @param states a list of states to collect on, must not be null
     */
    fun collect(states: List<State>) {
        for (state in states) {
            collect(state)
        }
    }

    /** Tabulate statistics on the state occurrences
     *
     * @param state if state is not one of the states created by this StateFrequency then
     * it is not tabulated (i.e. it is ignored)
     */
    fun collect(state: State) {
        if (myStates.contains(state)) {
            val newValue = state.number
            if (myFreq.totalCount > 0) {
                // there was a previous value collected, update the transition counts
                myTransCnts[lastValue][newValue]++
            }
            myFreq.collect(newValue)
            lastValue = newValue
            lastState = state
        }
    }

    /**
     *
     * @return an array of the count of state transitions from state i to state j
     */
    val transitionCounts: Array<IntArray>
        get() {
            val cnt = Array(myStates.size) { IntArray(myStates.size) }
            for (i in myStates.indices) {
                for (j in myStates.indices) {
                    cnt[i][j] = myTransCnts[i][j]
                }
            }
            return cnt
        }

    /**
     *
     * @return an array of the proportion of state transitions from state i to state j
     */
    val transitionProportions: Array<DoubleArray>
        get() {
            val p = Array(myStates.size) { DoubleArray(myStates.size) }
            val total = myFreq.totalCount.toDouble()
            if (total >= 1) {
                for (i in myStates.indices) {
                    var sum = 0.0
                    for (j in myStates.indices) {
                        sum = sum + myTransCnts[i][j]
                    }
                    if (sum >= 1) {
                        for (j in myStates.indices) {
                            p[i][j] = myTransCnts[i][j] / sum
                        }
                    }
                }
            }
            return p
        }

    /** Returns an array of size getNumberOfCells() containing
     * the values increasing by value
     *
     * @return the array of values observed or an empty array
     */
    val values: IntArray
        get() = myFreq.values

    /** Returns an array of size getNumberOfCells() containing
     * the frequencies by value
     *
     * @return the array of frequencies observed or an empty array
     */
    val frequencies: IntArray
        get() = myFreq.frequencies

    /** Returns an array of size getNumberOfCells() containing
     * the proportion by value
     *
     * @return the array of proportions observed or an empty array
     */
    val proportions: DoubleArray
        get() = myFreq.proportions

    /** Returns the cumulative frequency up to an including i
     *
     * @param i the integer for the desired frequency
     * @return the cumulative frequency
     */
    fun cumulativeFrequency(i: Int): Double {
        return myFreq.cumulativeFrequency(i)
    }

    /** Returns the cumulative proportion up to an including i
     *
     * @param i the integer for the desired proportion
     * @return the cumulative proportion
     */
    fun cumulativeProportion(i: Int): Double {
        return myFreq.cumulativeProportion(i)
    }

    /**
     * Returns Map holding the values and frequencies as arrays with
     * keys "values" and "frequencies"
     *
     * @return the Map
     */
    val valueFrequencies: Map<String, IntArray>
        get() = myFreq.valueFrequencies

    /**
     * Returns Map holding the values and proportions as arrays with
     * keys "values" and "proportions"
     *
     * @return the Map
     */
    val valueProportions: Map<String, DoubleArray>
        get() = myFreq.valueProportions

    /**
     * Returns Map holding the values and cumulative proportions as arrays with
     * keys "values" and "cumulativeProportions"
     *
     * @return the Map
     */
    val valueCumulativeProportions: Map<String, DoubleArray>
        get() = myFreq.valueCumulativeProportions

    /**
     * Returns the number of cells tabulated
     * This is also the total number of different integers observed
     *
     * @return the number of cells tabulated
     */
    val numberOfValues: Int
        get() = myFreq.numberOfValues

    /** The total count associated with the values
     *
     * @return total count associated with the values
     */
    val totalCount: Double
        get() = myFreq.totalCount

    /** Returns the current frequency for the provided integer
     *
     * @param x the provided integer
     * @return the frequency
     */
    fun frequency(x: Int): Double {
        return myFreq.frequency(x)
    }

    /** Gets the proportion of the observations that
     * are equal to the supplied integer
     *
     * @param x the integer
     * @return the proportion
     */
    fun proportion(x: Int): Double {
        return myFreq.proportion(x)
    }

    /** Interprets the elements of x[] as values
     * and returns an array representing the frequency
     * for each value
     *
     * @param x the values for the frequencies
     * @return the returned frequencies
     */
    fun frequencies(x: IntArray): DoubleArray {
        return myFreq.frequencies(x)
    }

    /** Returns a copy of the cells in a list
     * ordered by the value of each cell, 0th element
     * is cell with smallest value, etc
     *
     * @return the list
     */
    val cellList: List<IntegerFrequency.Cell>
        get() = myFreq.cellList()

    /**
     *
     * @return a DEmpirical based on the frequencies
     */
    fun createDEmpiricalCDF(): DEmpiricalCDF {
        return myFreq.createDEmpiricalCDF()
    }

    /**
     *
     * @return a Statistic over the observed integers mapped to the states
     */
    fun statistic(): Statistic = myFreq.statistic()

    /**
     *
     * @return a string representation
     */
    override fun toString(): String {
        return asString()
    }

    /**
     *
     * @return a string representation
     */
    fun asString(): String {
        val sb = StringBuilder()
        sb.append("State Frequency Tabulation for: ").append(name)
        sb.append(System.lineSeparator())
        sb.append("State Labels")
        sb.append(System.lineSeparator())
        for (state in myStates) {
            sb.append(state)
            sb.append(System.lineSeparator())
        }
        sb.append("State transition counts")
        sb.append(System.lineSeparator())
        for (row in myTransCnts) {
            sb.append(row.contentToString())
            sb.append(System.lineSeparator())
        }
        sb.append("State transition proportions")
        sb.append(System.lineSeparator())
        val transitionProportions = transitionProportions
        for (row in transitionProportions) {
            sb.append(row.contentToString())
            sb.append(System.lineSeparator())
        }
        sb.append(System.lineSeparator())
        sb.append(myFreq.toString())
        sb.append(System.lineSeparator())
        return sb.toString()
    }

}

fun main(){
    val rv = DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.2, 0.7, 1.0))
    val sf = StateFrequency(3)
    val states = sf.states
    for (i in 1..20000) {
        val x: Int = rv.value().toInt()
        sf.collect(states[x-1])
    }
    println(sf)
}