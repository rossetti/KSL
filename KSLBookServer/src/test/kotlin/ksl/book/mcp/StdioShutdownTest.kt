package ksl.book.mcp

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WS1 regression guard for the orphaned-JVM leak: a stdio MCP server must exit when its client
 * disconnects. Reproduces the leak's trigger by launching the server with stdin already at EOF
 * (exactly the "client is gone" condition) and asserting the process exits promptly. Before the
 * fix, the server hooked the SDK's server-level onClose (which never fires on session teardown)
 * and parked in runBlocking forever; the fix hooks the transport's onClose and forces exit.
 */
class StdioShutdownTest {

    @Test
    @Timeout(90)
    @DisplayName("stdio server exits promptly when its client closes stdin (no orphaned JVM)")
    fun serverExitsOnStdinEof() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val classpath = System.getProperty("java.class.path")
        val emptyStdin = File.createTempFile("ksl-book-mcp-stdin", ".empty").also { it.deleteOnExit() }

        val proc = ProcessBuilder(java, "-cp", classpath, "ksl.book.mcp.LauncherKt", "--stdio")
            .redirectInput(emptyStdin)                        // immediate EOF == client disconnected
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()

        val exited = proc.waitFor(60, TimeUnit.SECONDS)
        if (!exited) proc.destroyForcibly()
        assertTrue(exited, "stdio server did not exit within 60s of stdin EOF — the orphaned-JVM leak (WS1)")
        assertEquals(0, proc.exitValue(), "stdio server should exit cleanly (0) after the client disconnects")
    }
}
