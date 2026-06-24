package ksl.app.config

import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import ksl.simulation.NominatedInput
import ksl.simulation.NominatedInputKind
import ksl.simulation.NominatedOutput
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Validates [CatalogValidation] against a real model's descriptor. */
class CatalogValidationTest {

    private fun descriptor(): ModelDescriptor {
        val model = Model("catalogValidationTest")
        GIGcQueue(model, numServers = 1, name = "Q")
        return model.modelDescriptor()
    }

    @Test
    fun `valid catalog has no problems`() {
        val d = descriptor()
        assertTrue(d.controls.numericControls.isNotEmpty(), "fixture must expose a numeric control")
        assertTrue(d.responseNames.isNotEmpty(), "fixture must expose a response")
        val numKey = d.controls.numericControls.first().keyName
        val catalog = ModelCatalog(
            nominatedInputs = listOf(NominatedInput(numKey, NominatedInputKind.NUMERIC_CONTROL)),
            nominatedOutputs = listOf(NominatedOutput(d.responseNames.first())),
        )
        assertTrue(CatalogValidation.validate(catalog, d).isEmpty())
    }

    @Test
    fun `unresolved entries are reported and dropped by sanitize`() {
        val d = descriptor()
        val bad = ModelCatalog(
            nominatedInputs = listOf(NominatedInput("Nope.bogus", NominatedInputKind.NUMERIC_CONTROL)),
            nominatedOutputs = listOf(NominatedOutput("notAResponse")),
        )
        val problems = CatalogValidation.validate(bad, d)
        assertEquals(2, problems.count { it.severity == CatalogValidation.Severity.ERROR })
        assertTrue(CatalogValidation.sanitize(bad, d).isEmpty, "unresolved entries should be dropped")
    }

    @Test
    fun `kind mismatch warns and sanitize re-derives the kind`() {
        val d = descriptor()
        val numKey = d.controls.numericControls.first().keyName
        val mismatched = ModelCatalog(
            nominatedInputs = listOf(NominatedInput(numKey, NominatedInputKind.RV_PARAMETER)),
        )
        val problems = CatalogValidation.validate(mismatched, d)
        assertTrue(problems.any { it.severity == CatalogValidation.Severity.WARNING })
        val fixed = CatalogValidation.sanitize(mismatched, d)
        assertEquals(NominatedInputKind.NUMERIC_CONTROL, fixed.nominatedInputs.single().kind)
    }
}
