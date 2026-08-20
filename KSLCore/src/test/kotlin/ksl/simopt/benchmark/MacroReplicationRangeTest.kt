package ksl.simopt.benchmark

import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * Tests for running a study's macro-replications in blocks, and for the absolute addressing of
 * starting points that makes blocking sound.
 *
 * A long study has to be able to survive an interruption, and the harness's natural checkpoint is
 * a completed experiment. Splitting by problem is not enough when a single expensive problem is
 * the whole study, so a study is instead run as several experiments over disjoint macro-replication
 * ranges. That is only legitimate if the blocks reproduce exactly what one whole run would have
 * produced, which in turn requires a starting point to depend on its (problem, macro-replication)
 * address alone and not on how many draws preceded it.
 *
 * Addressing is by (problem POSITION, macro-replication), which buys three properties, each
 * worth having on its own: composition (a study may be blocked), repeatability (a run may be
 * repeated), and stability under growth (widening a study's macro-replications, or appending a
 * problem, leaves the existing draws where they were). Inserting a problem ahead of others is
 * deliberately not covered -- it shifts their positions, and with them their streams.
 */
@Timeout(120)
class MacroReplicationRangeTest {

    private companion object {
        const val OBJ = "objFn"
        const val BUDGET = 60
        const val REPS_PER_EVAL = 5
    }

