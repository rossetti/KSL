package ksl.server.suite

import ksl.agent.config.AGENT_CONFIG_HOME_PROPERTY
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupCliTest {

    @AfterEach
    fun clearRedirect() {
        System.clearProperty(AGENT_CONFIG_HOME_PROPERTY)
    }

    @Test
    @DisplayName("--configure writes the ksl-suite entry via the shared config library")
    fun configureWritesSuiteEntry(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()

        val results = SetupCli.run(arrayOf("--configure", "--bridge", "ksl-bridge", "--url", "http://127.0.0.1:3001/"))

        assertEquals(1, results.size)
        val claude = File(tmp.toFile(), "Claude/claude_desktop_config.json").readText()
        assertTrue("\"ksl-suite\"" in claude)
        assertTrue("ksl-bridge" in claude)
        assertTrue("http://127.0.0.1:3001/" in claude)
    }

    @Test
    @DisplayName("configure(bridge) writes the entry from an explicit override — the one-click / Advanced path")
    fun configureWithExplicitOverride(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()

        val results = SetupCli.configure("my-bridge", "http://127.0.0.1:3001/")

        assertEquals(1, results.size)
        val claude = File(tmp.toFile(), "Claude/claude_desktop_config.json").readText()
        assertTrue("\"ksl-suite\"" in claude && "my-bridge" in claude)
    }

    @Test
    @DisplayName("--remove is idempotent")
    fun removeIsIdempotent(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()
        SetupCli.run(arrayOf("--configure", "--bridge", "ksl-bridge"))

        val removed = SetupCli.run(arrayOf("--remove"))
        assertEquals("removed", removed.single().action)
    }

    @Test
    @DisplayName("in a classes/dev run there is no co-located bridge, so auto-detect is null and --configure is rejected")
    fun autoDetectNullInDev(@TempDir tmp: Path) {
        System.setProperty(AGENT_CONFIG_HOME_PROPERTY, tmp.toString())
        File(tmp.toFile(), "Claude").mkdirs()
        // Tests run from compiled classes (no ksl-bridge.jar sibling), so the default cannot resolve.
        assertNull(SetupCli.defaultBridgeSpec())
        assertFailsWith<IllegalStateException> { SetupCli.run(arrayOf("--configure")) }
    }

    @Test
    @DisplayName("isSetupCommand distinguishes setup flags from a plain server launch")
    fun recognizesSetupCommands() {
        assertTrue(SetupCli.isSetupCommand(arrayOf("--configure", "--bridge", "x")))
        assertTrue(SetupCli.isSetupCommand(arrayOf("--remove")))
        assertTrue(!SetupCli.isSetupCommand(arrayOf()))
        assertTrue(!SetupCli.isSetupCommand(arrayOf("--serve")))
    }
}
