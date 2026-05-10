package ksl.app.swing.simopt

import javax.swing.SwingUtilities

/** Entry point for the simulation-optimization reference Swing application. */
fun main() {
    SwingUtilities.invokeLater {
        SimoptAppFrame().apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
