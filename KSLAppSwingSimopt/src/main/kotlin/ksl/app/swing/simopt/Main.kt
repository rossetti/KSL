package ksl.app.swing.simopt

import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import javax.swing.SwingUtilities

/** Entry point for the simulation-optimization reference Swing application. */
fun main() {
    LookAndFeel.install(theme = AppTheme.SYSTEM, appName = "KSL Simopt")
    SwingUtilities.invokeLater {
        SimoptAppFrame().apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
