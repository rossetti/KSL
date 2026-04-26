package ksl.controls

import kotlinx.serialization.Serializable

/**
 * A serializable snapshot of all controls extracted from a model instance,
 * spanning all three control families.
 *
 * Produced by [Controls.exportAll] and consumed by [Controls.importAll].
 * The JSON form is produced and accepted by [Controls.exportAllAsJson] and
 * [Controls.importAllFromJson].
 *
 * Each family defaults to an empty list so that partial exports are valid —
 * a caller may construct this with only the families of interest populated.
 * Controls not present in an imported snapshot are left unchanged in the model.
 *
 * @param modelName        name of the model that produced this export
 * @param numericControls  snapshot of all [ControlData] DTOs
 * @param stringControls   snapshot of all [StringControlData] DTOs
 * @param jsonControls     snapshot of all [JsonControlData] DTOs
 */
@Serializable
data class ModelControlsExport(
    val modelName:       String,
    val numericControls: List<ControlData>       = emptyList(),
    val stringControls:  List<StringControlData> = emptyList(),
    val jsonControls:    List<JsonControlData>   = emptyList(),
) {
    /** Total number of controls across all three families. */
    val totalControls: Int
        get() = numericControls.size + stringControls.size + jsonControls.size
}
