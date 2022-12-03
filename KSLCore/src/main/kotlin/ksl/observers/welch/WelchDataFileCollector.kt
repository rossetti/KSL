package ksl.observers.welch

import ksl.utilities.KSLArrays
import ksl.utilities.io.KSL
import ksl.utilities.io.KSLFileUtil
import java.io.File
import java.io.IOException
import java.io.PrintWriter
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class WelchDataFileCollector(pathToDirectory: Path, statisticType: StatisticType, name: String, batchSize: Double) :
    AbstractWelchDataCollector(statisticType, name, batchSize) {
    /**
     * The file made for the raw data
     *
     * @return a file with the raw data
     */
    var dataFile: File
        private set

    // does not have to be RandomAccessFile
    private lateinit var myData: RandomAccessFile

    /**
     * The file handle for the meta-data file. The meta-data file contains the
     * number of replications as the first line, and the number of observations
     * in each of the replications as the subsequent lines
     *
     * @return the meta data file
     */
    var metaDataFile: File
        private set
    private var myMetaData: PrintWriter

    /**
     * The directory for the files
     *
     * @return the directory for the file
     */
    val directory: File

    /**
     * The base file name for the files
     *
     * @return the base file name
     */
    val fileName: String

    init {
        // make the directory
        try {
            Files.createDirectories(pathToDirectory)
        } catch (e: IOException) {
            val str = "Problem creating directory for $pathToDirectory"
            KSL.logger.error(str, e)
            e.printStackTrace()
        }
        // now make the file to hold the observations within the directory
        directory = pathToDirectory.toFile()
        // make a name for the file based on provided name
        fileName = name + "_" + statisticType.name
        dataFile = KSLFileUtil.createFile(pathToDirectory.resolve("$fileName.wdf"))
        metaDataFile = KSLFileUtil.createFile(pathToDirectory.resolve("$fileName.json"))
        myMetaData = KSLFileUtil.createPrintWriter(metaDataFile)
        try {
            myData = RandomAccessFile(dataFile, "rw")
        } catch (ex: IOException) {
            val str = "Problem creating RandomAccessFile for " + dataFile.absolutePath
            KSL.logger.error(str, ex)
        }
    }

    /**
     * Makes a WelchDataFileAnalyzer based on the file in this collector
     *
     * @return a WelchDataFileAnalyzer
     */
    fun makeWelchDataFileAnalyzer(): WelchDataFileAnalyzer {
        return WelchDataFileAnalyzer(makeWelchFileMetaDataBean())
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine("-------------------")
        sb.appendLine("Welch Data File Collector")
        sb.appendLine(welchFileMetaDataBeanAsJson)
        sb.appendLine("-------------------")
        return sb.toString()
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
            myObsCount++
            // need to observe time between observations
            if (myObsCount >= 2) {
                // enough observations to collect time between
                myTBOStats.collect(time - lastTime)
            }
            // need to save the observation
            lastValue = myWithinRepStats.average()
            lastTime = time
            writeObservation(lastValue)
            // clear the batching
            myWithinRepStats.reset()
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
            val tb = (myObsCount + 1) * batchSize
            if (time > tb) {
                // then a batch can be formed
                // close out the batch at time tb
                updateTimeWeightedStatistic(tb)
                // an observation is a batch of size deltaT
                myObsCount++
                myTBOStats.collect(batchSize)
                // record the time average during the deltaT
                writeObservation(myWithinRepStats.average())
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
        var weight = time - lastTime
        if (weight <= 0.0) {
            weight = 0.0
        }
        // last value persisted for (time - myLastTime)
        myWithinRepStats.collect(lastValue, weight) // collect weighted by time
    }

    private fun writeObservation(observation: Double) {
        try {
            myData.writeDouble(observation)
            myRepStat.collect(observation)
        } catch (ex: IOException) {
            KSL.logger.error("Unable to write observation in welch data file ", ex)
        }
    }

    fun makeWelchFileMetaDataBean(): WelchFileMetaDataBean {
        val w: WelchFileMetaDataBean = WelchFileMetaDataBean(
            dataName = myName,
        pathToFile = dataFile.absolutePath,
        numberOfReplications = numberOfReplications,
        numObsInEachReplication = numberOfObservationsForEachReplication,
        timeOfLastObsInEachReplication = timeOfLastObservationForReps,
        minNumObsForReplications = KSLArrays.min(numberOfObservationsForEachReplication),
        endReplicationAverages = replicationAverages,
        timeBtwObsInEachReplication= avgTimeBtwObservationsForEachReplication,
        batchSize = batchSize,
        statisticType = myStatType
        )
        return w
    }

    val welchFileMetaDataBeanAsJson: String
        get() {
            val bean = makeWelchFileMetaDataBean()
            return bean.toJSON()
        }

    override fun cleanUpCollector() {
        myMetaData.println(welchFileMetaDataBeanAsJson)
    }
}