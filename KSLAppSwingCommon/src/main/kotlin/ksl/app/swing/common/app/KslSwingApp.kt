/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.common.app

import ksl.app.swing.common.appearance.AppTheme
import ksl.app.swing.common.appearance.LookAndFeel
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 *  Standard launch path for a KSL configuration-style Swing app
 *  (Experiment / Simopt / Scenario): install the look-and-feel, then on the
 *  EDT build the controller from [appName], wrap it in its top-level frame, and
 *  show the window centered.  Captures the byte-identical `main()` body those
 *  apps used to each copy, so a maintainer sees one launch path across them.
 *
 *  The look-and-feel is installed **before** [SwingUtilities.invokeLater] so
 *  the macOS bootstrap (`apple.laf.useScreenMenuBar`,
 *  `apple.awt.application.name`) takes effect before any AWT class loads.
 *
 *  Single is intentionally not a client of this helper — its `kslSingleApp`
 *  DSL is a developer-facing scaffold with a different launch flow.
 *
 *  @param appName window title + per-app workspace subdirectory name; must be
 *    non-blank.
 *  @param controller builds the app controller from the app name.
 *  @param frame wraps the controller in its top-level [JFrame].
 *  @param theme look-and-feel theme; defaults to [AppTheme.SYSTEM].
 */
fun <C> launchKslSwingApp(
    appName: String,
    controller: (String) -> C,
    frame: (C) -> JFrame,
    theme: AppTheme = AppTheme.SYSTEM,
) {
    require(appName.isNotBlank()) { "appName must be non-blank" }
    LookAndFeel.install(theme = theme, appName = appName)
    SwingUtilities.invokeLater {
        frame(controller(appName)).apply {
            pack()
            setLocationRelativeTo(null)
            isVisible = true
        }
    }
}
