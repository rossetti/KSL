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

import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.modeling.variable.TWResponse
import ksl.observers.ModelElementObserver
import ksl.simulation.ModelElement
import ksl.utilities.io.KSLFileUtil
import java.io.PrintWriter
import java.nio.file.Path

/**  Collects Welch data in to an array.  The size of the array must be specified
 * when creating the observer.  Any data that is observed that results in more
 * data than the array size is ignored (not stored).
 *
 * Permits the creation of CSV files that hold the data and the Welch plotting data
 *
 * This is essentially an in memory version of the WelchDataFileObserver
 * @param responseVariable the ResponseVariable or TimeWeighted variable to observe
 * @param maxNumObs the limit on the number of observations in each replication to store
 * @param maxNumReps the limit on the number of replications
 * @param batchSize  the batch size for condensing the data
 */
class WelchDataArrayObserver(
    responseVariable: Response,
    maxNumObs: Int = 50000,
    maxNumReps: Int = 20,
    batchSize: Double
) :
    ModelElementObserver() {

    /**
     * @param responseVariable the response to be observed
     * @param maxNumObs the limit on the number of observations in each replication to store
     * @param maxNumReps the limit on the number of replications
     * @param batchSize the batch size for batching or discretizing the data
     */
    constructor(responseVariable: ResponseCIfc, maxNumObs: Int = 50000, maxNumReps: Int = 20, batchSize: Double) : this(
        responseVariable as Response,
        maxNumObs,
        maxNumReps,
        batchSize
    )

    private val myWelchDataArrayCollector: WelchDataArrayCollector
    private val myResponse: Response = responseVariable

    init {
        val statType: StatisticType = if (responseVariable is TWResponse) {
            StatisticType.TIME_PERSISTENT
        } else {
            StatisticType.TALLY
        }
        myWelchDataArrayCollector = WelchDataArrayCollector(
            maxNumObs, maxNumReps, statType,
            responseVariable.name, batchSize
        )
        responseVariable.attachModelElementObserver(this)
    }

    /**
     * This results in a comma separated value file that has each row
     * containing each observation for each replication and each replication
     * as columns. The last two columns are avg is the average across the replications and cumAvg.
     * The file is flushed and closed. The file is stored in the base directory holding the
     * Welch data files and has the name of the data with _WelchData.csv appended.
     *
     * The header row is: Rep1, Rep2, ..., RepN, Avg, CumAvg
     */
    fun makeCSVWelchData() {
        val outDir: Path = myResponse.model.outputDirectory.outDir
        val fName: String = myResponse.name + "_WelchData.csv"
        val filePath = outDir.resolve(fName)
        val out: PrintWriter = KSLFileUtil.createPrintWriter(filePath)
        myWelchDataArrayCollector.writeCSVWelchData(out)
    }

    /**
     * Makes and writes out the welch plot data. Squelches inconvenient IOExceptions
     * The file is stored in the base directory holding the
     * Welch data files and has the name of the data with _WelchPlotData.csv appended.
     *
     * The header row is Avg, CumAvg
     */
    fun makeCSVWelchPlotData() {
        val outDir: Path = myResponse.model.outputDirectory.outDir
        val fName: String = myResponse.name + "_WelchPlotData.csv"
        val filePath = outDir.resolve(fName)
        val out: PrintWriter = KSLFileUtil.createPrintWriter(filePath)
        myWelchDataArrayCollector.writeCSVWelchPlotData(out)
    }

    val responseName: String
        get() = myResponse.name

    override fun toString(): String {
        return myWelchDataArrayCollector.toString()
    }

    val batchSize: Double
        get() = myWelchDataArrayCollector.batchSize
    val lastTime: Double
        get() = myWelchDataArrayCollector.lastTime
    val lastValue: Double
        get() = myWelchDataArrayCollector.lastValue
    val numberOfObservationsForEachReplication: LongArray
        get() = myWelchDataArrayCollector.numberOfObservationsForEachReplication
    val avgTimeBtwObservationsForEachReplication: DoubleArray
        get() = myWelchDataArrayCollector.avgTimeBtwObservationsForEachReplication
    val replicationAverages: DoubleArray
        get() = myWelchDataArrayCollector.replicationAverages
    val timeOfLastObservationForReps: DoubleArray
        get() = myWelchDataArrayCollector.timeOfLastObservationForReps
    val numberOfReplications: Int
        get() = myWelchDataArrayCollector.numberOfReplications

    /**
     *
     * @return the minimum number of observations across the replications
     */
    val minNumberOfObservationsAcrossReplications: Long
        get() = myWelchDataArrayCollector.minNumberOfObservationsAcrossReplications

    /**
     * Welch average is across each replication for each observation
     *
     * @return an array of the Welch averages
     */
    val welchAverages: DoubleArray
        get() = myWelchDataArrayCollector.welchAverages

    /**
     * Gets an array that contains the cumulative average over the Welch
     * Averages
     *
     * @return returns an array that contains the cumulative average
     */
    val welchCumulativeAverages: DoubleArray
        get() = myWelchDataArrayCollector.welchCumulativeAverages

    /** Columns are the replications, rows are the data
     *
     * @return a copy of the data
     */
    val data: Array<DoubleArray>
        get() = myWelchDataArrayCollector.data

    /**
     *
     * @param repNum the replication number 1, 2, etc
     * @return the within replication data for the indicated replication
     */
    fun replicationData(repNum: Int): DoubleArray {
        return myWelchDataArrayCollector.replicationData(repNum)
    }

    override fun beforeExperiment(modelElement: ModelElement) {
        myWelchDataArrayCollector.setUpCollector()
    }

    override fun beforeReplication(modelElement: ModelElement) {
        myWelchDataArrayCollector.beginReplication()
    }

    override fun afterReplication(modelElement: ModelElement) {
        myWelchDataArrayCollector.endReplication()
    }

    override fun update(modelElement: ModelElement) {
        val rv: Response = modelElement as Response
        myWelchDataArrayCollector.collect(rv.time, rv.value)
    }

    override fun afterExperiment(modelElement: ModelElement) {
        myWelchDataArrayCollector.cleanUpCollector()
    }

    companion object {

        /** Creates a WelchDataArrayObserver
         *
         * @param responseVariable the ResponseVariable or TimeWeighted variable to observe
         * @param maxNumObs the limit on the number of observations in each replication to store
         * @param maxNumReps the limit on the number of replications
         * @param batchSize the size of the batch, defaults to 1 for Response variables
         */
        fun createWelchArrayObserver(
            responseVariable: Response,
            maxNumObs: Int = 50000,
            maxNumReps: Int = 20,
            batchSize: Double = 1.0
        ): WelchDataArrayObserver {
            return WelchDataArrayObserver(responseVariable, maxNumObs, maxNumReps, batchSize)
        }

        /** Creates a WelchDataArrayObserver for time weighted responses. The discretizing interval of 10 time units
         *
         * Defaults to a maximum number of observations of 10000 and maximum number of replications of 20
         *
         * @param timeWeighted the TimeWeighted to observe
         * @param maxNumObs the limit on the number of observations in each replication to store
         * @param maxNumReps the limit on the number of replications
         * @return the created WelchDataArrayObserver
         */
        fun createWelchArrayObserver(
            timeWeighted: TWResponse,
            maxNumObs: Int = 10000,
            maxNumReps: Int = 20,
            deltaTInterval: Double = 10.0
        ): WelchDataArrayObserver {
            return WelchDataArrayObserver(timeWeighted, maxNumObs, maxNumReps, deltaTInterval)
        }
    }
}