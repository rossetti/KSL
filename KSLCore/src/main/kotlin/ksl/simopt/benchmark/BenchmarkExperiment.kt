package ksl.simopt.benchmark

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.ModelInputs
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.ReplicationBudgetStoppingCriterion
import ksl.simopt.solvers.SolutionQualityEvaluatorIfc
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.SolverResult
import ksl.simopt.solvers.concurrent.ConcurrentSolverRunner
import ksl.simopt.solvers.concurrent.ConfirmationOptions
import ksl.simopt.solvers.concurrent.ConfirmationOutcome
import ksl.simopt.solvers.concurrent.MemberStatus
import ksl.simopt.solvers.concurrent.SolutionConfirmation
import ksl.simopt.solvers.concurrent.SolverFactoryIfc
import ksl.simopt.solvers.concurrent.SolverMemberResult
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import ksl.simopt.solvers.concurrent.SolverMemberTask
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  The benchmark grid: problems × solver configurations × macro-replications, run under
 *  the experiment-level fairness policies and concurrent execution, producing a
 *  [BenchmarkSummary] of cell-level run records, per-problem confirmation outcomes, and
 *  optimality gaps.
 *
 *  Fairness and reproducibility policies:
 *
 *  1. **Equal replication budgets.** Every cell's solver is wrapped to receive a
 *     replication-budget stopping criterion for [replicationBudgetPerRun] and an
 *     iteration ceiling equal to the budget (every iteration requests at least one
 *     replication, so the budget itself is a generous ceiling). The criterion is checked
 *     between iterations, so batch solvers can overshoot by up to one generation; the
 *     run record captures the actual consumption for normalization. If a case's factory
 *     installed its own solution-quality criterion, the wrap composes them (either
 *     stops the solver); the solver-internal convergence fallbacks that live behind the
 *     criterion slot are superseded, which is the intended equal-effort semantics.
 *  2. **Common starting points.** For each (problem, macro-replication), one starting
 *     point is pre-drawn on the launching thread and shared by every solver case —
 *     solvers race from common starts and macro-reps vary the start. Each cell receives
 *     its own copy. Population-based solvers that ignore starting points simply ignore
 *     them. The draw is addressed **absolutely** by (problem position, macro-replication):
 *     the problem at position p draws from stream p + 1 of the experiment's provider, and
 *     macro-replication r takes sub-stream r - 1 of it. A starting point therefore does not
 *     depend on how many draws preceded it, which is what makes [macroReplicationRange]
 *     compose and makes a repeated run reproduce. Widening a study's macro-replications, or
 *     appending a problem, leaves every existing draw where it was; inserting a problem
 *     shifts the positions after it, and with them their streams.
 *  3. **Confirmation (optional).** After all of a problem's cells complete, the best
 *     solutions are re-evaluated under common random numbers via a dedicated evaluator
 *     (provisioned as an extra member, so it has its own stream block) and the winner is
 *     picked from the confirmed estimates.
 *  4. **Determinism.** Cell identity, starting points, member stream blocks, and
 *     solver-side streams are all fixed at launch time, and results are consumed in cell
 *     order — for a fixed experiment configuration the summary does not depend on the
 *     worker count or thread timing (the concurrent substrate's guarantee, inherited).
 *
 *  Gap recording: when a problem has a [ReferenceSolution], every run's gap is computed
 *  against it (gap type from the reference); otherwise runs are gapped against the best
 *  objective found across the problem's valid runs (gap type BEST_FOUND). Gaps are
 *  oriented so that larger is worse regardless of the problem's optimization type.
 *
 *  Problems run sequentially; the cells of each problem run concurrently on at most
 *  [numWorkers] workers.
 *
 *  @param name the experiment's name; flows into every run record
 *  @param problems the problem cases; names must be unique
 *  @param solverCases the solver configurations; labels must be unique
 *  @param macroReplications the number of macro-replications per (problem, solver) pair
 *  in the study this experiment belongs to; it fixes the addressing of starting points,
 *  so blocks of one study must all declare the same value
 *  @param macroReplicationRange which of those macro-replications THIS experiment runs.
 *  Defaults to all of them. Running a study as several experiments over disjoint
 *  sub-ranges is cell-for-cell identical to running it as one, which is how a long study
 *  is checkpointed: each block is saved on completion and a resumed run skips the blocks
 *  already present
 *  @param replicationBudgetPerRun the per-cell replication budget
 *  @param confirmation confirmation-stage options; null disables confirmation
 *  @param captureIterationTraces when true, every cell solver's per-iteration progress
 *  (iteration, cumulative replications, best penalized objective) is captured into the
 *  summary's traces, keyed by cell label — opt-in because traces grow with the budget
 *  @param verificationReplications when non-null, each problem's winning point is
 *  re-simulated at this replication count on a dedicated evaluator and recorded — the
 *  classic verify-at-elevated-replications step
 *  @param numWorkers the maximum number of cells running at the same time; null uses
 *  the smaller of the cell count and the available processors
 *  @param experimentStreamProvider the stream provider for experiment-level draws
 *  (currently: the common starting points); defaults to a fresh provider so identically
 *  configured experiments reproduce each other exactly
 *  @param cellSolverDecorator invoked with each freshly created cell solver before it
 *  runs, on the cell's worker thread — the attachment hook for per-cell trackers and
 *  instrumentation; anything it touches must be safe to use from worker threads
 */
class BenchmarkExperiment(
    val name: String,
    val problems: List<ProblemCase>,
    val solverCases: List<SolverCase>,
    val macroReplications: Int,
    val macroReplicationRange: IntRange = 1..macroReplications,
    val replicationBudgetPerRun: Int,
    val confirmation: ConfirmationOptions? = ConfirmationOptions(),
    val captureIterationTraces: Boolean = false,
    val verificationReplications: Int? = null,
    val numWorkers: Int? = null,
    experimentStreamProvider: RNStreamProviderIfc = RNStreamProvider(),
    private val cellSolverDecorator: ((solver: Solver, problemName: String, solverLabel: String, repNum: Int) -> Unit)? = null
) {

    init {
        require(name.isNotBlank()) { "The experiment name must not be blank" }
        require(problems.isNotEmpty()) { "At least one problem case must be supplied" }
        require(solverCases.isNotEmpty()) { "At least one solver case must be supplied" }
        val problemNames = problems.map { it.name }
        require(problemNames.toSet().size == problemNames.size) {
            "Problem case names must be unique; got $problemNames"
        }
        val labels = solverCases.map { it.label }
        require(labels.toSet().size == labels.size) {
            "Solver case labels must be unique; got $labels"
        }
        require(macroReplications >= 1) { "macroReplications must be >= 1" }
        require(!macroReplicationRange.isEmpty()) { "macroReplicationRange must not be empty" }
        require(macroReplicationRange.first >= 1 && macroReplicationRange.last <= macroReplications) {
            "macroReplicationRange $macroReplicationRange must lie within 1..$macroReplications"
        }
        require(replicationBudgetPerRun >= 1) { "replicationBudgetPerRun must be >= 1" }
        require(verificationReplications == null || verificationReplications >= 1) {
            "verificationReplications must be >= 1 when specified"
        }
        require(numWorkers == null || numWorkers > 0) { "numWorkers must be > 0 when specified" }
    }

    private val myExperimentStreamProvider: RNStreamProviderIfc = experimentStreamProvider

    /** The macro-replications this experiment runs, in order. */
    private val myRepNumbers: List<Int> = macroReplicationRange.toList()
    private val myTraces = java.util.concurrent.ConcurrentHashMap<String, MutableList<IterationTracePoint>>()
    private val mySolverConfigurations = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /**
     *  Runs the full grid: for each problem (in order), launches all of its cells
     *  concurrently, awaits them in cell order, runs the optional confirmation stage,
     *  computes gaps, and records everything.
     *
     *  Repeatable: starting points are addressed absolutely by (problem, macro-replication)
     *  rather than drawn in sequence, so a second call reproduces the first.
     */
    fun run(): BenchmarkSummary {
        logger.info { "Benchmark experiment '$name': ${problems.size} problems x ${solverCases.size} solver cases x macro-replications $macroReplicationRange of $macroReplications, budget $replicationBudgetPerRun" }
        val startTime = Clock.System.now()
        val problemResults = problems.mapIndexed { problemIndex, problemCase ->
            runProblem(problemIndex, problemCase)
        }
        val endTime = Clock.System.now()
        logger.info { "Benchmark experiment '$name' complete" }
        return BenchmarkSummary(
            experimentName = name,
            macroReplications = myRepNumbers.size,
            replicationBudgetPerRun = replicationBudgetPerRun,
            confirmation = confirmation,
            verificationReplications = verificationReplications,
            startTime = startTime,
            endTime = endTime,
            problemResults = problemResults,
            solverCaseDescriptions = solverCases.associate { it.label to it.description },
            solverConfigurations = mySolverConfigurations.toMap(),
            traces = myTraces.mapValues { (_, points) -> points.toList() }
        )
    }

    private fun runProblem(problemIndex: Int, problemCase: ProblemCase): ProblemBenchmarkResult {
        logger.info { "Benchmark '$name': running problem '${problemCase.name}'" }
        val problemDefinition = problemCase.problemDefinitionFactory()
        // A member's stream block is derived from the index it is created with, and the runner
        // numbers members by their position in ITS task list -- which shrinks when only part of
        // the study runs. Remap those positions onto study-wide member numbers so that a cell's
        // streams depend on (solver, macro-replication) rather than on which block it ran in.
        // For the full range this mapping is the identity, so an unblocked run is unchanged.
        val evaluatorFactory = StudyIndexedEvaluatorFactory(
            problemCase.evaluatorFactoryProvider(problemDefinition),
            ::studyMemberIndex
        )
        // One common starting point per macro-replication, pre-drawn on this thread and
        // addressed absolutely: this problem draws from its own stream, and macro-replication
        // r sits on sub-stream r - 1 of it. The point for (problem, r) therefore depends only
        // on (problem, r) -- not on how many problems or replications came before -- so
        // disjoint macroReplicationRange blocks reproduce the same points a whole run would
        // give, and adding a problem does not perturb any other problem's starts.
        val startingPointStream = myExperimentStreamProvider.rnStream(problemIndex + 1)
        val startingPoints: Map<Int, InputMap> = myRepNumbers.associateWith { repNum ->
            startingPointStream.resetStartStream()
            startingPointStream.advanceSubStreams((repNum - 1).toLong())
            problemDefinition.startingPoint(startingPointStream)
        }
        // cells in deterministic order: solver case major, macro-replication minor
        val tasks = mutableListOf<SolverMemberTask>()
        for (solverCase in solverCases) {
            for (repNum in myRepNumbers) {
                val cellLabel = "${problemCase.name}_${solverCase.label}_r$repNum"
                tasks.add(
                    SolverMemberTask(
                        solverFactory = budgeted(problemDefinition, solverCase),
                        label = cellLabel,
                        // a private copy: starting points are shared across cells by value
                        startingPoint = problemDefinition.toInputMap(startingPoints.getValue(repNum).toMutableMap()),
                        innerSolverDecorator = cellSolverDecorator?.let { decorator ->
                            { solver, _ -> decorator(solver, problemCase.name, solverCase.label, repNum) }
                        }
                    )
                )
            }
        }
        val runner = ConcurrentSolverRunner(problemDefinition, tasks, evaluatorFactory, numWorkers)
        val memberResults: List<SolverMemberResult>
        try {
            runner.launchAll()
            memberResults = tasks.indices.map { runner.awaitMember(it) }
        } finally {
            runner.shutdown()
        }
        // confirmation and verification share a dedicated evaluator, provisioned as an
        // extra member index so it gets its own stream block
        var confirmationOutcome: ConfirmationOutcome? = null
        var verification: Solution? = null
        val candidates = memberResults.filter { it.isSuccess && it.bestSolution.isValid }.map { it.bestSolution }
        if (candidates.isNotEmpty() && (confirmation != null || verificationReplications != null)) {
            val extraEvaluator = evaluatorFactory.createEvaluator(CONFIRMATION_MEMBER)
            try {
                if (confirmation != null) {
                    confirmationOutcome = SolutionConfirmation.confirmBest(
                        candidates, extraEvaluator, problemDefinition, confirmation
                    )
                }
                if (verificationReplications != null) {
                    val winningPoint = confirmationOutcome?.winner
                        ?: candidates.minByOrNull { it.penalizedObjFncValue }
                    if (winningPoint != null) {
                        val modelInputs = ModelInputs(
                            modelIdentifier = problemDefinition.modelIdentifier,
                            numReplications = verificationReplications,
                            inputs = winningPoint.inputMap,
                            responseNames = problemDefinition.allResponseNames.toSet()
                        )
                        val request = EvaluationRequest(
                            modelIdentifier = problemDefinition.modelIdentifier,
                            modelInputs = listOf(modelInputs),
                            cachingAllowed = false
                        )
                        verification = extraEvaluator.evaluate(request).values.firstOrNull()
                    }
                }
            } finally {
                evaluatorFactory.release(CONFIRMATION_MEMBER, extraEvaluator, true)
            }
        }
        return recordProblem(
            problemCase, problemDefinition, startingPoints, memberResults,
            confirmationOutcome, verification
        )
    }

    /**
     *  Binds a case's factory to the problem and wraps it so every created solver
     *  receives the experiment's budget criterion (composed with any criterion the case
     *  installed) and an iteration ceiling equal to the budget. The wrap also captures
     *  the case's actually-running configuration (once, from the first instance) and
     *  attaches the iteration-trace listener when trace capture is on.
     */
    private fun budgeted(
        problemDefinition: ProblemDefinition,
        solverCase: SolverCase
    ): SolverFactoryIfc {
        val budgetCriterion = ReplicationBudgetStoppingCriterion(replicationBudgetPerRun)
        return SolverFactoryIfc { evaluator, memberIndex, solverName ->
            val solver = solverCase.solverFactory.create(problemDefinition, evaluator, memberIndex, solverName)
            solver.maximumNumberIterations = replicationBudgetPerRun
            val caseCriterion = solver.solutionQualityEvaluator
            solver.solutionQualityEvaluator = if (caseCriterion == null) {
                budgetCriterion
            } else {
                SolutionQualityEvaluatorIfc { s ->
                    budgetCriterion.isStoppingCriteriaReached(s) || caseCriterion.isStoppingCriteriaReached(s)
                }
            }
            mySolverConfigurations.putIfAbsent(solverCase.label, solver.configurationProperties.toMap())
            if (captureIterationTraces) {
                // the cell's worker owns the list; the map is concurrent because cells
                // are created on different workers
                val tracePoints = mutableListOf<IterationTracePoint>()
                myTraces[solverName] = tracePoints
                solver.snapShotFrequency = 1
                solver.iterationEmitter.attach { snapshot ->
                    tracePoints.add(
                        IterationTracePoint(
                            iteration = snapshot.iterationNumber,
                            cumulativeReplications = snapshot.numReplicationsRequested,
                            bestPenalizedObjective = snapshot.penalizedObjFncValue
                        )
                    )
                }
            }
            solver
        }
    }

    /**
     *  The study-wide member number for a task at [taskIndex] in this experiment's task list.
     *  Tasks are ordered solver-case major, macro-replication minor, so the study-wide number is
     *  the position the same cell would occupy if the whole study ran as one experiment.
     */
    private fun studyMemberIndex(taskIndex: Int): Int {
        if (taskIndex == CONFIRMATION_MEMBER) {
            // one past the last study member, so it never collides with a cell
            return solverCases.size * macroReplications
        }
        val solverIndex = taskIndex / myRepNumbers.size
        val repNum = myRepNumbers[taskIndex % myRepNumbers.size]
        return solverIndex * macroReplications + (repNum - 1)
    }

    /**
     *  Presents a member-evaluator factory under study-wide member numbering. See
     *  [studyMemberIndex]; the sentinel [CONFIRMATION_MEMBER] addresses the confirmation and
     *  verification evaluator.
     */
    private class StudyIndexedEvaluatorFactory(
        private val inner: MemberEvaluatorFactoryIfc,
        private val studyIndexOf: (Int) -> Int
    ) : MemberEvaluatorFactoryIfc {
        override fun createEvaluator(memberIndex: Int): EvaluatorIfc =
            inner.createEvaluator(studyIndexOf(memberIndex))

        override fun release(memberIndex: Int, evaluator: EvaluatorIfc, reusable: Boolean) =
            inner.release(studyIndexOf(memberIndex), evaluator, reusable)
    }

    private fun recordProblem(
        problemCase: ProblemCase,
        problemDefinition: ProblemDefinition,
        startingPoints: Map<Int, InputMap>,
        memberResults: List<SolverMemberResult>,
        confirmationOutcome: ConfirmationOutcome?,
        verification: Solution?
    ): ProblemBenchmarkResult {
        // orient objectives so that smaller is always better for basis/gap computations
        val orientation = problemDefinition.objFncFactor
        val reference = problemCase.referenceSolution
        val validBests = memberResults.filter { it.isSuccess && it.bestSolution.isValid }
        val gapBasis: Double?
        val gapType: GapType?
        if (reference != null) {
            gapBasis = reference.objectiveValue
            gapType = when (reference.type) {
                ReferenceType.KNOWN_OPTIMUM -> GapType.KNOWN_OPTIMUM
                ReferenceType.BEST_KNOWN -> GapType.BEST_KNOWN
            }
        } else {
            gapBasis = validBests.minOfOrNull { orientation * it.bestSolution.average }
                ?.let { it * orientation }
            gapType = if (gapBasis != null) GapType.BEST_FOUND else null
        }
        val runs = memberResults.mapIndexed { cellIndex, member ->
            val solverCase = solverCases[cellIndex / myRepNumbers.size]
            val repNum = myRepNumbers[cellIndex % myRepNumbers.size]
            val best = member.bestSolution
            val isBestValid = member.isSuccess && best.isValid
            val completed = member.solverResult as? SolverResult.Completed
            val gap = if (isBestValid && gapBasis != null) {
                orientation * (best.average - gapBasis)
            } else {
                null
            }
            BenchmarkRunResult(
                experimentName = name,
                problemName = problemCase.name,
                solverLabel = solverCase.label,
                repNum = repNum,
                cellLabel = member.label,
                status = member.status,
                startingPoint = startingPoints.getValue(repNum).toMap(),
                bestInputs = best.inputMap.toMap(),
                bestObjective = best.average,
                bestPenalizedObjective = best.penalizedObjFncValue,
                isBestValid = isBestValid,
                isInputFeasible = best.isInputFeasible(),
                responseConstraintViolation = best.responseConstraintViolationPenalty,
                numOracleCalls = member.numOracleCalls,
                numReplicationsRequested = member.numReplicationsRequested,
                totalIterations = completed?.totalIterations,
                wallClockMillis = completed?.executionTimeMillis,
                gap = gap,
                gapType = if (gap != null) gapType else null,
                errorMessage = member.error?.message
            )
        }
        val winner = confirmationOutcome?.winner
            ?: validBests.minByOrNull { it.bestSolution.penalizedObjFncValue }?.bestSolution
        return ProblemBenchmarkResult(
            problemName = problemCase.name,
            tags = problemCase.tags,
            dimension = problemDefinition.inputSize,
            optimizationType = problemDefinition.optimizationType,
            numResponseConstraints = problemDefinition.responseConstraints.size,
            runs = runs,
            confirmation = confirmationOutcome,
            winner = winner,
            verification = verification,
            gapBasisObjective = gapBasis,
            gapType = gapType
        )
    }

    companion object {
        /**
         *  Addresses the dedicated confirmation and verification evaluator rather than a cell.
         *  Negative so it can never be a task-list position.
         */
        private const val CONFIRMATION_MEMBER: Int = -1

        val logger: KLogger = KotlinLogging.logger {}
    }
}
