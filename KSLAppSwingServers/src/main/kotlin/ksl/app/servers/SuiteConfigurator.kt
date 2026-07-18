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

package ksl.app.servers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/**
 * Writes/removes the ONE aggregated-suite entry (`ksl-suite`) in a coding agent's config, so a
 * student configures a single server for all three tool surfaces. Both Claude Desktop and Codex get
 * the same stdio entry that launches the thin `ksl-bridge`, which forwards to the long-running
 * KSLMcpSuite over HTTP. (Direct-Codex-over-Streamable-HTTP is a later refinement; until then both
 * clients use the bridge.)
 *
 * Honors the `ksl.agent.config.home` system property / `KSL_AGENT_CONFIG_HOME` env for a test/sandbox
 * redirect, exactly like the servers' setup path. Adapted from the servers' `AgentSetup`; Phase 6
 * unifies the two into one shared config lib.
 */
object SuiteConfigurator {

    const val SUITE_KEY: String = "ksl-suite"

    data class SuiteLaunchSpec(val command: String, val args: List<String>)

    /** One agent's outcome: what was written and where (or why it was left alone). */
    data class ConfigResult(val agent: String, val action: String, val path: String)

    /** Write the `ksl-suite` entry (bridge command + `--url <suiteUrl>`) into every detected agent. */
    fun configure(bridgeCommand: String, suiteUrl: String): List<ConfigResult> {
        val spec = SuiteLaunchSpec(bridgeCommand, listOf("--url", suiteUrl))
        return listOf(
            applyClaude { existed, f -> mergeClaudeSuiteJson(if (existed) f.readText() else null, spec) },
            applyCodex { existed, f -> mergeCodexSuiteToml(if (existed) f.readText() else null, spec) },
        ).filterNotNull()
    }

    /** Remove the `ksl-suite` entry from every detected agent. */
    fun remove(): List<ConfigResult> = listOf(
        applyClaude { existed, f -> if (existed) removeClaudeSuiteJson(f.readText()) else null },
        applyCodex { existed, f -> if (existed) removeCodexSuiteToml(f.readText()) else null },
    ).filterNotNull()

    // ---- adapters (filesystem side; detection == config dir exists) ----

    private fun applyClaude(compute: (existed: Boolean, f: File) -> String?): ConfigResult? {
        val dir = claudeDir()
        if (!dir.isDirectory) return null
        val f = File(dir, "claude_desktop_config.json")
        return writeResult("Claude Desktop", f, compute)
    }

    private fun applyCodex(compute: (existed: Boolean, f: File) -> String?): ConfigResult? {
        val dir = codexDir()
        if (!dir.isDirectory) return null
        val f = File(dir, "config.toml")
        return writeResult("Codex", f, compute)
    }

    private fun writeResult(agent: String, f: File, compute: (Boolean, File) -> String?): ConfigResult {
        val existed = f.exists()
        return try {
            val updated = compute(existed, f)
                ?: return ConfigResult(agent, "no KSL suite entry (nothing to do)", f.path)
            if (existed) backupOnce(f)
            f.parentFile?.mkdirs()
            f.writeText(updated)
            ConfigResult(agent, if (existed) "updated" else "created", f.path)
        } catch (e: Exception) {
            ConfigResult(agent, "left unchanged (${e.message})", f.path)
        }
    }

    // ---- locations (honor the redirect, mirror AgentSetup) ----

    private fun agentConfigRoot(): File? =
        (System.getProperty("ksl.agent.config.home") ?: System.getenv("KSL_AGENT_CONFIG_HOME"))
            ?.takeIf { it.isNotBlank() }?.let { File(it) }

    private fun home(): File = File(System.getProperty("user.home"))
    private fun osName(): String = System.getProperty("os.name").orEmpty().lowercase()

    private fun claudeDir(): File = agentConfigRoot()?.let { File(it, "Claude") } ?: when {
        osName().contains("mac") -> File(home(), "Library/Application Support/Claude")
        osName().contains("win") -> File(System.getenv("APPDATA") ?: home().path, "Claude")
        else -> File(home(), ".config/Claude")
    }

    private fun codexDir(): File = agentConfigRoot()?.let { File(it, ".codex") } ?: File(home(), ".codex")

    private fun backupOnce(f: File) {
        val bak = File("${f.path}.ksl-suite-backup")
        if (!bak.exists()) runCatching { f.copyTo(bak) }
    }
}

// ---- pure merge / remove (unit-tested; no filesystem) ----

private val prettyJson = Json { prettyPrint = true }

/** Add/replace `mcpServers.ksl-suite` in Claude Desktop JSON, preserving the rest. */
internal fun mergeClaudeSuiteJson(existing: String?, spec: SuiteConfigurator.SuiteLaunchSpec): String {
    val root: JsonObject = existing?.takeIf { it.isNotBlank() }
        ?.let { Json.parseToJsonElement(it).jsonObject }
        ?: JsonObject(emptyMap())
    val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
    servers[SuiteConfigurator.SUITE_KEY] = buildJsonObject {
        put("command", spec.command)
        putJsonArray("args") { spec.args.forEach { add(it) } }
    }
    val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(servers) }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/** Remove `mcpServers.ksl-suite` from Claude Desktop JSON; null if there is nothing to remove. */
internal fun removeClaudeSuiteJson(existing: String?): String? {
    val root = existing?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
    val servers = root["mcpServers"] as? JsonObject ?: return null
    if (SuiteConfigurator.SUITE_KEY !in servers) return null
    val newServers = servers.toMutableMap().apply { remove(SuiteConfigurator.SUITE_KEY) }
    val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(newServers) }
    return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
}

/** Add/replace `[mcp_servers.ksl-suite]` in Codex TOML, preserving the rest. Literal-string paths. */
internal fun mergeCodexSuiteToml(existing: String?, spec: SuiteConfigurator.SuiteLaunchSpec): String {
    val block = listOf(
        "[mcp_servers.${SuiteConfigurator.SUITE_KEY}]",
        "command = '${spec.command}'",
        "args = [${spec.args.joinToString(", ") { "'$it'" }}]",
    )
    val text = existing ?: ""
    if (codexSuiteTableRange(text) == null) {
        return if (text.isBlank()) block.joinToString("\n") + "\n"
        else text.trimEnd() + "\n\n" + block.joinToString("\n") + "\n"
    }
    val (start, end) = codexSuiteTableRange(text)!!
    val lines = text.lines()
    return (lines.subList(0, start) + block + lines.subList(end, lines.size)).joinToString("\n")
}

/** Remove the `[mcp_servers.ksl-suite]` table from Codex TOML; null if absent. */
internal fun removeCodexSuiteToml(existing: String?): String? {
    val text = existing ?: return null
    val (start, end) = codexSuiteTableRange(text) ?: return null
    val lines = text.lines()
    val before = lines.subList(0, start).toMutableList()
    while (before.isNotEmpty() && before.last().isBlank()) before.removeAt(before.size - 1)
    return (before + lines.subList(end, lines.size)).joinToString("\n")
}

/** Line range [start, end) of the `[mcp_servers.ksl-suite]` table, or null if absent. */
private fun codexSuiteTableRange(text: String): Pair<Int, Int>? {
    val header = "[mcp_servers.${SuiteConfigurator.SUITE_KEY}]"
    val lines = text.lines()
    val start = lines.indexOfFirst { it.trim() == header }
    if (start < 0) return null
    var end = lines.size
    for (i in start + 1 until lines.size) {
        if (lines[i].trimStart().startsWith("[")) { end = i; break }
    }
    return start to end
}
