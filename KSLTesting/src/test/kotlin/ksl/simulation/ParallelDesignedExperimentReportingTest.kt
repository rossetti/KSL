package ksl.simulation

import ksl.controls.experiments.DesignedExperimentIfc
import ksl.controls.experiments.Factor
import ksl.controls.experiments.ParallelDesignedExperiment
import ksl.controls.experiments.TwoLevelFactor
import ksl.controls.experiments.TwoLevelFactorialDesign
import ksl.examples.book.appendixD.GIGcQueue
import ksl.utilities.io.report.ast.ReportNode
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.designedExperiment
import ksl.utilities.io.report.extensions.designedExperimentRegression
import ksl.utilities.io.report.extensions.toReport
import ksl.utilities.io.report.renderer.RenderContext
import ksl.utilities.io.report.renderer.TextReportRenderer
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParallelDesignedExperimentReportingTest {

    companion object {
        private const val REPS_PER_POINT = 3
        private const val LENGTH = 1000.0
        private const val WARMUP = 200.0
    }

    private lateinit var pde: ParallelDesignedExperiment

    @BeforeAll
    fun setup() {
        pde = buildParallelDesignedExperiment()
        pde.simulateAll(numRepsPerDesignPoint = REPS_PER_POINT)
    }

    @Test
    fun parallelDesignedExperimentImplementsDesignedExperimentInterface() {
        val experiment: Any = pde

        assertTrue(experiment is DesignedExperimentIfc)
    }

    @Test
    fun parallelDesignedExperimentToReportReturnsDocument() {
        assertNotNull(pde.toReport())
    }

    @Test
    fun parallelDesignedExperimentToReportTitleIsSet() {
        val doc = pde.toReport("Parallel DOE Report")

        assertEquals("Parallel DOE Report", doc.title)
    }

    @Test
    fun parallelDesignedExperimentToReportTextContainsSystemTime() {
        val text = pde.toReport().renderToText()

        assertTrue(
            text.contains("System Time"),
            "Parallel designed experiment report must mention the System Time response"
        )
    }

    @Test
    fun parallelDesignedExperimentDslProducesNonEmptyText() {
        val doc = report("Parallel DOE DSL") {
            designedExperiment(pde)
        }

        assertFalse(doc.renderToText().isBlank())
    }

    @Test
    fun parallelDesignedExperimentRegressionDslProducesNonEmptyText() {
        val linearModel = pde.design.linearModel()
        val doc = report("Parallel DOE Regression") {
            designedExperimentRegression(
                de = pde,
                responseName = "System Time",
                linearModel = linearModel,
                showDiagnosticPlots = false
            )
        }

        val text = doc.renderToText()
        assertFalse(text.isBlank())
        assertTrue(text.contains("Regression Setup"))
    }

    private fun buildParallelDesignedExperiment(): ParallelDesignedExperiment {
        val modelName = "ParallelDOEReport_${System.nanoTime()}"
        val fServer = TwoLevelFactor("Server", 1.0, 2.0)
        val fST = TwoLevelFactor("MeanST", 0.5, 0.8)
        val design = TwoLevelFactorialDesign(setOf(fServer, fST))
        val factors = mapOf<Factor, String>(
            fServer to "MM1Q.numServers",
            fST to "$modelName:ServiceTime.mean"
        )
        val builder = object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                val model = Model(modelName, autoCSVReports = false)
                model.lengthOfReplication = LENGTH
                model.lengthOfReplicationWarmUp = WARMUP
                GIGcQueue(
                    model,
                    numServers = 1,
                    ad = ExponentialRV(1.0, 1),
                    sd = ExponentialRV(0.5, 2),
                    name = "MM1Q"
                )
                return model
            }
        }

        return ParallelDesignedExperiment(
            name = "ParallelDOEReport_${System.nanoTime()}",
            modelBuilder = builder,
            factorSettings = factors,
            design = design
        )
    }

    private fun ReportNode.Document.renderToText(): String {
        val ctx = RenderContext()
        val renderer = TextReportRenderer(ctx)
        accept(renderer)
        return renderer.result()
    }
}
