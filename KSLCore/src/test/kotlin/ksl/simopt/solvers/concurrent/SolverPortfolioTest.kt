package ksl.simopt.solvers.concurrent

import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.algorithms.ExponentialCoolingSchedule
import ksl.simopt.solvers.algorithms.RandomWalkSolver
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.algorithms.TemperatureConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for SolverPortfolio: heterogeneous members racing concurrently on the same
 * problem, with deterministic worker-count-independent results, member reporting,
 * starting-point inheritance, configuration flattening, and the confirmation stage.
 */
class SolverPortfolioTest {

    private companion object {
        const val ITERS = 3
        const val REPS = 2
    }

    // ── Member factories (each creates instance-private collaborators) ────────

    private fun shcFactory(pd: ProblemDefinition) = SolverFactoryIfc { evaluator, _, name ->
        StochasticHillClimber(
            problemDefinition = pd, evaluator = evaluator,
            maximumIterations = ITERS, replicationsPerEvaluation = REPS, name = name
        )
    }

    private fun saFactory(pd: ProblemDefinition) = SolverFactoryIfc { evaluator, _, name ->
        SimulatedAnnealing(
            problemDefinition = pd, evaluator = evaluator,
            temperatureConfiguration = TemperatureConfiguration.Fixed(100.0),
            coolingSchedule = ExponentialCoolingSchedule(100.0),   // fresh per instance
            maxIterations = ITERS, replicationsPerEvaluation = REPS, name = name
        )
    }

    private fun walkFactory(pd: ProblemDefinition) = SolverFactoryIfc { evaluator, _, name ->
        RandomWalkSolver(
            problemDefinition = pd, evaluator = evaluator, maximumIterations = ITERS,
            replicationsPerEvaluation = FixedReplicationsPerEvaluation(REPS), name = name
        )
    }

    private fun heterogeneousMembers(pd: ProblemDefinition): List<SolverMemberTask> = listOf(
        SolverMemberTask(shcFactory(pd), "shc"),
        SolverMemberTask(saFactory(pd), "sa"),
        SolverMemberTask(walkFactory(pd), "walk")
    )

    private fun buildPortfolio(
        pd: ProblemDefinition,
        members: List<SolverMemberTask>,
        options: ConcurrentRunOptions = ConcurrentRunOptions()
    ): SolverPortfolio = SolverPortfolio.create(
        problemDefinition = pd,
        modelBuilder = BuildLKModel,
        members = members,
        concurrentOptions = options,
        replicationsPerEvaluation = REPS,
        streamNum = 1
    )

    // ── Heterogeneous determinism across worker counts ────────────────────────

    private fun runHeterogeneous(numWorkers: Int?): SolverPortfolio {
        val pd = makeLKInventoryModelProblemDefinition()
        val portfolio = buildPortfolio(
            pd, heterogeneousMembers(pd), ConcurrentRunOptions(numWorkers = numWorkers)
        )
        portfolio.runAllIterations()
        return portfolio
    }

    @Test
    @DisplayName("Heterogeneous portfolio is deterministic across worker counts")
    fun heterogeneousDeterministicAcrossWorkerCounts() {
        val one = runHeterogeneous(numWorkers = 1)
        val three = runHeterogeneous(numWorkers = 3)
        assertEquals(3, one.memberResults.size)
        assertEquals(listOf("shc", "sa", "walk"), one.memberResults.map { it.label })
        for (i in one.memberResults.indices) {
            val a = one.memberResults[i]
            val b = three.memberResults[i]
            assertEquals(MemberStatus.COMPLETED, a.status, "member $i status")
            assertEquals(a.bestSolution.inputMap, b.bestSolution.inputMap,
                "member $i best input point must not depend on worker count")
            assertEquals(a.bestSolution.penalizedObjFncValue,
                b.bestSolution.penalizedObjFncValue, 0.0)
            assertEquals(a.numOracleCalls, b.numOracleCalls)
        }
        assertEquals(one.bestSolution.inputMap, three.bestSolution.inputMap)
        assertEquals(one.numOracleCalls, three.numOracleCalls)
        assertEquals(3, one.iterationCounter)
    }

    // ── Portfolio-of-one degenerates to the plain solver ──────────────────────

