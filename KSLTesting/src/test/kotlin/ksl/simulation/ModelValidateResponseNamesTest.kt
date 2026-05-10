package ksl.simulation

import ksl.modeling.variable.Response
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [Model.validateResponseNames].
 *
 * The earlier implementation aliased its parameter as `val names = responseNames`
 * and then iterated `responseNames` checking membership in `names` — a
 * tautology that always returned `true`, regardless of the model's actual
 * responses.  These tests lock the corrected behavior, which checks the
 * supplied set against the model's `responseNames`.
 */
class ModelValidateResponseNamesTest {

    private fun modelWithResponses(): Model {
        val model = Model("ModelValidateResponseNamesTest", autoCSVReports = false)
        Response(model, "ResponseA")
        Response(model, "ResponseB")
        return model
    }

    @Test
    fun returnsTrueWhenAllRequestedNamesArePresent() {
        val model = modelWithResponses()
        assertTrue(model.validateResponseNames(setOf("ResponseA")))
        assertTrue(model.validateResponseNames(setOf("ResponseA", "ResponseB")))
    }

    @Test
    fun returnsFalseWhenAnyRequestedNameIsMissing() {
        val model = modelWithResponses()
        assertFalse(model.validateResponseNames(setOf("UnknownResponse")))
        assertFalse(model.validateResponseNames(setOf("ResponseA", "UnknownResponse")))
    }

    @Test
    fun returnsTrueForEmptyInputSet() {
        // Vacuous truth: an empty set is trivially a subset of the model's responses.
        val model = modelWithResponses()
        assertTrue(model.validateResponseNames(emptySet()))
    }
}
