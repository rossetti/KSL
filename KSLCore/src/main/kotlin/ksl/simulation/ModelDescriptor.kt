package ksl.simulation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ksl.controls.ControlData
import ksl.controls.ModelControlsExport
import ksl.controls.experiments.ExperimentRunDefaults
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
 *  The [experimentRunDefaults] field captures only the *model-intrinsic* run-parameter
 *  values — replication count, replication length, warm-up, stream options, etc. — and
 *  deliberately omits the runtime-identification fields (`experimentName`,
 *  `experimentId`, `runName`) that belong to a specific run rather than to the model.
 *  This keeps the descriptor JSON byte-stable across enrich runs.  See
 *  [ksl.controls.experiments.ExperimentRunDefaults] for the precise shape and its
 *  relationship to [ksl.controls.experiments.ExperimentRunParameters].
 *
 *  The `description`, `inputNames`, and `outputDirectory` fields that appeared in earlier
 *  versions of this class have been removed as stored fields:
 *  - `description` was an auto-generated string embedding a wall-clock construction
 *    marker and had no consumers other than its own passthrough.  Descriptive text for
 *    a bundled model lives on the bundle SPI instead (see
 *    `ksl.app.bundle.KSLModelBundle.description`,
 *    `ksl.app.bundle.KSLBundledModel.description`, and the optional
 *    `META-INF/ksl/bundle.toml` file).
 *  - [inputNames] is now a computed property derived from [controls] and [rvParameterData]
 *    (it contained no independent information).
 *  - `outputDirectory` was runtime filesystem state rather than model configuration and had
 *    no consumers that read it back from a descriptor.
 *
 *  @param modelIdentifier        user-assigned identifier for the model
 *  @param modelName              assigned name of the model (unique within the element hierarchy)
 *  @param responseNames          names of all responses registered in the model
 *  @param experimentRunDefaults  model-intrinsic run-parameter defaults; see
 *                                [ksl.controls.experiments.ExperimentRunDefaults]
 *  @param controls               snapshot of all controls extracted from the model element graph,
 *                                spanning numeric ([ksl.controls.ControlData]),
 *                                string ([ksl.controls.StringControlData]),
 *                                and JSON ([ksl.controls.JsonControlData]) families
 *  @param rvParameterData        random variable parameter data extracted from the model
 *  @param configuration          optional `Map<String, String>` of model configuration settings
 *  @param baseTimeUnit           base time unit for the model
 *  @param catalog                optional author-curated catalog of nominated inputs and
 *                                outputs (see [ModelCatalog]); `null` when the model developer
 *                                nominated nothing.  Applications may use it to focus their UX
 *                                but must not depend on its presence.
 */
@Serializable
data class ModelDescriptor(
    val modelIdentifier: String,
    val modelName: String,
    val responseNames: Set<String>,
    val experimentRunDefaults: ExperimentRunDefaults,
    val controls: ModelControlsExport,
    val rvParameterData: List<RVParameterData>,
    val configuration: Map<String, String>? = null,
    val baseTimeUnit: ModelElement.TimeUnit,
    val catalog: ModelCatalog? = null
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

    /**
     * Regroups the flat [rvParameterData] DTOs into the nested map form that
     * [ksl.utilities.random.rvariable.parameters.RVParameterSetter.changeParameters] expects.
     *
     * The outer map is keyed by [RVParameterData.rvName]; the inner map is keyed by
     * [RVParameterData.paramName] with the corresponding [RVParameterData.paramValue].
     *
     * Any consumer that needs to pass RV parameter changes to an
     * [ksl.utilities.random.rvariable.parameters.RVParameterSetter] can use this
     * property directly instead of re-implementing the grouping.
     */
    val rvParameterMap: Map<String, Map<String, Double>>
        get() = rvParameterData
            .groupBy { it.rvName }
            .mapValues { (_, params) ->
                params.associate { it.paramName to it.paramValue }
            }

    override fun toJson(): String {
        val format = Json {
            prettyPrint = true
            encodeDefaults = true
            allowSpecialFloatingPointValues = true  // required: ControlData bounds can be ±∞
        }
        return format.encodeToString(this)
    }
}
