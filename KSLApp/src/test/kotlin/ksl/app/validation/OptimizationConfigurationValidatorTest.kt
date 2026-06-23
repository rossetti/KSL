package ksl.app.validation

import ksl.app.config.ModelReference
import ksl.app.config.ModelRunTemplate
import ksl.app.config.optimization.CESamplerSpec
import ksl.app.config.optimization.CoolingScheduleSpec
import ksl.app.config.optimization.LinearConstraintSpec
import ksl.app.config.optimization.OptimizationInputSpec
import ksl.app.config.optimization.OptimizationProblemSpec
import ksl.app.config.optimization.OptimizationRunConfiguration
import ksl.app.config.optimization.ResponseConstraintSpec
import ksl.app.config.optimization.SolverSpec
import ksl.app.config.optimization.TemperatureSpec
import ksl.controls.ModelControlsExport
import ksl.controls.ControlData
import ksl.controls.ControlType
import ksl.examples.book.appendixD.GIGcQueue
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.MapModelProvider
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelProviderIfc
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 5.85 Step 4 acceptance: cross-reference and model-dependent
 * validation in [OptimizationConfigurationValidator].  Single-field
 * invariants are enforced in `init` blocks (Step 3.5) and are not
 * exercised here.
 */
class OptimizationConfigurationValidatorTest {

    // ── Test fixtures ────────────────────────────────────────────────────────

    private fun mm1Model(): Model {
        val model = Model(MM1_MODEL_ID, autoCSVReports = false)
        GIGcQueue(model, numServers = 1, name = "MM1Queue")
        model.numberOfReplications = 3
        model.lengthOfReplication = 100.0
        return model
    }

