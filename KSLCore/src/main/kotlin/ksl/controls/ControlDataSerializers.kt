package ksl.controls

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A `Double` serializer that tolerates a JSON `null` on decode, mapping it to [whenNull], while
 * keeping the field a non-nullable `Double`.
 *
 * Why this exists. A control's unbounded bounds are `±∞`, and an `EventGenerator`'s "run forever"
 * value is `+∞`. Over the MCP wire, a document's `structuredContent` sanitizes every non-finite
 * double to `null` (the transport's serializer cannot carry `±∞` — it would hang). Because
 * `ControlData.value`/`lowerBound`/`upperBound` are non-nullable `Double`, a re-ingested `null`
 * would otherwise fail to decode, so `run_template`'s output was not accepted by `run_config`. This
 * serializer accepts that `null` as the field's canonical non-finite value, restoring the round-trip.
 *
 * Encoding is deliberately unchanged: the finite-or-non-finite double is written as-is (JSON
 * `Infinity`/`-Infinity` under `allowSpecialFloatingPointValues`, TOML's native `inf`), so no output
 * format changes and no other codec or consumer is affected — only decode gains the `null` tolerance.
 * A literal `Infinity`/`-Infinity`/`inf`/`NaN` on the wire (an already-saved document) still decodes
 * normally through the underlying primitive.
 */
sealed class NullTolerantDoubleSerializer(private val whenNull: Double) : KSerializer<Double> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ksl.controls.NullTolerantDouble", PrimitiveKind.DOUBLE).nullable

    override fun serialize(encoder: Encoder, value: Double) = encoder.encodeDouble(value)

    override fun deserialize(decoder: Decoder): Double =
        if (decoder.decodeNotNullMark()) {
            decoder.decodeDouble()
        } else {
            decoder.decodeNull()
            whenNull
        }
}

/** Decodes a `null` upper bound as `+∞` (unbounded above). */
object UpperBoundDoubleSerializer : NullTolerantDoubleSerializer(Double.POSITIVE_INFINITY)

/** Decodes a `null` lower bound as `-∞` (unbounded below). */
object LowerBoundDoubleSerializer : NullTolerantDoubleSerializer(Double.NEGATIVE_INFINITY)

/**
 * Decodes a `null` control value as `+∞` — the only non-finite value a real control carries (an
 * `EventGenerator`'s "run forever" `initialEndingTime`). A control value of `-∞` or `NaN` does not
 * occur in practice, so mapping `null` to `+∞` round-trips every real control exactly.
 */
object ControlValueDoubleSerializer : NullTolerantDoubleSerializer(Double.POSITIVE_INFINITY)
