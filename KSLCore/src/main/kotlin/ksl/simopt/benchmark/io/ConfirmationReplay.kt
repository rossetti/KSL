package ksl.simopt.benchmark.io

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import ksl.simopt.benchmark.BenchmarkSummaryHeader
import ksl.simopt.benchmark.GapType
import ksl.simopt.benchmark.ProblemBenchmarkResult
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.ConfirmationOptions
import ksl.simopt.solvers.concurrent.ConfirmationOutcome
import ksl.simopt.solvers.concurrent.SolutionConfirmation

/**
 *  One problem's replayed selection.
 *
 *  @param problemName the problem replayed
 *  @param candidates the cell bests rebuilt from the database, in run order
 *  @param outcome the confirmation outcome under the replayed rule
 *  @param originalWinnerInputs the inputs of the winner the source experiment reported, or null
 *  when it recorded none
 *  @param winnerChanged whether the replayed rule selected a different design point than the
 *  source experiment did — the question a replay exists to answer
 */
data class ProblemReplayResult(
    val problemName: String,
    val candidates: List<Solution>,
    val outcome: ConfirmationOutcome,
    val originalWinnerInputs: Map<String, Double>?,
    val winnerChanged: Boolean
)

/**
 *  Re-runs the confirmation stage of a finished benchmark against stored results, under a different
 *  selection rule, **without re-running the search**.
 *
 *  Why this exists. Evaluating a change to the selection rule against a completed study used to mean
 *  running the study again — days of compute to answer a question about the last few minutes of it.
 *  The search is the expensive part and it does not depend on the rule: the same cells, the same
 *  bests. Only the choice made among those bests changes, and the estimates that choice needs are in
 *  the database.
 *
 *  What makes it possible is `tblRunResponse`. `tblRun` preserves each cell best's inputs, which is
 *  enough to re-simulate a point but not to re-rank it — ranking needs each response's average,
 *  variance and count. With those stored, candidates sufficient for selection are rebuilt from the
 *  database and only the finalists the new rule chooses need fresh simulation.
 *
 *  **The source experiment is never mutated.** A replay is written as its own experiment under a new
 *  name, through the same sink an ordinary experiment uses, so it is queryable by every existing
 *  helper and can be compared with its source by the same queries.
 *
 *  **What a replay is not.** The rebuilt candidates carry the estimates the search produced, not the
 *  penalty state it carried — penalty memory is not stored, so a replayed selection under a rule
 *  that reads penalties would not reproduce the original. That is not a limitation in practice:
 *  `FeasibilityFirstComparator`, and any rule fit for cross-iteration selection, is clock- and
 *  penalty-independent by design. A rule that reads the penalized objective is the thing this
 *  library deliberately does not use for selection.
 */
object ConfirmationReplay {

    val logger: KLogger = KotlinLogging.logger {}

    private val myJson = Json { encodeDefaults = true }

    /**
     *  Rebuilds one problem's cell bests as [Solution] instances from stored rows alone.
     *
     *  Cells whose best was never valid — a failed or never-started member — are excluded, matching
     *  what confirmation does with them when a run happens live.
     *
     *  @param db the results database holding the source experiment
     *  @param expId the source experiment's id
     *  @param problemName the problem whose cells to rebuild
     *  @param problemDefinition the problem the rebuilt solutions belong to; must be the definition
     *  the study ran, since solutions validate their problem identity
     *  @param evaluationNumber the clock to stamp the rebuilt solutions with. Selection must be
     *  clock-independent, so this exists only so that anything recording a penalized value has a
     *  defensible number; it plays no part in the ranking.
     */
    fun rebuildCandidates(
        db: BenchmarkResultsDb,
        expId: Int,
        problemName: String,
        problemDefinition: ProblemDefinition,
        evaluationNumber: Int = 1
    ): List<Solution> {
        val runs = db.runs(expId).filter { it.problemName == problemName }
        val responsesByRun = db.runResponses(expId).groupBy { it.runId }
        val objectiveName = problemDefinition.objFnResponseName
        val candidates = mutableListOf<Solution>()
        for (run in runs) {
            if (!run.bestValid) {
                continue
            }
            val stored = responsesByRun[run.runId] ?: continue
            val byName = stored.associateBy { it.responseName }
            val objective = byName[objectiveName] ?: continue
            val others = problemDefinition.responseNames.mapNotNull { name -> byName[name] }
            if (others.size != problemDefinition.responseNames.size) {
                // A partially recorded cell cannot be ranked against a complete one without
                // silently treating a missing response as satisfied.
                logger.warn {
                    "Skipping cell ${run.cellLabel}: stored responses ${byName.keys} do not cover " +
                        "the problem's ${problemDefinition.responseNames}"
                }
                continue
            }
            val inputs: Map<String, Double> = myJson.decodeFromString(run.bestInputsJson)
            candidates.add(
                Solution(
                    inputMap = problemDefinition.toInputMap(inputs.toMutableMap()),
                    estimatedObjFnc = objective.toEstimate(),
                    responseEstimates = others.map { it.toEstimate() },
                    evaluationNumber = evaluationNumber
                )
            )
        }
        return candidates
    }

    private fun RunResponseTableData.toEstimate(): EstimatedResponse =
        EstimatedResponse(responseName, average, variance, count)

