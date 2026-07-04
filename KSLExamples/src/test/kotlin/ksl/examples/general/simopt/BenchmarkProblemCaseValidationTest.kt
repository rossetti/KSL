package ksl.examples.general.simopt

import ksl.examples.general.models.inventory.twoEchelonProblemCase
import ksl.examples.general.supplychain.BuildMultiEchelonNetworkOptModel
import ksl.examples.general.supplychain.multiEchelonNetworkProblemCase
import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.NoisySphere
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Validates the DEDS benchmark problem cases against freshly built models — every
 * input name must be a control key of the model and every response name must exist —
 * so that model/control renames surface here instead of failing mid-benchmark. Also
 * verifies the standard solver-case registry creates fresh, distinct, correctly named
 * solver instances. Only model CONSTRUCTION happens here; nothing is simulated.
 */
@Timeout(120)
class BenchmarkProblemCaseValidationTest {

    @Test
    @DisplayName("The LK inventory problem case matches its model's controls and responses")
    fun lkInventoryCaseMatchesModel() {
        val case = lkInventoryProblemCase()
        val pd = case.problemDefinitionFactory()
        val model = BuildLKModel.build(null, null)
        assertTrue(pd.validateProblemDefinition(model))
    }

    @Test
    @DisplayName("The (R,Q) inventory problem case matches its model's controls and responses")
    fun rqInventoryCaseMatchesModel() {
        val case = rqInventoryProblemCase()
        val pd = case.problemDefinitionFactory()
        val model = BuildRQModel.build(null, null)
        assertTrue(pd.validateProblemDefinition(model))
    }

    @Test
    @DisplayName("Both two-echelon problem cases match their model's controls and responses")
    fun twoEchelonCasesMatchModel() {
        val model = ksl.examples.general.models.inventory.BuildTwoEchelonModel.build(null, null)
        for (constrained in listOf(true, false)) {
            val pd = twoEchelonProblemCase(constrained).problemDefinitionFactory()
            assertTrue(pd.validateProblemDefinition(model)) {
                "constrained=$constrained problem definition does not match the model"
            }
        }
    }

    @Test
    @DisplayName("The multi-echelon network problem case matches its model's controls and responses")
    fun multiEchelonNetworkCaseMatchesModel() {
        val case = multiEchelonNetworkProblemCase()
        val pd = case.problemDefinitionFactory()
        val model = BuildMultiEchelonNetworkOptModel.build(null, null)
        assertTrue(pd.validateProblemDefinition(model))
        assertEquals(8, pd.inputNames.size)
        assertEquals(3, pd.responseConstraints.size)
    }

    @Test
    @DisplayName("The standard solver-case registry creates fresh, distinct, named instances")
    fun standardSolverCasesCreateFreshInstances() {
        val cases = standardSolverCases()
        assertEquals(5, cases.size)
        assertEquals(cases.size, cases.map { it.label }.toSet().size)
        // an integer-ordered synthetic problem serves all five (R-SPLINE included)
        val sphere = NoisySphere(2, NoiseLevel.LOW)
        val pd = sphere.problemDefinition()
        val evaluatorFactory = FunctionMemberEvaluatorFactory(pd, sphere.responseFunctionBuilder())
        for (case in cases) {
            val evaluator = evaluatorFactory.createEvaluator(0)
            val first = case.solverFactory.create(pd, evaluator, 0, "${case.label}_a")
            val second = case.solverFactory.create(pd, evaluator, 1, "${case.label}_b")
            assertNotSame(first, second) { "${case.label} did not create a fresh instance" }
            assertTrue(first.configurationProperties.isNotEmpty())
        }
    }
}
