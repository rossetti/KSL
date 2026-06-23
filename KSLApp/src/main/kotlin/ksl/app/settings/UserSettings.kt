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

package ksl.app.settings

import kotlinx.serialization.Serializable

/**
 * Typed view of `~/.ksl/settings.toml` — the per-user persistence file shared
 * by every Phase-6-era KSL app.  Scope is the OS user; one file per
 * `$HOME`, hand-editable.
 *
 * The file is loaded eagerly at `UserSettingsStore` construction and written
 * lazily as the user makes changes.  Unwritable parent directories degrade
 * to in-memory defaults — see `UserSettingsStore` for the resilience rules.
 *
 * @property workspace working-directory state shared by every app.
 */
@Serializable
data class UserSettings(
    val workspace: WorkspaceSettings = WorkspaceSettings(),
    val configurations: ConfigurationRecent = ConfigurationRecent()
)

/**
 * Working-directory state.  One `currentDirectory` shared by all four apps
 * per scenario workflow §2 (per-user, not per-app).
 *
 * @property currentDirectory absolute path of the active workspace, or
 *   `null` when the user has not chosen one yet (fall back to `$HOME`).
 *   Stored as a string so the TOML serialization stays portable.
 * @property recent recent-workspaces list, most-recent first, capped at 8.
 */
@Serializable
data class WorkspaceSettings(
    val currentDirectory: String? = null,
    val recent: WorkspaceRecent = WorkspaceRecent()
)

/**
 * Recent-workspaces list.  Capped at `UserSettingsStore.RECENT_LIMIT` (8)
 * by the store's mutation methods; deserializing a file with more than the
 * limit is permitted but the next mutation will truncate.
 *
 * @property directories absolute paths, most-recent first, no duplicates.
 */
@Serializable
data class WorkspaceRecent(
    val directories: List<String> = emptyList()
)

/**
 * Recent-configurations list.  Tracks the last few TOML configuration
 * files the analyst saved or loaded, surfaced as a *Recent
 * Configurations* submenu in the per-app File menu so the user can
 * reload a recent document without navigating the file chooser.
 *
 * Capped at `UserSettingsStore.RECENT_LIMIT` by the store's mutation
 * methods.  Entries that no longer exist on disk are filtered out of
 * the menu's display but stay in the persisted list — they may reappear
 * if the file is restored.  Deserializing a file with more than the
 * limit is permitted; the next mutation truncates.
 *
 * @property files absolute paths to TOML files, most-recent first, no duplicates.
 */
@Serializable
data class ConfigurationRecent(
    val files: List<String> = emptyList()
)
