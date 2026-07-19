package ksl.agent.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentConfiguratorTest {

    @AfterEach
    fun clearRedirect() {
        System.clearProperty(AGENT_CONFIG_HOME_PROPERTY)
    }

    @Test
    @DisplayName("configure honors the sandbox redirect and writes both detected agents")
    fun configureHonorsConfigHome(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()
        File(tmp.toFile(), ".codex").mkdirs()

        val results = AgentConfigurator.configure(
            "ksl-suite", LaunchSpec("ksl-bridge", listOf("--url", "http://127.0.0.1:3001/"))
        )

        assertEquals(2, results.size, "both agents present -> two results")
        assertTrue(results.all { it.path.startsWith(tmp.toString()) }, "everything written under the sandbox root")

        val claude = File(tmp.toFile(), "Claude/claude_desktop_config.json")
        val codex = File(tmp.toFile(), ".codex/config.toml")
        assertTrue(claude.exists() && codex.exists())
        assertTrue(claude.readText().contains("\"ksl-suite\""))
        assertTrue(codex.readText().contains("[mcp_servers.ksl-suite]"))
    }

    @Test
    @DisplayName("an agent whose config directory is absent is skipped")
    fun absentAgentSkipped(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs() // Codex intentionally absent

        val results = AgentConfigurator.configure("ksl-suite", LaunchSpec("ksl-bridge", listOf("--url", "u")))

        assertEquals(1, results.size)
        assertEquals("Claude Desktop", results[0].agent)
    }

    @Test
    @DisplayName("remove is idempotent: removes when present, reports nothing to do when absent")
    fun removeIsIdempotent(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()
        AgentConfigurator.configure("ksl-suite", LaunchSpec("ksl-bridge", listOf("--url", "u")))

        val removed = AgentConfigurator.remove("ksl-suite")
        assertEquals(1, removed.size)
        assertEquals("removed", removed[0].action)
        assertFalse(File(tmp.toFile(), "Claude/claude_desktop_config.json").readText().contains("ksl-suite"))

        val again = AgentConfigurator.remove("ksl-suite")
        assertTrue(again[0].action.contains("nothing to remove"), "second remove is a no-op")
    }
}
