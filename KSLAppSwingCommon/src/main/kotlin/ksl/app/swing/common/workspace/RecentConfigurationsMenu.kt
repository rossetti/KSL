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
import kotlin.io.path.exists
import javax.swing.JMenu
import javax.swing.JMenuItem

/**
 * Dynamically-populated *File → Recent Configurations ▶* submenu.
 * Items are rebuilt whenever the store's recent-configurations list
 * changes; clicking an item delegates to [onSelect] with the chosen
 * file path.  The host frame is expected to route through its existing
 * Open Configuration handler (e.g. read the TOML, call
 * `controller.loadConfiguration`, mark saved, surface notifications).
 *
 * Entries whose file no longer exists on disk are silently skipped in
 * the rendered menu but stay in the persisted list — they may reappear
 * if the file is restored.  An empty rendered menu shows
 * [emptyLabel] as a disabled placeholder.
 *
 * Subscription lifecycle is owned by [scope]; cancelling the scope
 * detaches the menu from the store.  Mirrors
 * [RecentWorkingDirectoriesMenu]'s structure exactly — only the source
 * field (configurations vs workspace.recent) and the click action
 * differ.
 *
 * @param store    source of the recent-configurations list.
 * @param scope    coroutine scope owning the flow subscription.
 * @param onSelect callback invoked with the chosen file path when the
 *                 user clicks a menu item.
 * @param title    menu title; defaults to "Recent Configurations".
 * @param emptyLabel placeholder item text when the list is empty
 *                   (or every entry refers to a missing file).
 */
class RecentConfigurationsMenu(
    private val store: UserSettingsStore,
    scope: CoroutineScope,
    private val onSelect: (Path) -> Unit,
    title: String = "Recent Configurations",
    private val emptyLabel: String = "(no recent configurations)"
) : JMenu(title) {

    init {
        scope.launch(Dispatchers.Swing) {
            store.settings
                .map { it.configurations.files }
                .distinctUntilChanged()
                .onEach { rebuild(it) }
                .collect { /* no-op terminal */ }
        }
        rebuild(store.settings.value.configurations.files)
    }

    private fun rebuild(files: List<String>) {
        removeAll()
        // Filter for files that actually exist on disk.  Missing
        // entries stay persisted (they may come back) but aren't
        // surfaced in the rendered menu — clicking a path that no
        // longer exists would just produce an error notification.
        val existing = files.map { Path_(it) }.filter { it.exists() }
        if (existing.isEmpty()) {
            add(JMenuItem(emptyLabel).apply { isEnabled = false })
            return
        }
        for (path in existing) {
            add(JMenuItem(path.toString()).apply {
                addActionListener { onSelect(path) }
            })
        }
    }
}
