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

}