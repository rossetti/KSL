package ksl.simopt.solvers.concurrent

import ksl.examples.general.simopt.BuildLKModel
import ksl.examples.general.simopt.makeLKInventoryModelProblemDefinition
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.utilities.random.rng.RNStreamProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for the concurrent solver execution substrate: the runner's determinism,
 * failure isolation, stop propagation, the pooled evaluator factory integration, and
 * the confirmation stage.
 *
 * Most tests use a deterministic simulation-free stub evaluator so they are fast and
 * exact; the pooled-factory tests run the real LK inventory model to exercise the full
 * chain including per-member stream blocks.
 */
class ConcurrentSolverRunnerTest {

    private companion object {
        const val OBJ_FN = "TotalCost"
        const val MEMBERS = 6
        const val ITERATIONS = 10
    }

    // ── Test doubles ──────────────────────────────────────────────────────────

    /**
     * A deterministic, simulation-free evaluator: the objective value of a point is
     * computed by valueFn (default: the sum of the input values). Optionally sleeps per
     * evaluation so stop-propagation tests have something in flight to stop, and can
     * return strictly improving values so a solver's no-improvement stopping criterion
     * never triggers (for tests that need genuinely long-running members).
     *
     * The variance is tiny but nonzero: a zero variance breaks the Welch
     * degrees-of-freedom computation inside the solution checker's
     * confidence-interval equality.
     */
    private class StubEvaluator(
        private val pd: ProblemDefinition,
        private val valueFn: (Map<String, Double>) -> Double = { it.values.sum() },
        private val perEvaluationDelayMillis: Long = 0,
        private val strictlyImproving: Boolean = false
    ) : EvaluatorIfc {
        override var totalEvaluatorCalls: Int = 0
        override var totalDesignPointsEvaluated: Int = 0
        override var totalOracleReplications: Int = 0
        override var totalCachedReplications: Int = 0
        override val cache: SolutionCacheIfc? = null

        override fun evaluate(evaluationRequest: EvaluationRequest): Map<ModelInputs, Solution> {
            if (perEvaluationDelayMillis > 0) {
                Thread.sleep(perEvaluationDelayMillis)
            }
            totalEvaluatorCalls++
            totalDesignPointsEvaluated += evaluationRequest.modelInputs.size
            return evaluationRequest.modelInputs.associateWith { modelInputs ->
                val inputMap = pd.toInputMap(modelInputs.inputs.toMutableMap())
                val count = modelInputs.numReplications.toDouble()
                val variance = if (count == 1.0) Double.NaN else 1e-12
                val value = if (strictlyImproving) {
                    -totalEvaluatorCalls.toDouble()
                } else {
                    valueFn(modelInputs.inputs)
                }
                Solution(
                    inputMap,
                    EstimatedResponse(OBJ_FN, value, variance, count),
                    emptyList(),
                    totalEvaluatorCalls
                )
            }
        }
    }

    /** Hands each member a private stub evaluator; records release calls and reusability. */
    private class StubMemberFactory(
        private val pd: ProblemDefinition,
        private val perEvaluationDelayMillis: Long = 0,
        private val failFor: Set<Int> = emptySet(),
        private val strictlyImproving: Boolean = false
    ) : MemberEvaluatorFactoryIfc {
        val released = ConcurrentHashMap<Int, Boolean>()

        override fun createEvaluator(memberIndex: Int): EvaluatorIfc {
            check(memberIndex !in failFor) { "Provisioning failure injected for member $memberIndex" }
            return StubEvaluator(
                pd,
                perEvaluationDelayMillis = perEvaluationDelayMillis,
                strictlyImproving = strictlyImproving
            )
        }

        override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
            released[memberIndex] = reusable
        }
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun shcFactory(pd: ProblemDefinition, iterations: Int = ITERATIONS) =
        SolverFactoryIfc { evaluator, _, name ->
            StochasticHillClimber(
                problemDefinition = pd,
                evaluator = evaluator,
                maximumIterations = iterations,
                replicationsPerEvaluation = 2,
                streamNum = 1,
                name = name
            )
        }

    /** Pre-draws one starting point per member from a dedicated stream (deterministic). */
    private fun memberTasks(
        pd: ProblemDefinition,
        count: Int,
        factory: SolverFactoryIfc
    ): List<SolverMemberTask> {
        val stream = RNStreamProvider().rnStream(2)
        return (0 until count).map { k ->
            SolverMemberTask(
                solverFactory = factory,
                label = "member_%02d".format(k),
                startingPoint = pd.startingPoint(stream)
            )
        }
    }

