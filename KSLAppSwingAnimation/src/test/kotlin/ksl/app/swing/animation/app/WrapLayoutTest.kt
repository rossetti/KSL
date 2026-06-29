package ksl.app.swing.animation.app

import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * G5: WrapLayout must report a taller preferred size when the available width forces its components onto
 * multiple rows — that's what lets a NORTH toolbar grow instead of clipping the wrapped buttons.
 */
class WrapLayoutTest {

    private fun panelOfThree(): JPanel = JPanel(WrapLayout(FlowLayout.LEFT, 5, 5)).apply {
        repeat(3) { add(JButton().apply { preferredSize = Dimension(100, 20) }) }
    }

    @Test
    fun `narrow width wraps to a taller preferred size than wide width`() {
        val wide = panelOfThree().apply { size = Dimension(1000, 50) }
        val narrow = panelOfThree().apply { size = Dimension(120, 50) } // only one 100px button fits per row

        val wideH = wide.preferredSize.height
        val narrowH = narrow.preferredSize.height

        assertTrue(narrowH > wideH, "wrapped layout must be taller (narrow=$narrowH, wide=$wideH)")
        // Three 100px buttons, one per ~120px row, gives ~3 rows vs 1 row — at least 3x the single-row content.
        assertTrue(narrowH >= wideH * 2, "narrow should span multiple rows (narrow=$narrowH, wide=$wideH)")
    }

    @Test
    fun `plain FlowLayout reports the same single-row height regardless of width (the bug WrapLayout fixes)`() {
        fun flowPanel() = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            repeat(3) { add(JButton().apply { preferredSize = Dimension(100, 20) }) }
        }
        val wide = flowPanel().apply { size = Dimension(1000, 50) }
        val narrow = flowPanel().apply { size = Dimension(120, 50) }
        // Demonstrates the defect: plain FlowLayout's preferred height ignores width, so NORTH clips the overflow.
        assertEquals(wide.preferredSize.height, narrow.preferredSize.height)
    }
}
