package ksl.controls

/**
 *   A control represents an element within a model that can be changed by the user.
 *   Every control has a type (DOUBLE, INTEGER, LONG, FLOAT, SHORT, BYTE, BOOLEAN).
 *   The user can set the value of the control.  If the supplied value is
 *   not within the allowed range of the control, the value will be limited to
 *   within the range.  If the user assigns the value of the control to less
 *   than the lower bound, then the value is set to the lower bound. If the user
 *   assigns the value greater than the upper bound, then the value is set
 *   at the upper bound. For example, suppose the lower bound of the control is 1.0
 *   and the upper bound is 10.0.  Setting the control value to 0.0 will set the
 *   control to 1.0. Setting the control value to 12.0 will set the control
 *   to 10.0. Thus, out-of-range values are not permitted and corrected (silently).
 *   The limitToRange() function can be used to inspect the value that will result
 *   if the control is set to the supplied value.
 */
interface ControlIfc {

    /**
     *  The type of the control (DOUBLE, INTEGER, LONG, FLOAT, SHORT, BYTE, BOOLEAN)
     */
    val type: ControlType

    /**
     *  The current value of the control.
     */
    var value: Double

    /**
     * The unique name for accessing the control.  This is the
     * string "${elementName}.${propertyName}"
     */
    val keyName: String

    /**
     *  The lower bound allowed for the value of the control.
     */
    val lowerBound: Double

    /**
     *  The upper bound allowed for the value of the control.
     */
    val upperBound: Double

    /**
     *  The name of the model element that has the control.
     */
    val elementName: String

    /**
     *  The model element identifier for the model element that has the control.
     */
    val elementId: Int

    /**
     *  The simple class name associated with the model element that has the control.
     */
    val elementType: String

    /**
     *  The name of the property that was annotated as a control.
     */
    val propertyName: String

    /**
     *  The comment string that was supplied in the control annotation.
     */
    val comment: String

    /**
     * The name of the model that holds the element associated with the control.
     */
    val modelName: String

    /**
     *  Name of the parent of the model element holding this control,
     *  or `null` when that element is a direct child of the Model.
     *
     *  Default implementation returns `null` so that external
     *  implementations of `ControlIfc` keep compiling; the concrete
     *  control classes in this package override to walk the
     *  model-element hierarchy.
     */
    val parentElementName: String?
        get() = null

    /**
     *  Identifier of the parent model element, or `null` when the
     *  parent is the Model itself or when no parent info is available.
     */
    val parentElementId: Int?
        get() = null

    /**
     *  Simple class name of the parent model element, or `null`.
     */
    val parentElementType: String?
        get() = null

    /**
     *  Ancestor names from the model root down to (but not including)
     *  the model element holding this control.  The list is **also
     *  exclusive of the Model itself** — the Model is identified
     *  separately via [modelName].  Empty when the control's element
     *  is a direct child of the Model.
     *
     *  Example: for a control attached to `Server` under
     *  `Subsystem` under the Model, `elementPath` is `["Subsystem"]`.
     */
    val elementPath: List<String>
        get() = emptyList()

    /**
     *  Checks if the supplied value is within the inclusive bounds.
     */
    fun withinRange(value: Double): Boolean = value in lowerBound..upperBound

    /**
     * Ensures that the supplied double is within the bounds
     * associated with the control. This function does
     * not change the state of the control.
     *
     * @param value the value to limit
     * @return the limited value for future use
     */
    fun limitToRange(value: Double): Double {
        if (value <= lowerBound) {
            return lowerBound
        } else if (value >= upperBound) {
            return upperBound
        }
        return value
    }

    /**
     *  Returns an array that has been mapped to legal values
     *  for the control
     */
    fun limitToRange(values: DoubleArray): DoubleArray {
        return values.map { value -> limitToRange(value) }.toDoubleArray()
    }

}