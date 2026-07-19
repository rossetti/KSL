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

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Pure, filesystem-free merge/remove of a single MCP-server entry in a coding agent's config,
 * parameterized by the entry key (e.g. "ksl-suite" or "ksl") so one implementation serves every KSL
 * server surface. This is the unit-tested core; the filesystem side (detection, backup, sandbox
 * redirect) lives in `AgentConfigurator`.
 *
 * Claude Desktop uses a JSON `mcpServers` object; Codex uses a TOML `[mcp_servers.<key>]` table with
 * literal-string paths (no escaping). Both operations preserve every other entry, and an unparseable
 * Claude config throws (the caller's writer catches and leaves the original untouched); a Codex
 * remove on absent input returns null.
 */
object AgentConfigCodec {

    private val prettyJson = Json { prettyPrint = true }

    /** Add/replace `mcpServers.<entryKey>` in Claude Desktop JSON, preserving the rest. Throws on unparseable input. */
    fun mergeClaudeJson(existing: String?, entryKey: String, spec: LaunchSpec): String {
        val root: JsonObject = existing?.takeIf { it.isNotBlank() }
            ?.let { Json.parseToJsonElement(it).jsonObject }
            ?: JsonObject(emptyMap())
        val servers = (root["mcpServers"] as? JsonObject)?.toMutableMap() ?: linkedMapOf()
        servers[entryKey] = buildJsonObject {
            put("command", spec.command)
            putJsonArray("args") { spec.args.forEach { add(it) } }
        }
        val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(servers) }
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    /** Remove `mcpServers.<entryKey>` from Claude Desktop JSON; null if there is nothing to remove. */
    fun removeClaudeJson(existing: String?, entryKey: String): String? {
        val root = existing?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return null
        val servers = root["mcpServers"] as? JsonObject ?: return null
        if (entryKey !in servers) return null
        val newServers = servers.toMutableMap().apply { remove(entryKey) }
        val merged = root.toMutableMap().apply { this["mcpServers"] = JsonObject(newServers) }
        return prettyJson.encodeToString(JsonObject.serializer(), JsonObject(merged))
    }

    /** Add/replace `[mcp_servers.<entryKey>]` in Codex TOML, preserving the rest. Literal-string paths. */
    fun mergeCodexToml(existing: String?, entryKey: String, spec: LaunchSpec): String {
        val block = listOf(
            "[mcp_servers.$entryKey]",
            "command = '${spec.command}'",
            "args = [${spec.args.joinToString(", ") { "'$it'" }}]",
        )
        val text = existing ?: ""
        if (codexTableRange(text, entryKey) == null) {
            return if (text.isBlank()) block.joinToString("\n") + "\n"
            else text.trimEnd() + "\n\n" + block.joinToString("\n") + "\n"
        }
        val (start, end) = codexTableRange(text, entryKey)!!
        val lines = text.lines()
        return (lines.subList(0, start) + block + lines.subList(end, lines.size)).joinToString("\n")
    }

    /** Remove the `[mcp_servers.<entryKey>]` table from Codex TOML; null if absent. */
    fun removeCodexToml(existing: String?, entryKey: String): String? {
        val text = existing ?: return null
        val (start, end) = codexTableRange(text, entryKey) ?: return null
        val lines = text.lines()
        val before = lines.subList(0, start).toMutableList()
        while (before.isNotEmpty() && before.last().isBlank()) before.removeAt(before.size - 1)
        return (before + lines.subList(end, lines.size)).joinToString("\n")
    }

    /** Line range [start, end) of the `[mcp_servers.<entryKey>]` table, or null if absent. */
    private fun codexTableRange(text: String, entryKey: String): Pair<Int, Int>? {
        val header = "[mcp_servers.$entryKey]"
        val lines = text.lines()
        val start = lines.indexOfFirst { it.trim() == header }
        if (start < 0) return null
        var end = lines.size
        for (i in start + 1 until lines.size) {
            if (lines[i].trimStart().startsWith("[")) { end = i; break }
        }
        return start to end
    }
}
