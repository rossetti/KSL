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

package ksl.app.swing.dist.panel

import ksl.app.dist.config.DistributionKind
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/** Friendly label for a distribution kind. */
fun kindLabel(kind: DistributionKind): String = when (kind) {
    DistributionKind.CONTINUOUS -> "Continuous"
    DistributionKind.DISCRETE -> "Discrete"
}

/**
 * A reusable two-column checklist over a catalog of `(id, displayName)` items,
 * with Defaults / All / None controls and a live selected-count. Backs the
 * per-dataset estimator and scoring selection dialogs.
 */
class CatalogChecklist(
    items: List<Pair<String, String>>,
    initial: Set<String>,
    private val defaults: Set<String>
) : JPanel(BorderLayout()) {

    private val checkBoxes = LinkedHashMap<String, JCheckBox>()
    private val counterLabel = JLabel()

    init {
        val listPanel = JPanel(GridLayout(0, 2, 12, 2))
        items.forEach { (id, label) ->
            val box = JCheckBox(label).apply {
                toolTipText = id
                isSelected = id in initial
                addActionListener { updateCounter() }
            }
            checkBoxes[id] = box
            listPanel.add(box)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JButton("Defaults").apply { addActionListener { setSelection(defaults) } })
            add(JButton("All").apply { addActionListener { setSelection(checkBoxes.keys) } })
            add(JButton("None").apply { addActionListener { setSelection(emptySet()) } })
            add(Box.createHorizontalStrut(12))
            add(counterLabel)
        }
        val scroll = JScrollPane(listPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
            preferredSize = Dimension(560, 320)
        }
        add(buttons, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        updateCounter()
    }

    fun selectedIds(): Set<String> = checkBoxes.filterValues { it.isSelected }.keys

    private fun setSelection(ids: Set<String>) {
        checkBoxes.forEach { (id, box) -> box.isSelected = id in ids }
        updateCounter()
    }

    private fun updateCounter() {
        val n = checkBoxes.values.count { it.isSelected }
        counterLabel.text = "$n of ${checkBoxes.size} selected"
    }
}
