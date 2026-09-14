package ksl.simopt.benchmark.io

import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.BenchmarkSolverFactoryIfc
import ksl.simopt.benchmark.BenchmarkSummary
import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.FeasibilityFirstComparator
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
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
        const val TIGHT = "tight"
        const val SLACK = "slack"
        const val BUDGET = 60
        const val WORKERS = 2
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


    // ── Constrained fixture (A1/A2/A3) ────────────────────────────────────────

    /**
     * A problem with TWO response constraints of which exactly ONE binds, which is the shape the
     * aggregate violation cannot describe. `tight` is held near 5.0 against a limit of 1.0, so it
     * is violated by about 4 and is confidently infeasible; `slack` is held near 0.0 against a
     * limit of 100.0, so it is satisfied with room to spare. The noise is small relative to both
     * margins, so neither verdict depends on the draw.
     */
    private fun constrainedProblem(name: String): ProblemCase {
        val inputNames = listOf("x1", "x2")
        return ProblemCase(
            name = name,
            problemDefinitionFactory = {
                val pd = ProblemDefinition(
                    problemName = name,
                    modelIdentifier = name,
                    objFnResponseName = OBJ,
                    inputNames = inputNames,
                    responseNames = listOf(TIGHT, SLACK)
                )
                for (inputName in inputNames) {
                    pd.inputVariable(inputName, -10.0, 10.0, 0.0)
                }
                pd.responseConstraint(TIGHT, rhsValue = 1.0, inequalityType = InequalityType.LESS_THAN)
                pd.responseConstraint(SLACK, rhsValue = 100.0, inequalityType = InequalityType.LESS_THAN)
                pd
            },
            evaluatorFactoryProvider = { pd ->
                FunctionMemberEvaluatorFactory(pd, ResponseFunctionBuilderIfc { streamProvider ->
                    val stream = streamProvider.rnStream(1)
                    ResponseFunctionIfc { inputs ->
                        val x1 = inputs.getValue("x1")
                        val x2 = inputs.getValue("x2")
                        mapOf(
                            OBJ to x1 * x1 + x2 * x2 + 0.1 * stream.randU01(),
                            TIGHT to 5.0 + 0.01 * stream.randU01(),
                            SLACK to 0.0 + 0.01 * stream.randU01()
                        )
                    }
                })
            },
            tags = mapOf("family" to "constrained")
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
        macroReplications: Int = 2,
        range: IntRange = 1..macroReplications,
        name: String = if (traces) "tracedExp" else "plainExp"
    ): BenchmarkSummary {
        return BenchmarkExperiment(
            name = name,
            problems = listOf(sphereProblem("sphereA"), sphereProblem("sphereB")),
            solverCases = listOf(shcCase("shcA", 10), shcCase("shcB", 5)),
            macroReplications = macroReplications,
            macroReplicationRange = range,
            replicationBudgetPerRun = BUDGET,
            captureIterationTraces = traces,
            verificationReplications = verification,
            numWorkers = WORKERS
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

        // CPU time is the portable cost measure: wall clock depends on the worker count and on
        // what else the machine was doing, so it cannot be compared across runs or machines.
        // It is measured on the member worker only, which is why it is bounded ABOVE by wall
        // clock times the worker count and is not expected to approach it.
        val timed = runs.filter { it.cpuTimeMillis != null }
        assertTrue(timed.isNotEmpty()) {
            "no cell reported CPU time; the JVM supports it here, so this is a wiring failure"
        }
        for (row in timed) {
            assertTrue(row.cpuTimeMillis!! >= 0) { "cell ${row.cellLabel} reported negative CPU time" }
            val wall = row.wallClockMillis
            if (wall != null) {
                assertTrue(row.cpuTimeMillis!! <= (wall + 1) * WORKERS) {
                    "cell ${row.cellLabel} reported ${row.cpuTimeMillis} ms of CPU against " +
                        "$wall ms of wall clock on $WORKERS workers, which is not physically " +
                        "possible — the field is reading the wrong clock"
                }
            }
        }
        assertTrue(timed.any { it.cpuTimeMillis!! > 0 }) {
            "every cell reported exactly zero CPU time, which means the measurement is not running"
        }

        // One summary row per problem whose confirmation stage ran, carrying the counts that make
        // a degenerate selection visible. These problems are unconstrained, so no selection here
        // can be degenerate; a true flag would mean the condition is being reported spuriously.
        val confirmationSummaries = db.confirmationSummaries(expId)
        assertEquals(setOf("sphereA", "sphereB"), confirmationSummaries.map { it.problemName }.toSet())
        for (row in confirmationSummaries) {
            assertTrue(row.numCandidates > 0)
            assertEquals(row.numCandidates, row.numConfidentlyFeasible) {
                "an unconstrained problem's candidates are all trivially feasible"
            }
            assertTrue(!row.selectionDegenerate)
        }

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

    @Test
    @DisplayName("A study run in blocks pools to the same MCB feed as the same study run whole")
    fun blockedStudyPoolsToTheWholeStudyMcbFeed() {
        val db = BenchmarkResultsDb("mcbPooled.db", tempDir).also { openDatabases += it }
        val whole = db.saveSummary(runExperiment(traces = false, macroReplications = 4, name = "whole"))
        val block1 = db.saveSummary(
            runExperiment(traces = false, macroReplications = 4, range = 1..2, name = "block1")
        )
        val block2 = db.saveSummary(
            runExperiment(traces = false, macroReplications = 4, range = 3..4, name = "block2")
        )

        val wholeFeed = db.mcbDataMap(whole, "sphereA")
        val pooledFeed = db.mcbDataMap(listOf(block1, block2), "sphereA")
        assertEquals(wholeFeed.keys, pooledFeed.keys)
        for ((label, values) in wholeFeed) {
            assertEquals(values.toList(), pooledFeed.getValue(label).toList()) {
                "Pooled feed differs for solver $label"
            }
        }

        // and the analyzer sees all four observations, not two of them twice
        val analyzer = db.mcbAnalyzer(listOf(block1, block2), "sphereA")
        assertNotNull(analyzer)
        assertTrue(pooledFeed.values.all { it.size == 4 }) {
            "Expected four pooled observations per solver, got ${pooledFeed.mapValues { it.value.size }}"
        }
    }

    @Test
    @DisplayName("Pooling the same block twice is rejected rather than silently doubling the sample")
    fun poolingOverlappingBlocksIsRejected() {
        val db = BenchmarkResultsDb("mcbOverlap.db", tempDir).also { openDatabases += it }
        val block1 = db.saveSummary(
            runExperiment(traces = false, macroReplications = 4, range = 1..2, name = "block1")
        )
        val alsoBlock1 = db.saveSummary(
            runExperiment(traces = false, macroReplications = 4, range = 1..2, name = "block1Again")
        )
        assertThrows(IllegalArgumentException::class.java) {
            db.mcbDataMap(listOf(block1, alsoBlock1), "sphereA")
        }
    }

    @Test
    @DisplayName("A blocked study's performance profile matches the whole study's")
    fun blockedStudyPoolsToTheWholeStudyProfile() {
        val db = BenchmarkResultsDb("profilePooled.db", tempDir).also { openDatabases += it }
        val whole = db.saveSummary(runExperiment(traces = true, macroReplications = 4, name = "whole"))
        val block1 = db.saveSummary(
            runExperiment(traces = true, macroReplications = 4, range = 1..2, name = "block1")
        )
        val block2 = db.saveSummary(
            runExperiment(traces = true, macroReplications = 4, range = 3..4, name = "block2")
        )

        // These problems have no reference solution, so each experiment recorded its own
        // best-found basis and the blocks disagree; the pooled profile must recompute the basis
        // across every pooled run rather than use one block's.
        val wholeProfile = db.performanceProfile(whole, tau = 1.0).sortedWith(
            compareBy({ it.solverLabel }, { it.budgetFraction })
        )
        val pooledProfile = db.performanceProfile(listOf(block1, block2), tau = 1.0).sortedWith(
            compareBy({ it.solverLabel }, { it.budgetFraction })
        )
        assertEquals(wholeProfile, pooledProfile)
        assertTrue(wholeProfile.isNotEmpty()) { "The profile fixture produced no points" }
    }

    // ── A1/A2/A3: per-constraint and per-response recording ───────────────────

    private fun runConstrainedExperiment(name: String = "constrainedExp"): BenchmarkSummary {
        return BenchmarkExperiment(
            name = name,
            problems = listOf(constrainedProblem("twoConstraints")),
            solverCases = listOf(shcCase("shcA", 10)),
            macroReplications = 2,
            replicationBudgetPerRun = BUDGET,
            numWorkers = WORKERS
        ).run()
    }

    /**
     * The aggregate `responseConstraintViolation` is a plain SUM of the per-constraint violations,
     * so the decomposition is checkable against the number that was already being recorded rather
     * than against a freshly computed expectation. That is the strongest available statement that
     * the new rows describe the same run: they must add up to the old column, and they must
     * attribute the whole of it to the one constraint that binds.
     */
    @Test
    @DisplayName("Per-constraint rows attribute the aggregate violation to the constraint that binds")
    fun perConstraintRowsDecomposeTheAggregateViolation() {
        val db = BenchmarkResultsDb("constraints.db", tempDir).also { openDatabases += it }
        val expId = db.saveSummary(runConstrainedExperiment())

        val runs = db.runs(expId)
        assertTrue(runs.isNotEmpty())
        val constraintsByRun = db.runConstraints(expId).groupBy { it.runId }
        assertEquals(runs.size, constraintsByRun.size) { "every cell must contribute constraint rows" }

        for (run in runs) {
            val rows = constraintsByRun.getValue(run.runId).associateBy { it.responseName }
            assertEquals(setOf(TIGHT, SLACK), rows.keys)

            assertEquals(run.responseConstraintViolation, rows.values.sumOf { it.violation }, 1e-9) {
                "the per-constraint violations must sum to the aggregate the run row already carried"
            }

            val tight = rows.getValue(TIGHT)
            val slack = rows.getValue(SLACK)
            assertTrue(tight.violation > 0.0) { "the tight constraint should bind, got ${tight.violation}" }
            assertEquals(0.0, slack.violation) { "the slack constraint should not bind" }
            assertTrue(!tight.feasibleAtCI) { "the tight constraint cannot be declared feasible" }
            assertTrue(slack.feasibleAtCI) { "the slack constraint is satisfied with room to spare" }

            // The interval is what makes "not feasible" a statistical claim rather than a point one.
            assertNotNull(tight.ciUpperLimit)
            assertTrue(tight.ciUpperLimit!! > 0.0) { "an infeasible constraint's upper limit exceeds zero" }
            assertTrue(slack.ciUpperLimit!! < 0.0) { "a confidently feasible constraint's upper limit is below zero" }
        }
    }

    /**
     * The constraint definitions travel with the results, so "was this winner feasible?" is a join
     * rather than a lookup in a specification document.
     */
    @Test
    @DisplayName("The problem's constraints are recorded, so the database says what a run had to meet")
    fun problemConstraintsAreSelfDescribing() {
        val db = BenchmarkResultsDb("problemConstraints.db", tempDir).also { openDatabases += it }
        val expId = db.saveSummary(runConstrainedExperiment())

        val defined = db.problemConstraints(expId).associateBy { it.responseName }
        assertEquals(setOf(TIGHT, SLACK), defined.keys)
        assertEquals(1.0, defined.getValue(TIGHT).rhsValue)
        assertEquals(100.0, defined.getValue(SLACK).rhsValue)
        assertTrue(defined.values.all { it.inequalityType == "LESS_THAN" })
        assertTrue(defined.values.all { it.problemName == "twoConstraints" })

        // The join the archive exists to support: every recorded constraint has a matching
        // assessment on every cell, with no orphan on either side.
        val assessed = db.runConstraints(expId).map { it.responseName }.toSet()
        assertEquals(defined.keys, assessed)
    }

    /**
     * A3's reason for existing. `tblRun` preserves the best point's INPUTS, which is enough to
     * re-simulate but not to re-select: ranking needs each response's average, variance and count.
     * This rebuilds solutions from the stored rows alone and asserts they rank identically to the
     * in-memory ones under the rule confirmation actually uses — which is the precondition for
     * replaying a selection offline instead of re-running a 32-hour search.
     */
    @Test
    @DisplayName("Solutions rebuilt from stored responses rank identically to the originals")
    fun storedResponsesAreSufficientToReplaySelection() {
        val db = BenchmarkResultsDb("replay.db", tempDir).also { openDatabases += it }
        val summary = runConstrainedExperiment()
        val expId = db.saveSummary(summary)

        val problemResult = summary.problemResults.single()
        val problemDefinition = problemResult.winner!!.problemDefinition
        val responsesByRun = db.runResponses(expId).groupBy { it.runId }
        val runsByCell = db.runs(expId).associateBy { it.cellLabel }

        val rebuilt = problemResult.runs.map { run ->
            val row = runsByCell.getValue(run.cellLabel)
            val stored = responsesByRun.getValue(row.runId).associateBy { it.responseName }
            fun estimate(name: String): EstimatedResponse {
                val r = stored.getValue(name)
                return EstimatedResponse(name, r.average, r.variance, r.count)
            }
            Solution(
                inputMap = problemDefinition.toInputMap(run.bestInputs.toMutableMap()),
                estimatedObjFnc = estimate(OBJ),
                responseEstimates = listOf(estimate(TIGHT), estimate(SLACK)),
                evaluationNumber = 1
            )
        }

        // Every response the original carried survived the round trip, value for value.
        for ((index, run) in problemResult.runs.withIndex()) {
            for ((name, original) in run.responseEstimates) {
                val restored = (rebuilt[index].responseEstimates + rebuilt[index].estimatedObjFnc)
                    .single { it.name == name }
                assertEquals(original.average, restored.average)
                assertEquals(original.count, restored.count)
            }
        }

        // The ranking check. FeasibilityFirstComparator judges an infeasible candidate by its
        // total response-constraint violation -- which tblRun recorded independently, as a single
        // aggregate column, before any of this phase's tables existed. So ordering the rebuilt
        // solutions with the comparator and ordering the run rows by that column are two routes to
        // the same answer, and they must agree. If the stored per-response estimates were
        // insufficient to reconstruct a rankable solution, they would not.
        val comparator = FeasibilityFirstComparator()
        val byCell = problemResult.runs.map { it.cellLabel }
        val rebuiltOrder = problemResult.runs.indices
            .sortedWith { a, b ->
                val c = comparator.compare(rebuilt[a], rebuilt[b])
                if (c != 0) c else byCell[a].compareTo(byCell[b])
            }
            .map { byCell[it] }
        val expectedOrder = problemResult.runs.indices
            .sortedWith(
                compareBy(
                    { runsByCell.getValue(byCell[it]).responseConstraintViolation },
                    { byCell[it] }
                )
            )
            .map { byCell[it] }
        assertEquals(expectedOrder, rebuiltOrder) {
            "solutions rebuilt from tblRunResponse do not rank as the recorded violations say they should"
        }
        assertTrue(expectedOrder.size > 1) { "a single cell cannot demonstrate an ordering" }

        assertTrue(rebuilt.none { it.isResponseConstraintFeasible() }) {
            "the fixture no longer exercises the infeasible branch"
        }
    }
}
