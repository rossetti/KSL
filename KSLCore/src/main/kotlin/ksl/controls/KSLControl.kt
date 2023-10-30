package ksl.controls

import ksl.utilities.NameIfc
import java.lang.annotation.Inherited

/**
 * A KSLControl annotation is used on the setter method of properties within
 * model elements to indicate that those properties should be used to control
 * the execution of the simulation model. The annotation field type must be supplied
 * and must be one of the valid control types as specified by the enum ControlType.
 * The user is responsible for making sure that the type field matches (or is consistent with)
 * the type of the property.  Even though the optional annotation fields (lowerBound and upperBound)
 * are specified as double values, they will be converted to an appropriate value for the specified
 * type.  Boolean properties are represented as a 1.0 (true) and 0.0 (false) within the numerical
 * conversion for the controls.  If a control is BOOLEAN, then the user can supply a 1.0 to represent true
 * and a 0.0 to represent false when setting the control, which will then be set to true or false, appropriately.
 *
 * Current control types (Double, Int, Long, Short, Byte, Float) and Boolean. Future
 * types may be more general class types, in which case, the bounds will be ignored.
 */
@MustBeDocumented // flag inclusion in documentation
@Inherited // flag that it is inherited by subclasses
@Target(
    AnnotationTarget.PROPERTY_SETTER
) // targets setters ONLY
annotation class KSLControl(
    /**
     * the type of the control
     */
    val controlType: ControlType,
    /**
     * the name of the control
     */
    val name: String = "",

    /** If this field is not specified, it will be translated to the smallest
     * negative value associated with the type specified by the field type.
     * For example, if the type is INTEGER then the default lower bound will
     * be Integer.MIN_VALUE. The user can supply more constraining values for
     * the bounds that are within the range of the associated type.
     */
    val lowerBound: Double = Double.NEGATIVE_INFINITY,

    /** If this field is not specified, it will be translated to the largest
     * positive value associated with the type specified by the field type.
     * For example, if the type is INTEGER then the default lower bound will
     * be Integer.MAX_VALUE. The user can supply more constraining values for
     * the bounds that are within the range of the associated type.
     */
    val upperBound: Double = Double.POSITIVE_INFINITY,

    /**
     *
     * comment associated with the annotation
     */
    val comment: String = "",

    /** Indicates whether to include in the controls for the model or not. Provides
     * a simple mechanism for turning off or not including specific controls.
     * The default is to include the control in the extraction process.
     */
    val include: Boolean = true
)