    private val mm1Provider: ModelProviderIfc = MapModelProvider(
        MM1_MODEL_ID,
        object : ModelBuilderIfc {
            override fun build(
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model = mm1Model()
        }
    )

    private fun mm1Inputs(): List<String> = mm1Model().inputKeys()
    private fun mm1Responses(): List<String> = mm1Model().responseNames

    /** Builds a minimal valid optimization config tied to the MM1 probe model. */
    private fun mm1Config(
        problem: OptimizationProblemSpec? = null,
        solver: SolverSpec? = null
    ): OptimizationRunConfiguration {
        val model = mm1Model()
        val firstInputKey = model.inputKeys().first()
        val firstResponseName = model.responseNames.first()

        return OptimizationRunConfiguration(
            model = ModelRunTemplate(
                modelReference = ModelReference.ByProviderId(MM1_MODEL_ID),
                runParameters = model.extractRunParameters()
            ),
            problem = problem ?: OptimizationProblemSpec(
                objectiveResponseName = firstResponseName,
                inputs = listOf(
                    OptimizationInputSpec(
                        name = firstInputKey,
                        lowerBound = 0.1,
                        upperBound = 10.0
                    )
                )
            ),
            solver = solver ?: SolverSpec.StochasticHillClimbing(
                maxIterations = 5,
                replicationsPerEvaluation = 2
            )
        )
    }

    // ── Document-only positive case ──────────────────────────────────────────

    @Test
    fun `valid configuration produces no errors and no warnings`() {
        val result = OptimizationConfigurationValidator.validate(mm1Config())
        assertTrue(result.isValid, "Expected valid; errors: ${result.errors}")
        assertTrue(result.warnings.isEmpty(), "Expected no warnings; got ${result.warnings}")
    }

    @Test
    fun `R-SPLINE on a non-integer-ordered problem is reported`() {
        // The default fixture problem has a continuous input (granularity 0.0), which is not integer ordered.
        val config = mm1Config(
            solver = SolverSpec.RSpline(
                maxIterations = 5,
                initialNumReps = 2,
                sampleSizeGrowthRate = 1.5,
                maxNumReplications = 10
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(result.hasError("problem.inputs", "RSPLINE_REQUIRES_INTEGER_ORDERED"))
    }

    @Test
    fun `R-SPLINE on an integer-ordered problem is accepted`() {
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = mm1Responses().first(),
                inputs = listOf(
                    OptimizationInputSpec(
                        name = mm1Inputs().first(),
                        lowerBound = 1.0,
                        upperBound = 10.0,
                        granularity = 1.0
                    )
                )
            ),
            solver = SolverSpec.RSpline(
                maxIterations = 5,
                initialNumReps = 2,
                sampleSizeGrowthRate = 1.5,
                maxNumReplications = 10
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertTrue(result.isValid, "Expected valid; errors: ${result.errors}")
        assertFalse(result.hasError("problem.inputs", "RSPLINE_REQUIRES_INTEGER_ORDERED"))
    }

    // ── Document-only negative cases ─────────────────────────────────────────

    @Test
    fun `unknown starting-point key is reported`() {
        val config = mm1Config(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                startingPoint = mapOf("nonexistent.key" to 1.0)
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("solver.startingPoint.nonexistent.key", "STARTING_POINT_UNKNOWN_INPUT")
        )
    }

    @Test
    fun `out-of-bounds starting-point value is reported`() {
        val firstInputKey = mm1Inputs().first()
        val config = mm1Config(
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                // declared bounds in the default mm1Config problem are [0.1, 10.0]
                startingPoint = mapOf(firstInputKey to 25.0)
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("solver.startingPoint.$firstInputKey", "STARTING_POINT_OUT_OF_BOUNDS")
        )
    }

    @Test
    fun `linear-constraint coefficient referencing unknown input is reported`() {
        val firstInputKey = mm1Inputs().first()
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = mm1Responses().first(),
                inputs = listOf(
                    OptimizationInputSpec(name = firstInputKey, lowerBound = 0.1, upperBound = 10.0)
                ),
                linearConstraints = listOf(
                    LinearConstraintSpec(
                        coefficients = mapOf("not_a_decision_variable" to 1.0),
                        rhsValue = 5.0
                    )
                )
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "problem.linearConstraints[0].coefficients.not_a_decision_variable",
                "LINEAR_CONSTRAINT_UNKNOWN_INPUT"
            )
        )
    }

    @Test
    fun `response-constraint name not declared in responseNames is reported`() {
        val firstInputKey = mm1Inputs().first()
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = mm1Responses().first(),
                inputs = listOf(
                    OptimizationInputSpec(name = firstInputKey, lowerBound = 0.1, upperBound = 10.0)
                ),
                responseNames = listOf("DeclaredResponse"),
                responseConstraints = listOf(
                    ResponseConstraintSpec(name = "UndeclaredResponse", rhsValue = 1.0)
                )
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "problem.responseConstraints[0].name",
                "RESPONSE_CONSTRAINT_NAME_UNDECLARED"
            )
        )
    }

    @Test
    fun `simulated annealing with stoppingTemperature greater than fixed initial is reported`() {
        val config = mm1Config(
            solver = SolverSpec.SimulatedAnnealing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                temperature = TemperatureSpec.Fixed(temperature = 100.0),
                coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 100.0),
                stoppingTemperature = 100.0
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("solver.stoppingTemperature", "SA_STOPPING_NOT_BELOW_INITIAL")
        )
    }

    @Test
    fun `simulated annealing with cooling-schedule initial temperature mismatch is a warning`() {
        val config = mm1Config(
            solver = SolverSpec.SimulatedAnnealing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                temperature = TemperatureSpec.Fixed(temperature = 100.0),
                coolingSchedule = CoolingScheduleSpec.Exponential(initialTemperature = 50.0),
                stoppingTemperature = 0.01
            )
        )
        val result = OptimizationConfigurationValidator.validate(config)
        // mismatch is a warning, not a blocking error
        assertTrue(result.isValid, "Expected isValid=true (mismatch is warning only); errors: ${result.errors}")
        assertTrue(
            result.hasWarning(
                "solver.coolingSchedule.initialTemperature",
                "SA_COOLING_INITIAL_TEMP_MISMATCH"
            )
        )
    }

    // ── Runtime positive case ───────────────────────────────────────────────

    @Test
    fun `validateForRun on a valid configuration returns isValid`() {
        val result = OptimizationConfigurationValidator.validateForRun(mm1Config(), mm1Provider)
        assertTrue(result.isValid, "Expected valid; errors: ${result.errors}")
    }

    // ── Runtime negative cases ──────────────────────────────────────────────

    @Test
    fun `validateForRun reports unknown provider id`() {
        val config = mm1Config().copy(
            model = mm1Config().model.copy(
                modelReference = ModelReference.ByProviderId("does_not_exist")
            )
        )
        val result = OptimizationConfigurationValidator.validateForRun(config, mm1Provider)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("model.modelReference.providerId", "MODEL_PROVIDER_ID_UNKNOWN")
        )
    }

    @Test
    fun `validateForRun reports decision-variable name not on the model`() {
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = mm1Responses().first(),
                inputs = listOf(
                    OptimizationInputSpec(name = "not.an.input.key", lowerBound = 0.0, upperBound = 1.0)
                )
            )
        )
        val result = OptimizationConfigurationValidator.validateForRun(config, mm1Provider)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("problem.inputs[0].name", "INPUT_NOT_ON_MODEL")
        )
    }

    @Test
    fun `validateForRun reports unknown objective response`() {
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = "NonexistentResponse",
                inputs = listOf(
                    OptimizationInputSpec(
                        name = mm1Inputs().first(),
                        lowerBound = 0.1,
                        upperBound = 10.0
                    )
                )
            )
        )
        val result = OptimizationConfigurationValidator.validateForRun(config, mm1Provider)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("problem.objectiveResponseName", "OBJECTIVE_RESPONSE_UNKNOWN")
        )
    }

    @Test
    fun `validateForRun reports unknown response constraint response`() {
        val firstResponse = mm1Responses().first()
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = firstResponse,
                inputs = listOf(
                    OptimizationInputSpec(name = mm1Inputs().first(), lowerBound = 0.1, upperBound = 10.0)
                ),
                // Declare the constraint name in responseNames so the document-only check passes,
                // but the name doesn't exist on the model — so the runtime check fires.
                responseNames = listOf("PhantomResponse"),
                responseConstraints = listOf(
                    ResponseConstraintSpec(name = "PhantomResponse", rhsValue = 1.0)
                )
            )
        )
        val result = OptimizationConfigurationValidator.validateForRun(config, mm1Provider)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError(
                "problem.responseConstraints[0].name",
                "RESPONSE_CONSTRAINT_RESPONSE_UNKNOWN"
            )
        )
    }

    @Test
    fun `validateForRun reports decision-variable name conflicting with a fixed control`() {
        val firstInputKey = mm1Inputs().first()
        val firstResponse = mm1Responses().first()

        // Build a controls export whose key collides with the decision variable name.
        val controls = ModelControlsExport(
            modelName = MM1_MODEL_ID,
            numericControls = listOf(
                ControlData(
                    controlType = ControlType.DOUBLE,
                    value = 1.0,
                    keyName = firstInputKey,
                    lowerBound = 0.0,
                    upperBound = 10.0,
                    elementName = "Conflict",
                    elementId = 0,
                    elementType = "Test",
                    propertyName = "value",
                    comment = "",
                    modelName = MM1_MODEL_ID
                )
            )
        )

        val config = mm1Config().run {
            copy(
                model = model.copy(controls = controls),
                problem = OptimizationProblemSpec(
                    objectiveResponseName = firstResponse,
                    inputs = listOf(
                        OptimizationInputSpec(
                            name = firstInputKey,
                            lowerBound = 0.1,
                            upperBound = 10.0
                        )
                    )
                )
            )
        }

        val result = OptimizationConfigurationValidator.validateForRun(config, mm1Provider)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("problem.inputs[0].name", "INPUT_CONFLICTS_WITH_CONTROL"),
            "Expected INPUT_CONFLICTS_WITH_CONTROL; errors: ${result.errors}"
        )
    }

    // ── Document-error short-circuit ────────────────────────────────────────

    @Test
    fun `validateForRun does not invoke provider when document errors are present`() {
        val firstInputKey = mm1Inputs().first()
        val firstResponse = mm1Responses().first()

        // A document-level error: starting-point key references an unknown input.
        val config = mm1Config(
            problem = OptimizationProblemSpec(
                objectiveResponseName = firstResponse,
                inputs = listOf(
                    OptimizationInputSpec(name = firstInputKey, lowerBound = 0.1, upperBound = 10.0)
                )
            ),
            solver = SolverSpec.StochasticHillClimbing(
                maxIterations = 5,
                replicationsPerEvaluation = 2,
                startingPoint = mapOf("not_a_real_input" to 1.0)
            )
        )

        // Throwing provider would make the test fail loudly if the validator tried to use it.
        val throwingProvider = object : ModelProviderIfc {
            override fun isModelProvided(modelIdentifier: String): Boolean {
                throw AssertionError("provider must not be consulted when document errors exist")
            }
            override fun provideModel(
                modelIdentifier: String,
                modelConfiguration: Map<String, String>?,
                experimentRunParameters: ExperimentRunParametersIfc?
            ): Model {
                throw AssertionError("provider must not be consulted when document errors exist")
            }
            override fun modelIdentifiers(): List<String> = listOf(MM1_MODEL_ID)
        }

        val result = OptimizationConfigurationValidator.validateForRun(config, throwingProvider)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("solver.startingPoint.not_a_real_input", "STARTING_POINT_UNKNOWN_INPUT")
        )
    }

    // ── Draft-document (null problem / solver) errors ────────────────────────

    @Test
    fun `validate flags missing problem section`() {
        val config = mm1Config().copy(problem = null)
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("problem", "MISSING_SECTION"),
            "Expected MISSING_SECTION at path 'problem'; got ${result.errors}"
        )
    }

    @Test
    fun `validate flags missing solver section`() {
        val config = mm1Config().copy(solver = null)
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(
            result.hasError("solver", "MISSING_SECTION"),
            "Expected MISSING_SECTION at path 'solver'; got ${result.errors}"
        )
    }

    @Test
    fun `validate flags both missing problem and solver in one pass`() {
        val config = mm1Config().copy(problem = null, solver = null)
        val result = OptimizationConfigurationValidator.validate(config)
        assertFalse(result.isValid)
        assertTrue(result.hasError("problem", "MISSING_SECTION"))
        assertTrue(result.hasError("solver", "MISSING_SECTION"))
    }

    @Test
    fun `validate skips cross-reference checks when problem is null`() {
        // A null problem section means there's nothing to cross-reference
        // against; the validator should report MISSING_SECTION only and
        // not produce additional STARTING_POINT_UNKNOWN_INPUT or similar
        // errors that would otherwise be triggered by the (now-missing)
        // inputs list.
        val config = mm1Config().copy(problem = null)
        val result = OptimizationConfigurationValidator.validate(config)
        assertTrue(result.errors.all { it.code == "MISSING_SECTION" },
            "Expected only MISSING_SECTION errors; got ${result.errors.map { it.code }}")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun ValidationResult.hasError(path: String, code: String): Boolean =
        errors.any { it.path == path && it.code == code }

    private fun ValidationResult.hasWarning(path: String, code: String): Boolean =
        warnings.any { it.path == path && it.code == code }

    private companion object {
        const val MM1_MODEL_ID = "MM1OptValidatorTest"
    }
}
