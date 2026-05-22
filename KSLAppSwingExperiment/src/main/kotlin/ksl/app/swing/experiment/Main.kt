package ksl.app.swing.experiment

import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import javax.swing.SwingUtilities

/** Entry point for the designed-experiment reference Swing application. */
fun main() {
    LookAndFeel.install(theme = AppTheme.SYSTEM, appName = "KSL Experiment")
    SwingUtilities.invokeLater {
        ExperimentAppFrame().apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
