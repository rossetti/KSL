package ksl.app.swing.scenario

import ksl.app.config.ScenarioSpec
import java.awt.BorderLayout
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * Read-only table of the scenarios in the current sweep.  Editing
 * scenarios in the GUI is deliberately out of scope for v1 — the
 * panel just surfaces what the bundled sweep contains so the user can
 * see what will run.
 */
internal class ScenarioListPanel(initial: List<ScenarioSpec>) : JPanel(BorderLayout()) {

    private val tableModel = object : DefaultTableModel(
        arrayOf("Scenario", "Replications", "Length", "Warm-up", "Variation"),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    private val table = JTable(tableModel)

    init {
        border = BorderFactory.createTitledBorder("Scenarios")
        add(JScrollPane(table), BorderLayout.CENTER)
        renderScenarios(initial)
    }

    /** Re-populate the table from a new list of scenarios. */
    fun renderScenarios(scenarios: List<ScenarioSpec>) {
        tableModel.rowCount = 0
        for (spec in scenarios) {
            val o = spec.runOverrides
            tableModel.addRow(
                arrayOf<Any?>(
                    spec.name,
                    o?.numberOfReplications?.toString() ?: "(model default)",
                    o?.lengthOfReplication?.toString() ?: "(model default)",
                    o?.lengthOfReplicationWarmUp?.toString() ?: "(model default)",
                    describeVariation(spec)
                )
            )
        }
    }

    private fun describeVariation(spec: ScenarioSpec): String {
        val parts = mutableListOf<String>()
        spec.rvOverrides.forEach { ov ->
            parts.add("${ov.rvName}.${ov.paramName}=${ov.value}")
        }
        if (spec.controlOverrides.totalControls > 0) {
            parts.add("controls overridden")
        }
        return if (parts.isEmpty()) "(run params only)" else parts.joinToString(", ")
    }
}
