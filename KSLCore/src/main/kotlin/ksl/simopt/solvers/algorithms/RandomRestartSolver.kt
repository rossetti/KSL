package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.concurrent.ConcurrentRunOptions
import ksl.simopt.solvers.concurrent.ConcurrentSolverRunner
import ksl.simopt.solvers.concurrent.ConfirmationOutcome
import ksl.simopt.solvers.concurrent.MemberEvaluatorFactoryIfc
import ksl.simopt.solvers.concurrent.MemberStatus
import ksl.simopt.solvers.concurrent.SolutionConfirmation
import ksl.simopt.solvers.concurrent.SolverFactoryIfc
import ksl.simopt.solvers.concurrent.SolverMemberTask
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import kotlin.math.min

/**
 * A class that implements the Random Restart optimization algorithm.
 * This algorithm repeatedly runs an inner solver, each run beginning at a different
 * (randomly generated) starting point, and reports the best solution found across all
 * runs.
 *
 * Two execution modes are supported:
 *
 * **Sequential mode** (the historical behavior, and the default): one inner solver
 * instance is reused, run to completion once per restart, with the evaluator's solution
 * cache optionally cleared between runs. Construct via the instance-based constructor,
 * or via the factory-based constructor with `concurrentRestarts = 1`.
 *
 * **Concurrent mode** (`concurrentRestarts > 1`, factory-based constructor only):
 * restarts are statistically independent, so up to `concurrentRestarts` of them run at
 * the same time, each on its own worker with its own solver instance (created by the
 * supplied [SolverFactoryIfc]) and its own private evaluation resources (provisioned by
 * the supplied [MemberEvaluatorFactoryIfc], typically a
 * `PooledMemberEvaluatorFactory`). Starting points for all restarts are pre-drawn from
 * the outer solver's stream before launching, and each restart's simulation streams
 * occupy a dedicated block of the sub-stream tape — so results are reproducible and do
 * not depend on scheduling or the worker count. Restart results are consumed in
 * submission order: outer iteration k reports restart k, keeping trackers and snapshots
 * deterministic. Because each concurrent restart owns a private, freshly created
 * solution cache, "clear the cache between runs" is structural in this mode; setting
 * [clearCacheBetweenRuns] to false is not supported concurrently.
 *
 * Note that the two modes consume randomness differently (one continuous stream tape
 * versus per-restart blocks), so sequential and concurrent runs of the same seed are
 * each reproducible but are not numerically identical to each other.
 *
 * An optional confirmation stage (see `ConcurrentRunOptions.confirmation`) re-evaluates
 * the best solutions of the completed restarts under common random numbers after all
 * restarts finish, and reports the confirmed winner as the final current solution; the
 * full outcome is available via [confirmationOutcome].
 */
