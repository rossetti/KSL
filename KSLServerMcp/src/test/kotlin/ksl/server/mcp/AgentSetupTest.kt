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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val JAR = "/opt/ksl/ksl-mcp.jar"
private const val JAVA = "/jdk/bin/java"

/** The `java -jar` form: what a self-contained fat jar / dev run is told to use. */
private val JAR_SPEC = LaunchSpec(JAVA, listOf("-jar", JAR, "--stdio"))

/** The pure agent-config merge / remove logic: add the ksl entry, preserve everything else. */
class AgentSetupTest {

    // ---- what the client is told to run ----

    @Test
    @DisplayName("launchSpec uses java -jar when no wrapper launched us")
    fun launchSpecFallsBackToJavaJar() {
        System.clearProperty(LAUNCHER_PROPERTY)
        val spec = launchSpec(JAR)
        assertEquals(listOf("-jar", JAR, "--stdio"), spec.args)
        assertTrue(
            spec.command.endsWith("java") || spec.command.endsWith("java.exe"),
            "expected a java launcher, got ${spec.command}"
        )
    }

    // The installed ksl-mcp.jar is THIN: no Main-Class, dependencies in the suite's shared
    // lib/. A `java -jar` config cannot start it ("no main manifest attribute"), so when the
    // suite's wrapper launched us the client must be pointed at the wrapper instead.
    @Test
    @DisplayName("launchSpec points the client at the wrapper that launched us")
    fun launchSpecPrefersTheWrapper() {
        val wrapper = File.createTempFile("ksl-mcp", "").apply { deleteOnExit() }
        try {
            System.setProperty(LAUNCHER_PROPERTY, wrapper.path)
            val spec = launchSpec(JAR)
            assertEquals(wrapper.path, spec.command, "the wrapper is the command")
            assertEquals(listOf("--stdio"), spec.args, "the wrapper needs no -jar")
        } finally {
            System.clearProperty(LAUNCHER_PROPERTY)
        }
    }

    @Test
    @DisplayName("launchSpec ignores a stale wrapper path that no longer exists")
    fun launchSpecIgnoresMissingWrapper() {
        try {
            System.setProperty(LAUNCHER_PROPERTY, "/nonexistent/ksl-mcp")
            assertEquals(listOf("-jar", JAR, "--stdio"), launchSpec(JAR).args)
        } finally {
            System.clearProperty(LAUNCHER_PROPERTY)
        }
    }

    // ---- Claude Desktop (JSON mcpServers) ----

    @Test
    fun `claude merge creates the ksl server when no config exists`() {
        val out = Json.parseToJsonElement(mergeClaudeJson(null, JAR_SPEC)).jsonObject
        val ksl = out["mcpServers"]!!.jsonObject["ksl"]!!.jsonObject
        assertEquals(JAVA, ksl["command"]!!.jsonPrimitive.content)
        val args = ksl["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("-jar", JAR, "--stdio"), args)
    }

    @Test
    fun `claude merge preserves other servers and top-level keys`() {
        val existing = """{"globalShortcut":"X","mcpServers":{"other":{"command":"node"}}}"""
        val out = Json.parseToJsonElement(mergeClaudeJson(existing, JAR_SPEC)).jsonObject
        assertEquals("X", out["globalShortcut"]!!.jsonPrimitive.content)
        val servers = out["mcpServers"]!!.jsonObject
        assertTrue("other" in servers, "the existing server must be kept")
        assertTrue("ksl" in servers, "the ksl server must be added")
    }

    @Test
    fun `claude merge overwrites a stale ksl entry`() {
        val existing = """{"mcpServers":{"ksl":{"command":"java","args":["-jar","/old.jar","--stdio"]}}}"""
        val out = Json.parseToJsonElement(mergeClaudeJson(existing, JAR_SPEC)).jsonObject
        val ksl = out["mcpServers"]!!.jsonObject["ksl"]!!.jsonObject
        assertEquals(JAVA, ksl["command"]!!.jsonPrimitive.content, "the absolute java path is written")
        val args = ksl["args"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("-jar", JAR, "--stdio"), args, "the path must be updated")
    }

    @Test
    fun `claude merge refuses to clobber an unparseable file`() {
        assertFailsWith<Exception> { mergeClaudeJson("{ this is not json", JAR_SPEC) }
    }

    @Test
    fun `claude remove deletes only the ksl server`() {
        val existing = """{"mcpServers":{"other":{"command":"node"},"ksl":{"command":"java"}}}"""
        val out = Json.parseToJsonElement(removeClaudeJson(existing)!!).jsonObject
        val servers = out["mcpServers"]!!.jsonObject
        assertTrue("ksl" !in servers, "ksl is removed")
        assertTrue("other" in servers, "other servers are kept")
    }

    @Test
    fun `claude remove returns null when there is nothing to remove`() {
        assertNull(removeClaudeJson("""{"mcpServers":{"other":{}}}"""))
        assertNull(removeClaudeJson(null))
    }

    // ---- Codex (TOML [mcp_servers.ksl]) ----

    @Test
    fun `codex merge creates the table when no config exists`() {
        val out = mergeCodexToml(null, JAR_SPEC)
        assertTrue("[mcp_servers.ksl]" in out)
        assertTrue("command = '$JAVA'" in out, "absolute java path, TOML literal string")
        assertTrue("'$JAR'" in out)
    }

    @Test
    fun `codex merge appends preserving other content`() {
        val existing = "model = 'gpt'\n\n[mcp_servers.other]\ncommand = 'node'\n"
        val out = mergeCodexToml(existing, JAR_SPEC)
        assertTrue("model = 'gpt'" in out, "existing top-level keys kept")
        assertTrue("[mcp_servers.other]" in out, "existing server kept")
        assertTrue("[mcp_servers.ksl]" in out, "ksl appended")
    }

    @Test
    fun `codex merge replaces an existing ksl table and is idempotent`() {
        val existing = "[mcp_servers.ksl]\ncommand = 'java'\nargs = ['-jar', '/old.jar', '--stdio']\n\n[mcp_servers.keep]\ncommand = 'x'\n"
        val once = mergeCodexToml(existing, JAR_SPEC)
        assertTrue("'$JAR'" in once && "/old.jar" !in once, "the stale path is replaced")
        assertTrue("[mcp_servers.keep]" in once, "the trailing table is preserved")
        assertEquals(once.count { it == '\n' }, mergeCodexToml(once, JAR_SPEC).count { it == '\n' }, "running twice is stable")
        assertEquals(1, Regex("\\[mcp_servers\\.ksl]").findAll(once).count(), "exactly one ksl table")
    }

    @Test
    fun `codex remove deletes the ksl table and keeps the rest`() {
        val existing = "model = 'gpt'\n\n[mcp_servers.ksl]\ncommand = 'java'\nargs = []\n\n[mcp_servers.keep]\ncommand = 'x'\n"
        val out = removeCodexToml(existing)!!
        assertTrue("[mcp_servers.ksl]" !in out, "ksl table removed")
        assertTrue("model = 'gpt'" in out && "[mcp_servers.keep]" in out, "the rest is preserved")
    }

    @Test
    fun `codex remove returns null when there is nothing to remove`() {
        assertNull(removeCodexToml("model = 'gpt'\n"))
        assertNull(removeCodexToml(null))
    }

    // ---- launch command ----

    @Test
    fun `javaCommand is an absolute java path or a bare fallback`() {
        val cmd = javaCommand()
        assertTrue(cmd == "java" || cmd == "java.exe" || cmd.endsWith("java") || cmd.endsWith("java.exe"), cmd)
    }
}
