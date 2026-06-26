package ksl.server.mcp

import ksl.app.bundle.KSLAppKind
import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.LKInventoryOptModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import ksl.examples.general.appsupport.RQInventoryOptModelBuilder
import ksl.service.capability.run.BundleRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shared manifest-bundle fixtures for the MCP server tests — the canonical
 * MM1 / LK-inventory / SimOpt dogfood bundles, assembled once via KSLTestModels'
 * [ManifestBundleFixtures] and loaded into a fresh [BundleRegistry] per call.
 * Replaces the retired classpath/ServiceLoader discovery (`BundleRegistry.fromClasspath`).
 */
internal object TestBundles {

    /** Directory holding the assembled dogfood bundles (MM1, LK, SimOpt), built once per JVM. */
    val bundlesDir: Path by lazy {
        val dir = Files.createTempDirectory("ksl-test-bundles")
        ManifestBundleFixtures.assembleManifestBundle(
            dir, "mm1", "ksl.examples.mm1", MM1ModelBuilder::class.java
        ) { session ->
            session.displayName = "M/M/1 Queue Example"
            session.models.forEach { it.supportedApps.add(KSLAppKind.SIMOPT) }
        }
        ManifestBundleFixtures.assembleManifestBundle(
            dir, "lk", "ksl.examples.lk-inventory", LKInventoryModelBuilder::class.java
        )
        ManifestBundleFixtures.assembleManifestBundle(
            dir, "simopt", "ksl.examples.simopt-test-models",
            LKInventoryOptModelBuilder::class.java, RQInventoryOptModelBuilder::class.java,
        ) { session -> session.models.forEach { it.supportedApps.add(KSLAppKind.SIMOPT) } }
        dir
    }

    /** A fresh [BundleRegistry] over the dogfood fixture bundles; the caller closes it. */
    fun registry(): BundleRegistry = BundleRegistry.fromDirectories(listOf(bundlesDir))
}
