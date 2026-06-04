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

import kotlinx.coroutines.launch
import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DistributionKind
import ksl.app.swing.dist.DistributionAppController
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants

/**
 * Estimators tab: the full catalog of parameter estimators for the current
 * distribution kind, as a checkbox per estimator bound to `config.estimatorIds`.
 * The list rebuilds when the kind changes (continuous and discrete offer
 * different estimators). Defaults/All/None operate on the current kind's set.
 */
class EstimatorPanel(private val controller: DistributionAppController) : JPanel(BorderLayout()) {

    private var updating = false
    private var renderedKind: DistributionKind? = null
    private val checkBoxes = LinkedHashMap<String, JCheckBox>()

    private val listPanel = JPanel(GridLayout(0, 2, 12, 2))
    private val counterLabel = JLabel()

    init {
        border = BorderFactory.createTitledBorder("Estimators")
        add(buildHeader(), BorderLayout.NORTH)
        add(JScrollPane(listPanel).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }, BorderLayout.CENTER)
        bindState()
    }

    private fun buildHeader(): Component {
        val hint = JLabel("Distribution families to fit. The available set depends on the distribution kind.")
        val buttonRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            add(JButton("Defaults").apply { addActionListener { controller.setEstimatorDefaults() } })
            add(JButton("All").apply { addActionListener { controller.selectAllEstimators() } })
            add(JButton("None").apply { addActionListener { controller.selectNoEstimators() } })
            add(Box.createHorizontalStrut(12))
            add(counterLabel)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(4, 8, 0, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                add(hint)
            })
            add(buttonRow)
        }
    }

    private fun bindState() {
        controller.edtScope.launch {
            controller.config.collect { cfg ->
                if (cfg.kind != renderedKind) rebuildFor(cfg.kind)
                applySelection(cfg.estimatorIds)
            }
        }
        val cfg = controller.config.value
        rebuildFor(cfg.kind)
        applySelection(cfg.estimatorIds)
    }

    private fun rebuildFor(kind: DistributionKind) {
        renderedKind = kind
        listPanel.removeAll()
        checkBoxes.clear()
        FittingCatalog.estimators
            .filter { it.kind == kind }
            .sortedBy { it.displayName }
            .forEach { descriptor ->
                val box = JCheckBox(descriptor.displayName).apply {
                    toolTipText = descriptor.id
                    addActionListener { if (!updating) controller.toggleEstimator(descriptor.id, isSelected) }
                }
                checkBoxes[descriptor.id] = box
                listPanel.add(box)
            }
        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun applySelection(ids: Set<String>) {
        updating = true
        try {
            checkBoxes.forEach { (id, box) -> box.isSelected = id in ids }
        } finally {
            updating = false
        }
        val selected = checkBoxes.keys.count { it in ids }
        counterLabel.text = "$selected of ${checkBoxes.size} selected"
    }
}
