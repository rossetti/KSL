package ksl.app.servers

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SuiteConfiguratorTest {

    private val spec = SuiteConfigurator.SuiteLaunchSpec("/x/ksl-bridge", listOf("--url", "http://127.0.0.1:3001/"))

    // ---- pure Claude JSON ----

    @Test
    fun claudeJsonAddsSuiteAndPreservesOthers() {
        val merged = mergeClaudeSuiteJson("""{"mcpServers":{"keepme":{"command":"foo","args":[]}}}""", spec)
        assertTrue("ksl-suite" in merged)
        assertTrue("keepme" in merged)
        assertTrue("ksl-bridge" in merged && "--url" in merged)
    }

    @Test
    fun claudeJsonRemoveDropsOnlyTheSuite() {
        val withSuite = mergeClaudeSuiteJson("""{"mcpServers":{"keepme":{"command":"foo","args":[]}}}""", spec)
        val removed = removeClaudeSuiteJson(withSuite)!!
        assertFalse("ksl-suite" in removed)
        assertTrue("keepme" in removed)
    }

    @Test
    fun claudeJsonRemoveIsNullWhenAbsent() {
        assertNull(removeClaudeSuiteJson("""{"mcpServers":{"keepme":{"command":"foo","args":[]}}}"""))
    }

    // ---- pure Codex TOML ----

    @Test
    fun codexTomlAddsSuiteAndPreservesOthers() {
        val merged = mergeCodexSuiteToml("[mcp_servers.keepme]\ncommand = 'foo'\nargs = []\n", spec)
        assertTrue("[mcp_servers.ksl-suite]" in merged)
        assertTrue("[mcp_servers.keepme]" in merged)
        assertTrue("ksl-bridge" in merged)
    }

    @Test
    fun codexTomlRemoveDropsOnlyTheSuite() {
        val withSuite = mergeCodexSuiteToml("[mcp_servers.keepme]\ncommand = 'foo'\nargs = []\n", spec)
        val removed = removeCodexSuiteToml(withSuite)!!
        assertFalse("[mcp_servers.ksl-suite]" in removed)
        assertTrue("[mcp_servers.keepme]" in removed)
    }

    // ---- end-to-end against the config redirect (no real agent config touched) ----

    @Test
    @DisplayName("configure then remove round-trips both agents under the config redirect")
    fun configureThenRemoveRoundTrips(@TempDir tmp: Path) {
        val prev = System.getProperty("ksl.agent.config.home")
        try {
            System.setProperty("ksl.agent.config.home", tmp.toString())
            val claude = tmp.resolve("Claude").also { Files.createDirectories(it) }.resolve("claude_desktop_config.json")
            Files.writeString(claude, """{"mcpServers":{"keepme":{"command":"foo","args":[]}}}""")
            val codex = tmp.resolve(".codex").also { Files.createDirectories(it) }.resolve("config.toml")
            Files.writeString(codex, "[mcp_servers.keepme]\ncommand = 'foo'\nargs = []\n")

            val configured = SuiteConfigurator.configure("/x/ksl-bridge", "http://127.0.0.1:3001/")
            assertEquals(2, configured.size, "both seeded agents should be detected: $configured")
            assertTrue("ksl-suite" in Files.readString(claude) && "keepme" in Files.readString(claude))
            assertTrue("[mcp_servers.ksl-suite]" in Files.readString(codex))

            val removed = SuiteConfigurator.remove()
            assertEquals(2, removed.size, "both agents should be updated on remove: $removed")
            assertFalse("ksl-suite" in Files.readString(claude))
            assertTrue("keepme" in Files.readString(claude))
            assertFalse("[mcp_servers.ksl-suite]" in Files.readString(codex))
            assertTrue("[mcp_servers.keepme]" in Files.readString(codex))
        } finally {
            if (prev == null) System.clearProperty("ksl.agent.config.home") else System.setProperty("ksl.agent.config.home", prev)
        }
    }
}
