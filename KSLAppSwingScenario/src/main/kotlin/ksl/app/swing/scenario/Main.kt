package ksl.app.swing.scenario

import javax.swing.SwingUtilities

/**
 * Entry point for the scenario-sweep reference Swing application.
 */
fun main() {
    SwingUtilities.invokeLater {
        ScenarioAppFrame().apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