    // ── Determinism (stub evaluator; exact) ──────────────────────────────────

    private fun runWithStub(numWorkers: Int): List<SolverMemberResult> {
        val pd = makeLKInventoryModelProblemDefinition()
        val runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = memberTasks(pd, MEMBERS, shcFactory(pd)),
            evaluatorFactory = StubMemberFactory(pd),
            numWorkers = numWorkers
        )
        runner.launchAll()
        val results = (0 until MEMBERS).map { runner.awaitMember(it) }
        runner.shutdown()
        return results
    }

    @Test
    @DisplayName("Member results are identical across worker counts")
    fun deterministicAcrossWorkerCounts() {
        val one = runWithStub(numWorkers = 1)
        val two = runWithStub(numWorkers = 2)
        val six = runWithStub(numWorkers = 6)
        for (i in 0 until MEMBERS) {
            assertEquals(MemberStatus.COMPLETED, one[i].status, "member $i status")
            for (other in listOf(two, six)) {
                assertEquals(one[i].bestSolution.inputMap, other[i].bestSolution.inputMap,
                    "member $i best input point must not depend on worker count")
                assertEquals(one[i].bestSolution.penalizedObjFncValue,
                    other[i].bestSolution.penalizedObjFncValue, 0.0,
                    "member $i best objective must not depend on worker count")
                assertEquals(one[i].numOracleCalls, other[i].numOracleCalls,
                    "member $i oracle calls must not depend on worker count")
                assertEquals(one[i].numReplicationsRequested, other[i].numReplicationsRequested,
                    "member $i replications must not depend on worker count")
            }
        }
    }

    // ── Failure isolation ─────────────────────────────────────────────────────

    @Test
    @DisplayName("A failing member yields a failed result; siblings are unaffected")
    fun failureIsolation() {
        val pd = makeLKInventoryModelProblemDefinition()
        val factory = StubMemberFactory(pd, failFor = setOf(2))
        val runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = memberTasks(pd, 4, shcFactory(pd)),
            evaluatorFactory = factory,
            numWorkers = 2
        )
        runner.launchAll()
        val results = runner.awaitAllMembers()
        runner.shutdown()
        for (result in results) {
            if (result.memberIndex == 2) {
                assertEquals(MemberStatus.FAILED, result.status)
                assertNotNull(result.error)
                assertTrue(!result.bestSolution.isValid) { "Failed member must carry the bad solution" }
            } else {
                assertEquals(MemberStatus.COMPLETED, result.status, "member ${result.memberIndex}")
                assertEquals(true, factory.released[result.memberIndex],
                    "completed member's resources must be released as reusable")
            }
        }
        // member 2's provisioning threw, so there was nothing to release
        assertTrue(2 !in factory.released.keys)
    }

    @Test
    @DisplayName("A member failing with an error yields a failed result, and its thread is left uninterrupted")
    fun errorFailureIsolation() {
        val pd = makeLKInventoryModelProblemDefinition()
        val interruptedAtRelease = ConcurrentHashMap<Int, Boolean>()
        val factory = object : MemberEvaluatorFactoryIfc {
            override fun createEvaluator(memberIndex: Int): EvaluatorIfc = when (memberIndex) {
                1 -> object : EvaluatorIfc by StubEvaluator(pd) {
                    override fun evaluate(evaluationRequest: ksl.simopt.evaluator.EvaluationRequest) =
                        TODO("member 1 is not written yet")
                }
                2 -> object : EvaluatorIfc by StubEvaluator(pd) {
                    override fun evaluate(evaluationRequest: ksl.simopt.evaluator.EvaluationRequest) =
                        throw InterruptedException("member 2 was interrupted")
                }
                else -> StubEvaluator(pd)
            }
            override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) {
                interruptedAtRelease[memberIndex] = Thread.currentThread().isInterrupted
            }
        }
        val runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = memberTasks(pd, 4, shcFactory(pd)),
            evaluatorFactory = factory,
            numWorkers = 2
        )
        runner.launchAll()
        val results = runner.awaitAllMembers()
        runner.shutdown()
        assertEquals(MemberStatus.FAILED, results[1].status)
        assertTrue(results[1].error is NotImplementedError)
        assertEquals(MemberStatus.FAILED, results[2].status)
        assertEquals(MemberStatus.COMPLETED, results[0].status)
        assertEquals(MemberStatus.COMPLETED, results[3].status)
        assertEquals(false, interruptedAtRelease[2], "the failed member's thread must not stay interrupted")
    }

    @Test
    @DisplayName("A member failing with a fatal JVM error is not turned into a failed result: the error is thrown on")
    fun fatalErrorIsThrownOn() {
        val pd = makeLKInventoryModelProblemDefinition()
        val factory = object : MemberEvaluatorFactoryIfc {
            override fun createEvaluator(memberIndex: Int): EvaluatorIfc = when (memberIndex) {
                1 -> object : EvaluatorIfc by StubEvaluator(pd) {
                    override fun evaluate(evaluationRequest: ksl.simopt.evaluator.EvaluationRequest) =
                        throw StackOverflowError("member 1 overflowed")
                }
                else -> StubEvaluator(pd)
            }
        }
        val runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = memberTasks(pd, 2, shcFactory(pd)),
            evaluatorFactory = factory,
            numWorkers = 2
        )
        runner.launchAll()
        try {
            org.junit.jupiter.api.assertThrows<StackOverflowError> { runner.awaitAllMembers() }
        } finally {
            runner.shutdown()
        }
    }

    // ── Stop propagation ──────────────────────────────────────────────────────

    @Test
    @Timeout(30)
    @DisplayName("requestStop ends in-flight members promptly and skips queued members")
    fun stopRequestEndsRunPromptly() {
        val pd = makeLKInventoryModelProblemDefinition()
        // Effectively endless members: a million iterations at 2ms per evaluation, with
        // strictly improving values so no stopping criterion can end them early.
        val runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = memberTasks(pd, 4, shcFactory(pd, iterations = 1_000_000)),
            evaluatorFactory = StubMemberFactory(pd, perEvaluationDelayMillis = 2, strictlyImproving = true),
            numWorkers = 2
        )
        runner.launchAll()
        Thread.sleep(300)   // let the first wave get in flight
        runner.requestStop("test stop")
        val results = runner.awaitAllMembers()
        runner.shutdown()
        assertEquals(4, results.size)
        for (result in results) {
            assertTrue(
                result.status == MemberStatus.COMPLETED ||
                        result.status == MemberStatus.STOPPED_BEFORE_START
            ) { "member ${result.memberIndex} status was ${result.status}" }
        }
        // at least the queued members must have been prevented from starting
        assertTrue(results.count { it.status == MemberStatus.STOPPED_BEFORE_START } >= 1) {
            "expected at least one queued member to be skipped"
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a stop requested while a member is being set up keeps that member from running")
    fun stopDuringSetUpSkipsTheMember() {
        val pd = makeLKInventoryModelProblemDefinition()
        lateinit var runner: ConcurrentSolverRunner
        val ran = ConcurrentHashMap<Int, Boolean>()
        val tasks = memberTasks(pd, 1, shcFactory(pd, iterations = 4)).map { task ->
            task.copy(innerSolverDecorator = { solver, index ->
                // the stop arrives after the runner's first check and before the member is registered
                runner.requestStop("stop during set-up")
                solver.iterationEmitter.attach { ran[index] = true }
            })
        }
        runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = tasks,
            evaluatorFactory = StubMemberFactory(pd),
            numWorkers = 1
        )
        runner.launchAll()
        val results = runner.awaitAllMembers()
        runner.shutdown()
        assertEquals(MemberStatus.STOPPED_BEFORE_START, results.single().status)
        assertTrue(ran.isEmpty()) { "the member ran ${ran.size} iteration(s)" }
    }

    @Test
    @DisplayName("a member whose runner asked for a stop before its run began is halted as the run starts")
    fun stopAskedBeforeTheRunStartsIsApplied() {
        // A stop that reaches a member after the runner's last check but before its run initializes must not be
        // cleared by that initialization. The member's solver asks its runner as the run starts.
        val pd = makeLKInventoryModelProblemDefinition()
        fun run(stopAsked: Boolean): Pair<String, Int> {
            val solver = shcFactory(pd, iterations = 6).create(StubMemberFactory(pd).createEvaluator(0), 0, "member")
            var iterations = 0
            solver.iterationEmitter.attach { iterations++ }
            solver.stopRequestedByRunner = { stopAsked }
            solver.runAllIterations()
            return (solver.solverResult as ksl.simopt.solvers.SolverResult.Completed).terminationReason to iterations
        }
        val (stoppedReason, stoppedIterations) = run(stopAsked = true)
        val (fullReason, fullIterations) = run(stopAsked = false)
        assertEquals(ksl.simopt.solvers.SolverResult.HALTED_BEFORE_COMPLETION, stoppedReason)
        assertTrue(fullReason != ksl.simopt.solvers.SolverResult.HALTED_BEFORE_COMPLETION) { "unstopped: $fullReason" }
        assertTrue(stoppedIterations < fullIterations) { "stopped ran $stoppedIterations, unstopped $fullIterations" }
    }

    // ── Pooled factory integration (real simulation) ─────────────────────────

    private fun runWithPooledFactory(numWorkers: Int): List<SolverMemberResult> {
        val pd = makeLKInventoryModelProblemDefinition()
        val runner = ConcurrentSolverRunner(
            problemDefinition = pd,
            tasks = memberTasks(pd, 3, shcFactory(pd, iterations = 3)),
            evaluatorFactory = PooledMemberEvaluatorFactory(pd, BuildLKModel),
            numWorkers = numWorkers
        )
        runner.launchAll()
        val results = runner.awaitAllMembers()
        runner.shutdown()
        return results
    }

    @Test
    @DisplayName("Pooled factory: real-model members complete and are deterministic across worker counts")
    fun pooledFactoryDeterministicAcrossWorkerCounts() {
        val sequential = runWithPooledFactory(numWorkers = 1)
        val parallel = runWithPooledFactory(numWorkers = 3)
        for (i in sequential.indices) {
            assertEquals(MemberStatus.COMPLETED, sequential[i].status, "member $i status")
            assertTrue(sequential[i].bestSolution.penalizedObjFncValue.isFinite())
            assertEquals(sequential[i].bestSolution.inputMap, parallel[i].bestSolution.inputMap,
                "member $i best input point must not depend on worker count")
            assertEquals(sequential[i].bestSolution.penalizedObjFncValue,
                parallel[i].bestSolution.penalizedObjFncValue, 0.0,
                "member $i best objective must not depend on worker count")
        }
    }

    // ── Confirmation stage ────────────────────────────────────────────────────

    @Test
    @DisplayName("Confirmation flips a noise-favored point-estimate winner")
    fun confirmationPicksTrueWinner() {
        val pd = makeLKInventoryModelProblemDefinition()
        val pointA = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 10.0, "Inventory.reorderPoint" to 10.0))
        val pointB = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 20.0, "Inventory.reorderPoint" to 20.0))
        // Point estimates favor A (1.0 < 2.0), but the confirmed truth favors B (5.0 > 3.0).
        val candidates = listOf(
            Solution(pointA, EstimatedResponse(OBJ_FN, 1.0, 0.0, 5.0), emptyList(), 0),
            Solution(pointB, EstimatedResponse(OBJ_FN, 2.0, 0.0, 5.0), emptyList(), 0)
        )
        val confirmingEvaluator = StubEvaluator(pd, valueFn = { inputs ->
            if (inputs.getValue("Inventory.orderQuantity") == 10.0) 5.0 else 3.0
        })
        val outcome = SolutionConfirmation.confirmBest(
            candidates = candidates,
            evaluator = confirmingEvaluator,
            problemDefinition = pd,
            options = ConfirmationOptions(topK = 2, replicationsPerCandidate = 7)
        )
        assertEquals(pointB, outcome.winner.inputMap, "confirmation must pick the true winner")
        assertEquals(2, outcome.numOracleCalls)
        assertEquals(14, outcome.numReplicationsRequested)
        assertEquals(2, outcome.confirmedSolutions.size)
    }

    @Test
    @DisplayName("Confirmation is skipped when the finalists collapse to one input point")
    fun confirmationSkipsSingleFinalist() {
        val pd = makeLKInventoryModelProblemDefinition()
        val point = pd.toInputMap(mutableMapOf(
            "Inventory.orderQuantity" to 10.0, "Inventory.reorderPoint" to 10.0))
        val candidates = listOf(
            Solution(point, EstimatedResponse(OBJ_FN, 1.0, 0.0, 5.0), emptyList(), 0),
            Solution(point, EstimatedResponse(OBJ_FN, 1.5, 0.0, 5.0), emptyList(), 0)
        )
        val outcome = SolutionConfirmation.confirmBest(
            candidates = candidates,
            evaluator = StubEvaluator(pd),
            problemDefinition = pd,
            options = ConfirmationOptions(topK = 2, replicationsPerCandidate = 7)
        )
        assertEquals(point, outcome.winner.inputMap)
        assertEquals(0, outcome.numOracleCalls)
        assertTrue(outcome.confirmedSolutions.isEmpty())
    }
}
