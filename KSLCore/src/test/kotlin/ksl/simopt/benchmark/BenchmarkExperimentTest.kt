package ksl.simopt.benchmark

import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.concurrent.MemberStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for the benchmark harness core over synthetic (response-function) problems:
 * grid completeness, worker-count-independent determinism, the common-starting-point
 * policy, budget enforcement per cell, confirmation and gap recording, and failure
 * isolation.
 */
@Timeout(120)
class BenchmarkExperimentTest {

    private companion object {
        const val OBJ = "objFn"
        const val BUDGET = 100
        const val REPS_PER_EVAL = 10
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun sphereFunction(inputNames: List<String>): ResponseFunctionBuilderIfc {
        return ResponseFunctionBuilderIfc { streamProvider ->
            val stream = streamProvider.rnStream(1)
            ResponseFunctionIfc { inputs ->
                val sum = inputNames.sumOf { name -> val v = inputs.getValue(name); v * v }
                mapOf(OBJ to sum + 0.1 * stream.randU01())
            }
        }
    }

    private fun sphereProblem(
        name: String,
        dimension: Int = 2,
        reference: ReferenceSolution? = null
    ): ProblemCase {
        val inputNames = (1..dimension).map { "x$it" }
        return ProblemCase(
            name = name,
            problemDefinitionFactory = {
                val pd = ProblemDefinition(
                    problemName = name,
                    modelIdentifier = name,
                    objFnResponseName = OBJ,
                    inputNames = inputNames
                )
                for (inputName in inputNames) {
                    pd.inputVariable(inputName, -10.0, 10.0, 0.0)
                }
                pd
            },
            evaluatorFactoryProvider = { pd ->
                FunctionMemberEvaluatorFactory(pd, sphereFunction(inputNames))
            },
            referenceSolution = reference
        )
    }

    private fun shcCase(label: String, repsPerEvaluation: Int = REPS_PER_EVAL): SolverCase {
        return SolverCase(
            label = label,
            solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
                // maxIterations = 1 on purpose: the harness must override it with the budget ceiling
                StochasticHillClimber(
                    pd, evaluator,
                    maximumIterations = 1,
                    replicationsPerEvaluation = repsPerEvaluation,
                    name = name
                )
            }
        )
    }

