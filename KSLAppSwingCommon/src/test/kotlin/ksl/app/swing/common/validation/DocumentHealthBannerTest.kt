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
import javax.swing.JButton
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentHealthBannerTest {

    private fun err(path: String) =
        FieldError(path = path, message = "$path bad", severity = ValidationSeverity.ERROR, code = "X")

    private fun warn(path: String) =
        FieldError(path = path, message = "$path suspect", severity = ValidationSeverity.WARNING, code = "W")

    @Test
    fun `clean document hides the banner`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus()
            val banner = onEdt { DocumentHealthBanner(bus, WidgetPathRegistry(), scope) }
            onEdt { assertFalse(banner.isVisible) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `banner is visible when at least one issue is present`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("scenarios[0].x"))))
            val banner = onEdt { DocumentHealthBanner(bus, WidgetPathRegistry(), scope) }
            onEdt { assertTrue(banner.isVisible) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `summary line contains error and warning counts`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(
                ValidationResult(
                    errors = listOf(err("a"), err("b"), err("c")),
                    warnings = listOf(warn("d"), warn("e"))
                )
            )
            val banner = onEdt { DocumentHealthBanner(bus, WidgetPathRegistry(), scope) }
            onEdt {
                val text = summaryText(banner)
                assertTrue(text.contains("3 errors"), "expected '3 errors' in summary: $text")
                assertTrue(text.contains("2 warnings"), "expected '2 warnings' in summary: $text")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `summary line uses singular forms for count of one`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(
                ValidationResult(errors = listOf(err("a")), warnings = listOf(warn("b")))
            )
            val banner = onEdt { DocumentHealthBanner(bus, WidgetPathRegistry(), scope) }
            onEdt {
                val text = summaryText(banner)
                assertTrue(text.contains("1 error"))
                assertFalse(text.contains("1 errors"))
                assertTrue(text.contains("1 warning"))
                assertFalse(text.contains("1 warnings"))
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `detail panel starts collapsed and toggles on user action`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("a"))))
            val banner = onEdt { DocumentHealthBanner(bus, WidgetPathRegistry(), scope) }
            onEdt {
                assertFalse(banner.isExpandedForTest())
                banner.toggleForTest()
                assertTrue(banner.isExpandedForTest())
                banner.toggleForTest()
                assertFalse(banner.isExpandedForTest())
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `jump button focuses the registered widget when present`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val field = onEdt { javax.swing.JTextField() }
            val registry = WidgetPathRegistry().apply { register("scenarios[0].x", field) }
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("scenarios[0].x"))))
            val banner = onEdt { DocumentHealthBanner(bus, registry, scope) }
            var missing: FieldError? = null
            val banner2 = onEdt {
                DocumentHealthBanner(bus, registry, scope, onMissingWidget = { missing = it })
            }
            onEdt {
                val jump = findJumpButton(banner2)
                requireNotNull(jump) { "expected a Jump button" }
                jump.doClick()
            }
            assertEquals(null, missing, "registry has the widget; onMissingWidget should not fire")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `jump button calls onMissingWidget when no widget is registered`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("absent"))))
            var missing: FieldError? = null
            val banner = onEdt {
                DocumentHealthBanner(bus, WidgetPathRegistry(), scope, onMissingWidget = { missing = it })
            }
            onEdt {
                val jump = findJumpButton(banner)
                requireNotNull(jump).doClick()
            }
            assertEquals("absent", missing?.path)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `banner reacts to publish updates`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)
        try {
            val bus = ValidationFeedbackBus()
            val banner = onEdt { DocumentHealthBanner(bus, WidgetPathRegistry(), scope) }
            onEdt { assertFalse(banner.isVisible) }

            bus.publish(ValidationResult(errors = listOf(err("x"))))
            // Drain EDT so the coroutine's apply(...) runs.
            onEdt { /* yield */ }
            onEdt {
                assertTrue(banner.isVisible)
                assertTrue(summaryText(banner).contains("1 error"))
            }
        } finally {
            scope.cancel()
        }
    }

    private fun summaryText(banner: DocumentHealthBanner): String {
        for (rowOrLabel in banner.components) {
            val s = collectText(rowOrLabel)
            if (s.isNotBlank()) return s
        }
        return ""
    }

    private fun collectText(c: java.awt.Component): String {
        val sb = StringBuilder()
        if (c is javax.swing.JLabel) sb.append(c.text).append(' ')
        if (c is java.awt.Container) for (child in c.components) sb.append(collectText(child)).append(' ')
        return sb.toString()
    }

    private fun findJumpButton(banner: DocumentHealthBanner): JButton? {
        // Make sure the detail panel is expanded so its rows are reachable; tests
        // that explicitly verify the toggle behaviour expand it themselves first.
        if (!banner.isExpandedForTest()) banner.toggleForTest()
        for (row in banner.detailRowsForTest()) {
            val btn = findButton(row)
            if (btn != null && btn.text == "Jump to source") return btn
        }
        return null
    }

    private fun findButton(c: java.awt.Component): JButton? {
        if (c is JButton) return c
        if (c is java.awt.Container) {
            for (child in c.components) {
                val b = findButton(child)
                if (b != null) return b
            }
        }
        return null
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
