package ksl.controls

import kotlinx.serialization.Serializable

/**
 *  A data class for transferring the data associated with a control.
 *  @param controlType the type of control (DOUBLE, INTEGER, LONG, FLOAT, SHORT, BYTE, BOOLEAN)
 *  @param value the value of the control
 *  @param keyName the name for the control. This is the string "${elementName}.${propertyName}"
 *  @param lowerBound the lower bound permitted for the control
 *  @param upperBound the upper bound permitted for the control
 *  @param elementName The name of the model element that has the control.
 *  @param elementType The simple class name associated with the model element that has the control.
 *  @param elementId The id of the model element associated with the control
 *  @param propertyName The name of the property annotated by the control
 *  @param comment The comment string that was supplied in the control annotation.
 *  @param modelName The name of the model that holds the element associated with the control.
 */
@Serializable
data class ControlData(
    val controlType: ControlType,
    val value: Double,
    val keyName: String,
    val lowerBound: Double,
    val upperBound: Double,
    val elementName: String,
    val elementId: Int,
    val elementType: String,
    val propertyName: String,
    val comment: String,
    val modelName: String,
)