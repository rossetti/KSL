package ksl.app.settings

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceLayoutTest {

    private val fixedTimestamp: Instant =
        LocalDateTime.of(2026, 5, 14, 19, 22, 8).toInstant(ZoneOffset.UTC)

    // ── runId ───────────────────────────────────────────────────────────────

    @Test fun `runId without filename is just the timestamp`() {
        assertEquals("2026-05-14_19-22-08", WorkspaceLayout.runId(null, fixedTimestamp))
    }

    @Test fun `runId with filename strips extension and prepends`() {
        assertEquals("queueing-2026-05-14_19-22-08", WorkspaceLayout.runId("queueing.toml", fixedTimestamp))
    }

    @Test fun `runId sanitizes filename to filesystem-safe characters`() {
        assertEquals("a_b_c-2026-05-14_19-22-08", WorkspaceLayout.runId("a b/c.toml", fixedTimestamp))
    }

    @Test fun `runId with blank or extension-only filename falls back to timestamp`() {
        assertEquals("2026-05-14_19-22-08", WorkspaceLayout.runId("", fixedTimestamp))
        assertEquals("2026-05-14_19-22-08", WorkspaceLayout.runId("   ", fixedTimestamp))
    }

    // ── abbreviate ──────────────────────────────────────────────────────────

    @Test fun `abbreviate returns the original when short enough`() {
        val short = Path.of("/tmp/x")
        assertEquals(short.toString(), WorkspaceLayout.abbreviate(short, maxLen = 40))
    }

    @Test fun `abbreviate collapses a long under-home path with tilde prefix`() {
        val home = Path.of("/Users/foo")
        val long = Path.of("/Users/foo/projects/queueing-2026/sub/leaf-directory")
        val result = WorkspaceLayout.abbreviate(long, maxLen = 30, userHome = home)
        assertTrue(result.startsWith("~/.../"), "expected tilde prefix, got: $result")
        assertTrue(result.endsWith("queueing-2026/sub/leaf-directory"), "expected last 3 segments, got: $result")
    }

    @Test fun `abbreviate collapses a long non-home path with plain prefix`() {
        val home = Path.of("/Users/foo")
        val long = Path.of("/var/data/projects/queueing-2026/sub/leaf-directory")
        val result = WorkspaceLayout.abbreviate(long, maxLen = 30, userHome = home)
        assertTrue(result.startsWith(".../"), "expected plain prefix, got: $result")
        assertFalse(result.startsWith("~"), "must not pretend to be under home: $result")
    }

    // ── dir resolvers ───────────────────────────────────────────────────────

    @Test fun `configsDir resolves without creating by default`(@TempDir tempDir: Path) {
        val resolved = WorkspaceLayout.configsDir(tempDir)
        assertEquals(tempDir.resolve("configs"), resolved)
        assertFalse(resolved.exists(), "must not auto-create when createIfMissing=false")
    }

    @Test fun `configsDir creates when asked`(@TempDir tempDir: Path) {
        val resolved = WorkspaceLayout.configsDir(tempDir, createIfMissing = true)
        assertTrue(resolved.exists() && resolved.isDirectory())
    }

    @Test fun `outputDir composes workspace, output, runId`(@TempDir tempDir: Path) {
        val resolved = WorkspaceLayout.outputDir(tempDir, "run-1", createIfMissing = true)
        assertEquals(tempDir.resolve("output").resolve("run-1"), resolved)
        assertTrue(resolved.exists())
    }

    @Test fun `reportsDir composes workspace, reports, runId`(@TempDir tempDir: Path) {
        val resolved = WorkspaceLayout.reportsDir(tempDir, "run-1", createIfMissing = true)
        assertEquals(tempDir.resolve("reports").resolve("run-1"), resolved)
        assertTrue(resolved.exists())
    }
}
