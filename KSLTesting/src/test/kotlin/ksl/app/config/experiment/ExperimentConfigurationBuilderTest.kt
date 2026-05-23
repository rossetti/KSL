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

package ksl.app.config.experiment

import ksl.app.config.ExecutionMode
import ksl.app.config.ModelReference
import ksl.app.config.OutputConfig
import ksl.controls.experiments.DesignedExperiment
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.examples.general.models.LKInventoryModel
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 *  End-to-end builder tests for [toDesignedExperiment].
 *
 *  The LK Inventory model is used as the substrate — it exposes
 *  several `@KSLControl`-marked properties (`Inventory.orderQuantity`,
 *  `Inventory.reorderPoint`, etc.) that the
 *  `ParallelDesignedExperiment` / `DesignedExperiment` constructors
 *  validate factor bindings against.  No simulations are run here
 *  (orchestrator-level end-to-end testing lives elsewhere); these
 *  tests just verify the builder produces the right substrate shape.
 */
class ExperimentConfigurationBuilderTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `CONCURRENT executionMode returns ParallelDesignedExperiment`() {
        val cfg = lkConfig(executionMode = ExecutionMode.CONCURRENT)
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        assertIs<ParallelDesignedExperiment>(exp)
    }

    @Test
    fun `SEQUENTIAL executionMode returns DesignedExperiment`() {
        val cfg = lkConfig(executionMode = ExecutionMode.SEQUENTIAL)
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        assertIs<DesignedExperiment>(exp)
    }

    @Test
    fun `FullFactorial design enumerates the Cartesian product of factor levels`() {
        // 2 factors × 2 levels each = 4 design points.
        val cfg = lkConfig(designSpec = DesignSpec.FullFactorial)
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pointCount = exp.design.designIterator().asSequence().count()
        assertEquals(4, pointCount)
    }

    @Test
    fun `TwoLevelFactorial (full) realises every factor-level combination`() {
        // 2 factors -> 4 design points.
        val cfg = lkConfig(designSpec = DesignSpec.TwoLevelFactorial(Fraction.Full))
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pointCount = exp.design.designIterator().asSequence().count()
        assertEquals(4, pointCount)
    }

    @Test
    fun `TwoLevelFactorial (half fraction) realises a half-fraction design`() {
        // 2-factor half-fraction (2^(2-1) = 2 points).
        val cfg = lkConfig(
            designSpec = DesignSpec.TwoLevelFactorial(
                fraction = Fraction.HalfFraction(sign = +1)
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pointCount = exp.design.designIterator().asSequence().count()
        assertEquals(2, pointCount)
    }

    @Test
    fun `TwoLevelFactorial (custom fraction) realises a custom-relation fraction`() {
        // 2-factor custom fraction with the single generator 'AB' -> 2 points.
        val cfg = lkConfig(
            designSpec = DesignSpec.TwoLevelFactorial(
                fraction = Fraction.Custom(words = listOf(listOf(1, 2)))
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pointCount = exp.design.designIterator().asSequence().count()
        assertEquals(2, pointCount)
    }

    @Test
    fun `CentralComposite design adds factorial + axial + single centre design point`() {
        // k=2 factors:
        //   factorial points = 2^2 = 4
        //   axial points     = 2 * 2 = 4
        //   centre points    = 1 (one DesignPoint with numCenterReps reps)
        val cfg = lkConfig(
            designSpec = DesignSpec.CentralComposite(
                axialSpacing = AxialSpacing.Explicit(1.414),
                numCenterReps = 3
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pointCount = exp.design.designIterator().asSequence().count()
        // 4 factorial + 4 axial + 1 centre = 9.
        assertEquals(9, pointCount)
    }

    @Test
    fun `CentralComposite preserves its three-way rep split`() {
        // numFactorialReps = 2, numAxialReps = 3, numCenterReps = 5
        // document-level Uniform(10) MUST NOT override the CCD knobs.
        val cfg = lkConfig(
            designSpec = DesignSpec.CentralComposite(
                axialSpacing = AxialSpacing.Explicit(1.414),
                numFactorialReps = 2,
                numAxialReps = 3,
                numCenterReps = 5
            ),
            replications = ReplicationSpec.Uniform(10)
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pts = exp.design.designIterator().asSequence().toList()
        assertEquals(9, pts.size)
        // First 4 points are the factorial portion.
        for (i in 0 until 4) assertEquals(2, pts[i].numReplications,
            "factorial point $i expected numFactorialReps=2")
        // Next 4 are axial.
        for (i in 4 until 8) assertEquals(3, pts[i].numReplications,
            "axial point $i expected numAxialReps=3")
        // Last is centre.
        assertEquals(5, pts[8].numReplications, "centre point expected numCenterReps=5")
    }

    @Test
    fun `CentralComposite with rotatable axial spacing builds without error`() {
        val cfg = lkConfig(
            designSpec = DesignSpec.CentralComposite(
                axialSpacing = AxialSpacing.Rotatable,
                numCenterReps = 1
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        // 4 factorial + 4 axial + 1 centre = 9 (k=2, full factorial core).
        assertEquals(9, exp.design.designIterator().asSequence().count())
    }

    @Test
    fun `Manual design materialises caller-supplied points`() {
        val cfg = lkConfig(
            designSpec = DesignSpec.Manual(
                points = listOf(
                    ManualPointSpec(
                        factorValues = mapOf("OrderQuantity" to 15.0, "ReorderPoint" to 20.0),
                        replications = 7
                    ),
                    ManualPointSpec(
                        factorValues = mapOf("OrderQuantity" to 25.0, "ReorderPoint" to 25.0)
                    )
                )
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val points = exp.design.designIterator().asSequence().toList()
        assertEquals(2, points.size)
        // Per-point inline replications survive into the substrate.
        assertEquals(7, points[0].numReplications)
        // Second point has no inline replications; document-level
        // default applies (Uniform(10) per lkConfig).
        assertEquals(10, points[1].numReplications)
    }

    @Test
    fun `Uniform replications apply to every design point`() {
        val cfg = lkConfig(
            designSpec = DesignSpec.FullFactorial,
            replications = ReplicationSpec.Uniform(25)
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pts = exp.design.designIterator().asSequence().toList()
        for (p in pts) assertEquals(25, p.numReplications)
    }

    @Test
    fun `PerPoint replications apply with overrides honoured`() {
        // Override index 0 to 99 reps; others get default 10.
        val cfg = lkConfig(
            designSpec = DesignSpec.FullFactorial,
            replications = ReplicationSpec.PerPoint(
                default = 10,
                overrides = mapOf(0 to 99)
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        val pts = exp.design.designIterator().asSequence().toList()
        assertEquals(4, pts.size)
        assertEquals(99, pts[0].numReplications)
        for (i in 1 until pts.size) assertEquals(10, pts[i].numReplications)
    }

    @Test
    fun `Control binding encodes as the bare controlKey`() {
        // Use ParallelDesignedExperiment's factorSettings via the
        // built-in reflection-free toString path of the inner Map.
        val cfg = lkConfig()
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir) as ParallelDesignedExperiment
        // The substrate validated the keys against the model; reaching
        // construction without an exception means the keys resolved.
        // Surface the keys for assertion via the design's factors map
        // (factor → name) cross-referenced against the document.
        val factorNames = exp.design.factors.keys
        assertTrue("OrderQuantity" in factorNames)
        assertTrue("ReorderPoint" in factorNames)
    }

    @Test
    fun `Independent stream policy with non-default knobs is applied`() {
        val cfg = lkConfig(
            streamPolicy = StreamPolicy.Independent(
                startingStreamAdvance = 7,
                streamAdvanceSpacing = 50
            )
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir) as ParallelDesignedExperiment
        // streamPolicy is exposed publicly on the substrate; the only
        // observable signal that the fluent setter ran is the enum
        // value itself (the advance knobs are private).  Independent
        // is the substrate default but applyStreamPolicy still calls
        // the setter — verify the enum is consistent.
        assertEquals(
            ksl.controls.experiments.DesignPointRandomStreamPolicy.INDEPENDENT_RANDOM_STREAMS,
            exp.streamPolicy
        )
    }

    @Test
    fun `Common random numbers stream policy is applied under CONCURRENT`() {
        val cfg = lkConfig(streamPolicy = StreamPolicy.CommonRandomNumbers)
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir) as ParallelDesignedExperiment
        assertEquals(
            ksl.controls.experiments.DesignPointRandomStreamPolicy.COMMON_RANDOM_NUMBERS,
            exp.streamPolicy
        )
    }

    @Test
    fun `Stream policy is ignored under SEQUENTIAL`() {
        // SEQUENTIAL uses DesignedExperiment which doesn't expose a
        // stream-policy knob.  The builder must not attempt to apply
        // streamPolicy in this branch — verified by the absence of an
        // exception and the returned type being the sequential
        // variant.  (The controller is responsible for surfacing a
        // warning when SEQUENTIAL + CRN is configured; that surface
        // is Phase E3, not Phase E2.)
        val cfg = lkConfig(
            executionMode = ExecutionMode.SEQUENTIAL,
            streamPolicy = StreamPolicy.CommonRandomNumbers
        )
        val exp = cfg.toDesignedExperiment(lkBuilder, tempDir)
        assertIs<DesignedExperiment>(exp)
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    /** Builder that constructs the LK Inventory model fresh per call.
     *  Mirrors the pattern in KSLAppSessionTest. */
    private val lkBuilder: ModelBuilderIfc = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?
        ): Model {
            val model = Model("LKInventory", autoCSVReports = false)
            LKInventoryModel(model, "Inventory")
            model.lengthOfReplication = 120.0
            model.numberOfReplications = 5
            model.lengthOfReplicationWarmUp = 20.0
            return model
        }
    }

    /** Build a standard 2-factor LK Inventory experiment configuration. */
    private fun lkConfig(
        designSpec: DesignSpec = DesignSpec.FullFactorial,
        replications: ReplicationSpec = ReplicationSpec.Uniform(10),
        streamPolicy: StreamPolicy = StreamPolicy.Independent(),
        executionMode: ExecutionMode = ExecutionMode.CONCURRENT
    ): ExperimentConfiguration = ExperimentConfiguration(
        outputConfig = OutputConfig(analysisName = "BuilderTest"),
        modelReference = ModelReference.Embedded("LKInventory"),
        factors = listOf(
            FactorSpec(
                name = "OrderQuantity",
                levels = listOf(10.0, 30.0),
                binding = ControlBinding.Control("Inventory.orderQuantity")
            ),
            FactorSpec(
                name = "ReorderPoint",
                levels = listOf(10.0, 30.0),
                binding = ControlBinding.Control("Inventory.reorderPoint")
            )
        ),
        designSpec = designSpec,
        replications = replications,
        streamPolicy = streamPolicy,
        executionMode = executionMode
    )
}
