package ksl.app.config.optimization

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.RVParameterOverride
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 5.85 Step 3 acceptance: persistable optimization-configuration shape.
 *
 * These tests verify that [OptimizationRunConfiguration] and the supporting
 * sealed hierarchies ([SolverSpec], [TemperatureSpec], [CoolingScheduleSpec],
 * [CESamplerSpec]) round-trip through JSON and TOML without loss, that the
 * sealed-class type discriminators match the documented `@SerialName` values,
 * and that defaults appear in the encoded output as required by
 * `encodeDefaults = true`.
 *
 * No validation, factory, or session integration is exercised here; that
 * lands in Steps 4, 6, and 8.
 */
class OptimizationRunConfigurationTest {

    // ── 1. JSON round-trip — Stochastic Hill Climbing ─────────────────────────

    @Test
    fun `JSON round-trip preserves a stochastic hill climbing configuration`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 50,
                replicationsPerEvaluation = 4,
                streamNum = 7,
                name = "shc-instance"
            )
        )

        val decoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )

        assertEquals(config, decoded)
    }

    // ── 2. TOML round-trip — Simulated Annealing with calibration + cooling ──

    @Test
    fun `TOML round-trip preserves simulated annealing with auto calibration and exponential cooling`() {
        val config = config(
            solver = SolverSpec.SimulatedAnnealing(
                maxIterations = 100,
                replicationsPerEvaluation = 5,
                temperature = TemperatureSpec.AutoCalibrate(
                    targetProbability = 0.7,
                    sampleSize = 50
                ),
                coolingSchedule = CoolingScheduleSpec.Exponential(
                    initialTemperature = 1000.0,
                    coolingRate = 0.93
                ),
                stoppingTemperature = 0.001,
                streamNum = 13,
                name = "sa-instance"
            )
        )

        val decoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, decoded)
        // verify the temperature variant survived the round-trip
        val sa = decoded.solver as SolverSpec.SimulatedAnnealing
        assertTrue(sa.temperature is TemperatureSpec.AutoCalibrate)
        assertTrue(sa.coolingSchedule is CoolingScheduleSpec.Exponential)
    }

    // ── 3. JSON round-trip — Cross Entropy with normal sampler ───────────────

    @Test
    fun `JSON round-trip preserves cross entropy configuration with normal sampler`() {
        val config = config(
            solver = SolverSpec.CrossEntropy(
                maxIterations = 30,
                replicationsPerEvaluation = 6,
                sampler = CESamplerSpec.Normal(
                    meanSmoother = 0.7,
                    sdSmoother = 0.7,
                    coefficientOfVariationThreshold = 0.05,
                    streamNum = 21
                ),
                elitePct = 0.15,
                ceSampleSize = 75,
                name = "ce-instance"
            )
        )

        val decoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )

        assertEquals(config, decoded)
        val ce = decoded.solver as SolverSpec.CrossEntropy
        assertTrue(ce.sampler is CESamplerSpec.Normal)
        assertEquals(0.15, ce.elitePct)
        assertEquals(75, ce.ceSampleSize)
    }

    // ── 4. JSON round-trip — RSpline ─────────────────────────────────────────

    @Test
    fun `JSON round-trip preserves an R-SPLINE configuration`() {
        val config = config(
            solver = SolverSpec.RSpline(
                maxIterations = 20,
                initialNumReps = 3,
                sampleSizeGrowthRate = 1.1,
                maxNumReplications = 100,
                streamNum = 5,
                name = "rspline-instance"
            )
        )

        val decoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )

        assertEquals(config, decoded)
    }

    // ── 5. RandomRestartSpec nested inside an SA config ──────────────────────

    @Test
    fun `RandomRestartSpec round-trips when nested inside a simulated annealing config`() {
        val config = config(
            solver = SolverSpec.SimulatedAnnealing(
                maxIterations = 100,
                replicationsPerEvaluation = 5,
                temperature = TemperatureSpec.Fixed(temperature = 750.0),
                coolingSchedule = CoolingScheduleSpec.Logarithmic(initialTemperature = 750.0),
                stoppingTemperature = 0.01,
                randomRestart = RandomRestartSpec(maxNumRestarts = 4)
            )
        )

        val jsonDecoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )
        val tomlDecoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertEquals(4, jsonDecoded.solver.randomRestart?.maxNumRestarts)
        assertEquals(4, tomlDecoded.solver.randomRestart?.maxNumRestarts)
    }

    // ── 6. Sealed SolverSpec discriminator coverage ──────────────────────────

    @Test
    fun `sealed SolverSpec uses the documented type discriminators in JSON output`() {
        val shcJson = OptimizationRunConfigurationJson.encode(
            config(SolverSpec.StochasticHillClimbing(maxIterations = 1, replicationsPerEvaluation = 1))
        )
        val saJson = OptimizationRunConfigurationJson.encode(
            config(SolverSpec.SimulatedAnnealing(
                maxIterations = 1,
                replicationsPerEvaluation = 1,
                coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 100.0),
                stoppingTemperature = 0.01
            ))
        )
        val ceJson = OptimizationRunConfigurationJson.encode(
            config(SolverSpec.CrossEntropy(maxIterations = 1, replicationsPerEvaluation = 1))
        )
        val rsJson = OptimizationRunConfigurationJson.encode(
            config(SolverSpec.RSpline(
                maxIterations = 1,
                initialNumReps = 1,
                sampleSizeGrowthRate = 1.0,
                maxNumReplications = 1
            ))
        )

        assertTrue(shcJson.contains(""""type": "stochasticHillClimbing""""),
            "SHC JSON should carry type=stochasticHillClimbing — got:\n$shcJson")
        assertTrue(saJson.contains(""""type": "simulatedAnnealing""""),
            "SA JSON should carry type=simulatedAnnealing — got:\n$saJson")
        assertTrue(ceJson.contains(""""type": "crossEntropy""""),
            "CE JSON should carry type=crossEntropy — got:\n$ceJson")
        assertTrue(rsJson.contains(""""type": "rSpline""""),
            "RSpline JSON should carry type=rSpline — got:\n$rsJson")
    }

    // ── 7. ModelRunTemplate.modelConfiguration survives nested round-trip ────

    @Test
    fun `ModelRunTemplate modelConfiguration survives JSON and TOML round-trips inside an optimization config`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId("MM1"),
                modelConfiguration = mapOf(
                    "input.schema" to "classpath:/models/mm1-schema.json",
                    "input.payload" to """{"a":1.0}"""
                ),
                runParameters = runParameters()
            )
        )

        val jsonDecoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )
        val tomlDecoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertNotNull(jsonDecoded.model.modelConfiguration)
        assertEquals(2, jsonDecoded.model.modelConfiguration!!.size)
    }

    // ── 8. ModelReference.ByJar survives in a full optimization config ───────

    @Test
    fun `ModelReference ByJar survives JSON and TOML round-trips inside an optimization config`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            model = ModelRunTemplate(
                modelReference = ModelReference.ByJar(
                    jarPath = "/tmp/models/example-model.jar",
                    builderClassName = "ksl.examples.ExampleModelBuilder"
                ),
                runParameters = runParameters()
            )
        )

        val jsonDecoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )
        val tomlDecoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertTrue(jsonDecoded.model.modelReference is ModelReference.ByJar)
        assertTrue(tomlDecoded.model.modelReference is ModelReference.ByJar)
    }

    // ── 9. Linear and response constraints round-trip ────────────────────────

    @Test
    fun `LinearConstraintSpec and ResponseConstraintSpec round-trip through JSON and TOML`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            problem = OptimizationProblemSpec(
                problemName = "ConstrainedDemo",
                objectiveResponseName = "TotalCost",
                inputs = listOf(
                    OptimizationInputSpec("x1", lowerBound = 0.0, upperBound = 10.0, granularity = 1.0),
                    OptimizationInputSpec("x2", lowerBound = 0.0, upperBound = 10.0, granularity = 1.0)
                ),
                responseNames = listOf("ServiceLevel"),
                linearConstraints = listOf(
                    LinearConstraintSpec(
                        coefficients = mapOf("x1" to 1.0, "x2" to 2.0),
                        rhsValue = 15.0,
                        inequalityType = InequalityType.LESS_THAN
                    )
                ),
                responseConstraints = listOf(
                    ResponseConstraintSpec(
                        name = "ServiceLevel",
                        rhsValue = 0.95,
                        inequalityType = InequalityType.GREATER_THAN,
                        target = 0.95,
                        tolerance = 0.01
                    )
                )
            )
        )

        val jsonDecoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )
        val tomlDecoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertEquals(1, jsonDecoded.problem.linearConstraints.size)
        assertEquals(1, jsonDecoded.problem.responseConstraints.size)
    }

    // ── 10. Defaults appear in encoded output (encodeDefaults = true) ────────

    @Test
    fun `default values appear in JSON output because encodeDefaults is true`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2)
        )

        val json = OptimizationRunConfigurationJson.encode(config)

        // EvaluationSpec defaults must appear so documents are self-documenting.
        assertTrue(json.contains("\"useSolutionCache\""),
            "EvaluationSpec.useSolutionCache default should appear in JSON output")
        assertTrue(json.contains("\"snapshotFrequency\""),
            "EvaluationSpec.snapshotFrequency default should appear in JSON output")
        // Problem default optimizationType must appear.
        assertTrue(json.contains("\"optimizationType\": \"MINIMIZE\""),
            "OptimizationProblemSpec default optimizationType should appear in JSON output")

        // Round-trip preserves the values populated by defaults.
        val decoded = OptimizationRunConfigurationJson.decode(json)
        assertEquals(EvaluationSpec(), decoded.evaluation)
        assertEquals(OptimizationType.MINIMIZE, decoded.problem.optimizationType)
        assertNull(decoded.solver.randomRestart)
    }

    // ── 11. Per-constraint penalty function round-trips ──────────────────────

    @Test
    fun `per-constraint penaltyFunction round-trips through JSON and TOML`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            problem = OptimizationProblemSpec(
                objectiveResponseName = "TotalCost",
                inputs = listOf(
                    OptimizationInputSpec("x1", lowerBound = 0.0, upperBound = 10.0, granularity = 1.0)
                ),
                responseNames = listOf("ServiceLevel"),
                linearConstraints = listOf(
                    LinearConstraintSpec(
                        coefficients = mapOf("x1" to 1.0),
                        rhsValue = 5.0,
                        penaltyFunction = PenaltyFunctionSpec.DynamicPolynomial(
                            basePenalty = 250.0, iterationExponent = 1.5, violationExponent = 3.0
                        )
                    )
                ),
                responseConstraints = listOf(
                    ResponseConstraintSpec(
                        name = "ServiceLevel",
                        rhsValue = 0.95,
                        inequalityType = InequalityType.GREATER_THAN,
                        penaltyFunction = PenaltyFunctionSpec.WithMemory(
                            basePenalty = 50.0, iterationExponent = 0.5, violationExponent = 2.0
                        )
                    )
                )
            )
        )

        val jsonDecoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )
        val tomlDecoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
        assertTrue(jsonDecoded.problem.linearConstraints.first().penaltyFunction
            is PenaltyFunctionSpec.DynamicPolynomial)
        assertTrue(jsonDecoded.problem.responseConstraints.first().penaltyFunction
            is PenaltyFunctionSpec.WithMemory)
    }

    // ── 12. Problem-level default penalty functions round-trip ───────────────

    @Test
    fun `non-default problem-level penalty defaults round-trip through JSON and TOML`() {
        val config = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 5, replicationsPerEvaluation = 2),
            problem = OptimizationProblemSpec(
                objectiveResponseName = "TotalCost",
                inputs = listOf(
                    OptimizationInputSpec("x1", lowerBound = 0.0, upperBound = 10.0)
                ),
                defaultLinearPenalty = PenaltyFunctionSpec.WithMemory(basePenalty = 200.0),
                defaultResponsePenalty = PenaltyFunctionSpec.DynamicPolynomial(basePenalty = 75.0)
            )
        )

        val jsonDecoded = OptimizationRunConfigurationJson.decode(
            OptimizationRunConfigurationJson.encode(config)
        )
        val tomlDecoded = OptimizationRunConfigurationToml.decode(
            OptimizationRunConfigurationToml.encode(config)
        )

        assertEquals(config, jsonDecoded)
        assertEquals(config, tomlDecoded)
    }

    // ── 13. PenaltyFunctionSpec sealed-class discriminator coverage ──────────

    @Test
    fun `PenaltyFunctionSpec sealed class uses the documented type discriminators`() {
        val configWithDynamic = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 1, replicationsPerEvaluation = 1),
            problem = OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = listOf(OptimizationInputSpec("x", 0.0, 1.0)),
                defaultLinearPenalty = PenaltyFunctionSpec.DynamicPolynomial()
            )
        )
        val configWithMemory = config(
            solver = SolverSpec.StochasticHillClimbing(maxIterations = 1, replicationsPerEvaluation = 1),
            problem = OptimizationProblemSpec(
                objectiveResponseName = "Cost",
                inputs = listOf(OptimizationInputSpec("x", 0.0, 1.0)),
                defaultLinearPenalty = PenaltyFunctionSpec.WithMemory()
            )
        )

        val dynJson = OptimizationRunConfigurationJson.encode(configWithDynamic)
        val memJson = OptimizationRunConfigurationJson.encode(configWithMemory)

        assertTrue(dynJson.contains(""""type": "dynamicPolynomial""""),
            "DynamicPolynomial JSON should carry type=dynamicPolynomial")
        assertTrue(memJson.contains(""""type": "withMemory""""),
            "WithMemory JSON should carry type=withMemory")
    }

    // ── shared fixture builders ──────────────────────────────────────────────

    private fun config(
        solver: SolverSpec,
        problem: OptimizationProblemSpec = defaultProblem(),
        model: ModelRunTemplate = defaultModel(),
        evaluation: EvaluationSpec = EvaluationSpec()
    ): OptimizationRunConfiguration =
        OptimizationRunConfiguration(model, problem, solver, evaluation)

    private fun defaultProblem(): OptimizationProblemSpec =
        OptimizationProblemSpec(
            problemName = "Demo",
            objectiveResponseName = "TotalCost",
            inputs = listOf(
                OptimizationInputSpec(
                    name = "Inventory.orderQuantity",
                    lowerBound = 1.0,
                    upperBound = 100.0,
                    granularity = 1.0
                )
            )
        )

    private fun defaultModel(): ModelRunTemplate =
        ModelRunTemplate(
            modelReference = ModelReference.ByProviderId("MM1"),
            runParameters = runParameters(),
            rvOverrides = listOf(
                RVParameterOverride(
                    rvName = "MM1:ServiceTime",
                    paramName = "mean",
                    value = 2.0
                )
            )
        )

    private fun runParameters() =
        Model("MM1", autoCSVReports = false).also { model ->
            GIGcQueue(model, numServers = 1, name = "MM1Queue")
            model.numberOfReplications = 3
            model.lengthOfReplication = 100.0
            model.lengthOfReplicationWarmUp = 10.0
        }.extractRunParameters()
}
