package ksl.app.swing.simopt

import ksl.app.config.optimization.OptimizationRunConfiguration
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.border.TitledBorder
import javax.swing.table.DefaultTableModel

/**
 * Read-only summary of the current bundled optimization — objective
 * response, input variables with bounds, and solver type / iteration
 * cap.  Editing the configuration in the GUI is deliberately out of
 * scope for v1; `OptimizationRunConfiguration` is too rich (problem +
 * solver + tuning) to wrap in a quick UI editor.
 */
internal class OptimizationSummaryPanel(initial: OptimizationRunConfiguration) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("Input", "Lower", "Upper", "Granularity"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JTable(tableModel)
    private val titledBorder = BorderFactory.createTitledBorder("Optimization")

    init {
        border = titledBorder
        add(JScrollPane(table), BorderLayout.CENTER)
        renderOptimization(initial)
    }

    /** Re-populate from a new optimization configuration (e.g. after model switch). */
    fun renderOptimization(config: OptimizationRunConfiguration) {
        tableModel.rowCount = 0
        val problem = config.problem
        val solver = config.solver
        val solverName = solver::class.simpleName ?: "Solver"
        (border as TitledBorder).title =
            "Optimization: minimize ${problem.objectiveResponseName} — " +
                "$solverName, max ${solver.maxIterations} iterations"

        for (input in problem.inputs) {
            tableModel.addRow(
                arrayOf<Any?>(
                    input.name,
                    input.lowerBound,
                    input.upperBound,
                    input.granularity
                )
            )
        }
        revalidate()
        repaint()
    }
}
