package ksl.simopt.benchmark

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
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
import ksl.simopt.solvers.concurrent.SolverMemberTask
import ksl.utilities.random.rng.RNStreamIfc
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
 *     point is pre-drawn on the launching thread from the experiment's stream and
 *     shared by every solver case — solvers race from common starts and macro-reps vary
 *     the start. Each cell receives its own copy. Population-based solvers that ignore
 *     starting points simply ignore them.
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
 *  @param replicationBudgetPerRun the per-cell replication budget
 *  @param confirmation confirmation-stage options; null disables confirmation
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
    val replicationBudgetPerRun: Int,
    val confirmation: ConfirmationOptions? = ConfirmationOptions(),
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
        require(replicationBudgetPerRun >= 1) { "replicationBudgetPerRun must be >= 1" }
        require(numWorkers == null || numWorkers > 0) { "numWorkers must be > 0 when specified" }
    }

    private val myExperimentStream: RNStreamIfc = experimentStreamProvider.rnStream(1)

    /**
     *  Runs the full grid: for each problem (in order), launches all of its cells
     *  concurrently, awaits them in cell order, runs the optional confirmation stage,
     *  computes gaps, and records everything.
     *
     *  May be called once per instance; the experiment stream advances as starting
     *  points are drawn, so a second call would not reproduce the first.
     */
    fun run(): BenchmarkSummary {
        logger.info { "Benchmark experiment '$name': ${problems.size} problems x ${solverCases.size} solver cases x $macroReplications reps, budget $replicationBudgetPerRun" }
        val problemResults = problems.map { runProblem(it) }
        logger.info { "Benchmark experiment '$name' complete" }
        return BenchmarkSummary(
            experimentName = name,
            macroReplications = macroReplications,
            replicationBudgetPerRun = replicationBudgetPerRun,
            problemResults = problemResults
        )
    }

    private fun runProblem(problemCase: ProblemCase): ProblemBenchmarkResult {
        logger.info { "Benchmark '$name': running problem '${problemCase.name}'" }
        val problemDefinition = problemCase.problemDefinitionFactory()
        val evaluatorFactory = problemCase.evaluatorFactoryProvider(problemDefinition)
        // one common starting point per macro-replication, pre-drawn on this thread
        val startingPoints: List<InputMap> = List(macroReplications) {
            problemDefinition.startingPoint(myExperimentStream)
        }
        // cells in deterministic order: solver case major, macro-replication minor
        val tasks = mutableListOf<SolverMemberTask>()
        for (solverCase in solverCases) {
            for (repNum in 1..macroReplications) {
                val cellLabel = "${problemCase.name}_${solverCase.label}_r$repNum"
                tasks.add(
                    SolverMemberTask(
                        solverFactory = budgeted(problemDefinition, solverCase.solverFactory),
                        label = cellLabel,
                        // a private copy: starting points are shared across cells by value
                        startingPoint = problemDefinition.toInputMap(startingPoints[repNum - 1].toMutableMap()),
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
        // confirmation across the problem's valid candidates, on a dedicated evaluator
        // provisioned as an extra member index so it gets its own stream block
        var confirmationOutcome: ConfirmationOutcome? = null
        if (confirmation != null) {
            val candidates = memberResults.filter { it.isSuccess }.map { it.bestSolution }
            if (candidates.isNotEmpty()) {
                val confirmationEvaluator = evaluatorFactory.createEvaluator(tasks.size)
                try {
                    confirmationOutcome = SolutionConfirmation.confirmBest(
                        candidates, confirmationEvaluator, problemDefinition, confirmation
                    )
                } finally {
                    evaluatorFactory.release(tasks.size, confirmationEvaluator, true)
                }
            }
        }
        return recordProblem(problemCase, problemDefinition, startingPoints, memberResults, confirmationOutcome)
    }

    /**
     *  Binds a case's factory to the problem and wraps it so every created solver
     *  receives the experiment's budget criterion (composed with any criterion the case
     *  installed) and an iteration ceiling equal to the budget.
     */
    private fun budgeted(
        problemDefinition: ProblemDefinition,
        baseFactory: BenchmarkSolverFactoryIfc
    ): SolverFactoryIfc {
        val budgetCriterion = ReplicationBudgetStoppingCriterion(replicationBudgetPerRun)
        return SolverFactoryIfc { evaluator, memberIndex, solverName ->
            val solver = baseFactory.create(problemDefinition, evaluator, memberIndex, solverName)
            solver.maximumNumberIterations = replicationBudgetPerRun
            val caseCriterion = solver.solutionQualityEvaluator
            solver.solutionQualityEvaluator = if (caseCriterion == null) {
                budgetCriterion
            } else {
                SolutionQualityEvaluatorIfc { s ->
                    budgetCriterion.isStoppingCriteriaReached(s) || caseCriterion.isStoppingCriteriaReached(s)
                }
            }
            solver
        }
    }

    private fun recordProblem(
        problemCase: ProblemCase,
        problemDefinition: ProblemDefinition,
        startingPoints: List<InputMap>,
        memberResults: List<SolverMemberResult>,
        confirmationOutcome: ConfirmationOutcome?
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
            val solverCase = solverCases[cellIndex / macroReplications]
            val repNum = (cellIndex % macroReplications) + 1
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
                startingPoint = startingPoints[repNum - 1].toMap(),
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
            runs = runs,
            confirmation = confirmationOutcome,
            winner = winner,
            gapBasisObjective = gapBasis,
            gapType = gapType
        )
    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}
    }
}
