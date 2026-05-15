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
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.border.LineBorder
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FieldErrorMarkerTest {

    private val originalBorder = BorderFactory.createEmptyBorder(2, 2, 2, 2)

    private fun err(path: String, msg: String = "bad") =
        FieldError(path = path, message = msg, severity = ValidationSeverity.ERROR, code = "X")

    private fun warn(path: String, msg: String = "suspect") =
        FieldError(path = path, message = msg, severity = ValidationSeverity.WARNING, code = "W")

    @Test
    fun `attach with clean state hides the icon and preserves the original border`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val field = JTextField().apply { border = originalBorder }
            val bus = ValidationFeedbackBus()
            val wrapped = onEdt { FieldErrorMarker.attach(field, "p", bus, scope) }

            onEdt {
                assertEquals(originalBorder, field.border)
                val icon = iconLabelIn(wrapped)
                assertNotNull(icon)
                assertEquals(false, icon!!.isVisible)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `error at path shows error icon and red outline`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val field = JTextField().apply { border = originalBorder }
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("p"))))
            val wrapped = onEdt { FieldErrorMarker.attach(field, "p", bus, scope) }

            onEdt {
                val icon = iconLabelIn(wrapped)!!.icon as? SeverityIcon
                assertNotNull(icon)
                assertEquals(ValidationSeverity.ERROR, icon!!.severity)
                assertTrue(field.border is javax.swing.border.CompoundBorder, "should compound outline + original")
                val outline = (field.border as javax.swing.border.CompoundBorder).outsideBorder as LineBorder
                assertEquals(SeverityIcon.ERROR_COLOR, outline.lineColor)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `error wins over warning at the same path`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val field = JTextField()
            val bus = ValidationFeedbackBus(
                ValidationResult(errors = listOf(err("p", "boom")), warnings = listOf(warn("p", "small")))
            )
            val wrapped = onEdt { FieldErrorMarker.attach(field, "p", bus, scope) }

            onEdt {
                val icon = iconLabelIn(wrapped)!!.icon as SeverityIcon
                assertEquals(ValidationSeverity.ERROR, icon.severity)
                val tip = wrapped.toolTipText
                assertNotNull(tip)
                assertTrue(tip!!.contains("boom"), "tooltip should still list the warning text alongside the error: $tip")
                assertTrue(tip.contains("small"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `decorateBorder leaves the component in place and only changes the border`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val field = JTextField().apply { border = originalBorder }
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("p"))))
            onEdt { FieldErrorMarker.decorateBorder(field, "p", bus, scope) }

            onEdt {
                assertNotEquals(originalBorder, field.border)
                assertTrue(field.border is javax.swing.border.CompoundBorder)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `unrelated issues do not affect the field`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val field = JTextField().apply { border = originalBorder }
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("other"))))
            val wrapped = onEdt { FieldErrorMarker.attach(field, "p", bus, scope) }

            onEdt {
                assertEquals(originalBorder, field.border)
                val icon = iconLabelIn(wrapped)!!
                assertEquals(false, icon.isVisible)
                assertNull(wrapped.toolTipText)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `attach registers the wrapped panel under the path`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val reg = WidgetPathRegistry()
            val field = JTextField()
            val bus = ValidationFeedbackBus()
            val wrapped = onEdt { FieldErrorMarker.attach(field, "scenarios[0].x", bus, scope, reg) }
            assertEquals(listOf(wrapped), reg.findAt("scenarios[0].x"))
        } finally {
            scope.cancel()
        }
    }

    private fun iconLabelIn(panel: javax.swing.JComponent): JLabel? {
        val parent = panel as JPanel
        for (i in 0 until parent.componentCount) {
            val comp = parent.getComponent(i)
            if (comp is JLabel) return comp
        }
        return null
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
