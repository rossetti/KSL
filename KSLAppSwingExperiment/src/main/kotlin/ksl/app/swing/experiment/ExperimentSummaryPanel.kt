package ksl.app.swing.experiment

import ksl.controls.experiments.ParallelDesignedExperiment
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel

/**
 * Read-only summary of the current bundled experiment — factor names,
 * low/high levels, and design-point count.  Control-key mapping is not
 * displayed because it lives on the experiment's private
 * `factorSettings` field; users wanting the mapping can read
 * `BundledExperiments.kt` directly.
 *
 * Editing the design in the GUI is deliberately out of scope for v1;
 * designed experiments are too rich to wrap in a quick UI editor.
 */
internal class ExperimentSummaryPanel(initial: ParallelDesignedExperiment) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("Factor", "Low", "High"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JTable(tableModel)
    private val titledBorder = BorderFactory.createTitledBorder("Experiment")

    init {
        border = titledBorder
        add(JScrollPane(table), BorderLayout.CENTER)
        renderExperiment(initial)
    }

    /** Re-populate from a new experiment instance (e.g. after model switch). */
    fun renderExperiment(experiment: ParallelDesignedExperiment) {
        tableModel.rowCount = 0
        val totalPoints = experiment.design.designPoints().size
        (border as TitledBorder).title =
            "Experiment: ${experiment.name} (${totalPoints} design points)"

        for ((_, factor) in experiment.design.factors) {
            val levels = factor.levels
            val low = if (levels.isNotEmpty()) levels.first().toString() else "(empty)"
            val high = if (levels.size >= 2) levels.last().toString() else "(empty)"
            tableModel.addRow(arrayOf<Any?>(factor.name, low, high))
        }
        revalidate()
        repaint()
    }
}
