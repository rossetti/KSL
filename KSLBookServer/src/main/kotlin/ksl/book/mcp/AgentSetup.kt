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

package ksl.book.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** What `--setup` / `--remove` did for one detected agent. */
internal data class SetupResult(val agent: String, val action: String, val path: String)

/**
 * Auto-configures the student's coding agent(s) to launch this jar over MCP stdio,
 * by **merging** a `ksl-book` entry into each agent's own config (preserving
 * everything else — in particular the model server's `ksl` entry, so both servers
 * coexist), and `--remove`s it cleanly. Any agent not handled here still works via
 * the universal snippet.
 *
 * Ported from KSLServerMcp's AgentSetup; the entry key, jar name, and backup
 * suffix are the only intentional differences.
 *
 * The launch command uses the **absolute** path to the running JVM's `java`
 * (`java.home/bin/java`), not bare `java`, because GUI-launched agents (notably
 * macOS) often don't inherit the shell `PATH`.
 *
 * Supported: **Claude Desktop** (`claude_desktop_config.json`, `mcpServers` JSON —
 * also Cursor / Claude Code) and **Codex** (`~/.codex/config.toml`,
 * `[mcp_servers.ksl-book]`). The merge/remove functions are pure and unit-tested; a
 * file is written only when the agent is detected, the original is backed up once,
 * and an unparseable config is never clobbered.
 */
internal object AgentSetup {

    private val adapters = listOf(ClaudeDesktopAdapter, CodexAdapter)

    fun configureDetected(jarPath: String): List<SetupResult> {
        val cmd = javaCommand()
        return adapters.mapNotNull { it.configure(cmd, jarPath) }
    }

    fun removeDetected(): List<SetupResult> = adapters.mapNotNull { it.remove() }

    fun universalSnippet(jarPath: String): String {
        val entry = buildJsonObject {
            put("command", javaCommand())
            putJsonArray("args") { add("-jar"); add(jarPath); add("--stdio") }
        }
        return "\"ksl-book\": " + prettyJson.encodeToString(JsonObject.serializer(), entry)
    }
}

private val prettyJson = Json { prettyPrint = true }

/** Absolute path to the running JVM's `java` launcher (falls back to bare `java`). */
internal fun javaCommand(): String {
    val home = System.getProperty("java.home") ?: return "java"
    val exe = if (osName().contains("win")) "java.exe" else "java"
    val candidate = File(File(home, "bin"), exe)
    return if (candidate.exists()) candidate.path else exe
}

// ---- pure merge / remove (unit-tested) ----

/** Add/replace `mcpServers.ksl-book` in Claude Desktop JSON, preserving the rest. Throws on unparseable input. */
internal fun mergeClaudeJson(existing: String?, command: String, jarPath: String): String {
    val root: JsonObject = existing?.takeIf { it.isNotBlank() }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())
    val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
    servers["ksl-book"] = buildJsonObject {
        put("command", command)
        putJsonArray("args") { add("-jar"); add(jarPath); add("--stdio") }
    }
    val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(servers) }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/** Remove `mcpServers.ksl-book` from Claude Desktop JSON; null if there's nothing to remove. */
internal fun removeClaudeJson(existing: String?): String? {
    val root = existing?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    val servers = root["mcpServers"] as? JsonObject ?: return null
    if ("ksl-book" !in servers) return null
    val newServers = servers.toMutableMap().apply { remove("ksl-book") }
    val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(newServers) }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/** Add/replace `[mcp_servers.ksl-book]` in Codex TOML, preserving the rest. Literal-string paths (no escaping). */
internal fun mergeCodexToml(existing: String?, command: String, jarPath: String): String {
    val block = listOf(
        "[mcp_servers.ksl-book]",
        "command = '$command'",
        "args = ['-jar', '$jarPath', '--stdio']",
    )
    val text = existing ?: ""
    if (!Regex("(?m)^\\s*\\[mcp_servers\\.ksl-book]").containsMatchIn(text)) {
        return if (text.isBlank()) block.joinToString("\n") + "\n"
        else text.trimEnd() + "\n\n" + block.joinToString("\n") + "\n"
    }
    val (start, end) = kslBookTomlTableRange(text) ?: return text
    val lines = text.lines()
    return (lines.subList(0, start) + block + lines.subList(end, lines.size)).joinToString("\n")
}

/** Remove the `[mcp_servers.ksl-book]` table from Codex TOML; null if absent. */
internal fun removeCodexToml(existing: String?): String? {
    val text = existing ?: return null
    val (start, end) = kslBookTomlTableRange(text) ?: return null
    val lines = text.lines()
    val before = lines.subList(0, start).toMutableList()
    while (before.isNotEmpty() && before.last().isBlank()) before.removeAt(before.size - 1)
    return (before + lines.subList(end, lines.size)).joinToString("\n")
}

