package ksl.controls

import java.lang.annotation.Inherited

/**
 * A JSLControl annotation is used to mark single parameter methods within
 * model elements to indicate that those methods should be used to control
 * the execution of the simulation model. The annotation field type must be supplied
 * and must be one of the valid control types as specified by the enum ControlType.
 * The user is responsible for making sure that the type field matches (or is consistent with)
 * the type of the parameter of the method.  For this purpose, primitive types and their wrapper
 * types (e.g. double/Double, Integer/int, etc.) are considered the same (interchangeable) as
 * denoted by the valid control types.  Even though the optional annotation fields (lowerBound and upperBound)
 * are specified as double values, they will be converted to an appropriate value for the specified
 * type.  Boolean/boolean parameters are represented as a 1 (true) and 0 (false) within the numerical
 * conversion for the controls.  If a control is BOOLEAN, then the user can supply a 1 to represent true
 * and a 0 to represent false when setting the control, which will then be set to true or false, appropriately.
 *
 * Current control types are the primitives and their wrappers as well as boolean/Boolean. Future
 * types may be more general object types, in which case, the bounds will be ignored.
 */
@MustBeDocumented // flag inclusion in documentation
@Inherited // flag that it is inherited by subclasses
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER
) // targets methods ONLY
annotation class KSLControl(
    /**
     * the type of the control
     */
    val type: ControlType,
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

    /** Indicated whether to include in the controls for the model or not. Provides
     * a simple mechanism for turning off or not including specific controls.
     * The default is to include thm control in the extraction process.
     */
    val include: Boolean = true
)