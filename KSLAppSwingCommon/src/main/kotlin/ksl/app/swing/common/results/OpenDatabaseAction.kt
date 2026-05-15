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
 * Reveals a `KSLDatabase` file by opening its containing directory
 * in the OS file manager.  Per scenario workflow §11 OQ 2, v1
 * surfaces the `.db` file via the file manager rather than
 * embedding an in-app database browser (deferred to Phase 6E).
 *
 * Implemented as `Desktop.open(file.parentFile)` rather than
 * `Desktop.browseFileDirectory(file)` because `browseFileDirectory`
 * isn't universally supported across platforms.  The parent
 * directory open is a reliable lowest-common-denominator that
 * gets the user close enough to the file to act on it.
 *
 * @param dbPath the `.db` file to reveal.
 * @param desktopOpener desktop integration; inject a fake in tests.
 * @param onUnavailable invoked when desktop integration fails or
 *   is unsupported; the argument is the parent directory whose
 *   open was attempted.
 * @param title display label.
 */
class OpenDatabaseAction(
    private val dbPath: Path,
    private val desktopOpener: DesktopOpener = DefaultDesktopOpener,
    private val onUnavailable: (Path) -> Unit = {},
    title: String = "Open Database"
) : AbstractAction(title) {

    override fun actionPerformed(e: ActionEvent?) {
        val parent: Path = dbPath.toAbsolutePath().parent
            ?: run { onUnavailable(dbPath); return }
        if (!desktopOpener.open(parent.toFile())) onUnavailable(parent)
    }
}
