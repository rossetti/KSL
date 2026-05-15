package ksl.app.validation

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValidationFeedbackBusTest {

    private fun err(path: String, code: String = "X") =
        FieldError(path = path, message = "$path bad", severity = ValidationSeverity.ERROR, code = code)

    private fun warn(path: String, code: String = "W") =
        FieldError(path = path, message = "$path suspect", severity = ValidationSeverity.WARNING, code = code)

    @Test
    fun `initial state defaults to an empty result`() {
        val bus = ValidationFeedbackBus()
        assertTrue(bus.result.value.isValid)
        assertTrue(bus.result.value.errors.isEmpty())
        assertTrue(bus.result.value.warnings.isEmpty())
    }

    @Test
    fun `initial state can be set from the constructor`() {
        val initial = ValidationResult(errors = listOf(err("a")))
        val bus = ValidationFeedbackBus(initial)
        assertEquals(initial, bus.result.value)
    }

    @Test
    fun `publish replaces the current state`() {
        val bus = ValidationFeedbackBus()
        val next = ValidationResult(errors = listOf(err("a"), err("b")))
        bus.publish(next)
        assertEquals(next, bus.result.value)
    }

    @Test
    fun `issuesAtPath returns errors and warnings that match exactly`() {
        val bus = ValidationFeedbackBus(
            ValidationResult(
                errors = listOf(err("scenarios[0].x"), err("scenarios[1].x")),
                warnings = listOf(warn("scenarios[0].x"))
            )
        )
        val found = bus.issuesAtPath("scenarios[0].x")
        assertEquals(2, found.size, "should return both error and warning at the exact path")
        assertEquals(setOf("scenarios[0].x"), found.map { it.path }.toSet())
    }

    @Test
    fun `issuesAtPath returns empty for an unknown path`() {
        val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("a"))))
        assertEquals(emptyList(), bus.issuesAtPath("b"))
    }

    @Test
    fun `issuesAtOrBelow includes descendants and the prefix itself`() {
        val bus = ValidationFeedbackBus(
            ValidationResult(
                errors = listOf(
                    err("scenarios[0]"),
                    err("scenarios[0].runOverrides.x"),
                    err("scenarios[1].x"),
                    err("bundleRefs[0]")
                )
            )
        )
        val found = bus.issuesAtOrBelow("scenarios[0]")
        assertEquals(2, found.size, "should match the exact and the descendant")
        assertTrue(found.any { it.path == "scenarios[0]" })
        assertTrue(found.any { it.path == "scenarios[0].runOverrides.x" })
    }

    @Test
    fun `issuesAtOrBelow does not match a string-prefix that is not a segment prefix`() {
        val bus = ValidationFeedbackBus(
            ValidationResult(errors = listOf(err("scenarios[30].x")))
        )
        assertEquals(emptyList(), bus.issuesAtOrBelow("scenarios[3]"))
    }

    @Test
    fun `issuesAtOrBelow with empty prefix returns every issue`() {
        val bus = ValidationFeedbackBus(
            ValidationResult(errors = listOf(err("a"), err("b.c")), warnings = listOf(warn("d")))
        )
        assertEquals(3, bus.issuesAtOrBelow("").size)
    }
}
