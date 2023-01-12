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

import ksl.utilities.statistic.Statistic
import java.io.PrintWriter

class WelchDataArrayCollector(
    maxNumObs: Int,
    maxNumReps: Int,
    statisticType: StatisticType,
    name: String,
    batchSize: Double
) : AbstractWelchDataCollector(statisticType, name, batchSize) {
    init {
        require(maxNumObs > 0) { "The maximum number of observations must be > 0" }
        require(maxNumReps > 0) { "The maximum number of replications must be > 0" }
    }

    /**
     * The maximum number of observations possible
     */
    private val myMaxNumObs: Int = maxNumObs

    /**
     * The maximum number of replications possible
     */
    private val myMaxNumReps: Int = maxNumReps

    /**
     * rows are the observations, columns are the replications
     */
    private val myData: Array<DoubleArray> = Array(myMaxNumObs) { DoubleArray(myMaxNumReps) }

    /**
     * The observed number of replications
     */
    private var myRepCount = 0

    /**
     * Used to count the number of observations when processing a replication
     */
    private var myRowCount = 0

    /**
     * Sets all the data to Double.NaN
     */
    fun clearData() {
        for (r in myData.indices) {
            for (c in myData[r].indices) {
                myData[r][c] = Double.NaN
            }
        }
    }

    /**
     * Welch average is across each replication for each observation
     *
     * @return an array of the Welch averages
     */
    val welchAverages: DoubleArray
        get() {
            val nRows = minNumberOfRowsAcrossReplications
            val w = DoubleArray(nRows)
            val s = Statistic()
            for (r in w.indices) {
                s.collect(myData[r])
                w[r] = s.average
                s.reset()
            }
            return w
        }

    /**
     * Gets an array that contains the cumulative average over the Welch
     * Averages
     *
     * @return returns an array that contains the cumulative average
     */
    val welchCumulativeAverages: DoubleArray
        get() {
            val w = welchAverages
            val cs = DoubleArray(w.size)
            val s = Statistic()
            for (r in w.indices) {
                s.collect(w[r])
                cs[r] = s.average
            }
            return cs
        }

    /**
     * Columns are the replications, rows are the data
     *
     * @return a copy of the data
     */
    val data: Array<DoubleArray>
        get() {
            val nRows = minNumberOfRowsAcrossReplications
            val nCols = numberOfReplications
            val data = Array(nRows) { DoubleArray(nCols) }
            for (r in data.indices) {
                myData[r].copyInto(data[r], 0, 0, nCols)
                // System.arraycopy(myData[r], 0, data[r], 0, nCols)
            }
            return data
        }

    /**
     * @param repNum the replication number 1, 2, etc
     * @return the within replication data for the indicated replication
     */
    fun replicationData(repNum: Int): DoubleArray {
        if (repNum > numberOfReplications) {
            return DoubleArray(0)
        }
        val nRows = minNumberOfRowsAcrossReplications
        val data = DoubleArray(nRows)
        for (r in 0 until nRows) {
            data[r] = myData[r][repNum - 1]
        }
        return data
    }

    /**  If no replications have been completed this returns 0
     *
     * @return the minimum number of observations (rows) across all the collected replications
     */
    val minNumberOfRowsAcrossReplications: Int
        get() {
            val min = minNumberOfObservationsAcrossReplications
            return Math.toIntExact(min)
        }

    /**
     * Writes out the number of observations to the supplied PrintWriter This
     * results in a comma separated value file that has each rows
     * containing each observation for each replication and each replication
     * as columns. The last two columns are avg is the average across the replications and cumAvg.
     * The file is flushed and closed.
     *
     * The header row is: Rep1, Rep2, ..., RepN, Avg, CumAvg
     *
     * @param out the PrintWriter
     */
    fun writeCSVWelchData(out: PrintWriter) {
        val nRows = minNumberOfRowsAcrossReplications
        val nCols = numberOfReplications
        for (c in 0 until nCols) {
            out.print("Rep" + (c + 1))
            out.print(",")
        }
        out.print("Avg")
        out.print(", ")
        out.println("CumAvg")
        val w = welchAverages
        val ca = welchCumulativeAverages
        for (r in 0 until nRows) {
            for (c in 0 until nCols) {
                out.print(myData[r][c])
                out.print(", ")
            }
            out.print(w[r])
            out.print(", ")
            out.print(ca[r])
            out.println()
        }
        out.flush()
        out.close()
    }

    /**
     * Writes out all the observations to the supplied PrintWriter This
     * results in a comma separated value file that has two columns: Avg, CumAvg
     * containing each Welch plot data point for all the observations.
     *
     * The file is flushed and closed.
     *
     * @param out  the PrintWriter
     */
    fun writeCSVWelchPlotData(out: PrintWriter) {
        val w = welchAverages
        val ca = welchCumulativeAverages
        out.print("Avg")
        out.print(", ")
        out.println("CumAvg")
        for (i in w.indices) {
            out.print(w[i])
            out.print(", ")
            out.println(ca[i])
        }
        out.flush()
        out.close()
    }

    override fun setUpCollector() {
        super.setUpCollector()
        myRepCount = 0
        myRowCount = 0
        clearData()
    }

    override fun beginReplication() {
        super.beginReplication()
        myRowCount = 0
    }

    override fun collect(time: Double, value: Double) {
        if (myStatType === StatisticType.TALLY) {
            collectTallyObservations(time, value)
        } else {
            collectTimePersistentObservations(time, value)
        }
    }

    private fun collectTallyObservations(time: Double, value: Double) {
        myWithinRepStats.collect(value) // collect with weight = 1.0
        if (myWithinRepStats.count >= batchSize) {
            // form a batch, a batch represents an observation to write to the file
            //myObsCount++;
            // need to observe time between observations
            if (myWithinRepStats.count >= 2) {
                // enough observations to collect time between
                myTBOStats.collect(time - lastTime)
            }
            // need to save the observation
            lastValue = myWithinRepStats.average()
            lastTime = time
            saveObservation(lastValue)
            // clear the batching
            myWithinRepStats.reset()
        }
    }

    private fun saveObservation(observation: Double) {
        if (myRowCount < myMaxNumObs && myRepCount < myMaxNumReps) {
            myData[myRowCount][myRepCount] = observation
            myRowCount++
            myObsCount++
        }
    }

    private fun collectTimePersistentObservations(time: Double, value: Double) {
        // need to collected time weighted statistics
        // need current time minus previous time to start
        if (time <= 0.0) {
            // starting
            lastTime = 0.0
            lastValue = value
        } else {
            // first time has occurred
            // compute time of next batch, myBatchSize is deltaT, each obs is a batch of size deltaT
            val tb: Double = (myObsCount + 1) * batchSize
            if (time > tb) {
                // then a batch can be formed
                // close out the batch at time tb
                updateTimeWeightedStatistic(tb)
                // an observation is a batch of size deltaT
                //myObsCount++;
                myTBOStats.collect(batchSize)
                // record the time average during the deltaT
                saveObservation(myWithinRepStats.average())
                //reset the time average for the next interval
                myWithinRepStats.reset()
                // update the last time to the beginning of interval
                lastTime = tb
            }
            // continue collecting new value and new time for new interval
            updateTimeWeightedStatistic(time)
            // update for new value and new time
            lastValue = value
            lastTime = time
        }
    }

    private fun updateTimeWeightedStatistic(time: Double) {
        var weight: Double = time - lastTime
        if (weight < 0.0) {
            weight = 0.0
        }
        // last value persisted for (time - myLastTime)
        myWithinRepStats.collect(lastValue, weight) // collect weighted by time
    }

    override fun endReplication() {
        super.endReplication()
        myRepCount++
    }

    override fun cleanUpCollector() {}
}