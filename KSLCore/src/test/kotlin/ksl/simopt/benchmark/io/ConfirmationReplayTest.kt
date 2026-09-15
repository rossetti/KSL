package ksl.simopt.benchmark.io

import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.BenchmarkSolverFactoryIfc
import ksl.simopt.benchmark.FunctionMemberEvaluatorFactory
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.SolverCase
import ksl.simopt.evaluator.ResponseFunctionBuilderIfc
import ksl.simopt.evaluator.ResponseFunctionIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.concurrent.ConfirmationOptions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Evaluating a change to the selection rule against a finished study used to mean running the study
 * again — days of compute to answer a question about its last few minutes. The search does not
 * depend on the rule: the same cells, the same bests. Only the choice among those bests changes.
 *
 * These tests pin the two things that makes true. First, that the stored per-response estimates are
 * sufficient to rebuild candidates a rule can rank — replaying the ORIGINAL rule must return the
 * ORIGINAL winner, since anything lost in the round trip would show up as a different answer.
 * Second, that a different rule really does reach a different decision, and that the source
 * experiment is left exactly as it was.
 */
@Timeout(180)
class ConfirmationReplayTest {

    @TempDir
    lateinit var tempDir: Path

    private val openDatabases = mutableListOf<AutoCloseable>()

    @AfterEach
    fun closeOpenDatabases() {
        openDatabases.forEach { runCatching { it.close() } }
        openDatabases.clear()
    }

    private companion object {
        const val OBJ = "objFn"
        const val LIMIT_RESPONSE = "x1Level"
        const val BUDGET = 60
        const val PROBLEM = "shiftedSphere"
        const val EXPERIMENT = "sourceStudy"

        /**
         * The objective's optimum sits at x1 = 5, well inside the region the constraint forbids
         * (x1 <= 0). So the best-objective design is infeasible and the best feasible design is not
         * the best-objective one — which is exactly the configuration in which a feasibility-first
         * rule and an objective-only rule reach different answers.
         */
        const val OBJ_OPTIMUM_X1 = 5.0
        const val X1_LIMIT = 0.0
    }

    private fun problemCase(): ProblemCase {
        val inputNames = listOf("x1", "x2")
        return ProblemCase(
            name = PROBLEM,
            problemDefinitionFactory = {
                val pd = ProblemDefinition(
                    problemName = PROBLEM,
                    modelIdentifier = PROBLEM,
                    objFnResponseName = OBJ,
                    inputNames = inputNames,
                    responseNames = listOf(LIMIT_RESPONSE)
                )
                for (inputName in inputNames) {
                    pd.inputVariable(inputName, -10.0, 10.0, 0.0)
                }
                pd.responseConstraint(
                    LIMIT_RESPONSE, rhsValue = X1_LIMIT, inequalityType = InequalityType.LESS_THAN
                )
                pd
            },
            evaluatorFactoryProvider = { pd ->
                FunctionMemberEvaluatorFactory(pd, ResponseFunctionBuilderIfc { streamProvider ->
                    val stream = streamProvider.rnStream(1)
                    ResponseFunctionIfc { inputs ->
                        val x1 = inputs.getValue("x1")
                        val x2 = inputs.getValue("x2")
                        mapOf(
                            OBJ to (x1 - OBJ_OPTIMUM_X1) * (x1 - OBJ_OPTIMUM_X1) + x2 * x2 +
                                0.01 * stream.randU01(),
                            // Small noise, so a design well inside or well outside the limit is
                            // judged on where it is rather than on the draw.
                            LIMIT_RESPONSE to x1 + 0.02 * stream.randU01()
                        )
                    }
                })
            },
            tags = mapOf("family" to "replay")
        )
    }

    private fun shcCase(label: String, reps: Int): SolverCase = SolverCase(
        label = label,
        solverFactory = BenchmarkSolverFactoryIfc { pd, evaluator, _, name ->
            StochasticHillClimber(
                pd, evaluator, maximumIterations = 1, replicationsPerEvaluation = reps, name = name
            )
        },
        description = "SHC at $reps reps/evaluation"
    )

