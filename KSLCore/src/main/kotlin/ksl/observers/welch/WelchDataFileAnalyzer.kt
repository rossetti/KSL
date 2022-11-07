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
/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package ksl.observers.welch

import ksl.utilities.KSLArrays
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import ksl.utilities.observers.ObservableComponent
import ksl.utilities.observers.ObservableIfc
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.statistic.BatchStatistic
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistics
import java.io.*
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/**
 * This class knows how to process data collected by the WelchDataFileCollector
 * class and produce "Welch Data". That is for every observation, this file will
 * average across the replications and compute the average across the
 * replications and compute the cumulative sum over the averages.
 *
 * It can make "wpdf" files which are binary DataOutputStream files holding the
 * Welch average and the cumulative average for each of the observations.
 *
 * wpdf = Welch Plot Data File
 *
 * It can make a csv file that holds the Welch average and cumulative average
 *
 * An Observer can be attached.  It will be notified when a call to get()
 * occurs. getLastDataPoint(), getLastObservationIndex(), and getLastReplicationIndex()
 * can be used by the observer to determine the value, observation number,
 * and replication of the observation after notification.
 *
 * Unless specifically redirected, files produced by the operation of this class are stored in the same directory
 * (getBaseDirectory()) that the wdf is stored as specified by the supplied WelchFileMetaDataBean information.
 *
 * @author rossetti
 */
class WelchDataFileAnalyzer(bean: WelchFileMetaDataBean) : ObservableIfc<WelchDataFileAnalyzer> {
    /**
     *
     * @return  the meta-data bean for the welch file
     */
    val welchFileMetaDataBean: WelchFileMetaDataBean = bean

    /**
     *
     * @return the name of the response
     */
    val responseName: String = bean.dataName

    private val myObsComponent: ObservableComponent<WelchDataFileAnalyzer> = ObservableComponent()
    private val myObsCounts: LongArray = bean.numObsInEachReplication
    private val myTimePerObs: DoubleArray = bean.timeBtwObsInEachReplication
    private val myRepAvgs: DoubleArray = bean.endReplicationAverages
    private val myTimeRepsEnd: DoubleArray = bean.timeOfLastObsInEachReplication

    /**
     * The number of observations across the replications
     *
     * @return number of observations across the replications
     */
    val minNumObservationsInReplications: Long = bean.minNumObsForReplications

    private val myRowData: DoubleArray = DoubleArray(myObsCounts.size)
    private val myAcrossRepStat: Statistic = Statistic()
    private val myPathToWDF: Path = Paths.get(bean.pathToFile)
    private lateinit var myWDFDataFile: RandomAccessFile
    init {
        // set up internal state
        // get the path to the data file
        val wdfDataFile = myPathToWDF.toFile()
        // connect the analyzer to the data in the file
        try {
            myWDFDataFile = RandomAccessFile(wdfDataFile, "r")
        } catch (ex: IOException) {
            val str = "Problem creating RandomAccessFile for " + wdfDataFile.absolutePath
            KSL.logger.error(str, ex)
        }
    }

    /**
     * Returns the last data point read or Double.NaN if none read. Can be used
     * by Observers when data is read.
     *
     * @return the last data point
     */
    var lastDataPoint = Double.NaN
        private set

    private var myLastObsIndex = Long.MIN_VALUE
    private var myLastRepIndex = Long.MIN_VALUE

    /**
     *
     * @return a path to the directory (folder) that holds the analysis files
     */
    val baseDirectory: Path
        get() = myPathToWDF.parent

    /**
     *
     * @return the base name of the wdf file used in the analysis
     */
    val baseFileName: Path
        get() = myPathToWDF.fileName

    override fun attachObserver(observer: ObserverIfc<WelchDataFileAnalyzer>) {
        myObsComponent.attachObserver(observer)
    }

    override fun detachObserver(observer: ObserverIfc<WelchDataFileAnalyzer>) {
        myObsComponent.detachObserver(observer)
    }

