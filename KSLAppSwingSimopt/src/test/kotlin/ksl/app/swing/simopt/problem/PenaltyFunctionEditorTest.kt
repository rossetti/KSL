package ksl.app.swing.simopt.problem

import ksl.app.config.optimization.PenaltyFunctionSpec
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests the Park-Kim additions to [PenaltyFunctionEditor]: the new spec variant round-trips through
 * the editor's parse/format, switching to it from a polynomial variant works, and the pre-existing
 * polynomial variants are unaffected. The editor is a lightweight JPanel (no heavyweight peer), so
 * these construct-and-read tests are headless-safe; the spec<->engine translation and JSON/TOML
 * serialization are covered separately by the KSLApp config tests.
 */
class PenaltyFunctionEditorTest {

    @Test
    fun parkKimSpecRoundTripsThroughTheEditor() {
        val spec = PenaltyFunctionSpec.ParkKim(
            appreciationFactor = 3.0, depreciationFactor = 0.25, initialLambda = 2.0,
            fallbackBasePenalty = 150.0, fallbackIterationExponent = 2.0, fallbackViolationExponent = 1.5
        )
        val editor = PenaltyFunctionEditor(initial = spec)
        assertEquals(spec, editor.value, "a Park-Kim spec round-trips through the editor")
        assertNull(editor.validationMessage(), "a valid Park-Kim spec has no validation message")
    }

    @Test
    fun switchingFromPolynomialToParkKimRoundTrips() {
        val editor = PenaltyFunctionEditor(initial = PenaltyFunctionSpec.DynamicPolynomial())
        val spec = PenaltyFunctionSpec.ParkKim(
            appreciationFactor = 2.5, depreciationFactor = 0.4, initialLambda = 1.5
        )
        editor.setValue(spec)
        assertEquals(spec, editor.value, "setValue then value round-trips a Park-Kim spec")
    }

    @Test
    fun polynomialVariantsStillRoundTrip() {
        val dyn = PenaltyFunctionSpec.DynamicPolynomial(
            basePenalty = 250.0, iterationExponent = 1.5, violationExponent = 3.0
        )
        val mem = PenaltyFunctionSpec.WithMemory(basePenalty = 50.0)
        assertEquals(dyn, PenaltyFunctionEditor(initial = dyn).value, "DynamicPolynomial still round-trips")
        assertEquals(mem, PenaltyFunctionEditor(initial = mem).value, "WithMemory still round-trips")
    }
}
