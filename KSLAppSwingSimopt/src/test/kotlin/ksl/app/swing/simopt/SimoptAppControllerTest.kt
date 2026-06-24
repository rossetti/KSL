/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.app.swing.simopt

import ksl.app.config.ModelReference
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.editor.BundleLibraryController
import ksl.app.swing.simopt.stepper.Step
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 *  Controller-level tests for the Phase O3 mutators and bundle /
 *  descriptor wiring.  Pure JVM tests — no Swing constructed.  The
 *  MM1 + LK models are assembled into manifest bundle JARs and
 *  injected via `SimoptBundleFixtures` (see the `controller()`
 *  factory), not discovered from the classpath.
 */
class SimoptAppControllerTest {

    @TempDir
    lateinit var bundleDir: Path

    /** MM1 + LK model bundle JARs, assembled once per test. */
    private val mm1Jar: Path by lazy { SimoptBundleFixtures.mm1Jar(bundleDir) }
    private val lkJar: Path by lazy { SimoptBundleFixtures.lkJar(bundleDir) }

    /** A fresh controller backed by an injected library holding the MM1 + LK bundles.
     *  Each call builds its own library; the controller closes it on `use {}` exit. */
    private fun controller() =
        SimoptAppController("Test", injectedBundleLibrary = SimoptBundleFixtures.library(mm1Jar, lkJar))

    private val mm1BundleId = SimoptBundleFixtures.MM1_BUNDLE_ID
    private val mm1ModelId = SimoptBundleFixtures.MM1_MODEL_ID
    private val lkBundleId = SimoptBundleFixtures.LK_BUNDLE_ID
    private val lkModelId = SimoptBundleFixtures.LK_MODEL_ID

    private fun mm1Ref() = ModelReference.ByBundleAndModelId(mm1BundleId, mm1ModelId)
    private fun lkRef() = ModelReference.ByBundleAndModelId(lkBundleId, lkModelId)

    @Test
    fun `injected library bundles are exposed via loadedBundles`() {
        controller().use { c ->
            val bundles = c.loadedBundles.value
            assertTrue(
                bundles.any { it.bundle.bundleId == mm1BundleId },
                "Expected the MM1 bundle to be loaded; got " +
                    "${bundles.map { it.bundle.bundleId }}"
            )
            assertTrue(
                bundles.any { it.bundle.bundleId == lkBundleId },
                "Expected the LK bundle to be loaded; got " +
                    "${bundles.map { it.bundle.bundleId }}"
            )
            assertNotNull(c.bundleProvider.value, "bundleProvider must be non-null when bundles are loaded")
        }
    }