    override fun detachAllObservers() {
        myObsComponent.detachAllObservers()
    }

    override fun isAttached(observer: ObserverIfc<WelchDataFileAnalyzer>): Boolean {
        return myObsComponent.isAttached(observer)
    }

    override fun countObservers(): Int {
        return myObsComponent.countObservers()
    }
    /**
     * Makes a file and writes out the welch data to the DataOutputStream. This
     * produces a file with the "wpdf" extension. wpdf = Welch Plot Data File
     * Squelches inconvenient IOExceptions
     *
     * @param numObs number of observations to write out
     * @return the file reference
     */
    fun makeWelchPlotDataFile(numObs: Long = minNumObservationsInReplications): File {
        val path = baseDirectory.resolve(responseName + ".wpdf")
        val wpdf: File = KSLFileUtil.createFile(path)
        try {
            val fout = FileOutputStream(wpdf)
            val out = DataOutputStream(fout)
            writeWelchPlotData(out, numObs)
        } catch (ex: IOException) {
            KSL.logger.error("Unable to make welch data plot file ", ex)
        }
        return wpdf
    }

    /** This produces a file with the "wpdf" extension. wpdf = Welch Plot Data File
     *
     * Writes out the welch plot data, xbar, cumxbar to the supplied
     * DataOutputStream. The file is flushed and closed.
     *
     * @param out the stream to write to
     * @param numObs number of observations to write out
     * @throws IOException could not write the data to the file for some reason
     */
    fun writeWelchPlotData(out: DataOutputStream, numObs: Long) {
        val n = Math.min(numObs, minNumObservationsInReplications)
        val s = Statistic()
        for (i in 1..n) {
            val x = acrossReplicationAverage(i)
            s.collect(x)
            out.writeDouble(x)
            out.writeDouble(s.average)
        }
        out.flush()
        out.close()
    }
    /**
     * Makes and writes out the welch plot data. Squelches inconvenient IOExceptions
     * The file is stored in the base directory holding the
     * Welch data files and has the name of the data with _WelchPlotData.csv appended.
     *
     * The header row is Avg, CumAvg
     *
     * @param numObs number of observations to write
     * @return the File reference
     */
    fun makeCSVWelchPlotDataFile(numObs: Long = minNumObservationsInReplications): File {
        val path = baseDirectory.resolve(responseName + "_WelchPlotData.csv")
        val file: File = KSLFileUtil.createFile(path)
        val pw: PrintWriter = KSLFileUtil.createPrintWriter(file)
        try {
            writeCSVWelchPlotData(pw, numObs)
        } catch (ex: IOException) {
            KSL.logger.error("Unable to make CSV welch data plot file ", ex)
        }
        return file
    }
    /**
     * Writes out the number of observations to the supplied PrintWriter This
     * results in a comma separated value file that has x_bar and cum_x_bar
     * where x_bar is the average across the replications. The file is flushed
     * and closed.
     *
     * The header row is Avg, CumAvg
     *
     * @param out the PrintWriter
     * @param numObs how many to write
     * @throws IOException if problem writing
     */
    fun writeCSVWelchPlotData(out: PrintWriter, numObs: Long = minNumObservationsInReplications) {
        val n = Math.min(numObs, minNumObservationsInReplications)
        out.print("Avg")
        out.print(",")
        out.println("CumAvg")
        val s = Statistic()
        for (i in 1..n) {
            val x = acrossReplicationAverage(i)
            s.collect(x)
            out.print(x)
            out.print(",")
            out.println(s.average)
        }
        out.flush()
        out.close()
    }

