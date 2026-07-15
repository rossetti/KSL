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

package ksl.server.mcp

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
 * by **merging** a `ksl` entry into each agent's own config (preserving everything
 * else), and `--remove`s it cleanly. Any agent not handled here still works via
 * the universal snippet.
 *
 * What the client is told to run comes from [launchSpec]: the suite's wrapper when we
 * were started by one, else `java -jar`.  Where it is `java`, the **absolute** path to
 * the running JVM's `java` (`java.home/bin/java`) is used rather than bare `java`,
 * because GUI-launched agents (notably macOS) often don't inherit the shell `PATH`.
 *
 * Supported: **Claude Desktop** (`claude_desktop_config.json`, `mcpServers` JSON —
 * also Cursor / Claude Code) and **Codex** (`~/.codex/config.toml`,
 * `[mcp_servers.ksl]`). The merge/remove functions are pure and unit-tested; a
 * file is written only when the agent is detected, the original is backed up once,
 * and an unparseable config is never clobbered.
 */
internal object AgentSetup {

    private val adapters = listOf(ClaudeDesktopAdapter, CodexAdapter)

    fun configureDetected(jarPath: String): List<SetupResult> {
        val spec = launchSpec(jarPath)
        return adapters.mapNotNull { it.configure(spec) }
    }

    fun removeDetected(): List<SetupResult> = adapters.mapNotNull { it.remove() }

    fun universalSnippet(jarPath: String): String {
        val spec = launchSpec(jarPath)
        val ksl = buildJsonObject {
            put("command", spec.command)
            putJsonArray("args") { spec.args.forEach { add(it) } }
        }
        return "\"ksl\": " + prettyJson.encodeToString(JsonObject.serializer(), ksl)
    }
}

/** How a client should start this server: an executable, plus the arguments to pass it. */
internal data class LaunchSpec(val command: String, val args: List<String>)

/** System property naming the wrapper that launched us; set by the suite's generated launcher. */
internal const val LAUNCHER_PROPERTY: String = "ksl.mcp.launcher"

/**
 * What to tell an agent to run.
 *
 * When the installed suite's wrapper started us it passes `-Dksl.mcp.launcher` naming
 * itself, and that wrapper is what the client must run — **not** `java -jar`. The installed
 * `ksl-mcp.jar` is *thin*: no `Main-Class`, and its dependencies live in the suite's shared
 * `lib/`, so `java -jar ksl-mcp.jar` fails outright with "no main manifest attribute". Only
 * the wrapper knows how to assemble the classpath.
 *
 * Otherwise fall back to `java -jar <jar> --stdio`, which is exactly right for a
 * self-contained fat jar and for a development run.
 */
internal fun launchSpec(jarPath: String): LaunchSpec {
    val wrapper = System.getProperty(LAUNCHER_PROPERTY)?.takeIf { it.isNotBlank() }
    return if (wrapper != null && File(wrapper).isFile) LaunchSpec(wrapper, listOf("--stdio"))
    else LaunchSpec(javaCommand(), listOf("-jar", jarPath, "--stdio"))
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

/** Add/replace `mcpServers.ksl` in Claude Desktop JSON, preserving the rest. Throws on unparseable input. */
internal fun mergeClaudeJson(existing: String?, spec: LaunchSpec): String {
    val root: JsonObject = existing?.takeIf { it.isNotBlank() }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())
    val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
    servers["ksl"] = buildJsonObject {
        put("command", spec.command)
        putJsonArray("args") { spec.args.forEach { add(it) } }
    }
    val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(servers) }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/** Remove `mcpServers.ksl` from Claude Desktop JSON; null if there's nothing to remove. */
internal fun removeClaudeJson(existing: String?): String? {
    val root = existing?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    val servers = root["mcpServers"] as? JsonObject ?: return null
    if ("ksl" !in servers) return null
    val newServers = servers.toMutableMap().apply { remove("ksl") }
    val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(newServers) }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/** Add/replace `[mcp_servers.ksl]` in Codex TOML, preserving the rest. Literal-string paths (no escaping). */