    /** Runs the source study and saves it. Returns the database and the saved experiment id. */
    private fun sourceStudy(dbName: String): Pair<BenchmarkResultsDb, Int> {
        val db = BenchmarkResultsDb(dbName, tempDir).also { openDatabases += it }
        val summary = BenchmarkExperiment(
            name = EXPERIMENT,
            problems = listOf(problemCase()),
            solverCases = listOf(shcCase("shcA", 10), shcCase("shcB", 5)),
            macroReplications = 3,
            replicationBudgetPerRun = BUDGET,
            numWorkers = 2
        ).run()
        return db to db.saveSummary(summary)
    }

    /** Ranks the worst objective first: guaranteed to disagree wherever the study's rule chose. */
    private val worstObjectiveFirst = Comparator<Solution> { a, b ->
        b.estimatedObjFncValue.compareTo(a.estimatedObjFncValue)
    }

    /**
     * The fidelity claim, and the one that makes every other use of a replay trustworthy. Candidates
     * rebuilt from stored rows, ranked by the rule the study itself used, must reach the study's own
     * answer. A round trip that dropped a variance or a count would rank differently and land
     * somewhere else.
     */
    @Test
    @DisplayName("Replaying the original rule reproduces the original winner")
    fun replayingTheOriginalRuleReproducesTheOriginalWinner() {
        val (db, expId) = sourceStudy("replayFidelity.db")
        val case = problemCase()
        val pd = case.problemDefinitionFactory()
        val factory = case.evaluatorFactoryProvider(pd)
        val evaluator = factory.createEvaluator(0)

        val candidates = ConfirmationReplay.rebuildCandidates(db, expId, PROBLEM, pd)
        assertTrue(candidates.size >= 2) { "too few candidates rebuilt to exercise a selection" }

        val result = ConfirmationReplay.replayProblem(
            db, expId, PROBLEM, pd, evaluator,
            ConfirmationOptions(topK = 3, replicationsPerCandidate = 30)
        )
        factory.release(0, evaluator, true)

        val originalWinner = db.problems(expId).single().winnerInputsJson
        assertTrue(originalWinner != null) { "the source study recorded no winner" }
        assertTrue(!result.winnerChanged) {
            "the original rule reached a different design than the study did; the rebuilt " +
                "candidates do not carry everything the ranking depends on. " +
                "original=${result.originalWinnerInputs} replayed=${result.outcome.winner.inputMap.toMap()}"
        }
    }

    /**
     * The replay must actually apply the rule it is given. Without this, the fidelity test above
     * could pass because the replay ignores its options and always reproduces the study.
     *
     * The rule used here is deliberately contrarian — among the candidates it prefers the WORST
     * objective — because it is guaranteed to disagree wherever the study's own rule had a choice
     * to make. A plausible-sounding alternative would not be: the solvers each hand confirmation a
     * feasibility-first best, so on a well-behaved problem the candidates are all feasible and most
     * sensible rules agree with each other. Proving the options are honoured needs a rule that
     * cannot agree, not one that merely might.
     */
    @Test
    @DisplayName("The replay applies the rule it is given rather than reproducing the study")
    fun aDifferentRuleReachesADifferentWinner() {
        val (db, expId) = sourceStudy("replayRuleChange.db")
        val case = problemCase()
        val pd = case.problemDefinitionFactory()
        val factory = case.evaluatorFactoryProvider(pd)
        val evaluator = factory.createEvaluator(0)

        val candidates = ConfirmationReplay.rebuildCandidates(db, expId, PROBLEM, pd)
        val objectives = candidates.map { it.average }
        assertTrue(objectives.toSet().size > 1) {
            "the candidates are indistinguishable on the objective, so no rule could disagree: $objectives"
        }

        val result = ConfirmationReplay.replayProblem(
            db, expId, PROBLEM, pd, evaluator,
            ConfirmationOptions(
                topK = 3, replicationsPerCandidate = 30, selectionComparator = worstObjectiveFirst
            )
        )
        factory.release(0, evaluator, true)

        assertTrue(result.winnerChanged) {
            "a rule preferring the worst objective reached the study's own winner, so the " +
                "supplied comparator is not being used"
        }
        val replayed = result.outcome.winner.inputMap.toMap()
        assertTrue(replayed != result.originalWinnerInputs) {
            "replayed=$replayed original=${result.originalWinnerInputs}"
        }
    }

