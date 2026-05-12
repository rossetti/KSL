package ksl.bundle.tools

import ksl.app.bundle.BundleLayout
import ksl.bundle.tools.support.StubBundle
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnrichCommandTest {

    private fun capture(block: (PrintStream, PrintStream) -> CommandResult): Triple<CommandResult, String, String> {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val result = block(PrintStream(outBuf), PrintStream(errBuf))
        return Triple(result, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    private fun jarEntryBytes(jar: Path, entryName: String): ByteArray? {
        JarFile(jar.toFile()).use { jf ->
            val entry = jf.getJarEntry(entryName) ?: return null
            return jf.getInputStream(entry).use { it.readBytes() }
        }
    }

    @Test
    fun `enrich writes descriptor entries for every bundled model`(@TempDir dir: Path) {
        val input = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))

        val (result, out, err) = capture { o, e ->
            EnrichCommand.run(listOf(input.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.Success, result, "stderr=$err")
        assertTrue(err.isEmpty(), "expected empty stderr, got: $err")
        val output = EnrichCommand.defaultOutputPath(input)
        assertTrue(Files.isRegularFile(output), "expected enriched JAR at $output")

        val descriptorPath = BundleLayout.descriptorPath("stub")
        val bytes = jarEntryBytes(output, descriptorPath)
        assertNotNull(bytes, "expected $descriptorPath in $output")
        val json = bytes.toString(Charsets.UTF_8)
        assertTrue("\"modelIdentifier\"" in json, "descriptor JSON should mention modelIdentifier:\n$json")
        assertTrue(descriptorPath in out, "stdout should list the embedded path:\n$out")
    }

    @Test
    fun `re-enriching a JAR preserves the JAR structure (no duplicated descriptor entries)`(@TempDir dir: Path) {
        val input = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))

        capture { o, e -> EnrichCommand.run(listOf(input.toString()), out = o, err = e) }
        val firstOutput = EnrichCommand.defaultOutputPath(input)

        // Enrich the already-enriched JAR. Re-enrichment must not duplicate
        // the descriptor entry: pre-existing descriptor paths in the input
        // are dropped during copy and re-emitted in the appended section.
        capture { o, e -> EnrichCommand.run(listOf(firstOutput.toString()), out = o, err = e) }
        val secondOutput = EnrichCommand.defaultOutputPath(firstOutput)
        assertTrue(Files.isRegularFile(secondOutput))

        val descriptorPath = BundleLayout.descriptorPath("stub")
        val firstEntries = JarFile(firstOutput.toFile()).use { jf ->
            jf.entries().asSequence().map { it.name }.toList()
        }
        val secondEntries = JarFile(secondOutput.toFile()).use { jf ->
            jf.entries().asSequence().map { it.name }.toList()
        }
        assertEquals(
            firstEntries.count { it == descriptorPath },
            1,
            "first enrich should produce exactly one descriptor entry"
        )
        assertEquals(
            secondEntries.count { it == descriptorPath },
            1,
            "re-enrich must not duplicate the descriptor entry"
        )
        // Both JARs carry the same set of entry paths (order may differ
        // because re-enrich moves the descriptor to the appended section).
        assertEquals(firstEntries.toSet(), secondEntries.toSet())
        // The descriptor JSON itself is permitted to differ between runs:
        // Model.modelDescriptor() captures a creation timestamp and an
        // auto-incrementing experimentId, both of which change each build.
    }

    @Test
    fun `enrich honors -o`(@TempDir dir: Path) {
        val input = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))
        val chosen = dir.resolve("custom-out.jar")

        val (result, _, err) = capture { o, e ->
            EnrichCommand.run(listOf(input.toString(), "-o", chosen.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.Success, result, "stderr=$err")
        assertTrue(Files.isRegularFile(chosen), "expected enriched JAR at $chosen")
        assertNotNull(
            jarEntryBytes(chosen, BundleLayout.descriptorPath("stub")),
            "expected descriptor entry inside $chosen"
        )
        // The default output path should not exist: -o redirected the write.
        assertTrue(
            !Files.exists(EnrichCommand.defaultOutputPath(input)),
            "default output path should be untouched when -o is supplied"
        )
    }

    @Test
    fun `enrich refuses to overwrite an existing output without --force`(@TempDir dir: Path) {
        val input = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))
        val output = EnrichCommand.defaultOutputPath(input)
        Files.createFile(output)

        val (result, _, err) = capture { o, e ->
            EnrichCommand.run(listOf(input.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.UserError, result)
        assertTrue("--force" in err, "Expected stderr to mention --force:\n$err")
    }

    @Test
    fun `enrich overwrites with --force`(@TempDir dir: Path) {
        val input = TestBundleBuilder.build(dir, "stub", listOf(StubBundle::class.java))
        val output = EnrichCommand.defaultOutputPath(input)
        Files.writeString(output, "stub placeholder")

        val (result, _, err) = capture { o, e ->
            EnrichCommand.run(listOf(input.toString(), "--force"), out = o, err = e)
        }

        assertEquals(CommandResult.Success, result, "stderr=$err")
        assertNotNull(jarEntryBytes(output, BundleLayout.descriptorPath("stub")))
    }

    @Test
    fun `enrich exits UserError when the JAR has no bundles`(@TempDir dir: Path) {
        val emptyJar = TestBundleBuilder.buildWithoutServicesFile(dir, "empty", emptyList())

        val (result, _, err) = capture { o, e ->
            EnrichCommand.run(listOf(emptyJar.toString()), out = o, err = e)
        }

        assertEquals(CommandResult.UserError, result)
        assertTrue("no KSLModelBundle providers" in err, "Expected diagnostic:\n$err")
    }

    @Test
    fun `enrich rejects unknown flags`() {
        val (result, _, err) = capture { o, e ->
            EnrichCommand.run(listOf("--nope", "input.jar"), out = o, err = e)
        }
        assertEquals(CommandResult.UserError, result)
        assertTrue("unknown flag --nope" in err)
    }
}
