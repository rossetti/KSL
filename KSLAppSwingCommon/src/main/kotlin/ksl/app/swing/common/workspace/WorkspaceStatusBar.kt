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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ksl.app.settings.UserSettingsStore
import ksl.app.settings.WorkspaceLayout
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingConstants

private val logger = KotlinLogging.logger {}

/**
 * Single-line status bar showing the active workspace path, with
 * click-to-reveal and right-click context menu (Reveal / Copy Path /
 * Set Working Directory…).
 *
 * Subscribes to [store]'s flow so any in-app change to the workspace
 * updates the label live.  Flow collection runs on the supplied [scope]
 * (which should be EDT-confined — typically
 * `CoroutineScope(SupervisorJob() + Dispatchers.Swing)` owned by the
 * frame); the bar reschedules to [Dispatchers.Swing] internally for
 * safety so callers can pass any scope.
 *
 * Path display falls back to `WorkspaceLayout.abbreviate(path, maxLen)`
 * when the full string exceeds [maxLen] characters.
 *
 * @param store the user-settings store the bar observes.
 * @param scope coroutine scope owning the flow subscription.  Cancel
 *   the scope to detach the bar from the store.
 * @param onSetWorkingDirectory action invoked when the user picks
 *   *Set Working Directory…* from the context menu.  Typically wired
 *   to a `SetWorkingDirectoryAction`; the bar does not own the file
 *   picker.
 * @param maxLen length threshold above which the displayed path is
 *   abbreviated.
 */
class WorkspaceStatusBar(
    private val store: UserSettingsStore,
    scope: CoroutineScope,
    private val onSetWorkingDirectory: () -> Unit = {},
    private val maxLen: Int = 40
) : JLabel("", SwingConstants.LEFT) {

    init {
        border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
        toolTipText = "Click to open in file manager; right-click for more"
        addMouseListener(BarMouse())

        scope.launch(Dispatchers.Swing) {
            store.settings
                .map { it.workspace.currentDirectory }
                .distinctUntilChanged()
                .onEach { refresh() }
                .collect { /* no-op terminal */ }
        }
        refresh()
    }

    /** Current displayed workspace path (the same path the click would open). */
    fun currentDisplayedWorkspace(): Path = store.activeWorkspace()

    private fun refresh() {
        val path = store.activeWorkspace()
        text = WorkspaceLayout.abbreviate(path, maxLen)
        toolTipText = path.toString()
    }

    private fun openInFileManager() {
        val path = store.activeWorkspace()
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile())
            } else {
                logger.warn { "Desktop.open unsupported on this platform; cannot reveal $path." }
            }
        } catch (t: Throwable) {
            logger.warn(t) { "Could not open $path in the OS file manager." }
        }
    }

    private fun copyPathToClipboard() {
        val text = store.activeWorkspace().toString()
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        } catch (t: Throwable) {
            logger.warn(t) { "Could not copy workspace path to the system clipboard." }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val menu = JPopupMenu()
        menu.add(JMenuItem("Reveal in File Manager").apply { addActionListener { openInFileManager() } })
        menu.add(JMenuItem("Copy Path").apply { addActionListener { copyPathToClipboard() } })
        menu.addSeparator()
        menu.add(JMenuItem("Set Working Directory…").apply { addActionListener { onSetWorkingDirectory() } })
        menu.show(e.component, e.x, e.y)
    }

    private inner class BarMouse : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                showContextMenu(e)
            } else if (e.button == MouseEvent.BUTTON1 && e.clickCount == 1) {
                openInFileManager()
            }
        }
        override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
        override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
    }
}
