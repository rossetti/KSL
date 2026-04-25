package ksl.simulation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.controls.ControlData
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunParameters
import ksl.utilities.io.ToJSONIfc
import ksl.utilities.random.rvariable.parameters.RVParameterData
import ksl.utilities.random.rvariable.parameters.RVParameterSetter

/**
 *  A data class that describes a model's configuration at a point in time.
 *
 *  The [controls] field captures all three control families (numeric, string, and JSON)
 *  in a single [ModelControlsExport] snapshot, replacing the previous `controlData` stored
 *  field.  Backward-compatible access to numeric controls is provided by the computed
 *  property [controlData].
 *
 *  The `inputNames` and `outputDirectory` fields that appeared in earlier versions of this
 *  class have been removed as stored fields:
 *  - [inputNames] is now a computed property derived from [controls] and [rvParameterData]
 *    (it contained no independent information).
 *  - `outputDirectory` was runtime filesystem state rather than model configuration and had
 *    no consumers that read it back from a descriptor.
 *
 *  @param modelIdentifier          user-assigned identifier for the model
 *  @param modelName                assigned name of the model (unique within the element hierarchy)
 *  @param description              user-assigned text description
 *  @param responseNames            names of all responses registered in the model
 *  @param experimentRunParameters  current run-parameter settings (replications, length, warm-up, etc.)
 *  @param controls                 snapshot of all controls extracted from the model element graph,
 *                                  spanning numeric ([ksl.controls.ControlData]),
 *                                  string ([ksl.controls.StringControlData]),
 *                                  and JSON ([ksl.controls.JsonControlData]) families
 *  @param rvParameterData          random variable parameter data extracted from the model
 *  @param configuration            optional `Map<String, String>` of model configuration settings
 *  @param baseTimeUnit             base time unit for the model
 */
@Serializable
data class ModelDescriptor(
    val modelIdentifier: String,
    val modelName: String,
    val description: String,
    val responseNames: Set<String>,
    val experimentRunParameters: ExperimentRunParameters,
    val controls: ModelControlsExport,
    val rvParameterData: List<RVParameterData>,
    val configuration: Map<String, String>? = null,
    val baseTimeUnit: ModelElement.TimeUnit
) : ToJSONIfc {

    /**
     * The names of all numerically settable inputs: numeric control keys plus random
     * variable parameter keys.  Derived from [controls] and [rvParameterData]; not stored
     * separately.
     *
     * These are the keys accepted by [ksl.simulation.Model.validateInputKeys] and by
     * simulation optimizers such as [ksl.simopt.problem.ProblemDefinition].
     *
     * String and JSON controls are intentionally excluded — they carry non-numeric values
     * that cannot be swept by a numeric solver.
     */
    val inputNames: Set<String>
        get() = controls.numericControls.map { it.keyName }.toSet() +
                rvParameterData.map { "${it.rvName}${RVParameterSetter.rvParamConCatChar}${it.paramName}" }.toSet()

    /**
     * Backward-compatible access to numeric controls as a flat list.
     * Equivalent to [controls.numericControls][ModelControlsExport.numericControls].
     */
    val controlData: List<ControlData>
        get() = controls.numericControls

    override fun toJson(): String {
        val format = Json {
            prettyPrint = true
            encodeDefaults = true
            allowSpecialFloatingPointValues = true  // required: ControlData bounds can be ±∞
        }
        return format.encodeToString(this)
    }
}