/** Line range [start, end) of the `[mcp_servers.ksl-book]` table, or null if absent. */
private fun kslBookTomlTableRange(text: String): Pair<Int, Int>? {
    if (!Regex("(?m)^\\s*\\[mcp_servers\\.ksl-book]").containsMatchIn(text)) return null
    val lines = text.lines()
    val start = lines.indexOfFirst { it.trim() == "[mcp_servers.ksl-book]" }
    if (start < 0) return null
    var end = lines.size
    for (i in start + 1 until lines.size) {
        if (lines[i].trimStart().startsWith("[")) { end = i; break }
    }
    return start to end
}

private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
private fun home(): File = File(System.getProperty("user.home"))

/** System property naming the agent-config redirect root; see `agentConfigRoot`. */
internal const val AGENT_CONFIG_HOME_PROPERTY: String = "ksl.agent.config.home"

/**
 * Test/sandbox redirect: when set, agent config is read and written under this root instead of
 * the real OS locations, so a throwaway install can exercise the wiring without touching the
 * student's real Claude/Codex config. Unset in a normal install, which then behaves exactly as
 * before. The system property is what unit tests set; the env var is the shell-friendly form,
 * mirroring KSL_HOME / KSL_STARTMENU.
 *
 * Only the config ROOT moves. Detection is still "does the agent's config directory exist?", so
 * a caller that wants an agent detected under the redirect creates that directory -- which keeps
 * the "agent absent -> skip" path testable.
 */
private fun agentConfigRoot(): File? =
    (System.getProperty(AGENT_CONFIG_HOME_PROPERTY) ?: System.getenv("KSL_AGENT_CONFIG_HOME"))
        ?.takeIf { it.isNotBlank() }?.let { File(it) }

/**
 * Where Claude Desktop reads its config on Windows. The Microsoft Store build is an MSIX
 * package, so the app's `%APPDATA%\Claude` is per-package virtualized and, to an ordinary
 * process, lives under the package store at
 * `%LOCALAPPDATA%\Packages\Claude_*\LocalCache\Roaming\Claude`. Writing the plain
 * `%APPDATA%\Claude` misses it. Prefer the packaged location when a Claude package is
 * present, else fall back to the classic (non-Store installer) `%APPDATA%\Claude`.
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

/** One agent's config location, detection, and merge/remove strategy. */
private interface AgentAdapter {
    val name: String
    fun configFile(): File
    fun present(): Boolean
    fun addEntry(existing: String?, command: String, jarPath: String): String
    fun removeEntry(existing: String?): String?

    /** Write the merged config when the agent is installed; null when it isn't. Never throws. */
    fun configure(command: String, jarPath: String): SetupResult? {
        if (!present()) return null
        val f = configFile()
        return try {
            val existed = f.exists()
            val merged = addEntry(if (existed) f.readText() else null, command, jarPath)
            if (existed) backupOnce(f)
            f.parentFile?.mkdirs()
            f.writeText(merged)
            SetupResult(name, if (existed) "updated" else "created", f.path)
        } catch (e: Exception) {
            SetupResult(name, "left unchanged (${e.message}); use the snippet below", f.path)
        }
    }

    /** Remove the ksl-book entry when present; reports "no entry" when there's nothing to do. */
    fun remove(): SetupResult? {
        if (!present()) return null
        val f = configFile()
        if (!f.exists()) return null
        return try {
            val updated = removeEntry(f.readText())
                ?: return SetupResult(name, "no KSL book entry found (nothing to remove)", f.path)
            backupOnce(f)
            f.writeText(updated)
            SetupResult(name, "removed", f.path)
        } catch (e: Exception) {
            SetupResult(name, "left unchanged (${e.message})", f.path)
        }
    }
}

/** Back up the original once (never overwrite an existing backup → the first original is preserved). */
private fun backupOnce(f: File) {
    val bak = File("${f.path}.ksl-book-backup")
    if (!bak.exists()) runCatching { f.copyTo(bak) }
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
    override fun addEntry(existing: String?, command: String, jarPath: String) =
        mergeClaudeJson(existing, command, jarPath)
    override fun removeEntry(existing: String?) = removeClaudeJson(existing)
}

private object CodexAdapter : AgentAdapter {
    override val name = "Codex"
    private fun dir(): File = agentConfigRoot()?.let { File(it, ".codex") } ?: File(home(), ".codex")
    override fun configFile() = File(dir(), "config.toml")
    override fun present() = dir().isDirectory
    override fun addEntry(existing: String?, command: String, jarPath: String) =
        mergeCodexToml(existing, command, jarPath)
    override fun removeEntry(existing: String?) = removeCodexToml(existing)
}
