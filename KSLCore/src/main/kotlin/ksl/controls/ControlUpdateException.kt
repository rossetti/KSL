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
 * For string controls this occurs when [allowedValues] is non-empty and the
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
 * Summary result returned by the batch-import function in [Controls].
 *
 * The import continues past individual [ControlUpdateException]s, accumulating
 * them here.  The caller can inspect [failures] for per-control diagnostics and
 * use [hasFailures] as a quick guard before iterating.
 *
 * @param successCount number of controls that were updated without error
 * @param failures     list of exceptions, one per failed control, in the order
 *                     they were encountered; empty when all updates succeeded
 */
data class ControlImportResult(
    val successCount: Int,
    val failures: List<ControlUpdateException>,
) {
    /** Number of controls that could not be updated. */
    val failureCount: Int
        get() = failures.size

    /** `true` if at least one control update failed. */
    val hasFailures: Boolean
        get() = failures.isNotEmpty()
}
