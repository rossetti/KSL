package ksl.controls

import kotlinx.serialization.Serializable

/**
 * A data-transfer object carrying the state of a single [JsonControlIfc].
 *
 * Complex properties (Arrays, Lists, Maps) are exposed purely as JSON strings.
 * The [typeHint] field carries a human-readable description of the expected
 * Kotlin type (e.g. `"List<Double>"`, `"Map<String, Int>"`) to assist external
 * UIs in constructing valid JSON without requiring them to understand Kotlin
 * reflection.
 *
 * @param keyName       unique access key: `"${elementName}.${propertyName}"`
 * @param jsonValue     current value of the control serialized as a JSON string
 * @param typeHint      human-readable description of the expected Kotlin type,
 *                      e.g. `"List<Double>"` or `"Map<String, Int>"`;
 *                      provided by `KSLJsonControl.expectedTypeHint` or inferred
 *                      from the property's [kotlin.reflect.KType] at extraction time
 * @param elementName   name of the model element that owns this control
 * @param elementId     identifier of the model element
 * @param elementType   simple class name of the model element
 * @param propertyName  name of the annotated property
 * @param comment       optional comment supplied in the annotation
 * @param modelName     name of the model that contains the element
 */
@Serializable
data class JsonControlData(
    val keyName: String,
    val jsonValue: String,
    val typeHint: String,
    val elementName: String,
    val elementId: Int,
    val elementType: String,
    val propertyName: String,
    val comment: String,
    val modelName: String,
    /** See [ControlData.parentElementName]. */
    val parentElementName: String? = null,
    /** See [ControlData.parentElementId]. */
    val parentElementId: Int? = null,
    /** See [ControlData.parentElementType]. */
    val parentElementType: String? = null,
    /** See [ControlData.elementPath]. */
    val elementPath: List<String> = emptyList(),
)
