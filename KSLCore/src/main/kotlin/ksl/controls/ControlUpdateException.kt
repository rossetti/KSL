package ksl.controls

/**
 * Thrown when a [JsonControlIfc] or [StringControlIfc] cannot apply an
 * incoming value.
 *
 * For JSON controls this occurs when the incoming string is structurally
 * invalid JSON or cannot be deserialized into the property's declared Kotlin
 * type.  When thrown from inside [JsonControl], the underlying property has
 * already been silently reverted to its safe [JsonControlIfc.initialJsonValue]
 * before this exception propagates.
 *
 * For string controls this occurs when `allowedValues` is non-empty and the
 * supplied value is not a member of that set.
 *
 * The batch-import function in [Controls] catches [ControlUpdateException]
 * per control, records each failure in a [ControlImportResult], and continues
 * processing the remaining controls rather than aborting the entire import.
 *
 * @param message        human-readable description of the failure
 * @param controlKey     the [ControlIfc.keyName] of the control that failed
 * @param attemptedValue the raw string value that triggered the failure
 * @param cause          the underlying exception (e.g. `SerializationException`),
 *                       or `null` if there is none
 */
class ControlUpdateException(
    message: String,
    val controlKey: String,
    val attemptedValue: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Summary result returned by [Controls.importAll] and [Controls.importAllFromJson].
 *
 * The import continues past individual errors, accumulating them here.
 * [failures] holds one [ControlUpdateException] per control whose value was
 * rejected (string `allowedValues` violation or JSON deserialization failure).
 * [missingKeys] holds the key name of every control present in the export
 * snapshot but absent from the live model — these are logged at WARN level and
 * left unchanged.
 *
 * @param successCount number of controls successfully updated across all families
 * @param failures     validation exceptions in encounter order; empty on clean import
 * @param missingKeys  keys present in the export but not found in this model;
 *                     empty when every exported key resolved to a live control
 */
data class ControlImportResult(
    val successCount: Int,
    val failures:     List<ControlUpdateException>,
    val missingKeys:  List<String> = emptyList(),
) {
    /** Number of controls that could not be updated due to validation errors. */
    val failureCount: Int
        get() = failures.size

    /** `true` if at least one control update failed validation. */
    val hasFailures: Boolean
        get() = failures.isNotEmpty()

    /** Number of export keys that had no matching control in this model. */
    val missingKeyCount: Int
        get() = missingKeys.size

    /** `true` if at least one exported key was not found in this model. */
    val hasMissingKeys: Boolean
        get() = missingKeys.isNotEmpty()
}
