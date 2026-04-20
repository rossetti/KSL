package ksl.controls

/**
 * Represents a string-valued simulation control extracted from a property
 * annotated with [KSLStringControl].
 *
 * The interface is intentionally parallel to [ControlIfc] for numeric controls
 * but replaces the numeric value and bounds with a `String` value and an
 * optional membership constraint ([allowedValues]).
 *
 * Setting [value] to a string that is not a member of [allowedValues] (when
 * that list is non-empty) throws a [ControlUpdateException].  The underlying
 * property is only updated when validation passes.
 *
 * The [initialValue] is captured once at extraction time and provides a known
 * safe fallback state.  It is not automatically restored on error — callers
 * may read it and assign it back manually if a revert is desired.
 */
interface StringControlIfc {

    /**
     * Current string value of the underlying property.
     * Setting this property validates against [allowedValues] before
     * delegating to the property setter via reflection.
     *
     * @throws ControlUpdateException if [allowedValues] is non-empty and
     *         the supplied value is not a member of that set
     */
    var value: String

    /**
     * The string value captured at extraction time.
     * Provides a safe fallback if the caller needs to revert after a
     * failed or unwanted update.
     */
    val initialValue: String

    /**
     * Permitted values declared on the [KSLStringControl] annotation.
     * An empty list means the control is unconstrained — any String is accepted.
     */
    val allowedValues: List<String>

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

    /** Optional comment supplied in the [KSLStringControl] annotation. */
    val comment: String

    /** Name of the model that contains the owning element. */
    val modelName: String

    /**
     * Returns `true` if [v] is permitted by [allowedValues].
     * Always returns `true` when [allowedValues] is empty (unconstrained).
     */
    fun isAllowed(v: String): Boolean = allowedValues.isEmpty() || v in allowedValues
}
