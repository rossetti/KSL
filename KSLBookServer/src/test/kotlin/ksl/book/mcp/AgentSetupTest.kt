package ksl.book.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge/remove functions must add and remove ONLY the ksl-book entry —
 * the model server's ksl entry has to survive both operations untouched.
 */
class AgentSetupTest {

    private val modelServerConfig = """
        {
          "mcpServers": {
            "ksl": { "command": "/usr/bin/java", "args": ["-jar", "/opt/ksl-mcp.jar", "--stdio"] }
          },
          "otherSetting": true
        }
    """.trimIndent()

    @Test
    fun `merge adds ksl-book beside the model server entry`() {
        val merged = mergeClaudeJson(modelServerConfig, "/usr/bin/java", "/opt/ksl-book-mcp.jar")
        val root = Json.parseToJsonElement(merged).jsonObject
        val servers = root["mcpServers"]!!.jsonObject
        assertTrue("ksl" in servers, "model server entry must survive")
        assertTrue("ksl-book" in servers)
        assertEquals("/opt/ksl-mcp.jar", servers["ksl"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("/opt/ksl-book-mcp.jar", servers["ksl-book"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
        assertEquals("true", root["otherSetting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `merge into empty or missing config creates the structure`() {
        val merged = mergeClaudeJson(null, "java", "/x/ksl-book-mcp.jar")
        val servers = Json.parseToJsonElement(merged).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("ksl-book"), servers.keys)
    }

    @Test
    fun `merge is idempotent and replaces a stale ksl-book entry`() {
        val once = mergeClaudeJson(modelServerConfig, "java", "/old/ksl-book-mcp.jar")
        val twice = mergeClaudeJson(once, "java", "/new/ksl-book-mcp.jar")
        val servers = Json.parseToJsonElement(twice).jsonObject["mcpServers"]!!.jsonObject
        assertEquals(setOf("ksl", "ksl-book"), servers.keys)
        assertEquals("/new/ksl-book-mcp.jar", servers["ksl-book"]!!.jsonObject["args"]!!.jsonArray[1].jsonPrimitive.content)
    }

    @Test
    fun `remove deletes only ksl-book`() {
        val merged = mergeClaudeJson(modelServerConfig, "java", "/x/ksl-book-mcp.jar")
        val removed = removeClaudeJson(merged)!!
        val root = Json.parseToJsonElement(removed).jsonObject
        val servers = root["mcpServers"]!!.jsonObject
        assertEquals(setOf("ksl"), servers.keys)
        assertEquals("true", root["otherSetting"]!!.jsonPrimitive.content)
    }

    @Test
    fun `remove without a ksl-book entry is a no-op`() {
        assertNull(removeClaudeJson(modelServerConfig))
        assertNull(removeClaudeJson(null))
        assertNull(removeClaudeJson("not json at all"))
    }

    private val modelServerToml = """
        model = "gpt"

        [mcp_servers.ksl]
        command = '/usr/bin/java'
        args = ['-jar', '/opt/ksl-mcp.jar', '--stdio']
    """.trimIndent()

    @Test
    fun `codex merge appends its own table and leaves ksl alone`() {
        val merged = mergeCodexToml(modelServerToml, "java", "/x/ksl-book-mcp.jar")
        assertTrue(merged.contains("[mcp_servers.ksl]"))
        assertTrue(merged.contains("[mcp_servers.ksl-book]"))
        assertTrue(merged.contains("'/opt/ksl-mcp.jar'"))
        assertTrue(merged.contains("'/x/ksl-book-mcp.jar'"))
    }

    @Test
    fun `codex merge replaces an existing ksl-book table in place`() {
        val once = mergeCodexToml(modelServerToml, "java", "/old.jar")
        val twice = mergeCodexToml(once, "java", "/new.jar")
        assertTrue(twice.contains("'/new.jar'"))
        assertTrue(!twice.contains("'/old.jar'"))
        assertEquals(1, Regex("\\[mcp_servers\\.ksl-book]").findAll(twice).count())
        assertEquals(1, Regex("\\[mcp_servers\\.ksl]").findAll(twice).count())
    }

    @Test
    fun `codex remove deletes only the ksl-book table`() {
        val merged = mergeCodexToml(modelServerToml, "java", "/x.jar")
        val removed = removeCodexToml(merged)!!
        assertTrue(removed.contains("[mcp_servers.ksl]"))
        assertTrue(!removed.contains("[mcp_servers.ksl-book]"))
        assertTrue(removed.contains("model = \"gpt\""))
    }

    @Test
    fun `codex remove without the table is a no-op`() {
        assertNull(removeCodexToml(modelServerToml))
        assertNull(removeCodexToml(null))
    }

    @Test
    fun `universal snippet names the ksl-book entry`() {
        val snippet = AgentSetup.universalSnippet("/x/ksl-book-mcp.jar")
        assertTrue(snippet.startsWith("\"ksl-book\":"))
        assertTrue(snippet.contains("--stdio"))
    }
}
