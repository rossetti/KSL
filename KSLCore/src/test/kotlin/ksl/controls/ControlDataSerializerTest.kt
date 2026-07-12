package ksl.controls

import kotlinx.serialization.json.Json
import net.peanuuutz.tomlkt.Toml
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the [ControlData] non-finite double serializers. The invariant: `run_template`'s
 * output — in which the MCP transport sanitizes a control's `±∞` bounds (and any `±∞` value) to a
 * JSON `null` — must decode back into `ControlData`, while ordinary encode output is unchanged.
 * Covers JSON (the failing path) and TOML (native `inf`, `explicitNulls = false`).
 */
class ControlDataSerializerTest {

    // Mirrors the config codecs (RunConfigurationJson): special floats allowed, defaults written.
    private val json = Json { allowSpecialFloatingPointValues = true; encodeDefaults = true }
    private val toml = Toml { explicitNulls = false }

    private fun cd(
        value: Double = 1.0,
        lower: Double = Double.NEGATIVE_INFINITY,
        upper: Double = Double.POSITIVE_INFINITY,
    ) = ControlData(
        controlType = ControlType.DOUBLE, value = value, keyName = "e.p",
        lowerBound = lower, upperBound = upper,
        elementName = "e", elementId = 1, elementType = "E", propertyName = "p",
        comment = "", modelName = "M",
    )

    // ── JSON ───────────────────────────────────────────────────────────────────

    @Test
    fun jsonRoundTripsInfiniteBounds() {
        val original = cd(value = 2.5, lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY)
        val decoded = json.decodeFromString(ControlData.serializer(), json.encodeToString(ControlData.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun jsonNullBoundsDecodeToInfinity() {
        // Exactly what run_template's sanitized structuredContent emits for an unbounded control.
        val text = """{"controlType":"DOUBLE","value":1.0,"keyName":"e.p","lowerBound":null,"upperBound":null,
            "elementName":"e","elementId":1,"elementType":"E","propertyName":"p","comment":"","modelName":"M"}"""
        val decoded = json.decodeFromString(ControlData.serializer(), text)
        assertEquals(Double.NEGATIVE_INFINITY, decoded.lowerBound)
        assertEquals(Double.POSITIVE_INFINITY, decoded.upperBound)
        assertEquals(1.0, decoded.value)
    }

    @Test
    fun jsonNullValueDecodesToPositiveInfinity() {
        // e.g. an EventGenerator's initialEndingTime = +inf, sanitized to null on the wire.
        val text = """{"controlType":"DOUBLE","value":null,"keyName":"g.initialEndingTime","lowerBound":0.0,"upperBound":null,
            "elementName":"g","elementId":1,"elementType":"E","propertyName":"initialEndingTime","comment":"","modelName":"M"}"""
        val decoded = json.decodeFromString(ControlData.serializer(), text)
        assertEquals(Double.POSITIVE_INFINITY, decoded.value)
    }

    @Test
    fun jsonLegacyInfinityLiteralStillDecodes() {
        // Back-compat: an already-saved document carries the non-standard Infinity literal.
        val text = """{"controlType":"DOUBLE","value":1.0,"keyName":"e.p","lowerBound":-Infinity,"upperBound":Infinity,
            "elementName":"e","elementId":1,"elementType":"E","propertyName":"p","comment":"","modelName":"M"}"""
        val decoded = json.decodeFromString(ControlData.serializer(), text)
        assertEquals(Double.NEGATIVE_INFINITY, decoded.lowerBound)
        assertEquals(Double.POSITIVE_INFINITY, decoded.upperBound)
    }

    @Test
    fun jsonEncodeIsUnchangedNonFiniteNotNull() {
        // Encoding is deliberately NOT changed by this fix: +inf is still written as the Infinity
        // literal, not null. (Only decode gains null tolerance; describe_model output is unaffected.)
        val encoded = json.encodeToString(ControlData.serializer(), cd(upper = Double.POSITIVE_INFINITY))
        assertTrue(encoded.contains("Infinity"), "encode still emits the Infinity literal, not null: $encoded")
        assertFalse(encoded.contains("\"upperBound\":null"), "encode must NOT emit null for a bound: $encoded")
    }

    // ── TOML ───────────────────────────────────────────────────────────────────

    @Test
    fun tomlRoundTripsInfiniteBounds() {
        val original = cd(value = 3.0, lower = Double.NEGATIVE_INFINITY, upper = Double.POSITIVE_INFINITY)
        val decoded = toml.decodeFromString(ControlData.serializer(), toml.encodeToString(ControlData.serializer(), original))
        assertEquals(original, decoded)
    }

    @Test
    fun tomlOmittedBoundsDecodeToInfinityDefaults() {
        val text = """
            controlType = "DOUBLE"
            value = 1.0
            keyName = "e.p"
            elementName = "e"
            elementId = 1
            elementType = "E"
            propertyName = "p"
            comment = ""
            modelName = "M"
        """.trimIndent()
        val decoded = toml.decodeFromString(ControlData.serializer(), text)
        assertEquals(Double.NEGATIVE_INFINITY, decoded.lowerBound)
        assertEquals(Double.POSITIVE_INFINITY, decoded.upperBound)
    }
}
