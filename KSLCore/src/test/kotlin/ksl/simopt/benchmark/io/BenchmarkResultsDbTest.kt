package ksl.simopt.benchmark.io

import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.BenchmarkSolverFactoryIfc
import ksl.simopt.benchmark.BenchmarkSummary
import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Tests for the benchmark results database: round-tripping a small experiment through
 * the schema, the append semantics that let a trace-enabled rerun land in the same
 * database (decision D3), the multiple-comparison feed shape, and performance-profile
 * data computed from captured traces.
 */
@Timeout(120)
class BenchmarkResultsDbTest {

    @TempDir
    lateinit var tempDir: Path

    // Close every database opened by a test so its SQLite file is released and @TempDir can be
    // deleted; on Windows an open connection blocks the temp-dir cleanup (invisible on Unix).
    private val openDatabases = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeOpenDatabases() {
        openDatabases.forEach { runCatching { it.close() } }
        openDatabases.clear()
    }

    private companion object {
        const val OBJ = "objFn"
        const val BUDGET = 60
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private fun sphereProblem(name: String): ProblemCase {
        val inputNames = listOf("x1", "x2")
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
                        val x1 = inputs.getValue("x1")
                        val x2 = inputs.getValue("x2")
                        mapOf(OBJ to x1 * x1 + x2 * x2 + 0.1 * stream.randU01())
                    }
                })
            },
            tags = mapOf("family" to "sphere", "noiseLevel" to "LOW")
        )
    }

    private fun shcCase(label: String, repsPerEvaluation: Int): SolverCase {
        return SolverCase(
            label = label,
            solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
                StochasticHillClimber(
                    pd, evaluator,
                    maximumIterations = 1,
                    replicationsPerEvaluation = repsPerEvaluation,
                    name = name
                )
            },
            description = "SHC at $repsPerEvaluation reps/evaluation"
        )
    }

    private fun runExperiment(
        traces: Boolean,
        verification: Int? = null,
        macroReplications: Int = 2
    ): BenchmarkSummary {
        return BenchmarkExperiment(
            name = if (traces) "tracedExp" else "plainExp",
            problems = listOf(sphereProblem("sphereA"), sphereProblem("sphereB")),
            solverCases = listOf(shcCase("shcA", 10), shcCase("shcB", 5)),
            macroReplications = macroReplications,
            replicationBudgetPerRun = BUDGET,
            captureIterationTraces = traces,
            verificationReplications = verification,
            numWorkers = 2
        ).run()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A small experiment round-trips: every table row matches the in-memory summary")
    fun roundTripSmallExperiment() {
        val db = BenchmarkResultsDb("bench.db", tempDir).also { openDatabases += it }
        val summary = runExperiment(traces = false, verification = 20)
        val expId = db.saveSummary(summary, kslVersion = "test")
        assertEquals(1, expId)

        val experiment = db.experiments().single()
        assertEquals("plainExp", experiment.expName)
        assertEquals(BUDGET, experiment.replicationBudgetPerRun)
        assertEquals(2, experiment.macroReplications)
        assertEquals(20, experiment.verificationReplications)
        assertTrue(!experiment.tracesCaptured)
        assertEquals("test", experiment.kslVersion)

        val problems = db.problems(expId)
        assertEquals(2, problems.size)
        for (problem in problems) {
            assertEquals(2, problem.dimension)
            assertEquals("MINIMIZE", problem.optimizationType)
            assertEquals("BEST_FOUND", problem.gapType)
            assertNotNull(problem.gapBasisObjective)
            assertNotNull(problem.winnerObjective)
            assertTrue(problem.tagsJson!!.contains("sphere"))
        }

        assertEquals(2, db.solverCases(expId).size)
        assertTrue(db.solverCaseParameters(expId).isNotEmpty())
        assertEquals(
            setOf("shcA", "shcB"),
            db.solverCaseParameters(expId).map { it.solverLabel }.toSet()
        )

        val runs = db.runs(expId)
        assertEquals(summary.allRuns.size, runs.size)
        val sourceByLabel = summary.allRuns.associateBy { it.cellLabel }
        for (row in runs) {
            val source = sourceByLabel.getValue(row.cellLabel)
            assertEquals(source.bestObjective, row.bestObjective)
            assertEquals(source.numReplicationsRequested, row.numReplicationsRequested)
            assertEquals(source.status.name, row.status)
            assertEquals(source.repNum, row.repNum)
            assertEquals(source.gap, row.gap)
            assertTrue(row.bestValid)
            assertTrue(row.startingPointJson.contains("x1"))
        }

        assertTrue(db.confirmations(expId).isNotEmpty())
        val verifications = db.verifications(expId)
        assertEquals(setOf("sphereA", "sphereB"), verifications.map { it.problemName }.toSet())
        assertTrue(verifications.all { it.count == 20.0 })
        assertTrue(db.traces(expId).isEmpty())
    }

    @Test
    @DisplayName("D3: a later trace-enabled rerun appends into the same database under a fresh id")
    fun traceEnabledRerunAppendsIntoSameDatabase() {
        val db = BenchmarkResultsDb("bench.db", tempDir).also { openDatabases += it }
        val exp1 = db.saveSummary(runExperiment(traces = false))
        // reopen the same file (no delete) as a separate session and save a traced rerun
        val reopened = BenchmarkResultsDb("bench.db", tempDir).also { openDatabases += it }
        val exp2 = reopened.saveSummary(runExperiment(traces = true))
        assertEquals(exp1 + 1, exp2)
        assertEquals(2, reopened.experiments().size)

        val traceRows = reopened.traces(exp2)
        assertTrue(traceRows.isNotEmpty())
        assertTrue(reopened.traces(exp1).isEmpty())
        val runIds1 = reopened.runs(exp1).map { it.runId }.toSet()
        val runIds2 = reopened.runs(exp2).map { it.runId }.toSet()
        assertTrue(runIds1.intersect(runIds2).isEmpty())
        assertTrue(traceRows.all { it.runId in runIds2 })
        // every completed run of the traced experiment has a trace ending at its budget consumption
        val runsById = reopened.runs(exp2).associateBy { it.runId }
        for ((runId, points) in traceRows.groupBy { it.runId }) {
            val run = runsById.getValue(runId)
            val lastPoint = points.maxBy { it.iteration }
            assertEquals(run.numReplicationsRequested, lastPoint.cumulativeReplications)
        }
    }

    @Test
    @DisplayName("The MCB feed has one equal-length array per solver case and constructs an analyzer")
    fun mcbFeedShape() {
        val db = BenchmarkResultsDb("mcb.db", tempDir).also { openDatabases += it }
        val expId = db.saveSummary(runExperiment(traces = false))
        val dataMap = db.mcbDataMap(expId, "sphereA")
        assertEquals(setOf("shcA", "shcB"), dataMap.keys)
        assertTrue(dataMap.values.all { it.size == 2 })
        val analyzer = db.mcbAnalyzer(expId, "sphereA")
        assertNotNull(analyzer)
    }

    @Test
    @DisplayName("The MCB feed declines a single-macro-replication experiment instead of throwing")
    fun mcbAnalyzerDeclinesSingleMacroReplication() {
        val db = BenchmarkResultsDb("mcbSingle.db", tempDir).also { openDatabases += it }
        val expId = db.saveSummary(runExperiment(traces = false, macroReplications = 1))
        // The runs are still recorded and readable -- only the comparison is declined.
        val dataMap = db.mcbDataMap(expId, "sphereA")
        assertEquals(setOf("shcA", "shcB"), dataMap.keys)
        assertTrue(dataMap.values.all { it.size == 1 })
        // One observation per case leaves no degrees of freedom for the interval arithmetic.
        assertNull(db.mcbAnalyzer(expId, "sphereA"))
    }

    @Test
    @DisplayName("Performance-profile data comes from captured traces with fractions in range")
    fun performanceProfileFromTraces() {
        val db = BenchmarkResultsDb("prof.db", tempDir).also { openDatabases += it }
        val expId = db.saveSummary(runExperiment(traces = true))
        val profile = db.performanceProfile(expId, tau = 5.0, numPoints = 10)
        assertTrue(profile.isNotEmpty())
        assertEquals(setOf("shcA", "shcB"), profile.map { it.solverLabel }.toSet())
        assertEquals(2 * 10, profile.size)
        for (point in profile) {
            assertTrue(point.budgetFraction > 0.0 && point.budgetFraction <= 1.0)
            assertTrue(point.fractionSolved in 0.0..1.0)
        }
        // the cell that set each problem's gap basis solves by the full budget
        val atFullBudget = profile.filter { it.budgetFraction == 1.0 }
        assertTrue(atFullBudget.any { it.fractionSolved > 0.0 })
        // monotone non-decreasing in the budget fraction per solver
        for ((_, points) in profile.groupBy { it.solverLabel }) {
            val ordered = points.sortedBy { it.budgetFraction }.map { it.fractionSolved }
            assertTrue(ordered.zipWithNext().all { (a, b) -> b >= a })
        }
        // an untraced experiment yields no profile data
        val plainId = db.saveSummary(runExperiment(traces = false))
        assertTrue(db.performanceProfile(plainId, tau = 5.0).isEmpty())
    }
}
