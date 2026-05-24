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

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import net.peanuuutz.tomlkt.Toml
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

private val logger = KotlinLogging.logger {}

/**
 * Per-user persistence for `~/.ksl/settings.toml`.
 *
 * Loads eagerly at construction so the first observer of [settings] sees
 * real state immediately.  Mutation methods write the file
 * synchronously; failures (read-only `~/.ksl/`, locked file, etc.) are
 * logged and absorbed — the in-memory flow always reflects the user's
 * latest action even when persistence is unavailable.
 *
 * Constructor injection of [settingsDir] lets tests redirect the
 * settings location to a temporary directory.  Production code uses the
 * no-arg `UserSettingsStore()` which resolves to `~/.ksl/`.
 *
 * Thread-safety: the underlying [MutableStateFlow] is safe for concurrent
 * updates from any thread; mutation methods are `@Synchronized` so the
 * file write and the flow update happen atomically.
 *
 * @param settingsDir directory containing `settings.toml`; defaults to
 *   `~/.ksl/`.
 * @param userHome the user's home directory, used to resolve the
 *   default workspace path and as the last-resort fallback for
 *   stale-path recovery.  Defaults to the JVM's `user.home` system
 *   property.
 * @param defaultWorkspaceProvider resolves the "out-of-the-box"
 *   workspace location when the user has not yet set one via
 *   [setCurrentDirectory] (and when a previously-saved path has gone
 *   stale).  Defaults to [resolveDefaultWorkspace], which prefers
 *   `<userHome>/Documents/KSLWork` and falls back to
 *   `<userHome>/KSLWork` on systems without a `Documents` folder.
 *   Tests can inject a temp-dir-based provider to stay isolated.
 */
