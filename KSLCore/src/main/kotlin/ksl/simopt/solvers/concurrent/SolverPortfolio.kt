package ksl.simopt.solvers.concurrent

import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 * A solver portfolio: N solver instances — typically of *different* algorithms — race on
 * the same problem concurrently, each on its own worker with its own factory-created
 * solver and private evaluation resources, and the portfolio reports the best solution
 * found across all members.
 *
 * The portfolio is itself a solver, following the convention the random-restart solver
 * established: the maximum number of iterations equals the member count, and outer
 * iteration k blocks until member k completes, no matter which member happened to finish
 * first. Trackers, snapshots, and results therefore see a deterministic per-member
 * sequence, and the whole orchestration/reporting stack works on a portfolio unchanged.
 *
 * Reproducibility follows the concurrent-substrate rules: member starting points are
 * fixed at launch (per task, or inherited from the portfolio's starting point), member
 * solvers own fresh stream providers, and each member's simulation streams occupy a
 * dedicated block of the sub-stream tape — so results do not depend on scheduling or the
 * worker count.
 *
 * Because members may estimate their bests with different statistical precision
 * (different replication budgets, different luck), picking the winner from raw point
 * estimates favors noise. Configure a confirmation stage via
 * `ConcurrentRunOptions.confirmation` to re-evaluate the top member bests under common
 * random numbers; the confirmed winner becomes the final current solution and the full
 * outcome is available via [confirmationOutcome].
 *
 * Starting points: a member task with its own starting point keeps it. Members without
 * one inherit the portfolio's starting point when set (racing algorithms from a common
 * start), and otherwise generate their own.
 *
 * @param problemDefinition the problem all members solve
 * @param evaluator the portfolio's own evaluator; used for the initial-point evaluation
 * and the optional confirmation stage (member evaluations never route through it)
 * @param members the member tasks, in reporting order; labels must be unique
 * @param memberEvaluatorFactory provisions each member's private evaluation resources
 * @param concurrentOptions worker count, stream-block size, and optional confirmation
 * @param replicationsPerEvaluation the replication strategy for the portfolio's own
 * evaluations (the initial point)
 * @param streamNum the random number stream number for the portfolio driver
 * @param streamProvider the stream provider for the portfolio driver
 * @param name optional name identifier for this instance of the solver
 */
