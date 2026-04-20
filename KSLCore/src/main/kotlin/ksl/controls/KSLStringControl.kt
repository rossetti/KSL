package ksl.controls

import java.lang.annotation.Inherited

/**
 * Marks a property setter as a string-valued simulation control.
 *
 * Parallel to [KSLControl] for numeric and boolean properties, but targets
 * `String` properties exclusively.  All values are passed and returned as
 * plain `String`; no numeric coercion occurs.
 *
 * If [allowedValues] is non-empty, the [ksl.controls.Controls] manager
 * enforces membership: any attempt to assign a value outside the set throws
 * a [ControlUpdateException].  An empty [allowedValues] array (the default)
 * means the control is unconstrained — any non-null String is accepted.
 *
 * Usage:
 * ```kotlin
 * @set:KSLStringControl(
 *     allowedValues = ["GASOLINE", "DIESEL", "ELECTRIC", "HYBRID"],
 *     comment = "Fuel type for the vehicle"
 * )
 * var fuelType: String = "GASOLINE"
 * ```
 *
 * @param allowedValues permitted string values; empty means unconstrained
 * @param name          optional alias used as the display name for the control;
 *                      defaults to the property name when blank
 * @param comment       optional description of the control's purpose
 * @param include       set to `false` to exclude this property from control
 *                      extraction without removing the annotation
 */
@MustBeDocumented
@Inherited
@Target(AnnotationTarget.PROPERTY_SETTER)
annotation class KSLStringControl(
    val allowedValues: Array<String> = [],
    val name:          String        = "",
    val comment:       String        = "",
    val include:       Boolean       = true,
)
