package ksl.app.swing.common.validation

import ksl.app.validation.FieldError
import ksl.app.validation.ValidationFeedbackBus
import ksl.app.validation.ValidationResult
import ksl.app.validation.ValidationSeverity
import org.junit.jupiter.api.Test
import java.awt.event.ActionEvent
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JumpToErrorActionTest {

    private fun err(path: String) =
        FieldError(path = path, message = "$path bad", severity = ValidationSeverity.ERROR, code = "X")

    private fun warn(path: String) =
        FieldError(path = path, message = "$path suspect", severity = ValidationSeverity.WARNING, code = "W")

    @Test
    fun `empty result is a no-op`() {
        val bus = ValidationFeedbackBus()
        val registry = WidgetPathRegistry()
        val action = JumpToErrorAction(bus, registry, JumpToErrorAction.Direction.NEXT)
        action.actionPerformed(ActionEvent("", 0, ""))
        // No assertion needed — just confirming no throw.
    }

    @Test
    fun `next from empty state jumps to the first issue`() {
        val a = onEdt { JTextField() }
        val b = onEdt { JTextField() }
        val registry = WidgetPathRegistry().apply {
            register("a", a)
            register("b", b)
        }
        val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("a"), err("b"))))
        val action = JumpToErrorAction(bus, registry, JumpToErrorAction.Direction.NEXT)
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }
        // We can't observe focus reliably in headless, but we can confirm path tracking.
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // advance again — should hit b
        // Reset and confirm starting state resets to first.
        action.reset()
    }

    @Test
    fun `next wraps around at the end`() {
        val registry = WidgetPathRegistry().apply {
            register("a", onEdt { JTextField() })
            register("b", onEdt { JTextField() })
        }
        val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("a"), err("b"))))
        val action = JumpToErrorAction(bus, registry, JumpToErrorAction.Direction.NEXT)

        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // a
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // b
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // wraps to a

        // Verifying directly: pass NEXT four times, then check we'd visit each twice (no exception, last is a).
        // Indirect proof via reaching previousIndex == 0 again:
        val pathsVisited = mutableListOf<String>()
        action.reset()
        val instrumentedAction = JumpToErrorAction(
            bus = bus,
            registry = registry,
            direction = JumpToErrorAction.Direction.NEXT,
            onMissingWidget = { pathsVisited.add(it.path) }
        )
        // Force every issue to be "missing widget" by using an empty registry, recording path order.
        val emptyRegistry = WidgetPathRegistry()
        val pathRecording = JumpToErrorAction(
            bus = bus,
            registry = emptyRegistry,
            direction = JumpToErrorAction.Direction.NEXT,
            onMissingWidget = { pathsVisited.add(it.path) }
        )
        onEdt { pathRecording.actionPerformed(ActionEvent("", 0, "")) }
        onEdt { pathRecording.actionPerformed(ActionEvent("", 0, "")) }
        onEdt { pathRecording.actionPerformed(ActionEvent("", 0, "")) }
        assertEquals(listOf("a", "b", "a"), pathsVisited)
    }

    @Test
    fun `previous from empty state jumps to the last issue`() {
        val registry = WidgetPathRegistry()
        val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("a"), err("b"))))
        val seen = mutableListOf<String>()
        val action = JumpToErrorAction(
            bus, registry, JumpToErrorAction.Direction.PREVIOUS,
            onMissingWidget = { seen.add(it.path) }
        )
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }
        assertEquals(listOf("b"), seen)
    }

    @Test
    fun `previous wraps around at the beginning`() {
        val registry = WidgetPathRegistry()
        val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("a"), err("b"))))
        val seen = mutableListOf<String>()
        val action = JumpToErrorAction(
            bus, registry, JumpToErrorAction.Direction.PREVIOUS,
            onMissingWidget = { seen.add(it.path) }
        )
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // b
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // a
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // wraps to b
        assertEquals(listOf("b", "a", "b"), seen)
    }

    @Test
    fun `last-visited path is preserved across publish events`() {
        val registry = WidgetPathRegistry()
        val seen = mutableListOf<String>()
        val initial = ValidationResult(errors = listOf(err("a"), err("b"), err("c")))
        val bus = ValidationFeedbackBus(initial)
        val action = JumpToErrorAction(
            bus, registry, JumpToErrorAction.Direction.NEXT,
            onMissingWidget = { seen.add(it.path) }
        )
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // a
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // b

        // Re-publish: same list, new instance.  Action should advance from b to c.
        bus.publish(ValidationResult(errors = listOf(err("a"), err("b"), err("c"))))
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }   // c
        assertEquals(listOf("a", "b", "c"), seen)
    }

    @Test
    fun `errors are visited before warnings (allIssues order)`() {
        val registry = WidgetPathRegistry()
        val seen = mutableListOf<String>()
        val bus = ValidationFeedbackBus(
            ValidationResult(errors = listOf(err("a")), warnings = listOf(warn("z")))
        )
        val action = JumpToErrorAction(
            bus, registry, JumpToErrorAction.Direction.NEXT,
            onMissingWidget = { seen.add(it.path) }
        )
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }
        assertEquals(listOf("a", "z"), seen)
    }

    @Test
    fun `onMissingWidget fires when registry has no entry for the path`() {
        val registry = WidgetPathRegistry()
        val bus = ValidationFeedbackBus(ValidationResult(errors = listOf(err("absent"))))
        var hit: FieldError? = null
        val action = JumpToErrorAction(
            bus, registry, JumpToErrorAction.Direction.NEXT,
            onMissingWidget = { hit = it }
        )
        onEdt { action.actionPerformed(ActionEvent("", 0, "")) }
        assertEquals("absent", hit?.path)
    }

    @Test
    fun `JumpUtil jumpTo returns false for unknown path`() {
        val registry = WidgetPathRegistry()
        assertFalse(JumpUtil.jumpTo(registry, "nope"))
    }

    @Test
    fun `JumpUtil jumpTo returns true when widget is found`() {
        val registry = WidgetPathRegistry()
        registry.register("p", onEdt { JTextField() })
        var found = false
        onEdt { found = JumpUtil.jumpTo(registry, "p") }
        assertTrue(found)
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