    @Test
    @DisplayName("A portfolio of one SHC member reproduces the directly-run SHC")
    fun portfolioOfOneMatchesDirectSolver() {
        // Direct: SHC with its own problem evaluator (fresh model, tape at 0).
        val pdDirect = makeLKInventoryModelProblemDefinition()
        val direct = StochasticHillClimber(
            problemDefinition = pdDirect,
            evaluator = Evaluator.createProblemEvaluator(pdDirect, BuildLKModel),
            maximumIterations = ITERS,
            replicationsPerEvaluation = REPS,
            streamNum = 1
        )
        direct.runAllIterations()

        // Portfolio of one: member 0 gets an equivalent private evaluator (fresh model,
        // stream block 0, fresh cache) and an equivalent solver from the factory.
        val pdPortfolio = makeLKInventoryModelProblemDefinition()
        val portfolio = buildPortfolio(
            pdPortfolio,
            members = listOf(SolverMemberTask(SolverFactoryIfc { evaluator, _, name ->
                StochasticHillClimber(
                    problemDefinition = pdPortfolio, evaluator = evaluator,
                    maximumIterations = ITERS, replicationsPerEvaluation = REPS,
                    streamNum = 1, name = name
                )
            }, "only"))
        )
        portfolio.runAllIterations()

        val memberBest = portfolio.memberResults.single().bestSolution
        assertEquals(direct.bestSolution.inputMap, memberBest.inputMap,
            "the single member must reproduce the directly-run solver")
        assertEquals(direct.bestSolution.penalizedObjFncValue,
            memberBest.penalizedObjFncValue, 0.0)
    }

    // ── Starting-point inheritance ────────────────────────────────────────────

    @Test
    @DisplayName("Members without their own starting point inherit the portfolio's")
    fun startingPointInheritance() {
        val pd = makeLKInventoryModelProblemDefinition()
        val portfolioPoint = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 42.0, "Inventory.reorderPoint" to 17.0))
        val memberOwnPoint = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 60.0, "Inventory.reorderPoint" to 5.0))
        val observed = ConcurrentHashMap<Int, InputMap?>()
        val decorator: (ksl.simopt.solvers.Solver, Int) -> Unit =
            { solver, index -> observed[index] = solver.startingPoint }
        val members = listOf(
            SolverMemberTask(shcFactory(pd), "inherits", innerSolverDecorator = decorator),
            SolverMemberTask(shcFactory(pd), "keepsOwn",
                startingPoint = memberOwnPoint, innerSolverDecorator = decorator)
        )
        val portfolio = buildPortfolio(pd, members)
        portfolio.startingPoint = portfolioPoint
        portfolio.runAllIterations()
        assertEquals(portfolioPoint, observed[0],
            "a member without its own point must inherit the portfolio's")
        assertEquals(memberOwnPoint, observed[1],
            "a member with its own point must keep it")
    }

    // ── Confirmation stage ────────────────────────────────────────────────────

    @Test
    @DisplayName("Confirmation stage runs and reports its winner as the final solution")
    fun confirmationStageReportsWinner() {
        val pd = makeLKInventoryModelProblemDefinition()
        val portfolio = buildPortfolio(
            pd, heterogeneousMembers(pd),
            ConcurrentRunOptions(confirmation = ConfirmationOptions(topK = 2, replicationsPerCandidate = 3))
        )
        portfolio.runAllIterations()
        val outcome = portfolio.confirmationOutcome
        assertNotNull(outcome, "a confirmation outcome must be recorded")
        assertEquals(outcome!!.winner.inputMap, portfolio.currentSolution.inputMap,
            "the confirmed winner must be the final current solution")
    }

    // ── Reporting shape and validation ────────────────────────────────────────

    @Test
    @DisplayName("Configuration properties flatten per-member settings")
    fun configurationPropertiesShape() {
        val pd = makeLKInventoryModelProblemDefinition()
        val portfolio = buildPortfolio(pd, heterogeneousMembers(pd))
        val props = portfolio.configurationProperties
        assertEquals("3", props["numMembers"])
        assertEquals("shc", props["member.0.label"])
        assertEquals("StochasticHillClimber", props["member.0.algorithm"])
        assertEquals("sa", props["member.1.label"])
        assertEquals("SimulatedAnnealing", props["member.1.algorithm"])
        assertEquals("walk", props["member.2.label"])
        assertTrue(props.keys.any { it.startsWith("member.1.") && it.contains("coolingSchedule") },
            "member prototypes must contribute their own configuration keys")
    }

    @Test
    @DisplayName("Empty member lists and duplicate labels are rejected")
    fun validation() {
        val pd = makeLKInventoryModelProblemDefinition()
        assertThrows<IllegalArgumentException> {
            buildPortfolio(pd, emptyList())
        }
        assertThrows<IllegalArgumentException> {
            buildPortfolio(pd, listOf(
                SolverMemberTask(shcFactory(pd), "dup"),
                SolverMemberTask(shcFactory(pd), "dup")
            ))
        }
    }
}
