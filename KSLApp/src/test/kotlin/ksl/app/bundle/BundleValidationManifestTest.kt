package ksl.app.bundle

import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import ksl.app.config.ModelManifestEntry
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 3.1: [BundleValidation] is manifest-aware — a manifest-driven bundle whose
 * `builderClass` is missing, is not a `ModelBuilderIfc`, or lacks a usable
 * constructor is reported as a precise ERROR rather than a generic
 * descriptor-extraction failure.
 */
class BundleValidationManifestTest {

    @TempDir
    lateinit var tmp: Path

    /** Writes a manifest-only JAR (no classes needed: bad builders fail at resolve time). */
    private fun jarFor(name: String, entry: ModelManifestEntry): Path {
        val manifest = BundleManifest(
            bundleId = "test.bundle.validation",
            displayName = "V",
            description = "",
            version = "1.0.0",
            kslApiVersion = "1.2",
            models = listOf(entry),
        )
        val target = tmp.resolve(name)
        JarOutputStream(Files.newOutputStream(target), Manifest()).use { jar ->
            jar.putNextEntry(JarEntry(BundleLayout.BUNDLE_TOML).apply { time = 0L })
            jar.write(BundleManifestToml.encode(manifest).toByteArray(Charsets.UTF_8))
            jar.closeEntry()
        }
        return target
    }

    private fun validate(jar: Path): BundleValidation.ValidationReport =
        BundleLoader.loadJar(jar).first().use { BundleValidation.validate(it) }

    private fun BundleValidation.ValidationReport.builderErrors() =
        findings.filter { it.severity == BundleValidation.Severity.ERROR && it.message.contains("builder is not usable") }

    @Test
    fun `a missing builder class is reported as a single precise builder ERROR`() {
        val report = validate(
            jarFor("missing.jar", ModelManifestEntry("m", "does.not.Exist", "M", supportedApps = setOf(KSLAppKind.SINGLE)))
        )
        // Exactly one builder error, and no duplicate generic "failed to extract descriptor".
        assertEquals(1, report.builderErrors().size, "expected one builder ERROR; got ${report.findings}")
        assertFalse(
            report.findings.any { it.message.contains("failed to extract descriptor") },
            "the generic descriptor finding should be suppressed when the builder is the cause"
        )
    }

    @Test
    fun `a builderClass not implementing ModelBuilderIfc is a builder ERROR`() {
        val report = validate(
            jarFor("notbuilder.jar", ModelManifestEntry("m", "java.lang.String", "M", supportedApps = setOf(KSLAppKind.SINGLE)))
        )
        assertTrue(report.builderErrors().isNotEmpty(), "expected a builder ERROR; got ${report.findings}")
    }

    @Test
    fun `a valid manifest bundle produces no builder ERROR`() {
        val report = validate(
            jarFor(
                "ok.jar",
                ModelManifestEntry("p1-model", Phase1TestBuilder::class.java.name, "M", supportedApps = setOf(KSLAppKind.SINGLE)),
            )
        )
        assertTrue(report.builderErrors().isEmpty(), "valid builder should not be flagged; got ${report.findings}")
    }
}
