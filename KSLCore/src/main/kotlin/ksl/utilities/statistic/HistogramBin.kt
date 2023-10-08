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

/**
 * @param theBinNumber the bin number
 * @param theLowerLimit the lower limit of the bin
 * @param theUpperLimit the upper limit of the bin
 */
class HistogramBin(theBinNumber: Int, theLowerLimit: Double, theUpperLimit: Double) {

    init {
        require(theLowerLimit < theUpperLimit) { "The lower limit of the bin must be < the upper limit" }
        require(theBinNumber > 0) { "The bin number must be greater than 0" }
    }

    val lowerLimit: Double = theLowerLimit
    val upperLimit: Double = theUpperLimit

    val closedRange: ClosedFloatingPointRange<Double>
        get() = lowerLimit.rangeTo(upperLimit)

    val openRange: OpenEndRange<Double>
        get() = lowerLimit.rangeUntil(upperLimit)

    val openIntRange: IntRange
        get() = lowerLimit.toInt().rangeUntil(upperLimit.toInt())

    val closedIntRange: IntRange
        get() = lowerLimit.toInt().rangeTo(upperLimit.toInt())

    val width
        get() = upperLimit - lowerLimit

    val midPoint
        get() = (upperLimit - lowerLimit)/2.0

    var count = 0
        private set

    /**
     * The label for the bin
     */
    var binLabel: String = String.format("%3d [%5.2f,%5.2f) ", theBinNumber, theLowerLimit, theUpperLimit)

    /**
     * Gets the number of the bin 1 = first bin, 2 = 2nd bin, etc
     *
     * @return the number of the bin
     */
    val binNumber: Int = theBinNumber

    /**
     * @return a copy of this bin
     */
    fun instance(): HistogramBin {
        val bin = HistogramBin(binNumber, lowerLimit, upperLimit)
        bin.count = count
        return bin
    }

    /**
     * Increments the bin count by 1.0
     */
    fun increment() {
        count = count + 1
    }

    /**
     * Resets the bin count to 0.0
     */
    fun reset() {
        count = 0
    }

    fun count(): Double {
        return count.toDouble()
    }

    override fun toString(): String {
        // String s = "[" + lowerLimit + "," + upperLimit + ") = " + count;
        return String.format("%s = %d", binLabel, count)
    }
}