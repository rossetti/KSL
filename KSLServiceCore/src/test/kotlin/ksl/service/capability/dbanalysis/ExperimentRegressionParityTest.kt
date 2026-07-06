package ksl.service.capability.dbanalysis

import kotlinx.coroutines.runBlocking
import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.experiments.Factor
import ksl.controls.experiments.LinearModel
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.modeling.variable.Response
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelElement
import ksl.utilities.io.dbutil.KSLDatabase
import org.junit.jupiter.api.Assertions.assertArrayEquals
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/** Element "F" with two numeric controls (keys `F.x1`, `F.x2`) whose response echoes a
 *  known linear-with-interaction function of them, once per replication. Top-level (not a
 *  private nested class) so the reflection-based Controls framework can set the controls. */
class TwoControlLinear(parent: ModelElement, name: String) : ModelElement(parent, name) {
    @set:KSLControl(controlType = ControlType.DOUBLE)
    var x1: Double = 0.0

    @set:KSLControl(controlType = ControlType.DOUBLE)
    var x2: Double = 0.0

    private val myY = Response(this, "Y")
    override fun replicationEnded() {
        myY.value = 3.0 + 2.0 * x1 - 1.5 * x2 + 0.5 * x1 * x2
    }
}

/**
 * Proves the DB-backed regression reader (`experimentRegressionResults`) reproduces the
 * desktop `DesignedExperimentIfc.regressionResults` exactly, by running one designed
 * experiment with a database and comparing the coefficients computed from the *live*
 * object against those the reader recovers from the same database.
 *
 * The model is deterministic — `Y = 3 + 2·x1 − 1.5·x2 + 0.5·x1·x2` — so the full-model
 * fit over a 2² factorial is exact, giving known coefficients (a correctness check) in
 * addition to the live-vs-database parity check.
 */
class ExperimentRegressionParityTest {

    private fun modelBuilder(modelName: String) = object : ModelBuilderIfc {
        override fun build(
            modelConfiguration: Map<String, String>?,
            experimentRunParameters: ExperimentRunParametersIfc?,
        ): Model {
            val model = Model(modelName, autoCSVReports = false)
            model.lengthOfReplication = 1.0
            TwoControlLinear(model, "F")
            return model
        }
    }

    private fun runExperimentWithDatabase(): Pair<ParallelDesignedExperiment, KSLDatabase> {
        val fx1 = TwoLevelFactor("x1", low = 1.0, high = 3.0)
        val fx2 = TwoLevelFactor("x2", low = 2.0, high = 4.0)
        val design = TwoLevelFactorialDesign(setOf(fx1, fx2))
        val factorSettings = mapOf<Factor, String>(fx1 to "F.x1", fx2 to "F.x2")
        val tmp = Files.createTempDirectory("exp-reg-parity")
        val db = KSLDatabase("experiment.db", tmp)
        val pde = ParallelDesignedExperiment(
            name = "ExpRegParity",
            modelBuilder = modelBuilder("ExpRegParityModel"),
            factorSettings = factorSettings,
            design = design,
            pathToOutputDirectory = tmp,
            kslDb = db,
            experimentName = "ExpRegParity",
            useDesignPointOutputDirs = false,
        )
        runBlocking { pde.simulateAll(numRepsPerDesignPoint = 3) }
        return pde to db
    }

    /** The desktop linear model over factor names, mirrored by the reader over control keys.
     *  Both list main effects then the interaction, so `parameters` align by position. */
    private fun desktopModel() = LinearModel(setOf("x1", "x2")).apply { twoWay("x1", "x2") }
    private val readerEffects = listOf("F.x1", "F.x2")
    private val readerInteractions = listOf(listOf("F.x1", "F.x2"))

    @Test
    fun `raw regression matches the desktop and recovers the known coefficients`() {
        val (pde, db) = runExperimentWithDatabase()
        val reference = pde.regressionResults("Y", desktopModel(), coded = false)
        val subject = DatabaseAnalysisService().use { svc ->
            svc.experimentRegressionResults(
                svc.attach(db), "Y", readerEffects, readerInteractions, coded = false,
            )
        }
        // Live-object vs database parity: identical coefficients (intercept, x1, x2, x1*x2).
        assertArrayEquals(reference.parameters, subject.parameters, 1e-8,
            "DB-recovered raw coefficients must match the live DesignedExperiment")
        // Correctness: the deterministic model's exact coefficients.
        assertArrayEquals(doubleArrayOf(3.0, 2.0, -1.5, 0.5), subject.parameters, 1e-6,
            "raw fit must recover Y = 3 + 2·x1 − 1.5·x2 + 0.5·x1·x2")
    }

    @Test
    fun `coded regression matches the desktop`() {
        val (pde, db) = runExperimentWithDatabase()
        val reference = pde.regressionResults("Y", desktopModel(), coded = true)
        val subject = DatabaseAnalysisService().use { svc ->
            svc.experimentRegressionResults(
                svc.attach(db), "Y", readerEffects, readerInteractions, coded = true,
            )
        }
        // The reader codes from the observed factor extremes; for a 2-level design those
        // are the factor low/high, so ±1 coding matches the desktop exactly.
        assertArrayEquals(reference.parameters, subject.parameters, 1e-8,
            "DB-recovered coded coefficients must match the live DesignedExperiment")
        assertTrue(subject.predictorNames.contains("F.x1*F.x2"),
            "the interaction term should be present as a predictor")
    }
}
