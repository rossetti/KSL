/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.animation.app

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * A [FlowLayout] that reports its preferred size based on the available width, so a wrapped row is
 * accounted for in the height instead of being clipped (G5).
 *
 * Plain `FlowLayout` always reports a single-row preferred height. When such a panel sits in a
 * `BorderLayout.NORTH` region and the window narrows, the layout *wraps* its components onto a second
 * row but the NORTH region keeps its one-row height — so the wrapped controls (e.g. the Replay "Load"
 * and "Rescan" buttons) disappear below the band. This subclass computes the true wrapped height for
 * the current width, letting the enclosing layout grow to show every component.
 *
 * Standard, widely-used technique (after Rob Camick's `WrapLayout`), ported to Kotlin.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).also { it.width -= (hgap + 1) }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // Use the width of the container we're scrolled inside, if any, else the target's own width.
            var targetWidth = target.size.width
            if (targetWidth == 0) targetWidth = Int.MAX_VALUE
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) as? JScrollPane
            if (scrollPane != null && targetWidth == Int.MAX_VALUE) targetWidth = scrollPane.viewport.width

            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + (hgap * 2)
            val maxWidth = targetWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                // Start a new row when the next component would overflow.
                if (rowWidth + d.width > maxWidth && rowWidth != 0) {
                    addRow(dim, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth != 0) rowWidth += hgap
                rowWidth += d.width
                rowHeight = maxOf(rowHeight, d.height)
            }
            addRow(dim, rowWidth, rowHeight)

            dim.width += horizontalInsetsAndGap
            dim.height += insets.top + insets.bottom + vgap * 2

            // When inside a viewport, trim one pixel so a horizontal scrollbar isn't forced needlessly.
            if (scrollPane != null && scrollPane.isValid) dim.width -= (hgap + 1)
            return dim
        }
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
