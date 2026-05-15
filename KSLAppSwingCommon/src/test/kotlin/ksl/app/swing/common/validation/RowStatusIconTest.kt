package ksl.app.swing.common.validation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.swing.Swing
import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import org.junit.jupiter.api.Test
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RowStatusIconTest {

    private fun err(path: String) =
        FieldError(path = path, message = "$path bad", severity = ValidationSeverity.ERROR, code = "X")

    private fun warn(path: String) =
        FieldError(path = path, message = "$path suspect", severity = ValidationSeverity.WARNING, code = "W")

    @Test
    fun `clean row hides the icon`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus()
            val icon = onEdt { RowStatusIcon("scenarios[0]", bus, scope) }
            onEdt {
                assertEquals(false, icon.isVisible)
                assertNull(icon.icon)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `errors-only row shows error icon with count tooltip`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(
                ValidationResult(errors = listOf(err("scenarios[0]"), err("scenarios[0].x")))
            )
            val icon = onEdt { RowStatusIcon("scenarios[0]", bus, scope) }
            onEdt {
                assertEquals(true, icon.isVisible)
                val si = icon.icon as SeverityIcon
                assertEquals(ValidationSeverity.ERROR, si.severity)
                val tip = icon.toolTipText
                assertNotNull(tip)
                assertTrue(tip!!.contains("2 errors"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `warnings-only row shows warning icon`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(
                ValidationResult(warnings = listOf(warn("scenarios[0].y")))
            )
            val icon = onEdt { RowStatusIcon("scenarios[0]", bus, scope) }
            onEdt {
                assertEquals(ValidationSeverity.WARNING, (icon.icon as SeverityIcon).severity)
                assertTrue(icon.toolTipText.contains("1 warning"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `mixed errors and warnings show error icon and combined tooltip`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(
                ValidationResult(
                    errors = listOf(err("scenarios[0].x")),
                    warnings = listOf(warn("scenarios[0].y"), warn("scenarios[0].z"))
                )
            )
            val icon = onEdt { RowStatusIcon("scenarios[0]", bus, scope) }
            onEdt {
                assertEquals(ValidationSeverity.ERROR, (icon.icon as SeverityIcon).severity)
                val tip = icon.toolTipText
                assertTrue(tip.contains("1 error"))
                assertTrue(tip.contains("2 warnings"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unrelated issues do not show on the row`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("scenarios[1].x"))))
            val icon = onEdt { RowStatusIcon("scenarios[0]", bus, scope) }
            onEdt {
                assertEquals(false, icon.isVisible)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `click callback receives the first matching issue`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val first = err("scenarios[0].x")
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(first, err("scenarios[0].y"))))
            var seen: FieldError? = null
            val icon = onEdt { RowStatusIcon("scenarios[0]", bus, scope, onClick = { seen = it }) }
            onEdt {
                val click = java.awt.event.MouseEvent(
                    icon, java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                    0, 0, 1, false, java.awt.event.MouseEvent.BUTTON1
                )
                for (l in icon.mouseListeners) l.mouseClicked(click)
            }
            assertEquals(first, seen)
        } finally {
            scope.cancel()
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
