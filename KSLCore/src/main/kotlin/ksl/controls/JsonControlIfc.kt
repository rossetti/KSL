package ksl.controls

/**
 * Represents a JSON-valued simulation control extracted from a property
 * annotated with [KSLJsonControl].
 *
 * The interface is parallel to [StringControlIfc] and [ControlIfc] but
 * carries its value as a JSON string rather than a `String` or `Double`.
 * The underlying property may be any Kotlin type supported by
 * `kotlinx.serialization` (e.g. `List<Double>`, `Map<String, Int>`,
 * a `@Serializable` data class).
 *
 * Setting [value] with a string that cannot be deserialized into the
 * property's declared Kotlin type throws a [ControlUpdateException].
 * The underlying property is not modified when the exception is thrown.
 *
 * The [initialJsonValue] is captured once at extraction time from the
 * property's value at that moment.  It is not automatically restored on
 * error — callers may read it and assign it back if a revert is desired.
 */
interface JsonControlIfc {

    /**
     * Current value of the underlying property serialized as a JSON string.
     * Setting this property deserializes [value] into the property's declared
     * Kotlin type before delegating to the property setter via reflection.
     *
     * @throws ControlUpdateException if the string is not valid JSON for the
     *         property's declared type
     */
    var value: String

    /**
     * The property value serialized as JSON at extraction time.
     * Provides a safe fallback if the caller needs to revert after a
     * failed or unwanted update.
     */
    val initialJsonValue: String

    /**
     * Human-readable description of the expected Kotlin type, e.g.
     * `"kotlin.collections.List<kotlin.Double>"`.
     * Provided by [KSLJsonControl.expectedTypeHint] or inferred from the
     * property's `KType` at extraction time when the annotation field is blank.
     */
    val typeHint: String

    /** Unique access key of the form `"${elementName}.${propertyName}"`. */
    val keyName: String

    /** Name of the model element that owns this control. */
    val elementName: String

    /** Identifier of the model element that owns this control. */
    val elementId: Int

    /** Simple class name of the model element that owns this control. */
    val elementType: String

    /** Name of the annotated property. */
    val propertyName: String

    /** Optional comment supplied in the [KSLJsonControl] annotation. */
    val comment: String

    /** Name of the model that contains the owning element. */
    val modelName: String
}
