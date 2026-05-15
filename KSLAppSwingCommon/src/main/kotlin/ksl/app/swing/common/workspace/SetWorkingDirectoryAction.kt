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

package ksl.app.swing.common.workspace

import ksl.app.settings.UserSettingsStore
import java.awt.Component
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.JFileChooser
import javax.swing.filechooser.FileSystemView

/**
 * Menu action that opens a directory picker and, on selection, writes
 * the chosen path through [UserSettingsStore.setCurrentDirectory].  No
 * confirmation dialog — picking a directory in the chooser commits the
 * change.
 *
 * The folder picker is injected as [chooser] so the action stays
 * testable headless: production wiring uses the default
 * `JFileChooser`-backed picker; tests pass a fake that returns a fixed
 * `Path?`.
 *
 * @param store target of `setCurrentDirectory`.
 * @param parentSupplier supplies the parent component for the file
 *   chooser (so the dialog centres on the active frame).  Default is
 *   `{ null }`, which lets the chooser pick a default parent.
 * @param chooser the folder-picker function.  Returns the chosen path
 *   or null when the user cancels.
 */
class SetWorkingDirectoryAction(
    private val store: UserSettingsStore,
    private val parentSupplier: () -> Component? = { null },
    private val chooser: (Component?, Path) -> Path? = ::defaultFolderChooser
) : AbstractAction("Set Working Directory…") {

    override fun actionPerformed(e: ActionEvent?) {
        val parent = parentSupplier()
        val start = store.activeWorkspace()
        val selected = chooser(parent, start) ?: return
        store.setCurrentDirectory(selected)
    }

    companion object {
        /**
         * Default folder-picker implementation backed by [JFileChooser]
         * in directories-only mode.
         */
        fun defaultFolderChooser(parent: Component?, start: Path): Path? {
            val chooser = JFileChooser(start.toFile(), FileSystemView.getFileSystemView()).apply {
                dialogTitle = "Set Working Directory"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
            return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.toPath()
            } else null
        }
    }
}
