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

import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.config.DistributionKind
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JPanel

/** Result of the estimator dialog: the chosen ids and whether to apply to all of the kind. */
data class EstimatorChoice(val ids: Set<String>, val applyToAll: Boolean)

/**
 * Modal dialog to choose the estimators for one dataset. Lists every estimator
 * in the catalog for the dataset's [kind]. On OK, [choice] holds the selection;
 * on Cancel it stays null.
 */
class EstimatorSelectionDialog(
    owner: Window?,
    datasetName: String,
    kind: DistributionKind,
    currentIds: Set<String>
) : JDialog(owner, "Edit estimators — $datasetName (${kindLabel(kind)})", ModalityType.APPLICATION_MODAL) {

    var choice: EstimatorChoice? = null
        private set

    private val checklist: CatalogChecklist
    private val applyToAllCheck = JCheckBox("Apply to all ${kindLabel(kind)} datasets")

    init {
        val items = FittingCatalog.estimators
            .filter { it.kind == kind }
            .sortedBy { it.displayName }
            .map { it.id to it.displayName }
        checklist = CatalogChecklist(items, currentIds, FittingCatalog.defaultEstimatorIds(kind))

        layout = BorderLayout()
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(checklist, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun buildSouth(): JPanel {
        val applyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { add(applyToAllCheck) }
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(JButton("Cancel").apply { addActionListener { choice = null; dispose() } })
            add(JButton("OK").apply {
                addActionListener {
                    choice = EstimatorChoice(checklist.selectedIds(), applyToAllCheck.isSelected)
                    dispose()
                }
            })
        }
        return JPanel(BorderLayout()).apply {
            add(applyRow, BorderLayout.WEST)
            add(buttonRow, BorderLayout.EAST)
        }
    }
}