    private fun sphereProblem(name: String, dimension: Int = 2): ProblemCase {
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
                FunctionMemberEvaluatorFactory(pd, ResponseFunctionBuilderIfc { streamProvider ->
                    val stream = streamProvider.rnStream(1)
                    ResponseFunctionIfc { inputs ->
                        val sum = inputNames.sumOf { n -> val v = inputs.getValue(n); v * v }
                        mapOf(OBJ to sum + 0.1 * stream.randU01())
                    }
                })
            }
        )
    }

    private fun shcCase(label: String): SolverCase = SolverCase(
        label = label,
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            StochasticHillClimber(
                pd, evaluator,
                maximumIterations = 1,
                replicationsPerEvaluation = REPS_PER_EVAL,
                name = name
            )
        }
    )

    private fun run(
        problems: List<ProblemCase>,
        macroReplications: Int,
        range: IntRange = 1..macroReplications
    ): BenchmarkSummary {
        return BenchmarkExperiment(
            name = "rangeTest",
            problems = problems,
            solverCases = listOf(shcCase("shcA"), shcCase("shcB")),
            macroReplications = macroReplications,
            macroReplicationRange = range,
            replicationBudgetPerRun = BUDGET,
            numWorkers = 2
        ).run()
    }

    /** Starting points keyed by problem name and macro-replication, taken from the run records. */
    private fun startingPoints(summary: BenchmarkSummary): Map<Pair<String, Int>, Map<String, Double>> {
        val points = mutableMapOf<Pair<String, Int>, Map<String, Double>>()
        for (problemResult in summary.problemResults) {
            for (runResult in problemResult.runs) {
                points[problemResult.problemName to runResult.repNum] = runResult.startingPoint
            }
        }
        return points
    }

    private fun bestObjectivesByCell(summary: BenchmarkSummary): Map<String, Double> {
        val values = mutableMapOf<String, Double>()
        for (problemResult in summary.problemResults) {
            for (runResult in problemResult.runs) {
                values[runResult.cellLabel] = runResult.bestObjective
            }
        }
        return values
    }

    @Test
    @DisplayName("Disjoint blocks reproduce the whole run, cell for cell")
    fun blocksComposeIntoTheWholeRun() {
        val problems = listOf(sphereProblem("sphereA"), sphereProblem("sphereB", dimension = 3))

        val whole = run(problems, macroReplications = 6)
        val blocks = listOf(1..2, 3..4, 5..6).map { range ->
            run(problems, macroReplications = 6, range = range)
        }

        // every cell of the whole run appears exactly once across the blocks, with the same
        // starting point and the same result
        val wholePoints = startingPoints(whole)
        val blockPoints = blocks.flatMap { startingPoints(it).entries }.associate { it.key to it.value }
        assertEquals(wholePoints.keys, blockPoints.keys)
        for ((key, point) in wholePoints) {
            assertEquals(point, blockPoints[key]) { "Starting point differs for $key" }
        }

        val wholeBests = bestObjectivesByCell(whole)
        val blockBests = blocks.flatMap { bestObjectivesByCell(it).entries }.associate { it.key to it.value }
        assertEquals(wholeBests.keys, blockBests.keys)
        for ((cell, value) in wholeBests) {
            assertEquals(value, blockBests[cell]) { "Best objective differs for cell $cell" }
        }

        // each block reports only the macro-replications it ran
        assertEquals(listOf(2, 2, 2), blocks.map { it.macroReplications })
        assertEquals(setOf(1, 2), blocks[0].problemResults.first().runs.map { it.repNum }.toSet())
        assertEquals(setOf(5, 6), blocks[2].problemResults.first().runs.map { it.repNum }.toSet())
    }

    @Test
    @DisplayName("An identically configured experiment reproduces the first run")
    fun runIsRepeatable() {
        val first = run(listOf(sphereProblem("sphereA")), macroReplications = 3)
        val second = run(listOf(sphereProblem("sphereA")), macroReplications = 3)

        assertEquals(startingPoints(first), startingPoints(second))
        assertEquals(bestObjectivesByCell(first), bestObjectivesByCell(second))
    }

    @Test
    @DisplayName("Widening a study, or appending a problem, leaves existing starting points alone")
    fun startingPointsSurviveWideningTheStudy() {
        val alone = run(listOf(sphereProblem("sphereB", dimension = 3)), macroReplications = 3)

        // Starting points are addressed by problem POSITION, so appending a problem leaves the
        // ones already there untouched. Inserting a problem ahead of others deliberately does
        // not: it shifts their positions, and with them their streams.
        val appended = run(
            listOf(sphereProblem("sphereB", dimension = 3), sphereProblem("sphereA")),
            macroReplications = 3
        )
        for (repNum in 1..3) {
            assertEquals(
                startingPoints(alone)["sphereB" to repNum],
                startingPoints(appended)["sphereB" to repNum]
            ) { "sphereB's starting point for replication $repNum moved when a problem was appended" }
        }

        // widening the study must not disturb the replications it already had
        val widened = run(listOf(sphereProblem("sphereB", dimension = 3)), macroReplications = 8)
        for (repNum in 1..3) {
            assertEquals(
                startingPoints(alone)["sphereB" to repNum],
                startingPoints(widened)["sphereB" to repNum]
            ) { "sphereB's starting point for replication $repNum moved when macroReplications was raised" }
        }

        // sanity: distinct macro-replications really do get distinct starts, so the assertions
        // above are not comparing everything against everything
        val points = startingPoints(alone)
        assertNotEquals(points["sphereB" to 1], points["sphereB" to 2])
        assertNotEquals(points["sphereB" to 2], points["sphereB" to 3])
    }

    @Test
    @DisplayName("Different problems draw from different streams")
    fun problemsDrawIndependentStartingPoints() {
        val summary = run(
            listOf(sphereProblem("sphereA"), sphereProblem("sphereB")),
            macroReplications = 3
        )
        val points = startingPoints(summary)
        for (repNum in 1..3) {
            assertNotEquals(
                points["sphereA" to repNum]?.values?.toList(),
                points["sphereB" to repNum]?.values?.toList()
            ) { "Both problems drew the same starting point at replication $repNum" }
        }
    }

    @Test
    @DisplayName("A range outside the study's macro-replications is rejected")
    fun rangeOutsideTheStudyIsRejected() {
        val problems = listOf(sphereProblem("sphereA"))
        assertThrows(IllegalArgumentException::class.java) {
            run(problems, macroReplications = 4, range = 3..5)
        }
        assertThrows(IllegalArgumentException::class.java) {
            run(problems, macroReplications = 4, range = 0..2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            run(problems, macroReplications = 4, range = IntRange.EMPTY)
        }
    }

    @Test
    @DisplayName("The default range runs the whole study")
    fun defaultRangeRunsEverything() {
        val summary = run(listOf(sphereProblem("sphereA")), macroReplications = 4)
        assertEquals(4, summary.macroReplications)
        val repNumbers = summary.problemResults.first().runs.map { it.repNum }.toSet()
        assertEquals(setOf(1, 2, 3, 4), repNumbers)
        assertTrue(summary.problemResults.first().runs.size == 4 * 2) {
            "Expected one run per (solver, replication)"
        }
    }
}
