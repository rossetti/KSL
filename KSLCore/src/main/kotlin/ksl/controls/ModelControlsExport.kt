package ksl.controls

import kotlinx.serialization.Serializable

/**
 * A unified serializable envelope for all controls belonging to a single model.
 *
 * This DTO is the single document produced by `model.controls().exportAll()` and
 * consumed by `model.controls().importAll(json)`.  External UIs receive one JSON
 * object that contains every controllable parameter, routed by type:
 *
 * - [numericControls] — properties annotated with [@KSLControl][KSLControl];
 *   values are `Double` and respect declared lower/upper bounds.
 * - [stringControls]  — properties annotated with `@KSLStringControl`;
 *   values are `String`, optionally constrained to [StringControlData.allowedValues].
 * - [complexControls] — properties annotated with `@KSLJsonControl` (Arrays,
 *   Lists, Maps); values are JSON strings, typed by [JsonControlData.typeHint].
 *
 * On import the three lists are routed to their respective maps inside [Controls].
 * Controls not present in an imported envelope are left unchanged.
 *
 * @param simulationName  name of the [ksl.simulation.Model] that produced this export
 * @param numericControls list of [ControlData] for all numeric/boolean controls
 * @param stringControls  list of [StringControlData] for all string controls
 * @param complexControls list of [JsonControlData] for all complex (JSON) controls
 */
@Serializable
data class ModelControlsExport(
    val simulationName: String,
    val numericControls: List<ControlData>,
    val stringControls: List<StringControlData>,
    val complexControls: List<JsonControlData>,
) {
    /** Total number of controls across all three categories. */
    val totalControls: Int
        get() = numericControls.size + stringControls.size + complexControls.size
}
