package ksl.app.swing.experiment

import ksl.app.editor.BundleLibraryController
import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import java.nio.file.Path

/**
 * Test helper: assembles the Experiment-app fixture models into manifest bundle JARs
 * and loads them into a [BundleLibraryController], so controller tests inject a real
 * library (the same way an app loads bundles) instead of relying on classpath
 * ServiceLoader discovery.
 *
 * The `*_BUNDLE_ID` / `*_MODEL_ID` constants match the originals (`MM1Bundle`,
 * `LKInventoryBundle`) so existing `(bundleId, modelId)` references resolve unchanged.
 * Assemble the JAR once per test, then build a fresh [library] per controller —
 * `ExperimentAppController.close()` closes the library it was given, so each controller
 * needs its own.
 */
internal object ExperimentBundleFixtures {

    const val MM1_BUNDLE_ID = "ksl.examples.mm1"
    const val MM1_MODEL_ID = "MM1"
    const val LK_BUNDLE_ID = "ksl.examples.lk-inventory"
    const val LK_MODEL_ID = "LKInventory"

    /** Assembles the MM1 model into a bundle JAR. */
    fun mm1Jar(dir: Path): Path = ManifestBundleFixtures.assembleManifestBundle(
        dir, "mm1", MM1_BUNDLE_ID, MM1ModelBuilder::class.java
    )

    /** Assembles the LK (s,S) inventory model into a bundle JAR. */
    fun lkJar(dir: Path): Path = ManifestBundleFixtures.assembleManifestBundle(
        dir, "lk", LK_BUNDLE_ID, LKInventoryModelBuilder::class.java
    )

    /** A fresh library preloaded with the given assembled bundle JARs. */
    fun library(vararg jars: Path): BundleLibraryController =
        BundleLibraryController().apply { jars.forEach { loadJar(it) } }
}
