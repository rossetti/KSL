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
/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.utilities.statistic

import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.distributions.DEmpiricalCDF
import ksl.utilities.random.rvariable.DEmpiricalRV
import ksl.utilities.random.rvariable.KSLRandom
import ksl.utilities.toDoubles

/**
 * This class tabulates the frequency associated with
 * the integers presented to it via the collect() method
 * Every value presented is interpreted as an integer
 * For every value presented a count is maintained.
 * There could be space/time performance issues if
 * the number of different values presented is large.
 *
 *
 * This class can be useful for tabulating a
 * discrete histogram over the values (integers) presented.
 *
 * @author rossetti
 *
 * @param lowerLimit the defined lower limit of the integers, values less than this are not tabulated
 * @param upperLimit the defined upper limit of the integers, values less than this are not tabulated
 * @param name a name for the instance
 */
class IntegerFrequency(
    val lowerLimit: Int = Int.MIN_VALUE,
    val upperLimit: Int = Int.MAX_VALUE,
    name: String? = null
) : IdentityIfc by Identity(name) {
    init {
        require(lowerLimit < upperLimit) { "The lower limit must be < the upper limit" }
    }

    /**
     * A Cell represents a value, count pairing
     */
    private val myCells: MutableMap<Int, Cell> = HashMap()

    /**
     * Collects statistical information
     */
    private var myStatistic: Statistic = Statistic(this.name)

    /**
     * The number of observations that fell below the first bin's lower limit
     */
    var underFlowCount = 0
        private set

    /**
     * The number of observations that fell past the last bin's upper limit
     *
     */
    var overFlowCount = 0
        private set

    /** Can tabulate any integer value
     *
     * @param name a name for the instance
     */
    constructor(name: String?) : this(Int.MIN_VALUE, Int.MAX_VALUE, name)

    /**
     * @param intArray collects on the values in the array
     */
    fun collect(intArray: IntArray) {
        for (i in intArray) {
            collect(i)
        }
    }

    /**
     * Tabulates the count of the number of i's
     * presented.
     *
     * @param i the presented integer
     */
    fun collect(i: Int) {
        myStatistic.collect(i.toDouble())
        if (i < lowerLimit) {
            underFlowCount = underFlowCount + 1
        }
        if (i > upperLimit) {
            overFlowCount = overFlowCount + 1
        }
        // myLowerLimit <= x <= myUpperLimit
        val c = myCells[i]
        if (c == null) {
            myCells[i] = Cell(i)
        } else {
            c.increment()
        }
    }

    /**
     *
     * @param i casts the double down to an int
     */
    fun collect(i: Double) {
        collect(i.toInt())
    }

    /**
     *
     * @param array casts the doubles to ints
     */
    fun collect(array: DoubleArray) {
        for (i in array) {
            collect(i)
        }
    }

    /**
     * Resets the statistical collection
     */
    fun reset() {
        overFlowCount = 0
        underFlowCount = 0
        myStatistic.reset()
        myCells.clear()
    }

    /**
     * Returns an array of size numberOfCells containing
     * the observed values increasing by value. The 0th element
     * of the array contains the smallest value observed, 1st element
     * the next bigger value, etc.
     *
     * @return the array of values observed or an empty array
     */
    val values: IntArray
        get() {
            if (myCells.isEmpty()) {
                return IntArray(0)
            }
            var j = 0
            val v = IntArray(myCells.size)
            for (i in min..max) {
                // look up the cell by the integer
                val cell = myCells[i]
                if (cell != null) {
                    v[j] = cell.value
                    j++
                }
            }
            return v
        }

    /**
     * Returns an array of size numberOfCells containing
     * the frequencies for each value observed. The 0th element
     * is the frequency for the value stored at element 0 of the
     * array returned by the values property
     *
     * @return the array of frequencies observed or an empty array
     */
    val frequencies: IntArray
        get() {
            if (myCells.isEmpty()) {
                return IntArray(0)
            }
            var j = 0
            val v = IntArray(myCells.size)
            for (i in min..max) {
                // look up the cell by the integer
                val cell = myCells[i]
                if (cell != null) {
                    v[j] = cell.count.toInt()
                    j++
                }
            }
            return v
        }

    /**
     * Returns an array of size numberOfCells containing
     * the proportion by value. The 0th element
     * is the proportion for the value stored at element 0 of the
     * array returned by the values property, etc.
     *
     * @return the array of proportions observed or an empty array
     */
    val proportions: DoubleArray
        get() {
            if (myCells.isEmpty()) {
                return DoubleArray(0)
            }
            var j = 0
            val v = DoubleArray(myCells.size)
            for (i in min..max) {
                // look up the cell by the integer
                val cell = myCells[i]
                if (cell != null) {
                    v[j] = cell.proportion
                    j++
                }
            }
            return v
        }

    /**
     * Returns the cumulative frequency up to an including i
     *
     * @param i the integer for the desired frequency
     * @return the cumulative frequency
     */
    fun cumulativeFrequency(i: Int): Double {
        if (myCells.isEmpty()) {
            return 0.0
        }
        val cells = cells()
        var sum = 0.0
        for (c in cells) {
            sum = if (c.value <= i) {
                sum + c.count
            } else {
                break
            }
        }
        return sum
    }

    /**
     * Returns the cumulative proportion up to an including i
     *
     * @param i the integer for the desired proportion
     * @return the cumulative proportion
     */
    fun cumulativeProportion(i: Int): Double {
        if (myCells.isEmpty()) {
            return 0.0
        }
        return cumulativeFrequency(i) / totalCount
    }

    /**
     * Returns Map holding the values and frequencies as arrays with
     * keys "values" and "frequencies"
     *
     * @return the Map
     */
    val valueFrequencies: Map<String, IntArray>
        get() {
            if (myCells.isEmpty()) {
                return emptyMap()
            }
            val map = buildMap {
                put("values", this@IntegerFrequency.values)
                put("frequencies", frequencies)
            }
            return map
        }

    /**
     * Returns Map holding the values and proportions as arrays with
     * keys "values" and "proportions"
     *
     * @return the Map
     */
    val valueProportions: Map<String, DoubleArray>
        get() {
            if (myCells.isEmpty()) {
                return emptyMap()
            }
            val map = buildMap {
                put("values", this@IntegerFrequency.values.toDoubles())
                put("proportions", proportions)
            }
            return map
        }

    /**
     * Returns Map holding the values and cumulative proportions as arrays with
     * keys "values" and "cumulativeProportions"
     *
     * @return the Map
     */
    val valueCumulativeProportions: Map<String, DoubleArray>
        get() {
            if (myCells.isEmpty()) {
                return emptyMap()
            }
            val map = buildMap {
                put("values", this@IntegerFrequency.values.toDoubles())
                put("cumulativeProportions", KSLRandom.makeCDF(proportions))
            }
            return map
        }

    /**
     * Returns the number of cells tabulated
     * This is also the total number of different integers observed
     *
     * @return the number of cells tabulated
     */
    val numberOfValues: Int
        get() = myCells.size

    /**
     * The total count associated with the values
     *  The total number of values observed
     *
     * @return total count associated with the values
     */
    val totalCount: Double
        get() = myStatistic.count

    /**
     *  The smallest integer observed
     */
    val min: Int
        get() = myStatistic.min.toInt()

    /**
     *  The largest integer observed
     */
    val max: Int
        get() = myStatistic.max.toInt()

    /**
     * Returns the current frequency for the provided integer
     *
     * @param x the provided integer
     * @return the frequency
     */
    fun frequency(x: Int): Double {
        val c = myCells[x]
        return c?.count ?: 0.0
    }

    /**
     * Gets the proportion of the observations that
     * are equal to the supplied integer
     *
     * @param x the integer
     * @return the proportion
     */
    fun proportion(x: Int): Double {
        val c = myCells[x]
        return c?.proportion ?: 0.0
    }

    /**
     * Interprets the elements of x[] as values
     * and returns an array representing the frequency
     * for each value
     *
     * @param x the values for the frequencies
     * @return the returned frequencies
     */
    fun frequencies(x: IntArray): DoubleArray {
        val f = DoubleArray(x.size)
        for (j in x.indices) {
            f[j] = frequency(x[j])
        }
        return f
    }

    /**
     * Returns a copy of the cells in a list
     * ordered by the value of each cell, 0th element
     * is cell with the smallest value (integer) observed, etc
     *
     * @return the list
     */
    fun cellList(): List<Cell> {
        val cellSet = cells()
        val list: MutableList<Cell> = ArrayList()
        for (c in cellSet) {
            list.add(c.instance())
        }
        return list
    }

    /**
     * @return a DEmpirical based on the frequencies
     */
    fun createDEmpiricalCDF(): DEmpiricalCDF {
        return DEmpiricalCDF(values.toDoubles(), KSLRandom.makeCDF(proportions))
    }

    /**
     * Returns a sorted set containing the cells
     *
     * @return the sorted set of cells
     */
    private fun cells(): List<Cell> {
        val list: MutableList<Cell> = ArrayList()
        // go through the integers from smallest observed to biggest
        for (i in min..max) {
            // look up the cell by the integer
            val cell = myCells[i]
            if (cell != null) {
                //insert only if it was in the cell map
                // cells will be inserted from smallest to largest
                list.add(cell)
            }
        }
        return list
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("Frequency Tabulation ").append(name).appendLine()
        sb.append("----------------------------------------").appendLine()
        sb.append("Number of cells = ").append(numberOfValues).appendLine()
        sb.append("Lower limit = ").append(lowerLimit).appendLine()
        sb.append("Upper limit = ").append(upperLimit).appendLine()
        sb.append("Under flow count = ").append(underFlowCount).appendLine()
        sb.append("Over flow count = ").append(overFlowCount).appendLine()
        sb.append("Total count = ").append(totalCount).appendLine()
        sb.append("Minimum value observed = ").append(min).appendLine()
        sb.append("Maximum value observed = ").append(max).appendLine()
        sb.append("----------------------------------------").appendLine()
        sb.append("Value \t Count \t Proportion\n")
        for (c in cells()) {
            sb.append(c).appendLine()
        }
        sb.append("----------------------------------------").appendLine()
        sb.appendLine()
        sb.append(myStatistic.toString())
        return sb.toString()
    }

    /**
     *
     * @return a Statistic over the observed integers
     */
    fun statistic(): Statistic {
        return myStatistic.instance()
    }

    /**
     * Holds the values and their counts
     */
    inner class Cell(val value: Int) : Comparable<Cell> {
        var count = 1.0
            private set

        val proportion: Double
            get() = count / totalCount

        override operator fun compareTo(other: Cell): Int {
            return value.compareTo(other.value)
        }

        fun increment() {
            count++
        }

        override fun toString(): String {
            return "$value \t $count  \t $proportion"
        }

        fun instance(): Cell {
            val c = Cell(value)
            c.count = count
            return c
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Cell

            if (value != other.value) return false

            return true
        }

        override fun hashCode(): Int {
            return value
        }
    }

}

fun main() {
    val freq = IntegerFrequency()
    val rv = DEmpiricalRV(doubleArrayOf(1.0, 2.0, 3.0), doubleArrayOf(0.2, 0.7, 1.0))
    for (i in 1..10000) {
        freq.collect(rv.value)
    }

    println(freq)

    val dEmpiricalCDF = freq.createDEmpiricalCDF()
    println(dEmpiricalCDF)

}