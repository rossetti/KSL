package ksl.app.swing.experiment

import javax.swing.SwingUtilities

/** Entry point for the designed-experiment reference Swing application. */
fun main() {
    SwingUtilities.invokeLater {
        ExperimentAppFrame().apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
