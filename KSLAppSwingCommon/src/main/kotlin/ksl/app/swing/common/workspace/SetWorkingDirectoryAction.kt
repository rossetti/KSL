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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.ActionEvent
import java.nio.file.Path
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.filechooser.FileSystemView
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

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
         * in directories-only mode.  Adds an explicit *Create Folder…*
         * accessory button so the user can author a new directory
         * regardless of the platform L&F's built-in *New Folder*
         * affordance (which is hidden on the macOS system look-and-feel
         * and may be disabled in read-only locations on others).
         */
        fun defaultFolderChooser(parent: Component?, start: Path): Path? {
            val chooser = JFileChooser(start.toFile(), FileSystemView.getFileSystemView()).apply {
                dialogTitle = "Set Working Directory"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isMultiSelectionEnabled = false
            }
            chooser.accessory = buildCreateFolderAccessory(chooser)
            return if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile?.toPath()
            } else null
        }

        /**
         * Builds the accessory panel hosting the *Create Folder…*
         * button.  The button prompts for a folder name, creates the
         * directory under the chooser's current directory, and
         * selects the new folder so the user can confirm by clicking
         * the chooser's primary action.
         */
        private fun buildCreateFolderAccessory(chooser: JFileChooser): JPanel {
            val button = JButton("Create Folder…").apply {
                toolTipText = "Create a new folder under the current directory"
                addActionListener { onCreateFolder(chooser) }
            }
            return JPanel(BorderLayout()).apply {
                border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                add(JPanel(FlowLayout(FlowLayout.CENTER)).apply { add(button) }, BorderLayout.NORTH)
            }
        }

        private fun onCreateFolder(chooser: JFileChooser) {
            val parentDir = chooser.currentDirectory ?: return
            val name = JOptionPane.showInputDialog(
                chooser,
                "Create a new folder under:\n${parentDir.absolutePath}\n\nFolder name:",
                "Create Folder",
                JOptionPane.PLAIN_MESSAGE
            )?.trim() ?: return
            if (name.isEmpty()) return
            if (name.any { it in INVALID_NAME_CHARS }) {
                JOptionPane.showMessageDialog(
                    chooser,
                    "Folder name cannot contain any of: ${INVALID_NAME_CHARS.joinToString(" ")}",
                    "Invalid folder name",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            val newDir = parentDir.toPath().resolve(name)
            if (newDir.exists()) {
                JOptionPane.showMessageDialog(
                    chooser,
                    "A file or folder named '$name' already exists in this location.",
                    "Folder already exists",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            try {
                newDir.createDirectories()
            } catch (t: Throwable) {
                JOptionPane.showMessageDialog(
                    chooser,
                    "Could not create folder:\n${t.message ?: t::class.simpleName}",
                    "Create folder failed",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }
            // Re-scan and select the new folder so the user can confirm.
            chooser.rescanCurrentDirectory()
            chooser.selectedFile = newDir.toFile()
        }

        private val INVALID_NAME_CHARS: Set<Char> = setOf(
            '/', '\\', ':', '*', '?', '"', '<', '>', '|'
        )
    }
}
