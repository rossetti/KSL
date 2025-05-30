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
import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.io.plotting.IntegerFrequencyPlot
import ksl.utilities.io.plotting.StateFrequencyPlot
import ksl.utilities.random.rvariable.DEmpiricalRV

/**
 *
 * @param numStates the number of states to observe
 */
class StateFrequency(numStates: Int, name: String? = null) : IdentityIfc by Identity(name) {
    private val myFreq: IntegerFrequency
    private val myTransCnts: Array<IntArray>
    private val myStates: List<State>

    init {
        require(numStates > 1) { "The number of states must be > 1" }
        val list = mutableListOf<State>()
        for (i in 0 until numStates) {
            list.add(State(i))
        }
        myStates = list
        myFreq = IntegerFrequency(lowerLimit = 0, upperLimit = numStates - 1, name = name)
        myTransCnts = Array(numStates) { IntArray(numStates) }
    }

    /**
     *
     * @return the last state number observed
     */
    var lastValue : Int = 0
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
        get() = myStates

    fun state(index: Int): State {
        return myStates[index]
    }

    operator fun get(index: Int): State {
        return myStates[index]
    }

    val stateNames: List<String>
        get() {
            val names = mutableListOf<String>()
            for (state in myStates) {
                names.add(state.name)
            }
            return names
        }

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
     * is cell with the smallest value, etc
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
     *  Creates a plot for the state frequencies. The parameter, [proportions]
     *  indicates whether proportions (true) or frequencies (false)
     *  will be shown on the plot. The default is false.
     */
    fun frequencyPlot(proportions: Boolean = false): StateFrequencyPlot {
        return StateFrequencyPlot(this, proportions)
    }

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
        sb.appendLine("State Frequency Tabulation for: $name")
        sb.appendLine("State Labels")
        for (state in myStates) {
            sb.appendLine("state: number: ${state.number} label: ${state.label} name: ${state.name}")
        }
        sb.appendLine("State transition counts")
        for (row in myTransCnts) {
            sb.appendLine(row.contentToString())
        }
        sb.appendLine("State transition proportions")
        val transitionProportions = transitionProportions
        for (row in transitionProportions) {
            sb.appendLine(row.contentToString())
        }
        sb.appendLine()
        sb.appendLine(myFreq.toString())
        return sb.toString()
    }

}

fun main() {
    val rv = DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.2, 0.7, 1.0))
    val sf = StateFrequency(3)
    for (i in 1..20000) {
        val x: Int = rv.value().toInt()
        sf.collect(sf.state(x - 1))
    }
    println(sf)
}