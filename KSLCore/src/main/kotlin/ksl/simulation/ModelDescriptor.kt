package ksl.simulation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.controls.ControlData
import ksl.controls.experiments.ExperimentRunParameters
import ksl.utilities.io.ToJSONIfc
import ksl.utilities.random.rvariable.parameters.RVParameterData

/**
 *  A data class that describes a model.
 *  @param modelIdentifier the user assigned identifier for the model
 *  @param modelName the assigned name of the model
 *  @param description a user assigned text description of the model
 *  @param responseNames the names of responses in the model as a set
 *  @param inputNames the names of the model changeable inputs as set
 *  @param outputDirectory the path string for the model's output directory
 *  @param experimentRunParameters the current settings of the model's run parameters
 *  @param controlData the controls data extracted from the model as a list
 *  @param rvParameterData the random variable parameter data extracted from the model as a list
 *  @param configuration (if available) the Map<String, String> of the configuration setting for the model
 *  @param baseTimeUnit the base time unit setting for the model from the TimeUnit enum
 */
@Serializable
data class ModelDescriptor(
    val modelIdentifier: String,
    val modelName: String,
    val description: String,
    val responseNames: Set<String>,
    val inputNames: Set<String>,
    val outputDirectory: String,
    val experimentRunParameters: ExperimentRunParameters,
    val controlData: List<ControlData>,
    val rvParameterData: List<RVParameterData>,
    val configuration: Map<String, String>? = null,
    val baseTimeUnit: ModelElement.TimeUnit
) : ToJSONIfc {

    override fun toJson(): String {
        val format = Json {
            prettyPrint = true
            encodeDefaults = true
        }
        return format.encodeToString(this)
    }

}
