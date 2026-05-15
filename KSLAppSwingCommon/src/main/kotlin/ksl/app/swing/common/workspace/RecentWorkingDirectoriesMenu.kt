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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import ksl.app.settings.UserSettingsStore
import java.nio.file.Path
import kotlin.io.path.Path as Path_
import javax.swing.JMenu
import javax.swing.JMenuItem

/**
 * Dynamically-populated *File → Recent Working Directories ▶* submenu.
 * Items are rebuilt whenever the store's recent list changes; clicking
 * an item calls [UserSettingsStore.setCurrentDirectory].
 *
 * Subscription lifecycle is owned by [scope]; cancelling the scope
 * detaches the menu from the store.
 *
 * @param store source of the recent-directory list.
 * @param scope coroutine scope owning the flow subscription.
 * @param emptyLabel placeholder item text when the list is empty.
 */
class RecentWorkingDirectoriesMenu(
    private val store: UserSettingsStore,
    scope: CoroutineScope,
    title: String = "Recent Working Directories",
    private val emptyLabel: String = "(no recent directories)"
) : JMenu(title) {

    init {
        scope.launch(Dispatchers.Swing) {
            store.settings
                .map { it.workspace.recent.directories }
                .distinctUntilChanged()
                .onEach { rebuild(it) }
                .collect { /* no-op terminal */ }
        }
        rebuild(store.settings.value.workspace.recent.directories)
    }

    private fun rebuild(directories: List<String>) {
        removeAll()
        if (directories.isEmpty()) {
            add(JMenuItem(emptyLabel).apply { isEnabled = false })
            return
        }
        for (directory in directories) {
            val path: Path = Path_(directory)
            add(JMenuItem(directory).apply {
                addActionListener { store.setCurrentDirectory(path) }
            })
        }
    }
}
