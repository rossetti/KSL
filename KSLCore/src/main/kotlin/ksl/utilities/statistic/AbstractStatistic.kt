/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
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
package ksl.utilities.statistic

const val DEFAULT_CONFIDENCE_LEVEL = 0.95

fun Double.isMissing(): Boolean {
    return this.isNaN() || this.isInfinite()
}

/**
 * Serves as an abstract base class for statistical collection.
 *
 *
 */
abstract class AbstractStatistic(name: String? = null) : Collector(name), StatisticIfc, Comparable<AbstractStatistic> {

    /**
     * Holds the confidence coefficient for the statistic
     */
    override var confidenceLevel: Double = DEFAULT_CONFIDENCE_LEVEL
        set(level) {
            require(!(level <= 0.0 || level >= 1.0)) { "Confidence Level must be (0,1)" }
            field = level
        }

    /**
     * Used to count the number of missing data points presented When a data
     * point having the value of (Double.NaN, Double.POSITIVE_INFINITY,
     * Double.NEGATIVE_INFINITY) are presented it is excluded from the summary
     * statistics and the number of missing points is noted. Implementers of
     * subclasses are responsible for properly collecting this value and
     * resetting this value.
     *
     */
    override var numberMissing = 0.0
        protected set

    override fun collect(obs: Double) {
        if (obs.isMissing()) {
            numberMissing++
            return
        }
        super.collect(obs)
    }

    override fun reset() {
        super.reset()
        numberMissing = 0.0
    }

    /**
     * Returns a negative integer, zero, or a positive integer if this object is
     * less than, equal to, or greater than the specified object.
     *
     * The natural ordering is based on the average
     *
     * @param other The statistic to compare this statistic to
     * @return Returns a negative integer, zero, or a positive integer if this
     * object is less than, equal to, or greater than the specified object based on the average
     */
    override operator fun compareTo(other: AbstractStatistic): Int {
        return average.compareTo(other.average)
    }

}

