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
import javax.swing.SwingUtilities

private const val APP_NAME = "KSL Distribution Fitting"

/**
 * Entry point for the distribution-fitting Swing application.
 *
 * Installs the shared FlatLaf look-and-feel, then builds the controller and
 * the frame on the event-dispatch thread. The frame is a thin binder over the
 * controller's state flows; the controller owns the editable configuration and
 * (in later steps) the fitting session. Run it with the green gutter arrow
 * beside `main`, or `./gradlew :KSLAppSwingDistribution:run`.
 */
fun main() {
    LookAndFeel.install(theme = AppTheme.SYSTEM, appName = APP_NAME)
    SwingUtilities.invokeLater {
        val controller = DistributionAppController(APP_NAME)
        DistributionAppFrame(controller).apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
