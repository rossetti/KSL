/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.common.layout

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 *  A [FlowLayout] that genuinely reflows.
 *
 *  Plain [FlowLayout] always reports a single-row preferred height, so when it is the
 *  layout of a toolbar placed in a `BorderLayout` NORTH/SOUTH region (or any parent that
 *  honours the preferred height) and the window narrows, the buttons wrap visually but
 *  the region does not grow taller — the overflow rows are clipped. [WrapLayout]
 *  overrides the size calculation to measure against the target container's actual width,
 *  so it reports the true multi-row height and the region grows to show every row.
 *
 *  Drop-in replacement for [FlowLayout]: use it as the layout of a button strip that
 *  should wrap when the window is too narrow to hold one row.
 *
 *  Adapted from Rob Camick's widely-used WrapLayout.
 */
class WrapLayout(align: Int = LEFT, hgap: Int = 5, vgap: Int = 5) : FlowLayout(align, hgap, vgap) {

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, preferred = true)

    override fun minimumLayoutSize(target: Container): Dimension =
        layoutSize(target, preferred = false).also { it.width -= hgap + 1 }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            // Lay out against the nearest ancestor that already has a width; fall back to unbounded.
            var container: Container? = target
            while (container != null && container.size.width == 0) container = container.parent
            var targetWidth = container?.size?.width ?: 0
            if (targetWidth == 0) targetWidth = Int.MAX_VALUE

            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = targetWidth - horizontalInsetsAndGap

            val dim = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0
            for (i in 0 until target.componentCount) {
                val m: Component = target.getComponent(i)
                if (!m.isVisible) continue
                val d = if (preferred) m.preferredSize else m.minimumSize
                if (rowWidth + d.width > maxWidth) {
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

            // Inside a scroll pane, trim a gap so repeated revalidation does not oscillate.
            if (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) != null) {
                dim.width -= hgap + 1
            }
            return dim
        }
    }

    private fun addRow(dim: Dimension, rowWidth: Int, rowHeight: Int) {
        dim.width = maxOf(dim.width, rowWidth)
        if (dim.height > 0) dim.height += vgap
        dim.height += rowHeight
    }
}
