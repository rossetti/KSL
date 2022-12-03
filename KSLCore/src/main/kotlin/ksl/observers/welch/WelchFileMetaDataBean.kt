package ksl.observers.welch

import kotlinx.serialization.*
import kotlinx.serialization.json.*
import ksl.utilities.KSLArrays
import ksl.utilities.io.KSLFileUtil
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*

/**
 * A data class that holds information about Welch data files in a form that facilitates
 * translation to JSON.
 *
 */
@Serializable
data class WelchFileMetaDataBean(
    val dataName: String,
    val pathToFile: String,
    val numberOfReplications: Int,
    val numObsInEachReplication: LongArray,
    val timeOfLastObsInEachReplication: DoubleArray,
    val minNumObsForReplications: Long,
    val endReplicationAverages: DoubleArray,
    val timeBtwObsInEachReplication: DoubleArray,
    val batchSize: Double,
    val statisticType: StatisticType
) {
    init {
        require(numberOfReplications > 0) { "The number of replications must be >= 1" }
        require(KSLArrays.min(numObsInEachReplication) > 0) { "Some replication had zero observations" }
        require(KSLArrays.min(timeOfLastObsInEachReplication) > 0.0) { "Some replication had minimum time <= 0.0" }
        require(minNumObsForReplications >= 0) { "The number of replications must be >= 0" }
        require(batchSize > 0.0) { "The batch size must be >= 1" }
        for (avg in endReplicationAverages) {
            require(!(avg.isNaN() || avg.isInfinite())) { "Some average in the end replication averages array was NaN or Inf" }
        }
        for (avg in timeBtwObsInEachReplication) {
            require(!(avg.isNaN() || avg.isInfinite())) { "Some average in the average time between observations array was NaN or Inf" }
        }
        //check if file has the wdf extension
        val path = Paths.get(pathToFile)
        val fileName = path.fileName.toString()
        val name: Optional<String> = KSLFileUtil.getExtensionByStringFileName(fileName)
        val fileExtension = name.orElse("")
        require(fileExtension == "wdf") { "The supplied file string does not have extension wdf" }
    }

    /**
     *
     * @return a string representation of the path to the JSON file related to this bean
     */
    val pathToJSONFile: String
        get() = KSLFileUtil.removeLastFileExtension(pathToFile) + ".json"

    /**
     *
     * @return returns a JSON representation as a String
     */
    fun toJSON(): String {
        val format = Json { prettyPrint = true }
        return format.encodeToString(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WelchFileMetaDataBean

        if (dataName != other.dataName) return false
        if (pathToFile != other.pathToFile) return false
        if (numberOfReplications != other.numberOfReplications) return false
        if (!numObsInEachReplication.contentEquals(other.numObsInEachReplication)) return false
        if (!timeOfLastObsInEachReplication.contentEquals(other.timeOfLastObsInEachReplication)) return false
        if (minNumObsForReplications != other.minNumObsForReplications) return false
        if (!endReplicationAverages.contentEquals(other.endReplicationAverages)) return false
        if (!timeBtwObsInEachReplication.contentEquals(other.timeBtwObsInEachReplication)) return false
        if (batchSize != other.batchSize) return false
        if (statisticType != other.statisticType) return false

        return true
    }

    override fun hashCode(): Int {
        var result = dataName.hashCode()
        result = 31 * result + pathToFile.hashCode()
        result = 31 * result + numberOfReplications
        result = 31 * result + numObsInEachReplication.contentHashCode()
        result = 31 * result + timeOfLastObsInEachReplication.contentHashCode()
        result = 31 * result + minNumObsForReplications.hashCode()
        result = 31 * result + endReplicationAverages.contentHashCode()
        result = 31 * result + timeBtwObsInEachReplication.contentHashCode()
        result = 31 * result + batchSize.hashCode()
        result = 31 * result + statisticType.hashCode()
        return result
    }

    companion object {
        fun makeFromJSON(pathToJSONFile: Path): WelchFileMetaDataBean {
            val fileName = pathToJSONFile.fileName.toString()
            val name: Optional<String> = KSLFileUtil.getExtensionByStringFileName(fileName)
            val fileExtension = name.orElse("")
            require(fileExtension == "json") { "The supplied file string does not have extension json" }
            val inputStream = pathToJSONFile.toFile().inputStream()
            val text = inputStream.readBytes().toString(Charsets.UTF_8)
            inputStream.close()
            return Json.decodeFromString(text)
        }
    }
}