    /**
     *  Replays one problem's selection under the supplied rule.
     *
     *  Only the finalists the rule chooses are simulated, against the supplied evaluator; the rest
     *  of the study is read from the database.
     *
     *  @param db the results database holding the source experiment
     *  @param expId the source experiment's id
     *  @param problemName the problem to replay
     *  @param problemDefinition the definition the study ran
     *  @param evaluator the evaluator used for the replayed confirmation's fresh simulation
     *  @param options the confirmation configuration to apply — this is the rule under test
     */
    fun replayProblem(
        db: BenchmarkResultsDb,
        expId: Int,
        problemName: String,
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        options: ConfirmationOptions
    ): ProblemReplayResult {
        val candidates = rebuildCandidates(db, expId, problemName, problemDefinition)
        require(candidates.isNotEmpty()) {
            "No valid cell bests could be rebuilt for problem '$problemName' of experiment $expId"
        }
        val outcome = SolutionConfirmation.confirmBest(candidates, evaluator, problemDefinition, options)
        val originalWinner = db.problems(expId)
            .firstOrNull { it.problemName == problemName }
            ?.winnerInputsJson
            ?.let { myJson.decodeFromString<Map<String, Double>>(it) }
        val replayedWinner = outcome.winner.inputMap.toMap()
        val changed = originalWinner != null && originalWinner != replayedWinner
        logger.info {
            "Replayed '$problemName' of experiment $expId over ${candidates.size} candidates: " +
                if (changed) "the winner CHANGED" else "the winner is unchanged"
        }
        return ProblemReplayResult(problemName, candidates, outcome, originalWinner, changed)
    }

    /**
     *  Replays every problem of a finished experiment and saves the result as a new experiment.
     *
     *  @param db the results database; the source experiment is read, never written
     *  @param sourceExperimentName the experiment to replay. When several share the name, the most
     *  recent is used.
     *  @param newExperimentName the name to save the replay under; must differ from the source
     *  @param problemCases the cases the study was defined with, supplying a fresh problem
     *  definition and evaluator per problem. Problems present in the database but absent here are
     *  skipped, so a subset can be replayed.
     *  @param options the confirmation configuration to apply
     *  @return the new experiment's id, paired with the per-problem replay results
     */
    fun replayExperiment(
        db: BenchmarkResultsDb,
        sourceExperimentName: String,
        newExperimentName: String,
        problemCases: List<ProblemCase>,
        options: ConfirmationOptions
    ): Pair<Int, List<ProblemReplayResult>> {
        require(newExperimentName != sourceExperimentName) {
            "The replay must be saved under a different name so the source experiment is not " +
                "confused with it; both were '$sourceExperimentName'"
        }
        val source = db.experiments()
            .filter { it.expName == sourceExperimentName }
            .maxByOrNull { it.expId }
        requireNotNull(source) { "No experiment named '$sourceExperimentName' is in this database" }

        val casesByName = problemCases.associateBy { it.name }
        val startTime = Clock.System.now()
        val results = mutableListOf<ProblemReplayResult>()
        val problemResults = mutableListOf<ProblemBenchmarkResult>()
        for (problemRow in db.problems(source.expId)) {
            val case = casesByName[problemRow.problemName] ?: continue
            val problemDefinition = case.problemDefinitionFactory()
            val evaluatorFactory = case.evaluatorFactoryProvider(problemDefinition)
            val evaluator = evaluatorFactory.createEvaluator(0)
            val result = try {
                replayProblem(
                    db, source.expId, problemRow.problemName, problemDefinition, evaluator, options
                )
            } finally {
                evaluatorFactory.release(0, evaluator, true)
            }
            results.add(result)
            problemResults.add(
                ProblemBenchmarkResult(
                    problemName = problemRow.problemName,
                    // Carried across so a replay groups in an analysis exactly as its source does.
                    tags = problemRow.tagsJson
                        ?.let { myJson.decodeFromString<Map<String, String>>(it) }
                        ?: emptyMap(),
                    dimension = problemDefinition.inputSize,
                    optimizationType = problemDefinition.optimizationType,
                    numResponseConstraints = problemDefinition.responseConstraints.size,
                    responseConstraints = problemDefinition.responseConstraints,
                    // A replay re-selects; it does not re-run. The cells belong to the source
                    // experiment and are not copied, so a replay record is small and never
                    // masquerades as a second execution of the study.
                    runs = emptyList(),
                    confirmation = result.outcome,
                    winner = result.outcome.winner,
                    verification = null,
                    gapBasisObjective = problemRow.gapBasisObjective,
                    gapType = problemRow.gapType?.let { GapType.valueOf(it) }
                )
            )
        }
        require(problemResults.isNotEmpty()) {
            "None of the supplied problem cases matched a problem of experiment '$sourceExperimentName'"
        }

        val header = BenchmarkSummaryHeader(
            experimentName = newExperimentName,
            macroReplications = source.macroReplications,
            replicationBudgetPerRun = source.replicationBudgetPerRun,
            confirmationTopK = options.topK,
            confirmationReplications = options.replicationsPerCandidate,
            verificationReplications = null,
            numProblems = problemResults.size,
            solverCaseDescriptions = db.solverCases(source.expId).associate { it.solverLabel to it.description },
            startTime = startTime,
            tracesCaptured = false,
            solverStateCaptured = false
        )
        // Written through the ordinary sink, so a replay is queryable exactly like the experiment
        // it came from and there is still only one writer for the schema.
        val newExpId = db.beginExperiment(header, resume = false)
        for (problemResult in problemResults) {
            db.problemCompleted(newExpId, problemResult)
        }
        db.endExperiment(newExpId, Clock.System.now())
        logger.info {
            "Saved replay of '$sourceExperimentName' as '$newExperimentName' (expId $newExpId); " +
                "${results.count { it.winnerChanged }} of ${results.size} winners changed"
        }
        return newExpId to results
    }
}