internal fun mergeCodexToml(existing: String?, spec: LaunchSpec): String {
    val block = listOf(
        "[mcp_servers.ksl]",
        "command = '${spec.command}'",
        "args = [${spec.args.joinToString(", ") { "'$it'" }}]",
    )
    val text = existing ?: ""
    if (!Regex("(?m)^\\s*\\[mcp_servers\\.ksl]").containsMatchIn(text)) {
        return if (text.isBlank()) block.joinToString("\n") + "\n"
        else text.trimEnd() + "\n\n" + block.joinToString("\n") + "\n"
    }
    val (start, end) = kslTomlTableRange(text) ?: return text
    val lines = text.lines()
    return (lines.subList(0, start) + block + lines.subList(end, lines.size)).joinToString("\n")
}

/** Remove the `[mcp_servers.ksl]` table from Codex TOML; null if absent. */
internal fun removeCodexToml(existing: String?): String? {
    val text = existing ?: return null
    val (start, end) = kslTomlTableRange(text) ?: return null
    val lines = text.lines()
    val before = lines.subList(0, start).toMutableList()
    while (before.isNotEmpty() && before.last().isBlank()) before.removeAt(before.size - 1)
    return (before + lines.subList(end, lines.size)).joinToString("\n")
}

/** Line range [start, end) of the `[mcp_servers.ksl]` table, or null if absent. */
private fun kslTomlTableRange(text: String): Pair<Int, Int>? {
    if (!Regex("(?m)^\\s*\\[mcp_servers\\.ksl]").containsMatchIn(text)) return null
    val lines = text.lines()
    val start = lines.indexOfFirst { it.trim() == "[mcp_servers.ksl]" }
    if (start < 0) return null
    var end = lines.size
    for (i in start + 1 until lines.size) {
        if (lines[i].trimStart().startsWith("[")) { end = i; break }
    }
    return start to end
}

private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()
private fun home(): File = File(System.getProperty("user.home"))

/** One agent's config location, detection, and merge/remove strategy. */
private interface AgentAdapter {
    val name: String
    fun configFile(): File
    fun present(): Boolean
    fun addEntry(existing: String?, spec: LaunchSpec): String
    fun removeEntry(existing: String?): String?

    /** Write the merged config when the agent is installed; null when it isn't. Never throws. */
    fun configure(spec: LaunchSpec): SetupResult? {
        if (!present()) return null
        val f = configFile()
        return try {
            val existed = f.exists()
            val merged = addEntry(if (existed) f.readText() else null, spec)
            if (existed) backupOnce(f)
            f.parentFile?.mkdirs()
            f.writeText(merged)
            SetupResult(name, if (existed) "updated" else "created", f.path)
        } catch (e: Exception) {
            SetupResult(name, "left unchanged (${e.message}); use the snippet below", f.path)
        }
    }

    /** Remove the ksl entry when present; reports "no KSL entry" when there's nothing to do. */
    fun remove(): SetupResult? {
        if (!present()) return null
        val f = configFile()
        if (!f.exists()) return null
        return try {
            val updated = removeEntry(f.readText())
                ?: return SetupResult(name, "no KSL entry found (nothing to remove)", f.path)
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
    val bak = File("${f.path}.ksl-backup")
    if (!bak.exists()) runCatching { f.copyTo(bak) }
}

private object ClaudeDesktopAdapter : AgentAdapter {
    override val name = "Claude Desktop"
    private fun dir(): File = when {
        osName().contains("mac") -> File(home(), "Library/Application Support/Claude")
        osName().contains("win") -> File(System.getenv("APPDATA") ?: home().path, "Claude")
        else -> File(home(), ".config/Claude") // no official Linux app; best-effort
    }
    override fun configFile() = File(dir(), "claude_desktop_config.json")
    override fun present() = dir().isDirectory
    override fun addEntry(existing: String?, spec: LaunchSpec) = mergeClaudeJson(existing, spec)
    override fun removeEntry(existing: String?) = removeClaudeJson(existing)
}

private object CodexAdapter : AgentAdapter {
    override val name = "Codex"
    private fun dir() = File(home(), ".codex")
    override fun configFile() = File(dir(), "config.toml")
    override fun present() = dir().isDirectory
    override fun addEntry(existing: String?, spec: LaunchSpec) = mergeCodexToml(existing, spec)
    override fun removeEntry(existing: String?) = removeCodexToml(existing)
}
