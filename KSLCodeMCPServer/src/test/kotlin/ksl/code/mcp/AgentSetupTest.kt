package ksl.code.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge/remove functions must add and remove ONLY the ksl-code entry — the
 * model server's `ksl` entry and the book server's `ksl-book` entry have to
 * survive both operations untouched, so all three servers coexist.
 */
class AgentSetupTest {

    private val siblingConfig = """
        {
          "mcpServers": {
            "ksl": { "command": "/usr/bin/java", "args": ["-jar", "/opt/ksl-mcp.jar", "--stdio"] },
            "ksl-book": { "command": "/usr/bin/java", "args": ["-jar", "/opt/ksl-book-mcp.jar", "--stdio"] }
          },
          "otherSetting": true
        }
    """.trimIndent()

    @Test
    fun `merge adds ksl-code beside the sibling server entries`() {
        val merged = mergeClaudeJson(siblingConfig, "/usr/bin/java", "/opt/ksl-code-mcp.jar")
        val root = Json.parseToJsonElement(merged).jsonObject
        val servers = root["mcpServers"]!!.jsonObject
        assertTrue("ksl" in servers, "model server entry must survive")
        assertTrue("ksl-book" in servers, "book server entry must survive")
        assertTrue("ksl-code" in servers)
        assertEquals("/opt/ksl-mcp.jar", servers["ksl"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("/opt/ksl-code-mcp.jar", servers["ksl-code"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("true", root["otherSetting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge into empty or missing config creates the structure`() {
        val merged = mergeClaudeJson(null, "java", "/x/ksl-code-mcp.jar")
        val servers = Json.parseToJsonElement(merged).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("ksl-code"), servers.keys)
    }

    @Test
    fun `merge is idempotent and replaces a stale ksl-code entry`() {
        val once = mergeClaudeJson(siblingConfig, "java", "/old/ksl-code-mcp.jar")
        val twice = mergeClaudeJson(once, "java", "/new/ksl-code-mcp.jar")
        val servers = Json.parseToJsonElement(twice).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("ksl", "ksl-book", "ksl-code"), servers.keys)
        assertEquals("/new/ksl-code-mcp.jar", servers["ksl-code"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `remove deletes only ksl-code`() {
        val merged = mergeClaudeJson(siblingConfig, "java", "/x/ksl-code-mcp.jar")
        val removed = removeClaudeJson(merged)!!
        val servers = Json.parseToJsonElement(removed).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("ksl", "ksl-book"), servers.keys)
    }

    @Test
    fun `remove without a ksl-code entry is a no-op`() {
        assertNull(removeClaudeJson(siblingConfig))
        assertNull(removeClaudeJson(null))
        assertNull(removeClaudeJson("not json at all"))
    }

    private val siblingToml = """
        model = "gpt"

        [mcp_servers.ksl]
        command = '/usr/bin/java'
        args = ['-jar', '/opt/ksl-mcp.jar', '--stdio']

        [mcp_servers.ksl-book]
        command = '/usr/bin/java'
        args = ['-jar', '/opt/ksl-book-mcp.jar', '--stdio']
    """.trimIndent()

    @Test
    fun `codex merge appends its own table and leaves siblings alone`() {
        val merged = mergeCodexToml(siblingToml, "java", "/x/ksl-code-mcp.jar")
        assertTrue(merged.contains("[mcp_servers.ksl]"))
        assertTrue(merged.contains("[mcp_servers.ksl-book]"))
        assertTrue(merged.contains("[mcp_servers.ksl-code]"))
        assertTrue(merged.contains("'/opt/ksl-mcp.jar'"))
        assertTrue(merged.contains("'/x/ksl-code-mcp.jar'"))
    }

    @Test
    fun `codex merge replaces an existing ksl-code table in place`() {
        val once = mergeCodexToml(siblingToml, "java", "/old.jar")
        val twice = mergeCodexToml(once, "java", "/new.jar")
        assertTrue(twice.contains("'/new.jar'"))
        assertTrue(!twice.contains("'/old.jar'"))
        assertEquals(1, Regex("\\[mcp_servers\\.ksl-code]").findAll(twice).count())
        assertEquals(1, Regex("\\[mcp_servers\\.ksl-book]").findAll(twice).count())
    }

    @Test
    fun `codex remove deletes only the ksl-code table`() {
        val merged = mergeCodexToml(siblingToml, "java", "/x.jar")
        val removed = removeCodexToml(merged)!!
        assertTrue(removed.contains("[mcp_servers.ksl]"))
        assertTrue(removed.contains("[mcp_servers.ksl-book]"))
        assertTrue(!removed.contains("[mcp_servers.ksl-code]"))
        assertTrue(removed.contains("model = \"gpt\""))
    }

    @Test
    fun `codex remove without the table is a no-op`() {
        assertNull(removeCodexToml(siblingToml))
        assertNull(removeCodexToml(null))
    }

    @Test
    fun `universal snippet names the ksl-code entry`() {
        val snippet = AgentSetup.universalSnippet("/x/ksl-code-mcp.jar")
        assertTrue(snippet.startsWith("\"ksl-code\":"))
        assertTrue(snippet.contains("--stdio"))
    }
}
