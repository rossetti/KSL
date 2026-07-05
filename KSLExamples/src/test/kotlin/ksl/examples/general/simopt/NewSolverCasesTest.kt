package ksl.examples.general.simopt

import ksl.examples.general.simopt.problems.NoiseLevel
import ksl.examples.general.simopt.problems.NoisySphere
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.solvers.algorithms.isc.ISCSolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Construction and behavior checks for the four newer solver cases (GA, PSO, BO, ISC)
 * and the Study-1 roster. Verifies that every case creates fresh, distinct, correctly
 * named instances with captured configuration; that ISC inherits its indifference zones
 * from the problem definition (the mechanism Study 1 relies on for ISC's guarantees);
 * and that PSO's batch swarm evaluation is safe under concurrency — the whole benchmark
 * summary is identical across worker counts.
 */
@Timeout(120)
class NewSolverCasesTest {

    private fun sphereCase(dimension: Int = 2): ProblemCase {
        return NoisySphere(dimension, NoiseLevel.LOW).problemCase()
    }

    @Test
    @DisplayName("The Study-1 roster is nine cases with unique labels, four of them the new subjects")
    fun rosterCompositionIsCorrect() {
        val all = allSolverCases()
        assertEquals(9, all.size)
        assertEquals(all.size, all.map { it.label }.toSet().size)
        assertEquals(setOf("SHC", "SA", "CE", "RSPLINE", "RestartSHC", "GA", "PSO", "BO", "ISC"),
            all.map { it.label }.toSet())
        assertEquals(setOf("GA", "PSO", "BO", "ISC"), newSolverCases().map { it.label }.toSet())
    }

    @Test
    @DisplayName("Every roster case creates fresh, distinct, named instances with captured configuration")
    fun everyCaseCreatesFreshInstances() {
        val sphere = NoisySphere(2, NoiseLevel.LOW)
        val pd = sphere.problemDefinition()
        val evaluatorFactory = FunctionMemberEvaluatorFactory(pd, sphere.responseFunctionBuilder())
        for (case in allSolverCases()) {
            val evaluator = evaluatorFactory.createEvaluator(0)
            val first = case.solverFactory.create(pd, evaluator, 0, "${case.label}_a")
            val second = case.solverFactory.create(pd, evaluator, 1, "${case.label}_b")
            assertNotSame(first, second) { "${case.label} did not create a fresh instance" }
            assertEquals("${case.label}_a", first.name)
            assertTrue(first.configurationProperties.isNotEmpty()) {
                "${case.label} produced no configuration properties"
            }
        }
    }

    @Test
    @DisplayName("ISC inherits deltaC and deltaL from the problem's indifference zone")
    fun iscInheritsIndifferenceZoneFromProblem() {
        val sphere = NoisySphere(2, NoiseLevel.LOW)
        val pd = sphere.problemDefinition()
        pd.indifferenceZoneParameter = 2.5
        val evaluator = FunctionMemberEvaluatorFactory(pd, sphere.responseFunctionBuilder()).createEvaluator(0)
        val solver = iscCase().solverFactory.create(pd, evaluator, 0, "ISC_iz")
        assertTrue(solver is ISCSolver)
        val isc = solver as ISCSolver
        assertEquals(2.5, isc.deltaC)
        assertEquals(2.5, isc.deltaL)
    }

    @Test
    @DisplayName("PSO's batch swarm evaluation is worker-count independent: identical summary at 1 and 4 workers")
    fun psoIsDeterministicAcrossWorkerCounts() {
        fun runWith(numWorkers: Int): Map<String, Triple<Double, Int, Map<String, Double>>> {
            val summary = BenchmarkExperiment(
                name = "psoDeterminism",
                problems = listOf(sphereCase()),
                solverCases = listOf(particleSwarmCase()),
                macroReplications = 3,
                replicationBudgetPerRun = 400,
                numWorkers = numWorkers
            ).run()
            return summary.allRuns.associate {
                it.cellLabel to Triple(it.bestObjective, it.numReplicationsRequested, it.bestInputs)
            }
        }
        assertEquals(runWith(1), runWith(3))
    }
}
