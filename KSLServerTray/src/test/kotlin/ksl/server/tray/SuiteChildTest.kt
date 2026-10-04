package ksl.server.tray

import ksl.server.manage.ServerManagerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  Quit stops what the tray started, found through the handle it kept rather than by command line.
 *
 *  The launcher here is a script that starts a long-lived child and waits on it: the same shape as the
 *  Windows `cmd.exe` running the server's `java.exe`. Nothing in these tests reads a command line, so
 *  they mean the same thing on every OS, including the one where command lines are unreadable.
 */
class SuiteChildTest {

    @TempDir
    lateinit var dir: Path

    private val scope = CoroutineScope(SupervisorJob())
    private val controller = ServerManagerController(healthUrl = DEAD_HEALTH_URL, scope = scope)
    private val leftovers = mutableListOf<ProcessHandle>()

    @AfterEach
    fun tearDown() {
        leftovers.filter { it.isAlive }.forEach { it.destroyForcibly() }
        scope.cancel()
    }

    /** A launcher whose only job is to start a child that outlives a stopped parent. */
    private fun launcherWithChild(): Path {
        val windows = System.getProperty("os.name").startsWith("Windows")
        val script = dir.resolve(if (windows) "ksl-suite.cmd" else "ksl-suite")
        if (windows) {
            Files.writeString(script, "@echo off\r\nping -n 300 127.0.0.1 >nul\r\n")
        } else {
            Files.writeString(script, "#!/bin/sh\nsleep 300 &\nwait\n")
            script.toFile().setExecutable(true)
        }
        return script
    }

    private fun awaitDescendant(root: ProcessHandle): ProcessHandle {
        val deadline = System.nanoTime() + 10_000_000_000
        while (System.nanoTime() < deadline) {
            root.descendants().filter { it.isAlive }.findFirst().orElse(null)?.let { return it }
            Thread.sleep(50)
        }
        error("the launcher never started its child")
    }

    @Test
    @DisplayName("Quit stops every process under the launcher the tray started")
    fun stopsTheWholeTree() {
        val launcher = launcherWithChild()
        val child = SuiteChild(controller, DEAD_HEALTH_URL, launcher = { launcher })
        assertTrue(child.startIfDown(), "nothing answers the health URL, so the tray must start the suite")

        val root = checkNotNull(child.started)
        val grandchild = awaitDescendant(root)
        leftovers += listOf(root, grandchild)

        val reaped = child.stopIfOurs()

        assertFalse(grandchild.isAlive, "the launcher's child (the server, on Windows) must not survive Quit")
        assertFalse(root.isAlive, "the launcher must not survive Quit")
        assertTrue(grandchild.pid() in reaped && root.pid() in reaped, "both are reported reaped: $reaped")
        assertEquals(null, child.started, "a stopped suite is no longer the tray's")
    }

    @Test
    @DisplayName("Quit leaves alone a suite the tray did not start")
    fun leavesOthersAlone() {
        val child = SuiteChild(controller, DEAD_HEALTH_URL, launcher = { null })
        assertFalse(child.startIfDown(), "with no launcher the tray starts nothing")
        assertEquals(emptyList(), child.stopIfOurs())
    }

    @Test
    @DisplayName("a process tree is ordered deepest first, its root last")
    fun deepestFirstOrdersByDepth() {
        val launcher = launcherWithChild()
        val process = ProcessBuilder(launcher.toString()).start()
        val root = process.toHandle()
        val grandchild = awaitDescendant(root)
        leftovers += listOf(root, grandchild)

        val ordered = deepestFirst(root)
        assertEquals(root, ordered.last())
        assertTrue(ordered.indexOf(grandchild) < ordered.indexOf(root))
    }

    private companion object {
        /** Port 1 is never a KSL suite, so /health is always down and the tray always starts its own. */
        const val DEAD_HEALTH_URL = "http://127.0.0.1:1/health"
    }
}
