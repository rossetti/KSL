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

package ksl.observers.welch

import ksl.utilities.KSLArrays
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.Statistic

/**
 * The purpose of this interface is to define the behavior of implementations that collect data for
 * the making of Welch plots.  The collection should work for either observation or time-persistent
 * data and should not depend upon whether the data is produced directly from a running
 * KSL model.  This is to facilitate the reuse of code for data that is generated from other sources
 * such as files or other simulation models.  The collection model assumes the following behavior:
 *
 * 1) There is a setup phase to prepare the collector.
 * 2) There is a need to indicate the beginning of a replication.
 * 3) There is a requirement to collect/record the individual observations during a replication.
 * 4) There is a requirement that the collected observations be stored for processing. The frequency of
 * storage may be different from the frequency of collected observations. For example, the raw observations
 * may be batched in order to facilitate analysis and plotting.
 * 5) There is a need to indicate the ending of a replication.
 * 6) There is a cleanup phase to close up the collector.
 *
 */
interface WelchDataCollectorIfc {
    /**
     * The number of full replications observed
     *
     * @return the number of replications observed
     */
    val numberOfReplications: Int

    /**
     * The average time between observations in each replication returned as an
     * array. 0 element is the first replication observed. If no replications
     * have been observed then the array will be empty.
     *
     * @return the average time between observations
     */
    val avgTimeBtwObservationsForEachReplication: DoubleArray

    /**
     * The number of observations in each replication returned as an array. 0
     * element is the first replication count. If no replications
     * have been observed then the array will be empty.
     *
     * @return the number of observations for each replication
     */
    val numberOfObservationsForEachReplication: LongArray

    /** If there have been no replications, then this returns 0
     *
     * @return the minimum number of observations across the replications
     */
    val minNumberOfObservationsAcrossReplications: Long
        get() {
            val counts = numberOfObservationsForEachReplication
            return if (counts.isEmpty()) {
                0
            } else KSLArrays.min(counts)
        }

    /** The size of a batch can be considered either in terms of the number of observations
     * in each batch or as the amount of time covered with each batch
     *
     * @return the size of a batch
     */
    val batchSize: Double

    /**
     * Should be executed once prior to any collection and should be used to clear
     * any statistical collection and prepare the collector for collecting data.
     */
    fun setUpCollector()

    /**
     * Should be executed prior to each replication
     */
    fun beginReplication()

    /**
     *
     * @param time the time that the observation occurred
     * @param value the value of the observation at the observed time
     */
    fun collect(time: Double, value: Double)

    /** The time of the observation recorded
     *
     * @return Returns the time of the last collected value.
     */
    val lastTime: Double

    /** The value of the observation recorded
     *
     * @return Returns the previously collected value.
     */
    val lastValue: Double

    /**
     * Should be executed after each replication
     */
    fun endReplication()

    /**
     * Should be executed once after all replications have been observed
     */
    fun cleanUpCollector()

    companion object {
        /**
         * Gets an array of the partial sum process for the provided data Based on
         * page 2575 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation for producing a partial sum plot The
         * batch means array is used as the data
         *
         * @param bm The BatchStatistic
         * @return n array of the partial sums
         */
        fun partialSums(bm: BatchStatistic): DoubleArray {
            val avg: Double = bm.average
            val data: DoubleArray = bm.batchMeans
            return partialSums(avg, data)
        }

        /**
         * Gets an array of the partial sum process for the provided data Based on
         * page 2575 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation for producing a partial sum plot
         *
         * @param avg the average of the supplied data array
         * @param data the data
         * @return the array of partial sums
         */
        fun partialSums(avg: Double, data: DoubleArray): DoubleArray {
            val n = data.size
            val s = DoubleArray(n + 1)
            if (n == 1) {
                s[0] = 0.0
                s[1] = 0.0
                return s
            }
            // first pass computes cum sums
            s[0] = 0.0
            for (j in 1..n) {
                s[j] = s[j - 1] + data[j - 1]
            }
            // second pass computes partial sums
            for (j in 1..n) {
                s[j] = j * avg - s[j]
            }
            return s
        }

        /**
         * Uses the batch means array from the BatchStatistic to compute the
         * positive bias test statistic
         *
         * @param bm the BatchStatistic
         * @return the positive bias test statistic
         */
        fun positiveBiasTestStatistic(bm: BatchStatistic): Double {
            val data: DoubleArray = bm.batchMeans
            return positiveBiasTestStatistic(data)
        }

        /**
         * Computes initialization bias (positive) test statistic based on algorithm
         * on page 2580 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation
         *
         * @param data the data
         * @return test statistic to be compared with F distribution
         */
        fun positiveBiasTestStatistic(data: DoubleArray): Double {
            //find min and max of partial sum series!
            val n = data.size / 2
            val x1 = data.copyOfRange(0, n)
            val x2 = data.copyOfRange(n + 1, 2 * n)
            val s = Statistic()
            s.collect(x1)
            val a1: Double = s.average
            s.reset()
            s.collect(x2)
            val a2: Double = s.average
            val ps1 = partialSums(a1, x1)
            val ps2 = partialSums(a2, x2)
            val mi1: Int = KSLArrays.indexOfMax(ps1)
            val max1: Double = KSLArrays.max(ps1)
            val mi2: Int = KSLArrays.indexOfMax(ps2)
            val max2: Double = KSLArrays.max(ps2)
            val num = mi2 * (n - mi2) * max1 * max1
            val denom = mi1 * (n - mi1) * max2 * max2
            if (max2 == 0.0) {
                return Double.NaN
            }
            return if (denom == 0.0) {
                Double.NaN
            } else num / denom
        }

        /**
         * Uses the batch means array from the BatchStatistic to compute the
         * positive bias test statistic
         *
         * @param bm the BatchStatistic
         * @return the computed test statistic
         */
        fun negativeBiasTestStatistic(bm: BatchStatistic): Double {
            val data: DoubleArray = bm.batchMeans
            return negativeBiasTestStatistic(data)
        }

        /**
         * Computes initialization bias (negative) test statistic based on algorithm
         * on page 2580 Chapter 102 Nelson Handbook of Industrial Engineering,
         * Quantitative Methods in Simulation
         *
         * @param data the data to test
         * @return test statistic to be compared with F distribution
         */
        fun negativeBiasTestStatistic(data: DoubleArray): Double {
            //find min and max of partial sum series!
            val n = data.size / 2
            val x1 = data.copyOfRange(0, n)
            val x2 = data.copyOfRange(n + 1, 2 * n)
            val s = Statistic()
            s.collect(x1)
            val a1: Double = s.average
            s.reset()
            s.collect(x2)
            val a2: Double = s.average
            val ps1 = partialSums(a1, x1)
            val ps2 = partialSums(a2, x2)
            val mi1: Int = KSLArrays.indexOfMin(ps1)
            val min1: Double = KSLArrays.min(ps1)
            val mi2: Int = KSLArrays.indexOfMin(ps2)
            val min2: Double = KSLArrays.min(ps2)
            val num = mi2 * (n - mi2) * min1 * min1
            val denom = mi1 * (n - mi1) * min2 * min2
            if (min2 == 0.0) {
                return Double.NaN
            }
            return if (denom == 0.0) {
                Double.NaN
            } else num / denom
        }
    }
}