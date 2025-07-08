package ksl.utilities.random.rvariable.parameters

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.utilities.io.ToJSONIfc
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Represents data necessary to define a KSL random variable. The data class
 * can be serialized and deserialized.
 *
 * @property rvType The type of the random variable, defined as an instance of the RVType enum,
 *                  which specifies the random variable's distribution.
 * @property parameters A map where keys are parameter names (as Strings) and values are their
 *                      corresponding data (as DoubleArray). These parameters define the behavior
 *                      of the random variable.
 */
@Serializable
@Suppress("unused")
data class RVData(
    val rvType: RVType,
    val parameters: Map<String, DoubleArray>
) : ToJSONIfc {
    /**
     * Converts the current RVData instance into an implementation of the RVariableIfc interface.
     * The method populates the parameters of the random variable type from the `parameters` map
     * and creates a new random variable instance based on those parameters.
     *
     * @return An instance of the `RVariableIfc` interface representing the random variable
     *         with parameters set according to the `parameters` map.
     */
    fun asRVariable() : RVariableIfc {
        val rvp = rvType.rvParameters
        rvp.fillFromDoubleArrayMap(parameters)
        return rvp.createRVariable()
    }

    override fun toJson(): String {
        val format = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        return format.encodeToString(this)
    }

    companion object {

        /**
         * Deserializes a JSON string into an instance of the RVData class.
         *
         * @param jsonString The JSON string to be deserialized into an RVData object.
         *                   The JSON representation must conform to the structure of the RVData class.
         * @return An instance of RVData populated with the data from the provided JSON string.
         */
        fun fromJson(jsonString: String): RVData {
            val format = Json {
                ignoreUnknownKeys = true
            }
            return format.decodeFromString<RVData>(jsonString)
        }

        /**
         * Converts a JSON string representation into an instance of `RVariableIfc`.
         * The method parses the JSON string, maps it to an `RVData` object, and then
         * transforms it into an implementation of `RVariableIfc` using the `asRVariable` method.
         *
         * @param jsonString The JSON string to be parsed, containing the necessary data
         * to construct a random variable.
         * @return An instance of `RVariableIfc` created from the provided JSON string.
         */
        fun fromJsonToRVariable(jsonString: String): RVariableIfc {
            return fromJson(jsonString).asRVariable()
        }
    }

}
