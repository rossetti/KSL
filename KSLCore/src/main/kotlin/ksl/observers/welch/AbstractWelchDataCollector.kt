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
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.WeightedStatistic


/**
 * An abstract base class for building collectors of Welch data
 * @param statisticType the type of statistic TALLY or TIME_PERSISTENT
 * @param name          the name of the observations being collected
 * @param batchSize     the amount of batching to perform on the observations within a replication
 */
abstract class AbstractWelchDataCollector(statisticType: StatisticType, name: String, batchSize: Double) :
    WelchDataCollectorIfc {
    init {
        require(batchSize > 0.0) { "The batch size must be > 0.0" }
    }

    /**
     * Counts the observations when processing a replication
     */
    protected var myObsCount: Long = 0

    /**
     * Holds the number of observations for each of the replications, zero is the
     * first replication
     */
    protected val myObsCountsForReps: ArrayList<Long> =  ArrayList()

    /**
     * Holds the average time between observations for each replication
     */
    protected val myAvgTBOForReps: ArrayList<Double> = ArrayList()

    /**
     * Holds the time of the last observation for each replication
     */
    protected val myTimeOfLastObsForReps: ArrayList<Double> = ArrayList()

    /**
     * Used to collect the average when processing a replication
     */
    protected val myWithinRepStats: WeightedStatistic = WeightedStatistic()

    /**
     * Used to collect the overall sample average across observations
     */
    protected val myRepStat: Statistic = Statistic()

    /**
     * Used to collect the time between observations when processing a replication
     */
    protected val myTBOStats: WeightedStatistic = WeightedStatistic()

    /**
     * Holds the average of the observations for each replication
     */
    protected val myAveragesForReps: ArrayList<Double> = ArrayList()

    /**
     * The time that the last observation occurred. The last observed time.
     */
    override var lastTime : Double = Double.NaN
        protected set

    /**
     * The observation at the last observed time.
     */
    override var lastValue : Double = Double.NaN
        protected set

    /**
     * The size associated with batching the within replication observations.
     * If the data is tally based, then it is the number of observations per batch.
     * If the data is observation-based, then it is the time period over which
     * the time average is computed.
     */
    final override val batchSize: Double = batchSize

    protected val myStatType: StatisticType = statisticType

    protected val myName: String = name

    override val numberOfObservationsForEachReplication: LongArray
        get() = KSLArrays.toPrimitives(myObsCountsForReps)
    override val avgTimeBtwObservationsForEachReplication: DoubleArray
        get() = KSLArrays.toPrimitives(myAvgTBOForReps)
    val replicationAverages: DoubleArray
        get() = KSLArrays.toPrimitives(myAveragesForReps)
    val timeOfLastObservationForReps: DoubleArray
        get() = KSLArrays.toPrimitives(myTimeOfLastObsForReps)
    override val numberOfReplications: Int
        get() = myObsCountsForReps.size

    override fun setUpCollector() {
        myObsCountsForReps.clear()
        myAvgTBOForReps.clear()
        myAveragesForReps.clear()
        myTimeOfLastObsForReps.clear()
        myObsCount = 0
        lastTime = Double.NaN
        lastValue = Double.NaN
        myWithinRepStats.reset()
        myTBOStats.reset()
        myRepStat.reset()
    }

    override fun beginReplication() {
        myObsCount = 0
        lastTime = Double.NaN
        lastValue = Double.NaN
        myWithinRepStats.reset()
        myTBOStats.reset()
        myRepStat.reset()
    }

    override fun endReplication() {
        myObsCountsForReps.add(myObsCount)
        if (myObsCount > 0) {
            myAvgTBOForReps.add(myTBOStats.average())
            myAveragesForReps.add(myRepStat.average)
            myTimeOfLastObsForReps.add(lastTime)
        } else {
            myAvgTBOForReps.add(Double.NaN)
            myAveragesForReps.add(Double.NaN)
            myTimeOfLastObsForReps.add(Double.NaN)
        }
    }
}