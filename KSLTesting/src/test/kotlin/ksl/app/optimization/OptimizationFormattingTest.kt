package ksl.app.optimization

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 *  Substrate-level tests for the [formatObjective] sentinel-aware
 *  Double formatter.  Used by any UI shell (Swing live panels,
 *  HTML reports, CLI status output) so they all render the same
 *  value the same way.
 */
class OptimizationFormattingTest {

    @Test
    fun `formatObjective maps Double_MAX_VALUE to plus infinity`() {
        assertEquals("+∞", formatObjective(Double.MAX_VALUE))
    }

    @Test
    fun `formatObjective maps negative Double_MAX_VALUE to minus infinity`() {
        assertEquals("−∞", formatObjective(-Double.MAX_VALUE))
    }

    @Test
    fun `formatObjective maps POSITIVE_INFINITY to plus infinity`() {
        assertEquals("+∞", formatObjective(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `formatObjective maps NEGATIVE_INFINITY to minus infinity`() {
        assertEquals("−∞", formatObjective(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `formatObjective maps NaN to em-dash`() {
        assertEquals("—", formatObjective(Double.NaN))
    }

    @Test
    fun `formatObjective renders finite values with four-decimal precision`() {
        assertEquals("142.3100", formatObjective(142.31))
        assertEquals("0.0000", formatObjective(0.0))
        assertEquals("-3.1416", formatObjective(-3.14159))
    }
}