    @Test
    fun `setModelReference builds a ModelRunTemplate with descriptor defaults`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            val template = c.modelTemplate.value
            assertNotNull(template)
            val descriptor = c.currentModelDescriptor.value
            assertNotNull(descriptor)
            assertEquals(
                descriptor.experimentRunDefaults.numberOfReplications,
                template.runParameters.numberOfReplications,
                "Template run parameters should match the descriptor's defaults"
            )
            assertEquals(
                descriptor.experimentRunDefaults.lengthOfReplication,
                template.runParameters.lengthOfReplication,
                "Length should match"
            )
        }
    }

    @Test
    fun `setModelReference publishes the descriptor`() {
        controller().use { c ->
            assertNull(c.currentModelDescriptor.value)
            c.setModelReference(mm1Ref())
            assertNotNull(c.currentModelDescriptor.value)
        }
    }

    @Test
    fun `setModelReference to an unloaded bundle leaves descriptor null`() {
        controller().use { c ->
            c.setModelReference(
                ModelReference.ByBundleAndModelId("ksl.examples.nonexistent", "X")
            )
            assertNotNull(c.modelTemplate.value, "Template is still installed for an unloaded ref")
            assertNull(c.currentModelDescriptor.value, "Descriptor must be null for unresolved ref")
        }
    }

    @Test
    fun `setModelReferenceAndClear drops problemSpec and solverSpec`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            c.setProblemSpec(
                OptimizationProblemSpec(
                    objectiveResponseName = "FillRate",
                    inputs = listOf(OptimizationInputSpec("x", 0.0, 10.0))
                )
            )
            c.setSolverSpec(
                SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 1)
            )
            assertNotNull(c.problemSpec.value)
            assertNotNull(c.solverSpec.value)

            val analysisBefore = c.output.value.analysisName
            c.setModelReferenceAndClear(lkRef())

            assertNull(c.problemSpec.value, "problemSpec must clear on switch-and-clear")
            assertNull(c.solverSpec.value, "solverSpec must clear on switch-and-clear")
            assertEquals(
                analysisBefore, c.output.value.analysisName,
                "analysisName must survive a switch-and-clear (not model-specific)"
            )
            assertEquals(lkRef(), c.modelTemplate.value?.modelReference)
        }
    }

    @Test
    fun `setLengthOfReplication updates modelTemplate run parameters`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            c.setLengthOfReplication(500.0)
            assertEquals(500.0, c.modelTemplate.value?.runParameters?.lengthOfReplication)
        }
    }

    @Test
    fun `setLengthOfReplication is a no-op when no model is set`() {
        controller().use { c ->
            assertNull(c.modelTemplate.value)
            c.setLengthOfReplication(123.0)
            assertNull(c.modelTemplate.value, "No model → no template → no-op")
        }
    }

    @Test
    fun `setLengthOfReplication rejects non-positive values`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            assertThrows<IllegalArgumentException> { c.setLengthOfReplication(0.0) }
            assertThrows<IllegalArgumentException> { c.setLengthOfReplication(-1.0) }
        }
    }

    @Test
    fun `setLengthOfReplicationWarmUp accepts zero and rejects negatives`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            c.setLengthOfReplicationWarmUp(0.0)  // zero is valid
            assertEquals(0.0, c.modelTemplate.value?.runParameters?.lengthOfReplicationWarmUp)
            assertThrows<IllegalArgumentException> { c.setLengthOfReplicationWarmUp(-1.0) }
        }
    }

    @Test
    fun `setNumberOfReplications updates the baseline replication count`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            c.setNumberOfReplications(42)
            assertEquals(42, c.modelTemplate.value?.runParameters?.numberOfReplications)
        }
    }

    @Test
    fun `setNumberOfReplications rejects non-positive values`() {
        controller().use { c ->
            c.setModelReference(mm1Ref())
            assertThrows<IllegalArgumentException> { c.setNumberOfReplications(0) }
            assertThrows<IllegalArgumentException> { c.setNumberOfReplications(-3) }
        }
    }

    @Test
    fun `setModelReference marks the document dirty`() {
        controller().use { c ->
            assertFalse(c.isDirty.value, "Fresh document is clean")
            c.setModelReference(mm1Ref())
            assertTrue(c.isDirty.value)
            assertTrue(c.editedSinceLastRun.value)
        }
    }

    @Test
    fun `setting a model unlocks the Problem step`() {
        controller().use { c ->
            assertFalse(c.canAdvanceTo(Step.PROBLEM))
            c.setModelReference(mm1Ref())
            assertTrue(c.canAdvanceTo(Step.PROBLEM))
        }
    }

    @Test
    fun `loadBundleJar with a non-existent path returns Failed`() {
        controller().use { c ->
            val result = c.loadBundleJar(Path.of("/does/not/exist.jar"))
            assertTrue(
                result is BundleLibraryController.LoadBundleResult.Failed,
                "Expected Failed for a nonexistent path; got $result"
            )
        }
    }

    @Test
    fun `loadBundleJar of an already-loaded bundle is a silent duplicate`() {
        // The injected library already holds the MM1 bundle; re-loading the
        // same JAR must be reported as a duplicate (AlreadyLoaded / NoBundles)
        // and must not grow the loaded set.
        controller().use { c ->
            val before = c.loadedBundles.value.size
            val result = c.loadBundleJar(mm1Jar)
            assertTrue(
                result is BundleLibraryController.LoadBundleResult.AlreadyLoaded ||
                    result is BundleLibraryController.LoadBundleResult.NoBundles,
                "Re-loading an already-loaded bundle must be a silent duplicate; got $result"
            )
            assertEquals(before, c.loadedBundles.value.size, "Duplicate load must not grow the loaded set")
        }
    }

    @Test
    fun `currentConfiguration returns non-null after only a model is set`() {
        // Phase O4.1: partial-save support.  As soon as a model is
        // selected the controller can snapshot a draft document with
        // null problem / solver.
        controller().use { c ->
            assertNull(c.currentConfiguration(),
                "Fresh document has no model — snapshot should be null")
            c.setModelReference(mm1Ref())
            val snap = c.currentConfiguration()
            assertNotNull(snap, "After model is set, snapshot should be non-null")
            assertNull(snap.problem, "Partial draft has null problem")
            assertNull(snap.solver, "Partial draft has null solver")
        }
    }

    @Test
    fun `saveConfiguration writes a partial document after only a model is set`(@TempDir tempDir: Path) {
        // The previous (pre-O4.1) gate required problem + solver to
        // be set before save; this test pins the relaxed behaviour.
        controller().use { c ->
            c.setModelReference(mm1Ref())
            val target = tempDir.resolve("draft.toml")
            c.saveConfiguration(target)
            assertTrue(target.toFile().exists())
            assertFalse(c.isDirty.value, "Save should clear the dirty flag")
            // Re-decode and verify the saved draft carries null problem
            // / solver.  Checking the raw text via substring match is
            // unreliable because the document-header banner contains
            // the literal text "[problem]" as commentary.
            val decoded = ksl.app.config.optimization
                .OptimizationRunConfigurationToml.decode(target.toFile().readText())
            assertNotNull(decoded.model)
            assertNull(decoded.problem, "Partial save must carry null problem")
            assertNull(decoded.solver, "Partial save must carry null solver")
        }
    }

    @Test
    fun `loadConfiguration of a partial doc populates model and leaves problem and solver null`(
        @TempDir tempDir: Path
    ) {
        // Save a draft from one controller, load it into another;
        // assert the load restores the editor state for continued
        // editing.
        val target = tempDir.resolve("draft.toml")
        controller().use { writer ->
            writer.setModelReference(mm1Ref())
            writer.saveConfiguration(target)
        }
        controller().use { reader ->
            val result = reader.loadConfiguration(target)
            assertTrue(
                result is SimoptAppController.LoadResult.Success,
                "Expected Success; got $result"
            )
            assertNotNull(reader.modelTemplate.value)
            assertNull(reader.problemSpec.value)
            assertNull(reader.solverSpec.value)
            assertTrue(reader.canAdvanceTo(ksl.app.swing.simopt.stepper.Step.PROBLEM),
                "Loaded partial doc with model set must unlock the Problem step")
            assertFalse(reader.canAdvanceTo(ksl.app.swing.simopt.stepper.Step.ALGORITHM),
                "No solver yet — Algorithm step must stay locked")
        }
    }

    @Test
    fun `save under workspace configs directory works and creates it lazily`(@TempDir tempDir: Path) {
        // Mirrors the convention used by Experiment / Scenario / Single
        // apps: TOML documents land in <appWorkspace>/configs/, created
        // lazily on first access via WorkspaceLayout.configsDir.
        controller().use { c ->
            // Re-point the active workspace at a TempDir so the test
            // doesn't write under the user's real KSLWork.
            c.settingsStore.setCurrentDirectory(tempDir)
            c.setModelReference(mm1Ref())

            val configsDir = ksl.app.settings.WorkspaceLayout.configsDir(
                c.appWorkspace, createIfMissing = true
            )
            assertTrue(java.nio.file.Files.isDirectory(configsDir),
                "configsDir(... createIfMissing=true) must create the directory")
            assertTrue(configsDir.endsWith(java.nio.file.Path.of(c.appNameSanitized, "configs")),
                "configsDir should resolve to <appNameSanitized>/configs; got $configsDir")

            val target = configsDir.resolve("draft.toml")
            c.saveConfiguration(target)
            assertTrue(target.toFile().exists())
            assertFalse(c.isDirty.value, "Save should clear the dirty flag")
        }
    }
}
