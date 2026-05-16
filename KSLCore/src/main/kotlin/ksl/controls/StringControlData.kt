package ksl.controls

import kotlinx.serialization.Serializable

/**
 * A data-transfer object carrying the state of a single `StringControlIfc`.
 *
 * Parallel to [ControlData] for numeric controls, but holds a [String] value
 * and an optional constraint list instead of a numeric range.
 *
 * @param keyName       unique access key: `"${elementName}.${propertyName}"`
 * @param value         current string value of the control
 * @param allowedValues the set of permitted values declared on the annotation;
 *                      an empty list means unconstrained (any string is accepted)
 * @param elementName   name of the model element that owns this control
 * @param elementId     identifier of the model element
 * @param elementType   simple class name of the model element
 * @param propertyName  name of the annotated property
 * @param comment       optional comment supplied in the annotation
 * @param modelName     name of the model that contains the element
 */
@Serializable
data class StringControlData(
    val keyName: String,
    val value: String,
    val allowedValues: List<String>,
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