class UserSettingsStore(
    private val settingsDir: Path = defaultSettingsDir(),
    private val userHome: Path = Path(System.getProperty("user.home")),
    private val defaultWorkspaceProvider: (Path) -> Path = ::resolveDefaultWorkspace
) {

    private val settingsFile: Path = settingsDir.resolve(SETTINGS_FILENAME)
    private val toml = Toml { ignoreUnknownKeys = true }

    private val mySettings: MutableStateFlow<UserSettings> =
        MutableStateFlow(loadOrDefault())

    /** Observable settings state.  Read-only; mutate via the public methods. */
    val settings: StateFlow<UserSettings> = mySettings.asStateFlow()

    /**
     * Resolves the active workspace path.  Returns the saved
     * `currentDirectory` when it exists on disk; otherwise resolves
     * the default workspace via [defaultWorkspaceProvider] and
     * materializes the directory if it does not yet exist (so
     * downstream consumers like file choosers always see a valid
     * starting point).  Stale saved paths are evicted on first read.
     *
     * The materialized default — typically `~/Documents/KSLWork` —
     * is *not* automatically promoted to the saved
     * `currentDirectory`; that requires an explicit
     * [setCurrentDirectory] call, normally from a user action
     * (*File ▸ Set Working Directory…* or saving a configuration
     * file into a new workspace).  Keeping the saved state empty
     * for first-run users means an upgrade that changes the default
     * location is transparent — there's no stale saved path to
     * migrate.
     */
    @Synchronized
    fun activeWorkspace(): Path {
        val current = mySettings.value.workspace.currentDirectory
        if (current != null) {
            val asPath = Path(current)
            if (asPath.exists() && asPath.isDirectory()) {
                return asPath
            }
            logger.warn { "Saved workspace '$current' no longer exists; falling back to default." }
            mySettings.update { s ->
                s.copy(
                    workspace = s.workspace.copy(
                        currentDirectory = null,
                        recent = s.workspace.recent.copy(
                            directories = s.workspace.recent.directories - current
                        )
                    )
                )
            }
            persist()
        }
        val fallback = defaultWorkspaceProvider(userHome)
        return try {
            if (!fallback.exists()) fallback.createDirectories()
            fallback
        } catch (t: Throwable) {
            logger.warn(t) { "Could not create default workspace '$fallback'; falling back to $userHome." }
            userHome
        }
    }

    /**
     * Sets the active workspace to [path] and bumps it to the front of
     * the recent-directories list (dedup, cap at [RECENT_LIMIT]).
     */
    @Synchronized
    fun setCurrentDirectory(path: Path) {
        val canonical = path.toAbsolutePath().normalize().toString()
        mySettings.update { s ->
            val newRecent = (listOf(canonical) + s.workspace.recent.directories.filter { it != canonical })
                .take(RECENT_LIMIT)
            s.copy(
                workspace = s.workspace.copy(
                    currentDirectory = canonical,
                    recent = s.workspace.recent.copy(directories = newRecent)
                )
            )
        }
        persist()
    }

    /**
     * Adds [path] to the recent-directories list without changing the
     * current directory.  Used when the user saves a file to a directory
     * other than the workspace — scenario §2 step 4 specifies the saved
     * file's parent directory becomes the new workspace, but consumers
     * may also want to record a directory as recently used without
     * promoting it.
     */
    @Synchronized
    fun addRecentDirectory(path: Path) {
        val canonical = path.toAbsolutePath().normalize().toString()
        mySettings.update { s ->
            val newRecent = (listOf(canonical) + s.workspace.recent.directories.filter { it != canonical })
                .take(RECENT_LIMIT)
            s.copy(workspace = s.workspace.copy(recent = s.workspace.recent.copy(directories = newRecent)))
        }
        persist()
    }

    /**
     * Promote [path] to the front of the recent-configurations list
     * (dedup, cap at [RECENT_LIMIT]).  Mirrors [addRecentDirectory] but
     * targets the configurations submenu rather than the workspace
     * submenu.  Called after a successful Save or Open of a TOML
     * configuration file, so reopening a recent document is one click
     * regardless of which workspace folder the user is currently in.
     */
    @Synchronized
    fun addRecentConfiguration(path: Path) {
        val canonical = path.toAbsolutePath().normalize().toString()
        mySettings.update { s ->
            val newRecent = (listOf(canonical) + s.configurations.files.filter { it != canonical })
                .take(RECENT_LIMIT)
            s.copy(configurations = s.configurations.copy(files = newRecent))
        }
        persist()
    }

    private fun loadOrDefault(): UserSettings =
        try {
            if (settingsFile.exists()) toml.decodeFromString(UserSettings.serializer(), settingsFile.readText())
            else UserSettings()
        } catch (t: Throwable) {
            logger.warn(t) { "Could not read $settingsFile; using defaults." }
            UserSettings()
        }

    private fun persist() {
        try {
            if (!settingsDir.exists()) settingsDir.createDirectories()
            val text = toml.encodeToString(UserSettings.serializer(), mySettings.value)
            settingsFile.writeText(text)
        } catch (t: Throwable) {
            logger.warn(t) { "Could not write $settingsFile; in-memory state retained." }
        }
    }

    companion object {
        const val SETTINGS_FILENAME: String = "settings.toml"
        const val RECENT_LIMIT: Int = 8

        /** Default settings directory: `~/.ksl/`. */
        fun defaultSettingsDir(): Path =
            Path(System.getProperty("user.home")).resolve(".ksl")

        /**
         * Default out-of-the-box workspace location for KSL apps.
         *
         * Preference order:
         *  1. `<userHome>/Documents/KSLWork` — when `Documents`
         *     exists.  Standard on macOS and Windows; also present
         *     on most desktop Linux distributions.
         *  2. `<userHome>/KSLWork` — fallback for systems without a
         *     `Documents` folder.
         *
         * Either path is created on demand by [activeWorkspace] when
         * it is selected.  The "KSLWork" subdirectory keeps the
         * app's `configs/` and `output/` folders contained, so KSL
         * does not pollute a user's general-purpose Documents tree.
         */
        fun resolveDefaultWorkspace(userHome: Path): Path {
            val documents = userHome.resolve("Documents")
            val base = if (documents.exists() && documents.isDirectory()) documents else userHome
            return base.resolve(WORKSPACE_FOLDER_NAME)
        }

        /** Name of the per-user workspace folder ("KSLWork"). */
        const val WORKSPACE_FOLDER_NAME: String = "KSLWork"
    }
}
