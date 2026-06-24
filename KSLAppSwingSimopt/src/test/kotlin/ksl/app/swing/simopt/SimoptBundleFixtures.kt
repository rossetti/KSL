package ksl.app.swing.simopt

import ksl.app.editor.BundleLibraryController
import ksl.examples.general.appsupport.LKInventoryModelBuilder
import ksl.examples.general.appsupport.LKInventoryOptModelBuilder
import ksl.examples.general.appsupport.MM1ModelBuilder
import ksl.examples.general.appsupport.ManifestBundleFixtures
import ksl.examples.general.appsupport.RQInventoryOptModelBuilder
import java.nio.file.Path

/**
 * Test helper: assembles the SimOpt-app fixture models into manifest bundle JARs and
 * loads them into a [BundleLibraryController], so controller tests inject a real library
 * (the same way an app loads bundles) instead of relying on classpath ServiceLoader
 * discovery.
 *
 * The `*_BUNDLE_ID` / `*_MODEL_ID` constants are the canonical ids of the assembled
 * MM1 / LK / SimOpt fixtures, so `(bundleId, modelId)` references resolve unchanged.  Assemble the JARs once per test, then build a fresh
 * [library] per controller — `SimoptAppController.close()` closes the library it was
 * given, so each controller needs its own.
 */
internal object SimoptBundleFixtures {

    const val MM1_BUNDLE_ID = "ksl.examples.mm1"
    const val MM1_MODEL_ID = "MM1"
    const val LK_BUNDLE_ID = "ksl.examples.lk-inventory"
    const val LK_MODEL_ID = "LKInventory"
    const val SIMOPT_BUNDLE_ID = "ksl.examples.simopt-test-models"
    const val LK_OPT_MODEL_ID = "LKInventoryOpt"
    const val RQ_OPT_MODEL_ID = "RQInventoryOpt"

    /** Assembles the MM1 model into a bundle JAR. */
    fun mm1Jar(dir: Path): Path = ManifestBundleFixtures.assembleManifestBundle(
        dir, "mm1", MM1_BUNDLE_ID, MM1ModelBuilder::class.java
    )

    /** Assembles the LK (s,S) inventory model into a bundle JAR. */
    fun lkJar(dir: Path): Path = ManifestBundleFixtures.assembleManifestBundle(
        dir, "lk", LK_BUNDLE_ID, LKInventoryModelBuilder::class.java
    )

    /** Assembles the SimOpt test-models bundle JAR (LKInventoryOpt + RQInventoryOpt). */
    fun simoptJar(dir: Path): Path = ManifestBundleFixtures.assembleManifestBundle(
        dir, "simopt", SIMOPT_BUNDLE_ID,
        LKInventoryOptModelBuilder::class.java, RQInventoryOptModelBuilder::class.java
    )

    /** A fresh library preloaded with the given assembled bundle JARs. */
    fun library(vararg jars: Path): BundleLibraryController =
        BundleLibraryController().apply { jars.forEach { loadJar(it) } }
}
