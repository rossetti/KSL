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
import ksl.app.dist.config.EvaluationMethod
import ksl.app.dist.config.RankingMethod
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Window
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel

/** Result of the scoring dialog: chosen scoring models, ranking/evaluation, and apply-to-all flag. */
data class ScoringChoice(
    val ids: Set<String>,
    val ranking: RankingMethod,
    val evaluation: EvaluationMethod,
    val applyToAll: Boolean
)

/**
 * Modal dialog to choose the scoring models, ranking method, and evaluation
 * method for one (continuous) dataset. Lists every scoring model in the
 * catalog. On OK, [choice] holds the selection; on Cancel it stays null.
 */
class ScoringSelectionDialog(
    owner: Window?,
    datasetName: String,
    kind: DistributionKind,
    currentIds: Set<String>,
    currentRanking: RankingMethod,
    currentEvaluation: EvaluationMethod
) : JDialog(owner, "Edit scoring — $datasetName (${kindLabel(kind)})", ModalityType.APPLICATION_MODAL) {

    var choice: ScoringChoice? = null
        private set

    private val checklist: CatalogChecklist
    private val rankingCombo = JComboBox(RankingMethod.entries.toTypedArray())
    private val evalCombo = JComboBox(EvaluationMethod.entries.toTypedArray())
    private val applyToAllCheck = JCheckBox("Apply to all ${kindLabel(kind)} datasets")

    init {
        val items = FittingCatalog.scoringModels
            .sortedBy { it.displayName }
            .map { it.id to it.displayName }
        checklist = CatalogChecklist(items, currentIds, FittingCatalog.defaultScoringModelIds())
        rankingCombo.selectedItem = currentRanking
        evalCombo.selectedItem = currentEvaluation

        layout = BorderLayout()
        (contentPane as JPanel).border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        add(checklist, BorderLayout.CENTER)
        add(buildSouth(), BorderLayout.SOUTH)
        pack()
        setLocationRelativeTo(owner)
    }

    private fun buildSouth(): JPanel {
        val methodsRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            add(JLabel("Ranking:"))
            add(rankingCombo)
            add(JLabel("Evaluation:"))
            add(evalCombo)
        }
        val applyRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 4)).apply { add(applyToAllCheck) }
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(JButton("Cancel").apply { addActionListener { choice = null; dispose() } })
            add(JButton("OK").apply {
                addActionListener {
                    choice = ScoringChoice(
                        ids = checklist.selectedIds(),
                        ranking = rankingCombo.selectedItem as RankingMethod,
                        evaluation = evalCombo.selectedItem as EvaluationMethod,
                        applyToAll = applyToAllCheck.isSelected
                    )
                    dispose()
                }
            })
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(methodsRow)
            add(JPanel(BorderLayout()).apply {
                add(applyRow, BorderLayout.WEST)
                add(buttonRow, BorderLayout.EAST)
            })
        }
    }
}
