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

package ksl.agent.config

import java.io.File

/**
 * Writes/removes a single NAMED MCP-server entry in each detected coding agent's own config,
 * preserving everything else. One place, parameterized by the entry key, replacing the per-server
 * `AgentSetup` copies and the manager's `SuiteConfigurator`.
 *
 * A file is written only when the agent is detected (its config directory exists), the original is
 * backed up once, and an unparseable config is never clobbered (the pure codec throws, and the
 * writer reports "left unchanged"). Honors the `ksl.agent.config.home` system property /
 * `KSL_AGENT_CONFIG_HOME` env for a test/sandbox redirect.
 *
 * Supported: Claude Desktop (`claude_desktop_config.json`, `mcpServers` JSON — also Cursor / Claude
 * Code) and Codex (`~/.codex/config.toml`, `[mcp_servers.<key>]`).
 */
object AgentConfigurator {

    /** One agent's outcome: what was written (or left alone) and where. */
    data class ConfigResult(val agent: String, val action: String, val path: String)

    private val adapters: List<AgentAdapter> = listOf(ClaudeDesktopAdapter, CodexAdapter)

    /** Write the `<entryKey>` entry (from [spec]) into every detected agent. */
    fun configure(entryKey: String, spec: LaunchSpec): List<ConfigResult> =
        adapters.mapNotNull { it.configure(entryKey, spec) }

    /** Remove the `<entryKey>` entry from every detected agent. */
    fun remove(entryKey: String): List<ConfigResult> =
        adapters.mapNotNull { it.remove(entryKey) }
}

/** System property naming the agent-config redirect root. */
const val AGENT_CONFIG_HOME_PROPERTY: String = "ksl.agent.config.home"

internal fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
private fun home(): File = File(System.getProperty("user.home"))

/**
 * Test/sandbox redirect: when set, agent config is read and written under this root (as `Claude/`
 * and `.codex/`) instead of the real OS locations, so a throwaway install can exercise the wiring
 * without touching the student's real config. The system property is what unit tests set; the env
 * var is the shell-friendly form.
 */
private fun agentConfigRoot(): File? =
    (System.getProperty(AGENT_CONFIG_HOME_PROPERTY) ?: System.getenv("KSL_AGENT_CONFIG_HOME"))
        ?.takeIf { it.isNotBlank() }?.let { File(it) }

/**
 * Where Claude Desktop reads its config on Windows. The Microsoft Store build is an MSIX package, so
 * `%APPDATA%\Claude` is per-package virtualized and, to an ordinary process, lives under the package
 * store at `%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude`. Prefer the packaged
 * location when a Claude package is present, else fall back to the classic `%APPDATA%\Claude`.
 */
private fun windowsClaudeConfigDir(): File {
    System.getenv("LOCALAPPDATA")?.let { localAppData ->
        File(localAppData, "Packages")
            .listFiles { f -> f.isDirectory && f.name.contains("Claude", ignoreCase = true) }
            ?.map { File(it, "LocalCache/Roaming/Claude") }
            ?.firstOrNull { it.isDirectory }
            ?.let { return it }
    }
    return File(System.getenv("APPDATA") ?: home().path, "Claude")
}

/** Back up the original once (never overwrite an existing backup → the first original is preserved). */
private fun backupOnce(f: File) {
    val bak = File("${f.path}.ksl-agent-backup")
    if (!bak.exists()) runCatching { f.copyTo(bak) }
}

/** One agent's config location, detection, and merge/remove strategy. */
private interface AgentAdapter {
    val name: String
    fun configFile(): File
    fun present(): Boolean
    fun addEntry(existing: String?, entryKey: String, spec: LaunchSpec): String
    fun removeEntry(existing: String?, entryKey: String): String?

    /** Write the merged config when the agent is installed; null when it isn't. Never throws. */
    fun configure(entryKey: String, spec: LaunchSpec): AgentConfigurator.ConfigResult? {
        if (!present()) return null
        val f = configFile()
        return try {
            val existed = f.exists()
            val merged = addEntry(if (existed) f.readText() else null, entryKey, spec)
            if (existed) backupOnce(f)
            f.parentFile?.mkdirs()
            f.writeText(merged)
            AgentConfigurator.ConfigResult(name, if (existed) "updated" else "created", f.path)
        } catch (e: Exception) {
            AgentConfigurator.ConfigResult(name, "left unchanged (${e.message})", f.path)
        }
    }

    /** Remove the entry when present; reports "no KSL entry" when there is nothing to do. */
    fun remove(entryKey: String): AgentConfigurator.ConfigResult? {
        if (!present()) return null
        val f = configFile()
        if (!f.exists()) return null
        return try {
            val updated = removeEntry(f.readText(), entryKey)
                ?: return AgentConfigurator.ConfigResult(name, "no KSL entry found (nothing to remove)", f.path)
            backupOnce(f)
            f.writeText(updated)
            AgentConfigurator.ConfigResult(name, "removed", f.path)
        } catch (e: Exception) {
            AgentConfigurator.ConfigResult(name, "left unchanged (${e.message})", f.path)
        }
    }
}

private object ClaudeDesktopAdapter : AgentAdapter {
    override val name = "Claude Desktop"
    private fun dir(): File = agentConfigRoot()?.let { File(it, "Claude") } ?: when {
        osName().contains("mac") -> File(home(), "Library/Application Support/Claude")
        osName().contains("win") -> windowsClaudeConfigDir()
        else -> File(home(), ".config/Claude") // no official Linux app; best-effort
    }

    override fun configFile() = File(dir(), "claude_desktop_config.json")
    override fun present() = dir().isDirectory
    override fun addEntry(existing: String?, entryKey: String, spec: LaunchSpec) =
        AgentConfigCodec.mergeClaudeJson(existing, entryKey, shellWrapForWindows(spec))
    override fun removeEntry(existing: String?, entryKey: String) =
        AgentConfigCodec.removeClaudeJson(existing, entryKey)
}

private object CodexAdapter : AgentAdapter {
    override val name = "Codex"
    private fun dir(): File = agentConfigRoot()?.let { File(it, ".codex") } ?: File(home(), ".codex")

    override fun configFile() = File(dir(), "config.toml")
    override fun present() = dir().isDirectory
    override fun addEntry(existing: String?, entryKey: String, spec: LaunchSpec) =
        AgentConfigCodec.mergeCodexToml(existing, entryKey, spec)
    override fun removeEntry(existing: String?, entryKey: String) =
        AgentConfigCodec.removeCodexToml(existing, entryKey)
}
