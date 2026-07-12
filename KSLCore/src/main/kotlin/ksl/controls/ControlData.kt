package ksl.controls

import kotlinx.serialization.Serializable

/**
 *  A data class for transferring the data associated with a control.
 *
 *  @param controlType the type of control (DOUBLE, INTEGER, LONG, FLOAT, SHORT, BYTE, BOOLEAN)
 *  @param value the value of the control. A wire `null` (a non-finite value the transport sanitized)
 *      decodes as `+∞` — the only non-finite value a real control carries.
 *  @param keyName the name for the control. This is the string "${elementName}.${propertyName}"
 *  @param lowerBound the lower bound permitted for the control. Defaults to `-∞` (unbounded); a wire
 *      `null` or an omitted value decodes as `-∞`.
 *  @param upperBound the upper bound permitted for the control. Defaults to `+∞` (unbounded); a wire
 *      `null` or an omitted value decodes as `+∞`.
 *  @param elementName The name of the model element that has the control.
 *  @param elementType The simple class name associated with the model element that has the control.
 *  @param elementId The id of the model element associated with the control
 *  @param propertyName The name of the property annotated by the control
 *  @param comment The comment string that was supplied in the control annotation.
 *  @param modelName The name of the model that holds the element associated with the control.
 *  @param parentElementName Name of the parent of the model element that holds the control,
 *      or `null` when that element is a direct child of the Model.  Old snapshots
 *      that pre-date this field deserialize with `null`.
 *  @param parentElementId Identifier of the parent model element, or `null`.
 *  @param parentElementType Simple class name of the parent model element, or `null`.
 *  @param elementPath Ancestor names from the model root down to (but not including)
 *      the model element holding the control, **also excluding the Model itself**.
 *      Empty when the holding element is a direct child of the Model.  Old snapshots
 *      deserialize with an empty list.
 */
@Serializable
data class ControlData(
    val controlType: ControlType,
    @Serializable(with = ControlValueDoubleSerializer::class)
    val value: Double,
    val keyName: String,
    @Serializable(with = LowerBoundDoubleSerializer::class)
    val lowerBound: Double = Double.NEGATIVE_INFINITY,
    @Serializable(with = UpperBoundDoubleSerializer::class)
    val upperBound: Double = Double.POSITIVE_INFINITY,
    val elementName: String,
    val elementId: Int,
    val elementType: String,
    val propertyName: String,
    val comment: String,
    val modelName: String,
    val parentElementName: String? = null,
    val parentElementId: Int? = null,
    val parentElementType: String? = null,
    val elementPath: List<String> = emptyList(),
)