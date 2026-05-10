package ksl.app.swing.single

import javax.swing.SwingUtilities

/**
 * Entry point for the single-model reference Swing application.
 *
 * Construction of Swing components must happen on the EDT, so the JFrame
 * is created inside [SwingUtilities.invokeLater].  The actual run-driving
 * code uses `Dispatchers.Swing` from `kotlinx-coroutines-swing` (see
 * [SingleAppFrame] and [SingleAppViewModel]).
 */
fun main() {
    SwingUtilities.invokeLater {
        SingleAppFrame().apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
