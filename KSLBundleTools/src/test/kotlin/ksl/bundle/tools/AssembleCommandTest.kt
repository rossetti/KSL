package ksl.bundle.tools

import ksl.app.bundle.BundleLayout
import ksl.app.bundle.BundleLoader
import ksl.bundle.tools.support.StubBModelBuilder
import ksl.bundle.tools.support.StubModelBuilder
import ksl.bundle.tools.support.TestBundleBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssembleCommandTest {

    private fun capture(block: (PrintStream, PrintStream) -> CommandResult): Triple<CommandResult, String, String> {
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val result = block(PrintStream(outBuf), PrintStream(errBuf))
        return Triple(result, outBuf.toString(Charsets.UTF_8), errBuf.toString(Charsets.UTF_8))
    }

    /** A plain builders JAR: just the ModelBuilderIfc class — no services file, no manifest. */
    private fun buildersJar(dir: Path, name: String = "builders"): Path =
        TestBundleBuilder.buildWithoutServicesFile(dir, name, listOf(StubModelBuilder::class.java))

    @Test
    fun `assemble --exclude drops the named model from the bundle`(@TempDir dir: Path) {
        // Two builders discovered; exclude one by modelId.
        val builders = TestBundleBuilder.buildWithoutServicesFile(
            dir, "builders", listOf(StubModelBuilder::class.java, StubBModelBuilder::class.java)
        )
        val output = dir.resolve("builders-bundle.jar")

        val (result, out, err) = capture { o, e ->
            AssembleCommand.run(
                listOf(builders.toString(), "--id", "edu.test.stub", "--exclude", "StubB", "-o", output.toString()),
                out = o, err = e
            )
        }

        assertEquals(CommandResult.Success, result, "stderr: $err")
        assertTrue("Models (1)" in out, "expected one included model:\n$out")
        assertTrue("(excluded) StubB" in out, "expected StubB reported as excluded:\n$out")

        BundleLoader.loadJar(output).single().use { lb ->
            val ids = lb.bundle.models.map { it.modelId }
            assertEquals(listOf("Stub"), ids, "bundle must contain only the non-excluded model; got $ids")
        }
    }

    @Test
    fun `assemble turns a builders JAR into a loadable manifest bundle`(@TempDir dir: Path) {
        val builders = buildersJar(dir)
        val output = dir.resolve("builders-bundle.jar")

        val (result, out, err) = capture { o, e ->
            AssembleCommand.run(
                listOf(builders.toString(), "--id", "edu.test.stub", "--name", "Stub", "-o", output.toString()),
                out = o, err = e
            )
        }

        assertEquals(CommandResult.Success, result, "stderr: $err")
        assertTrue("Assembled edu.test.stub" in out, "expected an assemble summary:\n$out")

        // The output is a real manifest bundle: bundle.toml + descriptor.json, no services file.
        JarFile(output.toFile()).use { jar ->
            assertNotNull(jar.getJarEntry(BundleLayout.BUNDLE_TOML), "missing bundle.toml manifest")
            assertNotNull(jar.getJarEntry(BundleLayout.descriptorPath("Stub")), "missing embedded descriptor.json")
            assertNull(
                jar.getJarEntry("META-INF/services/ksl.app.bundle.KSLModelBundle"),
                "a manifest bundle needs no services file"
            )
        }

        // It loads back as a ManifestBackedBundle with the CLI-supplied identity.
        BundleLoader.loadJar(output).single().use { lb ->
            assertEquals("edu.test.stub", lb.bundle.bundleId)
            assertEquals("Stub", lb.bundle.displayName)
            assertEquals(listOf("Stub"), lb.bundle.models.map { it.modelId })
        }

        // The input builders JAR is never modified.
        JarFile(builders.toFile()).use { jar ->
            assertNull(jar.getJarEntry(BundleLayout.BUNDLE_TOML), "input builders JAR must remain untouched")
        }
    }

    @Test
    fun `assemble requires --id`(@TempDir dir: Path) {
        val builders = buildersJar(dir)
        val (result, _, err) = capture { o, e -> AssembleCommand.run(listOf(builders.toString()), out = o, err = e) }
        assertEquals(CommandResult.UserError, result)
        assertTrue("--id" in err, "expected a --id-required diagnostic:\n$err")
    }

    @Test
    fun `assemble defaults the output to the builders-stem plus -bundle`(@TempDir dir: Path) {
        val builders = buildersJar(dir, "mymodels")
        val (result, _, err) = capture { o, e ->
            AssembleCommand.run(listOf(builders.toString(), "--id", "edu.test.stub"), out = o, err = e)
        }
        assertEquals(CommandResult.Success, result, "stderr: $err")
        assertTrue(Files.isRegularFile(dir.resolve("mymodels-bundle.jar")), "expected default output mymodels-bundle.jar")
    }

    @Test
    fun `assemble refuses to overwrite an existing output without --force`(@TempDir dir: Path) {
        val builders = buildersJar(dir)
        val output = dir.resolve("out-bundle.jar")
        Files.writeString(output, "placeholder")

        val (r1, _, err) = capture { o, e ->
            AssembleCommand.run(listOf(builders.toString(), "--id", "x.y", "-o", output.toString()), out = o, err = e)
        }
        assertEquals(CommandResult.UserError, r1)
        assertTrue("already exists" in err, "expected an overwrite diagnostic:\n$err")

        val (r2, _, _) = capture { o, e ->
            AssembleCommand.run(listOf(builders.toString(), "--id", "x.y", "-o", output.toString(), "--force"), out = o, err = e)
        }
        assertEquals(CommandResult.Success, r2)
    }

    @Test
    fun `assemble reports when no builders are found`(@TempDir dir: Path) {
        val empty = TestBundleBuilder.buildWithoutServicesFile(dir, "empty", emptyList())
        val (result, _, err) = capture { o, e ->
            AssembleCommand.run(listOf(empty.toString(), "--id", "x.y"), out = o, err = e)
        }
        assertEquals(CommandResult.UserError, result)
        assertTrue("no ksl.simulation.ModelBuilderIfc" in err, "expected a no-builders diagnostic:\n$err")
    }
}