    /**
     * This results in a comma separated value file that has each rows
     * containing each observation for each replication and each replication
     * as columns. The last two columns are avg is the average across the replications and cumAvg.
     * The file is flushed and closed. The file is stored in the base directory holding the
     * Welch data files and has the name of the data with _WelchData.csv appended.
     *
     * The header row is: Rep1, Rep2, ..., RepN, Avg, CumAvg
     *
     * @param numObs how many to write
     * @throws IOException if problem writing
     */
    fun makeCSVWelchDataFile(numObs: Long = minNumObservationsInReplications): File {
        val path = baseDirectory.resolve(responseName + "_WelchData.csv")
        val file: File = KSLFileUtil.createFile(path)
        val pw: PrintWriter = KSLFileUtil.createPrintWriter(file)
        writeCSVWelchData(pw, numObs)
        return file
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
     * @param numObs how many to write
     * @throws IOException if problem writing
     */
    fun writeCSVWelchData(out: PrintWriter, numObs: Long = minNumObservationsInReplications) {
        val n = Math.min(numObs, minNumObservationsInReplications)
        val nReps = numberOfReplications()
        // make the header
        val joiner = StringJoiner(", ")
        for (i in 1..nReps) {
            joiner.add("Rep$i")
        }
        joiner.add("Avg")
        joiner.add("CumAvg")
        out.println(joiner)
        // write each row
        val stat = Statistic()
        for (i in 1..n) {
            acrossReplicationData(i, myRowData)
            val row: String = KSLArrays.toCSVString(myRowData)
            myAcrossRepStat.reset()
            myAcrossRepStat.collect(myRowData)
            val avg: Double = myAcrossRepStat.average
            stat.collect(avg)
            out.print(row)
            out.print(", ")
            out.print(avg)
            out.print(", ")
            out.println(stat.average)
        }
    }

    /**
     * Returns an array of the Welch averages. Since the number of observations
     * in the file may be very large, this may have memory implications.
     *
     * @param numObs the number of observations to get
     * @return the array of data
     * @throws IOException if there was a problem accessing the file
     */
    fun welchAverages(numObs: Int): DoubleArray {
        val n: Int = if (numObs <= minNumObservationsInReplications) {
            numObs
        } else {
            Math.toIntExact(minNumObservationsInReplications)
        }
        val x = DoubleArray(n)
        for (i in 1..n) {
            x[i - 1] = acrossReplicationAverage(i.toLong())
        }
        return x
    }

    /**
     * Returns an array of the Welch averages. Since the number of observations
     * in the file may be very large, this may have memory implications.
     *
     * Squelches any IOExceptions
     *
     * @param numObs the number of observations to get
     * @return the array of data
     */
    fun welchAveragesNE(numObs: Int): DoubleArray {
        var avgs = DoubleArray(0)
        try {
            avgs = welchAverages(numObs)
        } catch (e: IOException) {
            e.printStackTrace()
        }
        return avgs
    }

    /**
     * Returns an array of the cumulative Welch averages. Since the number of observations
     * in the file may be very large, this may have memory implications.
     *
     * Squelches any IOExceptions
     *
     * @param numObs the number of observations to get
     * @return the array of data
     */
    fun cumulativeWelchAverages(numObs: Int): DoubleArray {
        val avgs = welchAveragesNE(numObs)
        val cumAvgs = DoubleArray(avgs.size)
        val s = Statistic()
        for (i in avgs.indices) {
            s.collect(avgs[i])
            cumAvgs[i] = s.average
        }
        return cumAvgs
    }

    /**
     * Creates a BatchStatistic that batches the Welch averages according to the
     * batching parameters. Uses the number of observations via
     * getMinNumObservationsInReplications() to determine the number of batches
     * based on MIN_BATCH_SIZE. No data is deleted.
     *
     * @return A BatchStatistic
     * @throws IOException if there was a problem accessing the file
     */
    fun batchWelchAverages(): BatchStatistic {
        return batchWelchAverages(0, MIN_BATCH_SIZE)
    }

    /**
     * Creates a BatchStatistic that batches the Welch averages according to the
     * batching parameters. Uses the number of observations via
     * getMinNumObservationsInReplications() to determine the number of batches
     * based on MIN_BATCH_SIZE.
     *
     * @param deletePt the number of observations to delete at beginning of
     * series
     * @return A BatchStatistic
     * @throws IOException if there was a problem accessing the file
     */
    fun batchWelchAverages(deletePt: Int): BatchStatistic {
        return batchWelchAverages(deletePt, MIN_BATCH_SIZE)
    }

    /**
     * Creates a BatchStatistic that batches the Welch averages according to the
     * batching parameters. Uses the number of observations via
     * getMinNumObservationsInReplications() to determine the number of batches
     * based on the supplied batch size.
     *
     * @param deletePt the number of observations to delete at beginning of
     * series
     * @param minBatchSize the size of the batches, must be GT 1
     * @return A BatchStatistic
     * @throws IOException if there was a problem accessing the file
     */
    fun batchWelchAverages(deletePt: Int, minBatchSize: Int): BatchStatistic {
        require(minBatchSize > 1) { "Batch size must be >= 2" }
        val n = minNumObservationsInReplications
        val k = n / minBatchSize
        val minNumBatches = Math.toIntExact(k)
        return batchWelchAverages(deletePt, minNumBatches, minBatchSize, 2)
    }

    /**
     * Creates a BatchStatistic that batches the Welch averages according to the
     * batching parameters. If the minNumBatches x minBatchSize = number of
     * observations then the maxNBMultiple does not matter. Uses a batch
     * multiple of 2.
     *
     * @param deletePt the number of observations to delete at beginning of
     * series
     * @param minNumBatches the minimum number of batches to make
     * @param minBatchSize the minimum batch size
     * @return a BatchStatistic
     * @throws IOException if there was a problem accessing the file
     */
    fun batchWelchAverages(deletePt: Int, minNumBatches: Int, minBatchSize: Int): BatchStatistic {
        return batchWelchAverages(deletePt, minNumBatches, minBatchSize, 2)
    }

    /**
     * Creates a BatchStatistic that batches the Welch averages according to the
     * batching parameters. If the minNumBatches x minBatchSize = number of
     * observations then the maxNBMultiple does not matter.
     *
     * @param deletePt the number of observations to delete at beginning of
     * series
     * @param minNumBatches the minimum number of batches to make
     * @param minBatchSize the minimum batch size
     * @param maxNBMultiple the batch means multiple
     * @return the BatchStatistic
     * @throws IOException if there was a problem accessing the file
     */
    fun batchWelchAverages(
        deletePt: Int, minNumBatches: Int,
        minBatchSize: Int, maxNBMultiple: Int
    ): BatchStatistic {
        var d = deletePt
        if (d < 0) {
            d = 0
        }
        val k = d + 1
        val b = BatchStatistic(minNumBatches, minBatchSize, maxNBMultiple)
        for (i in k..minNumObservationsInReplications) {
            b.collect(acrossReplicationAverage(i))
        }
        return b
    }

    /**
     * The number of observations in each replication
     *
     * @return number of observations in each replication
     */
    val observationCounts: LongArray
        get() = myObsCounts.copyOf(myObsCounts.size)

    /**
     * Returns the average amount of time taken per observation in each of the
     * replications
     *
     * @return the average amount of time taken per observation in each of the
     * replications
     */
    val timePerObservation: DoubleArray
        get() = myTimePerObs.copyOf(myTimePerObs.size)

    /**
     * Returns the average within each replication. That is, the average of the
     * observations within each replication. zero is the first replication
     *
     * @return the average within each replication
     */
    val replicationAverages: DoubleArray
        get() = myRepAvgs.copyOf(myRepAvgs.size)

    /**
     * The average time between observations in the simulation across all the
     * replications. This can be used to determine a warmup period in terms of
     * time.
     *
     * @return average time between observations
     */
    val averageTimePerObservation: Double
        get() = myTimePerObs.statistics().average

    /**
     * Computes and returns the across replication average for ith row of
     * observations
     *
     * @param i row number
     * @return  the across replication average for ith row
     * @throws IOException if there was trouble with the file
     */
    fun acrossReplicationAverage(i: Long): Double {
        myAcrossRepStat.reset()
        myAcrossRepStat.collect(acrossReplicationData(i, myRowData))
        return myAcrossRepStat.average
    }

    /**
     * Fills the supplied array with a row of observations across the
     * replications
     *
     * @param i row number
     * @param x array to hold across replication observations
     * @return the array of filled observations
     * @throws IOException if there was trouble with the file
     */
    fun acrossReplicationData(i: Long, x: DoubleArray): DoubleArray {
        require(x.size == myObsCounts.size) { "The supplied array's length was not " + myObsCounts.size }
        require(i <= minNumObservationsInReplications) { "The desired row is larger than $minNumObservationsInReplications" }
        for (j in 1..x.size) {
            x[j - 1] = get(i, j)
        }
        return x
    }

    /**
     * The number of replications
     *
     * @return The number of replications
     */
    fun numberOfReplications(): Int {
        return myObsCounts.size
    }

    /**
     * Returns the ith observation in the jth replication
     *
     * @param i ith observation
     * @param j jth replication
     * @return the ith observation in the jth replication
     * @throws IOException if there was trouble with the file
     */
    operator fun get(i: Long, j: Int): Double {
        set(i, j)
        return get()
    }

    /**
     * Returns the value at the current position
     *
     * @return the value at the current position
     * @throws IOException if there was trouble with the file
     */
    fun get(): Double {
        lastDataPoint = myWDFDataFile.readDouble()
        myObsComponent.notifyAttached(this)
        return lastDataPoint
    }

    /**
     * Moves the file pointer to the position associated with the ith
     * observation in the jth replication
     *
     * @param i ith observation
     * @param j jth replication
     * @throws IOException if there was trouble with the file
     */
    operator fun set(i: Long, j: Int) {
        myWDFDataFile.seek(position(i, j))
    }

    /**
     * Gets the position within the file relative to the beginning of the file of
     * the ith observation in the jth replication. This assumes that the data is
     * a double 8 bytes stored in column major form
     *
     * @param i the index to the ith observation
     * @param j the index to the jth replication
     * @return the position in the file relative to the beginning of the file of
     * the ith observation in the jth replication
     */
    fun position(i: Long, j: Int): Long {
        require((i < 1) || (j < 1) || (j > myObsCounts.size) || (i <= myObsCounts[j - 1])) { "Invalid observation# or replication#" }
        myLastObsIndex = i
        myLastRepIndex = j.toLong()
        var pos: Long = 0
        for (n in 0 until j - 1) {
            pos = pos + myObsCounts[n]
        }
        pos = pos + (i - 1)
        return pos * NUMBYTES
    }

    /** Returns the last observation index asked for.  Can be used by observers
     * Returns Integer.MIN_VALUE if no observations have been read
     * @return  the last observation index asked for.
     */
    fun lastObservationIndex(): Long {
        return myLastObsIndex
    }

    /** Returns the last replication index asked for.  Can be used by observers
     * Returns Integer.MIN_VALUE if no observations have been read
     * @return  last replication index asked for
     */
    fun lastReplicationIndex(): Long {
        return myLastRepIndex
    }

    override fun toString(): String {
        return welchFileMetaDataBean.toJSON()
    }

    companion object {
        const val NUMBYTES = 8
        const val MIN_BATCH_SIZE = 10

        /**
         *
         * @param pathToWelchFileMetaDataBeanJson must not be null, must be JSON, must represent WelchFileMetaDataBean
         * @return an optional holding the WelchDataFileAnalyzer
         */
        fun makeFromJSON(pathToWelchFileMetaDataBeanJson: Path): WelchDataFileAnalyzer {
            val metaDataBean: WelchFileMetaDataBean =
                WelchFileMetaDataBean.makeFromJSON(pathToWelchFileMetaDataBeanJson)
            return WelchDataFileAnalyzer(metaDataBean)
        }

    }
}

fun main() {
    val y = doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0)
    val stat = Statistic(y)
    val avg: Double = stat.average
    val partialSums: DoubleArray = WelchDataCollectorIfc.partialSums(avg, y)
    println("avg = $avg")
    println(Arrays.toString(partialSums))
}