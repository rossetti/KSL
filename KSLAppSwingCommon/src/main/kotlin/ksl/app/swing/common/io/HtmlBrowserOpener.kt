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

package ksl.app.swing.common.io

import java.awt.Desktop
import java.nio.file.Path

/**
 *  Open the given HTML file in the user's default browser via
 *  `java.awt.Desktop.browse`.  Substrate report writers
 *  (`BatchReportsWriter`, `ComparisonReportRenderer`) intentionally
 *  do not perform this side effect — they return paths and let the
 *  host decide whether and how to surface the rendered HTML.  Swing
 *  hosts that want the "auto-open after Generate" behavior call this
 *  helper with the path from `WriteOutcome.htmlPath`.
 *
 *  Throws [UnsupportedOperationException] when running on a JVM /
 *  platform that does not expose Desktop or its BROWSE action;
 *  callers wrap the call in try/catch and surface the error through
 *  whatever notification channel they prefer.
 *
 *  Equivalent to the private helper previously embedded in
 *  `BatchReports.openInBrowser` and `ComparisonReportRenderer.openInBrowser`
 *  before the D-Common-6 substrate-vs-shell split.
 */
fun openHtmlInBrowser(htmlPath: Path) {
    if (!Desktop.isDesktopSupported()) {
        throw UnsupportedOperationException("Desktop browser open is not supported on this platform.")
    }
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.BROWSE)) {
        throw UnsupportedOperationException("Browser action is not supported on this platform.")
    }
    desktop.browse(htmlPath.toUri())
}