    private fun experiment(
        problems: List<ProblemCase>,
        solverCases: List<SolverCase>,
        macroReplications: Int = 2,
        numWorkers: Int? = 2,
        cellSolverDecorator: ((ksl.simopt.solvers.Solver, String, String, Int) -> Unit)? = null
    ): BenchmarkExperiment {
        return BenchmarkExperiment(
            name = "benchTest",
            problems = problems,
            solverCases = solverCases,
            macroReplications = macroReplications,
            replicationBudgetPerRun = BUDGET,
            numWorkers = numWorkers,
            cellSolverDecorator = cellSolverDecorator
        )
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The grid is complete: one completed run per (problem, solver, rep) with unique labels")
    fun gridIsComplete() {
        val summary = experiment(
            problems = listOf(sphereProblem("sphereA"), sphereProblem("sphereB", dimension = 3)),
            solverCases = listOf(shcCase("shc10"), shcCase("shc5", repsPerEvaluation = 5)),
            macroReplications = 3
        ).run()
        assertEquals(2, summary.problemResults.size)
        val runs = summary.allRuns
        assertEquals(2 * 2 * 3, runs.size)
        assertEquals(runs.size, runs.map { it.cellLabel }.toSet().size)
        for (run in runs) {
            assertEquals(MemberStatus.COMPLETED, run.status)
            assertTrue(run.isBestValid)
            assertEquals("${run.problemName}_${run.solverLabel}_r${run.repNum}", run.cellLabel)
            assertNotNull(run.totalIterations)
        }
        val cells = runs.map { Triple(it.problemName, it.solverLabel, it.repNum) }.toSet()
        assertEquals(2 * 2 * 3, cells.size)
    }

    @Test
    @DisplayName("For a fixed configuration the summary is identical across worker counts")
    fun deterministicAcrossWorkerCounts() {
        fun runWith(numWorkers: Int): Map<String, Triple<Double, Int, Map<String, Double>>> {
            val summary = experiment(
                problems = listOf(sphereProblem("sphereA"), sphereProblem("sphereB", dimension = 3)),
                solverCases = listOf(shcCase("shc10"), shcCase("shc5", repsPerEvaluation = 5)),
                macroReplications = 2,
                numWorkers = numWorkers
            ).run()
            return summary.allRuns.associate {
                it.cellLabel to Triple(it.bestObjective, it.numReplicationsRequested, it.bestInputs)
            }
        }
        val serial = runWith(1)
        val parallel = runWith(4)
        assertEquals(serial, parallel)
    }

    @Test
    @DisplayName("Every solver case of a macro-replication starts from the same pre-drawn point; reps differ")
    fun commonStartingPointsPerRep() {
        val observedStarts = ConcurrentHashMap<Pair<String, Int>, MutableSet<Map<String, Double>>>()
        val summary = experiment(
            problems = listOf(sphereProblem("sphereA")),
            solverCases = listOf(shcCase("shc10"), shcCase("shc5", repsPerEvaluation = 5)),
            macroReplications = 2,
            cellSolverDecorator = { solver, problemName, _, repNum ->
                val start = solver.startingPoint?.toMap() ?: emptyMap()
                observedStarts.computeIfAbsent(problemName to repNum) {
                    ConcurrentHashMap.newKeySet()
                }.add(start)
            }
        ).run()
        // each (problem, rep) saw exactly one distinct starting point across the solver cases
        assertEquals(2, observedStarts.size)
        for ((key, starts) in observedStarts) {
            assertEquals(1, starts.size) { "Cell group $key saw multiple starting points: $starts" }
        }
        // different macro-replications start from different points (continuous draws)
        val rep1 = observedStarts.getValue("sphereA" to 1).first()
        val rep2 = observedStarts.getValue("sphereA" to 2).first()
        assertTrue(rep1 != rep2)
        // and the recorded run records agree with what the solvers saw
        for (run in summary.allRuns) {
            assertEquals(observedStarts.getValue(run.problemName to run.repNum).first(), run.startingPoint)
        }
    }

    @Test
    @DisplayName("Every cell consumes its replication budget, within one iteration of overshoot")
    fun budgetIsRespectedPerCell() {
        val summary = experiment(
            problems = listOf(sphereProblem("sphereA")),
            solverCases = listOf(shcCase("shc10")),
            macroReplications = 3
        ).run()
        for (run in summary.allRuns) {
            assertTrue(run.numReplicationsRequested >= BUDGET) {
                "Cell ${run.cellLabel} consumed ${run.numReplicationsRequested} < budget $BUDGET"
            }
            assertTrue(run.numReplicationsRequested <= BUDGET + 3 * REPS_PER_EVAL) {
                "Cell ${run.cellLabel} overshot: ${run.numReplicationsRequested} for budget $BUDGET"
            }
            // the case factory set maxIterations = 1; the harness must have overridden it
            assertTrue((run.totalIterations ?: 0) > 1)
        }
    }

    @Test
    @DisplayName("Confirmation and gaps are recorded: reference gaps for referenced problems, best-found otherwise")
    fun confirmationAndGapsAreRecorded() {
        val reference = ReferenceSolution(
            inputs = mapOf("x1" to 0.0, "x2" to 0.0),
            objectiveValue = 0.05,
            type = ReferenceType.KNOWN_OPTIMUM
        )
        val summary = experiment(
            problems = listOf(
                sphereProblem("withRef", reference = reference),
                sphereProblem("noRef")
            ),
            solverCases = listOf(shcCase("shc10"), shcCase("shc5", repsPerEvaluation = 5)),
            macroReplications = 2
        ).run()
        val withRef = summary.problemResults.first { it.problemName == "withRef" }
        assertEquals(GapType.KNOWN_OPTIMUM, withRef.gapType)
        assertEquals(0.05, withRef.gapBasisObjective)
        assertNotNull(withRef.confirmation)
        assertNotNull(withRef.winner)
        for (run in withRef.runs) {
            assertNotNull(run.gap)
            assertEquals(GapType.KNOWN_OPTIMUM, run.gapType)
        }
        val noRef = summary.problemResults.first { it.problemName == "noRef" }
        assertEquals(GapType.BEST_FOUND, noRef.gapType)
        assertNotNull(noRef.confirmation)
        assertNotNull(noRef.winner)
        val gaps = noRef.runs.map { it.gap!! }
        // gapped against the best found in the experiment: the best run gaps to zero
        assertEquals(0.0, gaps.min(), 1e-12)
        assertTrue(gaps.all { it >= 0.0 })
    }

    @Test
    @DisplayName("A failing solver case is isolated: its cells record FAILED, siblings complete")
    fun failingCaseIsIsolated() {
        val failingCase = SolverCase(
            label = "failsOnB",
            solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
                check(pd.modelIdentifier != "sphereB") { "injected provisioning failure" }
                StochasticHillClimber(
                    pd, evaluator,
                    maximumIterations = 1,
                    replicationsPerEvaluation = REPS_PER_EVAL,
                    name = name
                )
            }
        )
        val summary = experiment(
            problems = listOf(sphereProblem("sphereA"), sphereProblem("sphereB")),
            solverCases = listOf(shcCase("shc10"), failingCase),
            macroReplications = 2
        ).run()
        val sphereB = summary.problemResults.first { it.problemName == "sphereB" }
        val failed = sphereB.runs.filter { it.solverLabel == "failsOnB" }
        assertEquals(2, failed.size)
        for (run in failed) {
            assertEquals(MemberStatus.FAILED, run.status)
            assertTrue(!run.isBestValid)
            assertNotNull(run.errorMessage)
            assertNull(run.gap)
        }
        // siblings on the same problem and the other problem's cells are unaffected
        assertTrue(sphereB.runs.filter { it.solverLabel == "shc10" }.all { it.status == MemberStatus.COMPLETED })
        val sphereA = summary.problemResults.first { it.problemName == "sphereA" }
        assertTrue(sphereA.runs.all { it.status == MemberStatus.COMPLETED })
        assertNotNull(sphereB.winner)
    }
}
