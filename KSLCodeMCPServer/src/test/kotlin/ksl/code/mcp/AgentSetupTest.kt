package ksl.code.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
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

    // ---- adapter wiring, exercised through the agent-config redirect ----
    //
    // dir()/present()/configure() had no coverage before: exercising them meant writing the
    // developer's real Claude/Codex config. The redirect root points them at a temp dir instead.

    private fun withAgentConfigRoot(block: (File) -> Unit) {
        val root = Files.createTempDirectory("ksl-agent-cfg").toFile()
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, root.path)
        try {
            block(root)
        } finally {
            System.clearProperty(AGENT_CONFIG_HOME_PROPERTY)
            root.deleteRecursively()
        }
    }

    @Test
    fun `configure writes a detected agent and skips one that is not installed`() {
        withAgentConfigRoot { root ->
            File(root, "Claude").mkdirs() // Claude "installed"; .codex deliberately absent
            val results = AgentSetup.configureDetected("/x/ksl-code-mcp.jar")
            assertEquals(listOf("Claude Desktop"), results.map { it.agent }, "only the detected agent")
            assertEquals("created", results.single().action)
            val servers = Json.parseToJsonElement(File(root, "Claude/claude_desktop_config.json").readText())
                .jsonObject["mcpServers"]!!.jsonObject
            assertTrue("ksl-code" in servers)
            assertTrue(!File(root, ".codex").exists(), "an undetected agent must not be created")
        }
    }

    @Test
    fun `configure merges an existing config and backs up the original once`() {
        withAgentConfigRoot { root ->
            val codex = File(root, ".codex").apply { mkdirs() }
            val cfg = File(codex, "config.toml").apply { writeText("model = \"gpt\"\n") }
            assertEquals("updated", AgentSetup.configureDetected("/x/ksl-code-mcp.jar").single().action)
            assertTrue(cfg.readText().contains("[mcp_servers.ksl-code]"))
            assertTrue(cfg.readText().contains("model = \"gpt\""), "existing content preserved")
            assertEquals(
                "model = \"gpt\"\n",
                File(codex, "config.toml.ksl-code-backup").readText(),
                "the backup is the untouched original"
            )
        }
    }

    @Test
    fun `remove takes the entry back out of a detected agent`() {
        withAgentConfigRoot { root ->
            File(root, ".codex").mkdirs()
            AgentSetup.configureDetected("/x/ksl-code-mcp.jar")
            assertEquals("removed", AgentSetup.removeDetected().single().action)
            assertTrue(!File(root, ".codex/config.toml").readText().contains("[mcp_servers.ksl-code]"))
        }
    }
}