class RandomRestartSolver private constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    restartingSolverInstance: Solver?,
    solverFactory: SolverFactoryIfc?,
    memberEvaluatorFactory: MemberEvaluatorFactoryIfc?,
    maxNumRestarts: Int,
    val concurrentRestarts: Int,
    val concurrentOptions: ConcurrentRunOptions,
    streamNum: Int,
    streamProvider: RNStreamProviderIfc,
    name: String?
) : StochasticSolver(
    problemDefinition, evaluator, maxNumRestarts,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    init {
        require(concurrentRestarts >= 1) { "concurrentRestarts must be >= 1" }
        require(restartingSolverInstance != null || solverFactory != null) {
            "Either a restarting solver instance or a solver factory must be supplied."
        }
        if (concurrentRestarts > 1) {
            requireNotNull(solverFactory) {
                "Concurrent restarts require a solver factory (the single-instance constructor is sequential only)."
            }
            requireNotNull(memberEvaluatorFactory) {
                "Concurrent restarts require a member evaluator factory to provision per-restart evaluation resources."
            }
        }
    }

    private val mySolverFactory: SolverFactoryIfc? = solverFactory
    private val myMemberEvaluatorFactory: MemberEvaluatorFactoryIfc? = memberEvaluatorFactory

    /**
     * The inner solver used by the randomized restarts. In sequential mode this is the
     * (single, reused) run instance. In concurrent mode the run instances are created
     * per restart by the solver factory, and this property holds a prototype instance —
     * created once from the factory, never run — so configuration reporting and
     * tracking probes that inspect the inner solver keep working.
     */
    val restartingSolver: Solver = restartingSolverInstance
        ?: solverFactory!!.create(
            evaluator,
            PROTOTYPE_MEMBER_INDEX,
            "${name ?: "RandomRestartSolver"}_prototype"
        )

    /**
     * Constructs a sequential random-restart solver around an existing inner solver
     * instance. This is the historical constructor: the instance is reused for every
     * restart, one restart at a time.
     *
     * @param restartingSolver The solver to be used for the randomized restarts.
     * @param maxNumRestarts The maximum number of restarts to be performed.
     * @param streamNum The random number stream number to be used for this solver.
     * @param streamProvider The random number stream provider to be used for this solver.
     * @param name Optional name identifier for this instance of the solver.
     */
    @JvmOverloads
    constructor(
        restartingSolver: Solver,
        maxNumRestarts: Int = defaultMaxRestarts,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = RNStreamProvider(),
        name: String? = null
    ) : this(
        problemDefinition = restartingSolver.problemDefinition,
        evaluator = restartingSolver.evaluator,
        replicationsPerEvaluation = restartingSolver.replicationsPerEvaluation,
        restartingSolverInstance = restartingSolver,
        solverFactory = null,
        memberEvaluatorFactory = null,
        maxNumRestarts = maxNumRestarts,
        concurrentRestarts = 1,
        concurrentOptions = ConcurrentRunOptions(),
        streamNum = streamNum,
        streamProvider = streamProvider,
        name = name
    )

    /**
     * Constructs a factory-based random-restart solver, capable of running restarts
     * concurrently.
     *
     * @param problemDefinition the problem being solved
     * @param evaluator the outer solver's evaluator; used to evaluate the initial point
     * and the optional confirmation stage. With `concurrentRestarts = 1` it is also the
     * evaluator of the (single, reused) inner solver instance created from the factory.
     * @param solverFactory creates the inner solver instances; see [SolverFactoryIfc]
     * @param memberEvaluatorFactory provisions per-restart evaluation resources;
     * required when `concurrentRestarts > 1`
     * @param maxNumRestarts the maximum number of restarts to be performed
     * @param concurrentRestarts the number of restarts allowed to run at the same time;
     * 1 (the default) preserves the sequential behavior
     * @param concurrentOptions stream-block size and optional confirmation stage; see
     * `ConcurrentRunOptions`
     * @param replicationsPerEvaluation the replication strategy for the outer solver's
     * own evaluations (the initial point)
     * @param streamNum the random number stream number for the outer restart driver
     * @param streamProvider the stream provider for the outer restart driver
     * @param name optional name identifier for this instance of the solver
     */
    @JvmOverloads
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        solverFactory: SolverFactoryIfc,
        memberEvaluatorFactory: MemberEvaluatorFactoryIfc? = null,
        maxNumRestarts: Int = defaultMaxRestarts,
        concurrentRestarts: Int = 1,
        concurrentOptions: ConcurrentRunOptions = ConcurrentRunOptions(),
        replicationsPerEvaluation: ReplicationPerEvaluationIfc =
            FixedReplicationsPerEvaluation(defaultReplicationsPerEvaluation),
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = RNStreamProvider(),
        name: String? = null
    ) : this(
        problemDefinition = problemDefinition,
        evaluator = evaluator,
        replicationsPerEvaluation = replicationsPerEvaluation,
        restartingSolverInstance = null,
        solverFactory = solverFactory,
        memberEvaluatorFactory = memberEvaluatorFactory,
        maxNumRestarts = maxNumRestarts,
        concurrentRestarts = concurrentRestarts,
        concurrentOptions = concurrentOptions,
        streamNum = streamNum,
        streamProvider = streamProvider,
        name = name
    )

    /**
     *  Indicates whether the evaluator cache should be cleared between runs.
     *  Defaults to true. If the evaluator does not support caching, this value is ignored.
     *  In concurrent mode this is structural (each restart's cache is private and born
     *  empty); setting it to false with concurrent restarts is rejected at initialization.
     */
    var clearCacheBetweenRuns = true

    /**
     * Invoked with each freshly created inner solver and its restart index, before the
     * restart runs. This is the attachment hook for per-restart trackers and other
     * instrumentation. In concurrent mode it is called on worker threads, so anything it
     * touches must be thread-safe (e.g. give each restart its own tracker/file). Not
     * used in sequential mode, where trackers attach to [restartingSolver] directly.
     */
    var innerSolverDecorator: ((solver: Solver, restartIndex: Int) -> Unit)? = null

    /**
     * The outcome of the confirmation stage, when one was configured via
     * `ConcurrentRunOptions.confirmation` and the run completed without a stop request;
     * null otherwise. The confirmed winner is also reported as the final current
     * solution. Note that the solver's best-solutions record keeps the unconfirmed
     * restart bests as well, so `bestSolution` may still report an unconfirmed
     * (noise-favored) point; consult this property when a confirmation stage is in use.
     */
    var confirmationOutcome: ConfirmationOutcome? = null
        private set

    /** True when this solver runs its restarts concurrently. */
    val isConcurrentMode: Boolean
        get() = concurrentRestarts > 1

    private var myRunner: ConcurrentSolverRunner? = null

    override fun initializeIterations() {
        if (isConcurrentMode) {
            require(clearCacheBetweenRuns) {
                "Sharing the evaluator cache across restarts (clearCacheBetweenRuns = false) " +
                        "is not supported with concurrent restarts: each restart owns a private cache."
            }
        }
        confirmationOutcome = null
        // Evaluates the initial point on the outer evaluator (legacy-identical) and sets
        // the initial/current solution.
        super.initializeIterations()
        if (isConcurrentMode) {
            val runner = ConcurrentSolverRunner(
                problemDefinition = problemDefinition,
                tasks = buildMemberTasks(),
                evaluatorFactory = myMemberEvaluatorFactory!!,
                numWorkers = min(concurrentRestarts, maximumNumberIterations)
            )
            myRunner = runner
            runner.launchAll()
        }
    }

    /**
     * Pre-draws every restart's starting point from the outer solver's stream, on the
     * launching thread, so the points a given seed produces do not depend on completion
     * order. Restart 0 honors a user-supplied starting point; all other restarts begin
     * at random input-feasible points.
     */
    private fun buildMemberTasks(): List<SolverMemberTask> {
        val tasks = mutableListOf<SolverMemberTask>()
        for (k in 0 until maximumNumberIterations) {
            val point = if (k == 0 && startingPoint != null) startingPoint!! else startingPoint()
            tasks.add(
                SolverMemberTask(
                    solverFactory = mySolverFactory!!,
                    label = "restart_%02d".format(k),
                    startingPoint = point,
                    innerSolverDecorator = innerSolverDecorator
                )
            )
        }
        return tasks
    }

    override fun mainIteration() {
        if (isConcurrentMode) {
            concurrentMainIteration()
        } else {
            sequentialMainIteration()
        }
    }

    private fun sequentialMainIteration() {
        // clear the evaluator cache between randomized runs, but allow caching during the run itself
        // this will cause new replications to be generated
        if (clearCacheBetweenRuns) {
            evaluator.cache?.clear()
        }
        // Each restart is an independent search: restart the evaluation clock so dynamic
        // penalty ramps begin fresh each run (matching concurrent mode, where every
        // restart's private evaluator starts its own clock). The evaluator's cumulative
        // statistics counters are unaffected.
        evaluator.resetEvaluationClock()
        // The first run honors a user-supplied starting point (the documented contract,
        // matching concurrent mode's restart 0); every other restart draws a random
        // input-feasible point.
        val startPoint = if (iterationCounter == 1 && startingPoint != null) {
            startingPoint!!
        } else {
            startingPoint()
        }
        restartingSolver.startingPoint = startPoint
        logger.debug { "Starting a new randomized run at point: ${startPoint.inputValues.joinToString()}" }
        // run the solver until it finds a solution
        restartingSolver.runAllIterations()
        numOracleCalls = numOracleCalls + restartingSolver.numOracleCalls
        numReplicationsRequested = numReplicationsRequested + restartingSolver.numReplicationsRequested
        // get the best solution from the solver run
        val bestSolution = restartingSolver.bestSolution
        logger.debug { "Best solution found from the solver run: ${bestSolution.asString()}" }
        currentSolution = bestSolution
        logger.debug { "Current best: ${currentSolution.asString()}" }
    }

    private fun concurrentMainIteration() {
        // Restart results are consumed in submission order: outer iteration k reports
        // restart k-1, no matter which restart happened to finish first.
        val memberIndex = iterationCounter - 1
        val result = myRunner!!.awaitMember(memberIndex)
        if (result.status == MemberStatus.FAILED) {
            logger.warn { "Solver: $name : restart ${result.label} failed: ${result.error?.message}" }
        }
        numOracleCalls = numOracleCalls + result.numOracleCalls
        numReplicationsRequested = numReplicationsRequested + result.numReplicationsRequested
        currentSolution = result.bestSolution
        logger.debug { "Solver: $name : restart ${result.label} reported best: ${result.bestSolution.asString()}" }
    }

    override fun mainIterationsEnded() {
        val runner = myRunner ?: return
        try {
            if (iterationCounter < maximumNumberIterations) {
                // The outer loop ended early (stop request or quality criterion); make
                // sure unconsumed restarts stop rather than running to completion.
                runner.requestStop("Outer restart iterations ended early")
            }
            val results = runner.awaitAllMembers()
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
        // The outer loop may be blocked awaiting a restart; forward the stop to the
        // in-flight restarts so their solvers exit after their current iterations.
        myRunner?.requestStop(msg)
    }

    override fun toString(): String {
        return """
        RandomRestartSolver(
            clearCacheBetweenRuns = $clearCacheBetweenRuns,
            concurrentRestarts = $concurrentRestarts,
            substreamBlockSize = ${concurrentOptions.substreamBlockSize},
            confirmation = ${confirmationDisplay()},
            innerSolver = ${restartingSolver.toString().prependIndent("    ").trimStart()},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    /**
     *  Flat representation: the inner solver's keys are re-emitted
     *  with an `innerSolver.` prefix so the resulting map stays
     *  flat and TOML-friendly (no nested tables, every entry a
     *  simple key/value pair).
     */
    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "clearCacheBetweenRuns" to clearCacheBetweenRuns.toString(),
            "concurrentRestarts" to concurrentRestarts.toString(),
            "substreamBlockSize" to concurrentOptions.substreamBlockSize.toString(),
            "confirmation" to confirmationDisplay()
        ) + restartingSolver.configurationProperties
            .mapKeys { (k, _) -> "innerSolver.$k" }

    private fun confirmationDisplay(): String =
        concurrentOptions.confirmation?.let {
            "topK=${it.topK}, replicationsPerCandidate=${it.replicationsPerCandidate}"
        } ?: "None"

    companion object {
        /**
         * Represents the default maximum number restarts to be executed
         * in a given process or algorithm.
         *
         * The default value is set to 5, but it can be modified based
         * on specific requirements or constraints.
         */
        @JvmStatic
        var defaultMaxRestarts = 5
            set(value) {
                require(value > 0) { "The default maximum number of restarts must be a positive value." }
                field = value
            }

        /**
         * The member index handed to the solver factory when creating the prototype
         * inner solver (which is never run). Factories that key behavior on the member
         * index can use this to recognize the prototype; most ignore the index.
         */
        const val PROTOTYPE_MEMBER_INDEX: Int = -1
    }
}
