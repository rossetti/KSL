package ksl.app.swing.common.overridefield

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.swing.Swing
import ksl.app.swing.common.validation.SeverityIcon
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SectionHeaderWithStatusTest {

    private fun err(path: String) =
        FieldError(path = path, message = "$path bad", severity = ValidationSeverity.ERROR, code = "X")

    private fun warn(path: String) =
        FieldError(path = path, message = "$path suspect", severity = ValidationSeverity.WARNING, code = "W")

    @Test
    fun `clean prefix hides status icon and counts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus()
            val header = onEdt {
                SectionHeaderWithStatus("Run Parameters", "scenarios[0].runOverrides", bus, scope)
            }
            onEdt {
                assertNull(header.statusIconForTest)
                assertEquals("", header.countsTextForTest)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `errors-only prefix shows error icon and singular count`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("scenarios[0].runOverrides.x"))))
            val header = onEdt {
                SectionHeaderWithStatus("Run Parameters", "scenarios[0].runOverrides", bus, scope)
            }
            onEdt {
                val icon = header.statusIconForTest as? SeverityIcon
                assertNotNull(icon)
                assertEquals(ValidationSeverity.ERROR, icon!!.severity)
                assertEquals("1 error", header.countsTextForTest)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `mixed errors and warnings show error icon and combined counts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(
                ValidationResult(
                    errors = listOf(err("scenarios[0].x")),
                    warnings = listOf(warn("scenarios[0].y"), warn("scenarios[0].z"))
                )
            )
            val header = onEdt { SectionHeaderWithStatus("Title", "scenarios[0]", bus, scope) }
            onEdt {
                val icon = header.statusIconForTest as SeverityIcon
                assertEquals(ValidationSeverity.ERROR, icon.severity)
                assertEquals("1 error, 2 warnings", header.countsTextForTest)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `unrelated path-prefix issues do not surface`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("scenarios[1].x"))))
            val header = onEdt { SectionHeaderWithStatus("Title", "scenarios[0]", bus, scope) }
            onEdt {
                assertNull(header.statusIconForTest)
            }
        } finally { scope.cancel() }
    }

    @Test
    fun `simulated click toggles the expanded state and fires onToggle`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus()
            val toggleEvents = mutableListOf<Boolean>()
            val header = onEdt {
                SectionHeaderWithStatus(
                    title = "Title",
                    pathPrefix = "p",
                    bus = bus,
                    scope = scope,
                    initiallyExpanded = false,
                    onToggle = { toggleEvents.add(it) }
                )
            }
            assertFalse(header.isExpanded)
            onEdt { header.simulateClick() }
            assertTrue(header.isExpanded)
            onEdt { header.simulateClick() }
            assertFalse(header.isExpanded)
            assertEquals(listOf(true, false), toggleEvents)
        } finally { scope.cancel() }
    }

    @Test
    fun `setExpanded is a no-op when state already matches`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus()
            val toggleEvents = mutableListOf<Boolean>()
            val header = onEdt {
                SectionHeaderWithStatus("Title", "p", bus, scope, initiallyExpanded = true, onToggle = { toggleEvents.add(it) })
            }
            onEdt { header.setExpanded(true) }
            assertEquals(emptyList(), toggleEvents)
        } finally { scope.cancel() }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