    /**
     * A replay must be safe to run against an archive. The source experiment's rows are compared
     * before and after, because a replay that quietly rewrote the study it was analysing would
     * invalidate the study and everything already published from it.
     */
    @Test
    @DisplayName("A replay leaves the source experiment untouched and lands under its own id")
    fun replayDoesNotMutateTheSourceExperiment() {
        val (db, expId) = sourceStudy("replayIsolation.db")

        val runsBefore = db.runs(expId).sortedBy { it.runId }.map { it.cellLabel to it.bestObjective }
        val problemsBefore = db.problems(expId).map { it.problemName to it.winnerInputsJson }
        val confirmationsBefore = db.confirmations(expId).size

        val (newExpId, results) = ConfirmationReplay.replayExperiment(
            db, EXPERIMENT, "replayed", listOf(problemCase()),
            ConfirmationOptions(topK = 3, replicationsPerCandidate = 30, selectionComparator = worstObjectiveFirst)
        )

        assertTrue(newExpId != expId) { "a replay must be its own experiment" }
        assertEquals(1, results.size)
        assertEquals(runsBefore, db.runs(expId).sortedBy { it.runId }.map { it.cellLabel to it.bestObjective })
        assertEquals(problemsBefore, db.problems(expId).map { it.problemName to it.winnerInputsJson })
        assertEquals(confirmationsBefore, db.confirmations(expId).size)

        // The replay is an ordinary experiment record: closed, named, and queryable like any other.
        val replayRow = db.experiments().single { it.expId == newExpId }
        assertEquals("replayed", replayRow.expName)
        assertTrue(replayRow.endTime.isNotEmpty())
        assertEquals(1, db.problems(newExpId).size)
        assertTrue(db.confirmationSummaries(newExpId).isNotEmpty())
        // It re-selects rather than re-runs, so it carries no cells of its own.
        assertTrue(db.runs(newExpId).isEmpty()) {
            "a replay must not masquerade as a second execution of the study"
        }
    }

    /** Saving a replay over its source's name would make the two indistinguishable afterwards. */
    @Test
    @DisplayName("A replay refuses to be saved under its source's name")
    fun replayRefusesToShadowItsSource() {
        val (db, _) = sourceStudy("replayNameClash.db")
        assertThrows(IllegalArgumentException::class.java) {
            ConfirmationReplay.replayExperiment(
                db, EXPERIMENT, EXPERIMENT, listOf(problemCase()),
                ConfirmationOptions(topK = 3, replicationsPerCandidate = 30)
            )
        }
    }

    /**
     * Only the finalists a rule selects are simulated. That is the whole economic argument: the
     * search is not re-run, so the cost of testing a rule is a handful of design points rather than
     * the study.
     */
    @Test
    @DisplayName("A replay simulates only the finalists, not the study")
    fun replaySimulatesOnlyTheFinalists() {
        val (db, expId) = sourceStudy("replayCost.db")
        val case = problemCase()
        val pd = case.problemDefinitionFactory()
        val factory = case.evaluatorFactoryProvider(pd)
        val evaluator = factory.createEvaluator(0)

        val topK = 3
        val replicationsPerCandidate = 30
        val result = ConfirmationReplay.replayProblem(
            db, expId, PROBLEM, pd, evaluator,
            ConfirmationOptions(topK = topK, replicationsPerCandidate = replicationsPerCandidate)
        )
        factory.release(0, evaluator, true)

        val studyReplications = db.runs(expId)
            .filter { it.problemName == PROBLEM }
            .sumOf { it.numReplicationsRequested }
        assertTrue(studyReplications > 0)
        assertTrue(result.outcome.numOracleCalls <= topK) {
            "a replay must simulate at most its finalists, not every candidate"
        }
        assertTrue(result.outcome.numReplicationsRequested <= topK * replicationsPerCandidate)
        assertTrue(result.outcome.numReplicationsRequested < studyReplications) {
            "the replay cost ${result.outcome.numReplicationsRequested} replications against the " +
                "study's $studyReplications; it is supposed to be the cheap way to test a rule"
        }
    }
}
