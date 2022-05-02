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