class SolverPortfolio @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    val members: List<SolverMemberTask>,
    memberEvaluatorFactory: MemberEvaluatorFactoryIfc,
    val concurrentOptions: ConcurrentRunOptions = ConcurrentRunOptions(),
    replicationsPerEvaluation: ReplicationPerEvaluationIfc =
        FixedReplicationsPerEvaluation(defaultReplicationsPerEvaluation),
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, members.size,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    init {
        require(members.isNotEmpty()) { "A portfolio requires at least one member" }
        val labels = members.map { it.label }
        require(labels.toSet().size == labels.size) { "Member labels must be unique; got $labels" }
    }

    private val myMemberEvaluatorFactory: MemberEvaluatorFactoryIfc = memberEvaluatorFactory

    private var myRunner: ConcurrentSolverRunner? = null

    private val myMemberResults = mutableListOf<SolverMemberResult>()

    /**
     * The results of the members, in member order, as consumed so far. Complete after
     * the portfolio finishes running.
     */
    val memberResults: List<SolverMemberResult>
        get() = myMemberResults.toList()

    /** The number of members in the portfolio. */
    @Suppress("unused")
    val numMembers: Int
        get() = members.size

    /**
     * The outcome of the confirmation stage, when one was configured via
     * `ConcurrentRunOptions.confirmation` and the run completed without a stop request;
     * null otherwise. The confirmed winner is also reported as the final current
     * solution. Note that the solver's best-solutions record keeps the unconfirmed
     * member bests as well, so `bestSolution` may still report an unconfirmed
     * (noise-favored) point; consult this property when a confirmation stage is in use.
     */
    var confirmationOutcome: ConfirmationOutcome? = null
        private set

    // Prototype instances (never run) for configuration reporting; created lazily so
    // portfolios that never report pay nothing.
    private val myMemberPrototypes: List<Solver> by lazy {
        members.map { task ->
            task.solverFactory.create(
                evaluator,
                SolverFactoryIfc.PROTOTYPE_MEMBER_INDEX,
                "${task.label}_prototype"
            )
        }
    }

    override fun initializeIterations() {
        confirmationOutcome = null
        myMemberResults.clear()
        // Evaluates the portfolio's initial point on its own evaluator and sets the
        // initial/current solution (the baseline the members must beat).
        super.initializeIterations()
        // A member without its own starting point inherits the portfolio's, when set.
        val tasks = members.map { task ->
            if (task.startingPoint == null && startingPoint != null) {
                task.copy(startingPoint = startingPoint)
            } else {
                task
            }
        }
        val runner = ConcurrentSolverRunner(
            problemDefinition = problemDefinition,
            tasks = tasks,
            evaluatorFactory = myMemberEvaluatorFactory,
            numWorkers = concurrentOptions.numWorkers
        )
        myRunner = runner
        runner.launchAll()
    }

    override fun mainIteration() {
        // Member results are consumed in submission order: outer iteration k reports
        // member k-1, no matter which member happened to finish first.
        val memberIndex = iterationCounter - 1
        val result = myRunner!!.awaitMember(memberIndex)
        if (result.status == MemberStatus.FAILED) {
            logger.warn { "Solver: $name : member ${result.label} failed: ${result.error?.message}" }
        }
        myMemberResults.add(result)
        numOracleCalls = numOracleCalls + result.numOracleCalls
        numReplicationsRequested = numReplicationsRequested + result.numReplicationsRequested
        currentSolution = result.bestSolution
        logger.debug { "Solver: $name : member ${result.label} reported best: ${result.bestSolution.asString()}" }
    }

    override fun mainIterationsEnded() {
        val runner = myRunner ?: return
        try {
            if (iterationCounter < maximumNumberIterations) {
                // The outer loop ended early (stop request or quality criterion); make
                // sure unconsumed members stop rather than running to completion.
                runner.requestStop("Portfolio iterations ended early")
            }
            val results = runner.awaitAllMembers()
            myMemberResults.clear()
            myMemberResults.addAll(results)
            val confirmation = concurrentOptions.confirmation
            if (confirmation != null && !runner.isStopRequested) {
                val candidates = results.filter { it.isSuccess }.map { it.bestSolution }
                if (candidates.isNotEmpty()) {
                    val outcome = SolutionConfirmation.confirmBest(
                        candidates = candidates,
                        evaluator = evaluator,
                        problemDefinition = problemDefinition,
                        options = confirmation
                    )
                    confirmationOutcome = outcome
                    numOracleCalls = numOracleCalls + outcome.numOracleCalls
                    numReplicationsRequested = numReplicationsRequested + outcome.numReplicationsRequested
                    currentSolution = outcome.winner
                    logger.debug { "Solver: $name : confirmation winner: ${outcome.winner.asString()}" }
                }
            }
        } finally {
            runner.shutdown()
            myRunner = null
        }
    }

    override fun onStopRequested(msg: String?) {
        // The outer loop may be blocked awaiting a member; forward the stop to the
        // in-flight members so their solvers exit after their current iterations.
        myRunner?.requestStop(msg)
    }

    override fun extractSolverSpecificState(): Map<String, Double>? {
        val completed = myMemberResults.count { it.isSuccess }
        val bestIndex = myMemberResults
            .filter { it.isSuccess }
            .minByOrNull { it.bestSolution.penalizedObjFncValue }
            ?.memberIndex ?: -1
        return mapOf(
            "numMembers" to members.size.toDouble(),
            "membersCompleted" to completed.toDouble(),
            "bestMemberIndex" to bestIndex.toDouble()
        )
    }

    override fun toString(): String {
        return """
        SolverPortfolio(
            numMembers = ${members.size},
            members = ${members.joinToString { it.label }},
            numWorkers = ${concurrentOptions.numWorkers ?: "auto"},
            substreamBlockSize = ${concurrentOptions.substreamBlockSize},
            confirmation = ${confirmationDisplay()},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    /**
     *  Flat representation: each member prototype's keys are re-emitted with a
     *  dotted, indexed prefix (member.0.label, member.0.solverName, ...) so the
     *  resulting map stays flat and TOML-friendly.
     */
    override val configurationProperties: Map<String, String>
        get() {
            val map = linkedMapOf(
                "numMembers" to members.size.toString(),
                "numWorkers" to (concurrentOptions.numWorkers?.toString() ?: "auto"),
                "substreamBlockSize" to concurrentOptions.substreamBlockSize.toString(),
                "confirmation" to confirmationDisplay()
            )
            for ((index, task) in members.withIndex()) {
                map["member.$index.label"] = task.label
                val prototype = myMemberPrototypes[index]
                map["member.$index.algorithm"] = prototype::class.simpleName ?: "Unknown"
                for ((key, value) in prototype.configurationProperties) {
                    map["member.$index.$key"] = value
                }
            }
            return super.configurationProperties + map
        }

    private fun confirmationDisplay(): String =
        concurrentOptions.confirmation?.let {
            "topK=${it.topK}, replicationsPerCandidate=${it.replicationsPerCandidate}"
        } ?: "None"

    companion object {

        /**
         * Creates a portfolio with the standard resource wiring: the portfolio's own
         * evaluator is a sequential problem evaluator built from the model builder, and
         * member resources come from a pooled member-evaluator factory over the same
         * builder (models pooled at the worker count, per-member stream blocks, private
         * per-member caches).
         *
         * @param problemDefinition the problem the members solve
         * @param modelBuilder builds fresh, independent models per call
         * @param members the member tasks; labels must be unique
         * @param concurrentOptions worker count, stream-block size, optional confirmation
         * @param solutionCache the portfolio's own solution cache (members always get
         * fresh private caches)
         * @param simulationRunCache optional run cache for the portfolio's own evaluator
         * @param experimentRunParameters run parameters applied when building models
         * @param replicationsPerEvaluation replications for the portfolio's own
         * evaluations (the initial point)
         * @param streamNum the stream number for the portfolio driver
         * @param streamProvider the stream provider for the portfolio driver
         * @param name optional name for the portfolio
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("unused")
        fun create(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            members: List<SolverMemberTask>,
            concurrentOptions: ConcurrentRunOptions = ConcurrentRunOptions(),
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            replicationsPerEvaluation: Int = Solver.defaultReplicationsPerEvaluation,
            streamNum: Int = 0,
            streamProvider: RNStreamProviderIfc = RNStreamProvider(),
            name: String? = null
        ): SolverPortfolio {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                solutionCache = solutionCache,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
            val memberEvaluatorFactory = PooledMemberEvaluatorFactory(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                baseRunParameters = experimentRunParameters,
                substreamBlockSize = concurrentOptions.substreamBlockSize
            )
            return SolverPortfolio(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                members = members,
                memberEvaluatorFactory = memberEvaluatorFactory,
                concurrentOptions = concurrentOptions,
                replicationsPerEvaluation = FixedReplicationsPerEvaluation(replicationsPerEvaluation),
                streamNum = streamNum,
                streamProvider = streamProvider,
                name = name
            )
        }
    }
}
