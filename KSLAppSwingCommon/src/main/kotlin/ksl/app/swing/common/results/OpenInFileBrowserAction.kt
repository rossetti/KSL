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

package ksl.app.swing.common.results

import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.AbstractAction

/**
 * Reveals a directory in the OS file manager via `Desktop.open`.
 * Used in the results pane's per-scenario *Open in File Browser*
 * button (scenario workflow §11), in workspace status-bar
 * right-click menus, and anywhere else the user expects a "show
 * this folder" shortcut.
 *
 * Accepts a directory path directly; pass `<workspace>/output/
 * <runId>/<scenarioName>/` from the caller's context.  The
 * action does not validate that the path exists — the file
 * manager will report a missing-directory error itself, which
 * is more informative than a silent no-op.  Callers wanting an
 * existence check should perform it before showing the action.
 *
 * @param directoryPath the directory to reveal.
 * @param desktopOpener desktop integration; inject a fake in tests.
 * @param onUnavailable invoked when desktop integration fails.
 * @param title display label.
 */
class OpenInFileBrowserAction(
    private val directoryPath: Path,
    private val desktopOpener: DesktopOpener = DefaultDesktopOpener,
    private val onUnavailable: (Path) -> Unit = {},
    title: String = "Open in File Browser"
) : AbstractAction(title) {

    override fun actionPerformed(e: ActionEvent?) {
        if (!desktopOpener.open(directoryPath.toFile())) onUnavailable(directoryPath)
    }
}
