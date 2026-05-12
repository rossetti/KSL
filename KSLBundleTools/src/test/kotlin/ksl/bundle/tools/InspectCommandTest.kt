package ksl.bundle.tools

import ksl.bundle.tools.support.StubBundle
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InspectCommandTest {

    private fun capture(block: (PrintStream, PrintStream) -> CommandResult): Triple<CommandResult, String, String> {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val result = block(PrintStream(outBuf), PrintStream(errBuf))
        return Triple(result, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    @Test
    fun `inspect prints bundle summary for a JAR with a ServiceLoader registration`(@TempDir dir: Path) {
        val jar = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))

        val (result, out, err) = capture { o, e ->
            InspectCommand.run(listOf(jar.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.Success, result)
        assertTrue(err.isEmpty(), "Expected no stderr output, got: $err")
        assertTrue("Bundle: test.stub" in out, "Missing bundle id in output:\n$out")
        assertTrue("Display name : Stub Bundle" in out, "Missing display name in output:\n$out")
        assertTrue("Discovery: ServiceLoader" in out, "Missing discovery line in output:\n$out")
        assertTrue("- stub (Stub Model)" in out, "Missing model line in output:\n$out")
        assertTrue("Apps         : SINGLE" in out, "Missing apps line in output:\n$out")
        assertTrue("Has in-JAR descriptor : no" in out, "Missing in-JAR-descriptor probe in output:\n$out")
    }

    @Test
    fun `inspect reports zero bundles cleanly on an empty JAR`(@TempDir dir: Path) {
        val jar = TestBundleBuilder.buildWithoutServicesFile(dir, "empty", emptyList())

        val (result, out, err) = capture { o, e ->
            InspectCommand.run(listOf(jar.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.Success, result)
        assertTrue(err.isEmpty())
        assertTrue("No bundles found." in out, "Expected 'No bundles found.' message:\n$out")
    }

    @Test
    fun `inspect rejects a missing file with UserError`(@TempDir dir: Path) {
        val missing = dir.resolve("does-not-exist.jar")

        val (result, _, err) = capture { o, e ->
            InspectCommand.run(listOf(missing.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.UserError, result)
        assertTrue("not a regular file" in err, "Expected diagnostic in stderr:\n$err")
    }

    @Test
    fun `inspect rejects wrong arg count with UserError`() {
        val (result, _, err) = capture { o, e ->
            InspectCommand.run(emptyList(), out = o, err = e)
        }

        assertEquals(CommandResult.UserError, result)
        assertTrue("expected exactly one argument" in err, "Expected arg-count diagnostic:\n$err")
    }
}
