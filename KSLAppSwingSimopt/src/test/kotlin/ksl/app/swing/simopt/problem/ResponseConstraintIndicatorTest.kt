package ksl.app.swing.simopt.problem

import ksl.app.config.optimization.PenaltyFunctionSpec
import ksl.app.config.optimization.ResponseConstraintSpec
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Test
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The constraint dialog carries the indicator flag: it is set from the box, kept when a constraint is edited,
 * and an indicator's right-hand side must be a probability. Needs a display, since a dialog cannot be built
 * headless.
 */
class ResponseConstraintIndicatorTest {

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T> = Result.failure(IllegalStateException("not run"))
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result.getOrThrow()
    }

    private fun dialog(mode: ResponseConstraintDialog.Mode) =
        ResponseConstraintDialog(null, listOf("P(Late)"), PenaltyFunctionSpec.WithMemory(), mode)

    @Test
    fun theIndicatorBoxSetsTheFlagAndBoundsTheRhs() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "a dialog needs a display")
        onEdt {
            val d = dialog(ResponseConstraintDialog.Mode.Add)
            try {
                d.setRhsForTest("0.05")
                d.setIndicatorForTest(true)
                assertEquals(true, d.specForTest()?.indicator)
                d.setRhsForTest("5.0")
                assertNull(d.specForTest(), "an indicator's RHS is a probability")
                assertTrue("probability" in d.statusForTest(), d.statusForTest())
            } finally {
                d.dispose()
            }
        }
    }

    @Test
    fun editingAnIndicatorConstraintKeepsTheFlag() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "a dialog needs a display")
        onEdt {
            val spec = ResponseConstraintSpec("P(Late)", 0.05, indicator = true)
            val d = dialog(ResponseConstraintDialog.Mode.Edit(0, spec))
            try {
                assertEquals(spec, d.specForTest())
            } finally {
                d.dispose()
            }
        }
    }
}
