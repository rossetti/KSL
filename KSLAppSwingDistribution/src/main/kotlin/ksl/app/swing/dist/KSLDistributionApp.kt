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

package ksl.app.swing.dist

import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import java.awt.Dimension
import javax.swing.JFrame
import javax.swing.SwingUtilities

private const val APP_NAME = "KSL Distribution Fitting"

/**
 * Entry point for the distribution-fitting Swing application.
 *
 * This first step launches a bare, titled window so the module wiring,
 * dependency resolution, and FlatLaf look-and-feel can be verified in
 * isolation before any application logic exists. The controller, header,
 * tabbed workflow (data, estimators, scoring, reports, bootstrap), and the
 * run/event wiring arrive in subsequent steps. Run it with the green gutter
 * arrow beside `main`, or `./gradlew :KSLAppSwingDistribution:run`.
 */
fun main() {
    LookAndFeel.install(theme = AppTheme.SYSTEM, appName = APP_NAME)
    SwingUtilities.invokeLater {
        JFrame(APP_NAME).apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            minimumSize = Dimension(640, 480)
            setSize(960, 640)
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
