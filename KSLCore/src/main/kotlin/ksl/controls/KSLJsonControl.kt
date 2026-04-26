package ksl.controls

import java.lang.annotation.Inherited

/**
 * Marks a property setter as a JSON-valued simulation control.
 *
 * Parallel to [KSLControl] (numeric) and [KSLStringControl] (string), but
 * targets properties of any Kotlin type that is serializable by
 * `kotlinx.serialization`.  Typical targets are collections and maps:
 * `List<Double>`, `Map<String, Int>`, or `@Serializable` data classes.
 *
 * Values are always transported as JSON strings.  The [ksl.controls.Controls]
 * manager serializes the property on read and deserializes on write; a
 * structurally invalid or type-incompatible JSON string causes the setter to
 * throw a [ControlUpdateException].
 *
 * If the property's Kotlin type is not supported by `kotlinx.serialization`
 * the control is silently skipped during extraction.
 *
 * Usage:
 * ```kotlin
 * @set:KSLJsonControl(comment = "Gross weight per axle in kg")
 * var axleWeights: List<Double> = listOf(4500.0, 6000.0, 6000.0)
 *
 * @set:KSLJsonControl(comment = "Named compartment capacities in m³")
 * var compartmentCapacities: Map<String, Double> =
 *     mapOf("front" to 12.5, "rear" to 18.0)
 * ```
 *
 * @param expectedTypeHint  optional human-readable description of the expected
 *                          Kotlin type (e.g. `"List<Double>"`); when blank the
 *                          hint is inferred from the property's `KType` at
 *                          extraction time
 * @param name              optional display name; defaults to the property name
 *                          when blank
 * @param comment           optional description of the control's purpose
 * @param include           set to `false` to exclude this property from control
 *                          extraction without removing the annotation
 */
@MustBeDocumented
@Inherited
@Target(AnnotationTarget.PROPERTY_SETTER)
annotation class KSLJsonControl(
    val expectedTypeHint: String  = "",
    val name:             String  = "",
    val comment:          String  = "",
    val include:          Boolean = true,
)
