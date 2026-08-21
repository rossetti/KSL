package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.utilities.Interval
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  The three-phase Industrial Strength COMPASS (ISC) driver: a **global** Niching-GA exploration
 *  phase, a **local** COMPASS phase run once per niche seed, and a **clean-up** ranking-and-selection
 *  phase that screens the local optima, selects the best, and reports a confidence interval. It
 *  orchestrates the in-package [NichingGeneticAlgorithmSolver], [CompassSolver], and
 *  [CleanUpProcedure] as a phase state machine: each [mainIteration] advances one macro-step (run the
 *  global phase, run one local search, or finish with clean-up).
 *
 *  **Requires an integer-ordered problem definition.** ISC's COMPASS local phase searches an integer
 *  lattice (unit-step neighborhood), so every input must have granularity 1.0. The constructor throws
 *  an IllegalArgumentException when the problem is not integer-ordered.
 *
 *  **Indifference zones and graceful degradation.** A single user parameter [deltaC] drives the
 *  statistical guarantees, with [deltaL] (COMPASS local-optimality) defaulting to it:
 *
 *  - **`deltaC > 0` — full ISC.** Clean-up runs the Rinott indifference-zone selection and reports
 *    `ḡ(x_B) ± δ_C` with the correct-selection guarantee; with `deltaL > 0` each COMPASS run confirms
 *    local optimality with Kim's (2005) sequential test.
 *  - **`deltaC = 0` (the default, inherited from [ProblemDefinition.indifferenceZoneParameter]) —
 *    degraded.** ISC still returns a feasible best, but the IZ-dependent guarantees are intentionally
 *    dropped: clean-up degrades to screening + an ordinary confidence interval (no `±δ_C`
 *    correct-selection guarantee), and COMPASS stops on the most-promising-area singleton condition
 *    plus the iteration budget. A positive `δ_C` is required to obtain the guarantees.
 *
 *  Set [globalPhase] to null (or use the COMPASS-only factory) for the paper's **unimodal shortcut**:
 *  the global phase is skipped and a single COMPASS run starts from the configured starting point (or
 *  a random feasible point).
 *
 *  The best solution found is tracked automatically by the base class through [currentSolution]; the
 *  selected best and its [confidenceInterval] are finalized by the clean-up phase.
 *
 *  **Reading the result (D2).** For ISC, read the answer from [currentSolution] together with
 *  [confidenceInterval] — that pair is the clean-up-selected best and its interval. The generic
 *  `Solver.bestSolution` returns the minimum *penalized point estimate* across every solution the run
 *  produced (including unconfirmed niching-GA population members), which can differ from — and is less
 *  trustworthy than — the ranking-and-selection winner that [confidenceInterval] describes.
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the evaluator responsible for assessing the quality of solutions
 *  @param streamNum the random number stream number; 0 (the default) means the next available stream
 *  @param streamProvider the provider of random number streams shared with the sub-solvers
 *  @param replicationsPerEvaluation strategy for the number of replications per evaluation
 *  @param deltaC the clean-up indifference zone `δ_C`; defaults to the problem's IZ parameter
 *  @param deltaL the COMPASS local-optimality indifference zone `δ_L`; defaults to [deltaC]
 *  @param skipGlobalPhase when true, skip the global phase (COMPASS-only unimodal shortcut)
 *  @param globalPhase the Niching-GA global phase; when null and not skipped, a default is built
 *  @param localPhaseFactory builds a [CompassSolver] for a given seed point; when null a default is used
 *  @param cleanUp the clean-up procedure; when null a default is built from [deltaC]
 *  @param globalBudget an optional replication budget that adds a [BudgetRule] to a default global phase
 *  @param maximumIterations the maximum number of orchestration macro-steps
 *  @param maxLocalPhaseReplications the per-run replication cap for a **default** COMPASS local phase
 *  (see [CompassSolver.maxReplications]); ignored when a [localPhaseFactory] is supplied, since the
 *  factory then owns its solvers. Defaults to [CompassSolver.defaultMaxReplications].
 *  @param maxCleanUpReplicationsPerSystem the per-survivor Rinott second-stage cap for a **default**
 *  clean-up phase (see [CleanUpProcedure.maxReplicationsPerSystem]); ignored when [cleanUp] is supplied.
 *  Defaults to [CleanUpProcedure.DEFAULT_MAX_REPLICATIONS_PER_SYSTEM].
 *  @param name an optional name for the solver
 */
class ISCSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    deltaC: Double = problemDefinition.indifferenceZoneParameter,
    deltaL: Double = deltaC,
    skipGlobalPhase: Boolean = false,
    globalPhase: NichingGeneticAlgorithmSolver? = null,
    localPhaseFactory: ((InputMap) -> CompassSolver)? = null,
    cleanUp: CleanUpProcedure? = null,
    globalBudget: Int? = null,
    maximumIterations: Int = iscDefaultMaxIterations,
    maxLocalPhaseReplications: Int = CompassSolver.defaultMaxReplications,
    maxCleanUpReplicationsPerSystem: Int = CleanUpProcedure.DEFAULT_MAX_REPLICATIONS_PER_SYSTEM,
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, maximumIterations,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    /**
     *  Constructs an ISC solver using a fixed number of replications per evaluation.
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = RNStreamProvider(),
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        deltaC: Double = problemDefinition.indifferenceZoneParameter,
        deltaL: Double = deltaC,
        skipGlobalPhase: Boolean = false,
        globalPhase: NichingGeneticAlgorithmSolver? = null,
        localPhaseFactory: ((InputMap) -> CompassSolver)? = null,
        cleanUp: CleanUpProcedure? = null,
        globalBudget: Int? = null,
        maximumIterations: Int = iscDefaultMaxIterations,
        maxLocalPhaseReplications: Int = CompassSolver.defaultMaxReplications,
        maxCleanUpReplicationsPerSystem: Int = CleanUpProcedure.DEFAULT_MAX_REPLICATIONS_PER_SYSTEM,
        name: String? = null
    ) : this(
        problemDefinition, evaluator, streamNum, streamProvider,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation), deltaC, deltaL,
        skipGlobalPhase, globalPhase, localPhaseFactory, cleanUp, globalBudget, maximumIterations,
        maxLocalPhaseReplications, maxCleanUpReplicationsPerSystem, name
    )

    init {
        require(problemDefinition.isIntegerOrdered) {
            problemDefinition.integerOrderedRequirementMessage("ISC (its COMPASS local phase)")
        }
        require(deltaC >= 0.0) { "deltaC must be >= 0" }
        require(deltaL >= 0.0) { "deltaL must be >= 0" }
    }

    /** The clean-up indifference zone `δ_C`. */
    val deltaC: Double = deltaC

    /** The COMPASS local-optimality indifference zone `δ_L`. */
    val deltaL: Double = deltaL

    /** Whether the global (Niching-GA) phase is skipped. */
    val skipGlobalPhase: Boolean = skipGlobalPhase

    private val globalBudget: Int? = globalBudget

    private val providedGlobalPhase: NichingGeneticAlgorithmSolver? = globalPhase
    private val providedLocalPhaseFactory: ((InputMap) -> CompassSolver)? = localPhaseFactory

    /** The per-run replication cap applied to a default COMPASS local phase (not to a supplied factory). */
    private val maxLocalPhaseReplications: Int = maxLocalPhaseReplications

    /** The per-survivor Rinott second-stage cap applied to a default clean-up procedure. */
    private val maxCleanUpReplicationsPerSystem: Int = maxCleanUpReplicationsPerSystem

    /** The clean-up procedure used to screen, select, and report. */
    val cleanUp: CleanUpProcedure = cleanUp
        ?: CleanUpProcedure(problemDefinition, deltaC, maxReplicationsPerSystem = maxCleanUpReplicationsPerSystem)

    /** The phases of the ISC orchestration. */
    enum class Phase { GLOBAL, LOCAL, CLEANUP, DONE }

    /** The current orchestration phase. */
    var phase: Phase = Phase.GLOBAL
        private set

    private var activeGlobalPhase: NichingGeneticAlgorithmSolver? = null
    private val seedQueue: ArrayDeque<InputMap> = ArrayDeque()
    private val myLocalOptima: MutableList<Solution> = mutableListOf()

    /** The local optima collected from the COMPASS runs (one per niche seed). */
    @Suppress("unused")
    val localOptima: List<Solution>
        get() = myLocalOptima.toList()

    /** The confidence interval reported by the clean-up phase for the selected best. */
    var confidenceInterval: Interval = Interval()
        private set

    private fun buildDefaultGlobalPhase(): NichingGeneticAlgorithmSolver {
        val rules = buildList {
            add(SingleNicheRule())
            add(ImprovementRule())
            globalBudget?.let { add(BudgetRule(it)) }
        }
        return NichingGeneticAlgorithmSolver(
            problemDefinition = problemDefinition,
            evaluator = evaluator,
            streamNum = 0,
            streamProvider = streamProvider,
            transitionRules = rules,
            replicationsPerEvaluation = replicationsPerEvaluation
        )
    }

    private fun buildDefaultCompass(seed: InputMap): CompassSolver {
        val compass = CompassSolver(
            problemDefinition = problemDefinition,
            evaluator = evaluator,
            streamNum = 0,
            streamProvider = streamProvider,
            deltaL = deltaL,
            maxReplications = maxLocalPhaseReplications,
            replicationsPerEvaluation = replicationsPerEvaluation
        )
        compass.seed = seed
        return compass
    }

    private fun makeCompass(seed: InputMap): CompassSolver {
        val factory = providedLocalPhaseFactory
        return if (factory != null) factory(seed).also { it.seed = seed } else buildDefaultCompass(seed)
    }

    override fun initializeIterations() {
        // D1: begin each ISC run with a fresh evaluation/penalty clock so re-running the same
        // instance is reproducible (the base Solver contract, bypassed by this override).
        evaluator.resetEvaluationClock()
        phase = if (skipGlobalPhase) Phase.LOCAL else Phase.GLOBAL
        seedQueue.clear()
        myLocalOptima.clear()
        confidenceInterval = Interval()
        activeGlobalPhase = if (skipGlobalPhase) null else (providedGlobalPhase ?: buildDefaultGlobalPhase())

        // Establish an initial incumbent so the base class has a current solution.
        // Honor a user-supplied startingPoint (the inherited Solver contract) before
        // falling back to the generated/random startingPoint() function.
        val start = startingPoint ?: startingPoint()
        val startSolution = requestEvaluation(start)
        myInitialSolution = startSolution
        currentSolution = startSolution

        if (skipGlobalPhase) {
            seedQueue.add(start)
        }
        logger.info { "Solver: $name : ISC initialized (skipGlobalPhase=$skipGlobalPhase, deltaC=$deltaC, deltaL=$deltaL)" }
    }

    override fun mainIteration() {
        when (phase) {
            Phase.GLOBAL -> runGlobalPhase()
            Phase.LOCAL -> runOneLocalSearch()
            Phase.CLEANUP -> runCleanUp()
            Phase.DONE -> { /* nothing to do */ }
        }
    }

    private fun runGlobalPhase() {
        val nga = activeGlobalPhase
        if (nga == null) {
            phase = Phase.LOCAL
            return
        }
        nga.runAllIterations()
        accumulateCounts(nga.numOracleCalls, nga.numReplicationsRequested)
        currentSolution = nga.bestSolution
        val seeds = nga.niches.map { it.center.inputMap }
        if (seeds.isEmpty()) {
            seedQueue.add(startingPoint ?: startingPoint())
        } else {
            seedQueue.addAll(seeds)
        }
        phase = Phase.LOCAL
    }

    private fun runOneLocalSearch() {
        if (seedQueue.isEmpty()) {
            phase = Phase.CLEANUP
            return
        }
        val seed = seedQueue.removeFirst()
        val compass = makeCompass(seed)
        compass.runAllIterations()
        accumulateCounts(compass.numOracleCalls, compass.numReplicationsRequested)
        val localOptimum = compass.bestSolution
        myLocalOptima.add(localOptimum)
        currentSolution = localOptimum
        if (seedQueue.isEmpty()) {
            phase = Phase.CLEANUP
        }
    }

    private fun runCleanUp() {
        if (myLocalOptima.isNotEmpty()) {
            // Hard-filter to the response-feasible local optima, run ranking & selection on that
            // subset, and fall back to the least-infeasible solution if none are feasible (B1).
            val result = cleanUp.cleanUp(myLocalOptima) { input, n -> requestEvaluation(input, n) }
            confidenceInterval = result.confidenceInterval
            currentSolution = result.best
        }
        phase = Phase.DONE
    }

    private fun accumulateCounts(oracleCalls: Int, replications: Int) {
        numOracleCalls += oracleCalls
        numReplicationsRequested += replications
    }

    override fun isStoppingCriteriaSatisfied(): Boolean = phase == Phase.DONE

    override fun mainIterationsEnded() {
        // Finalize with clean-up if the iteration cap interrupted the orchestration mid-stream.
        if (phase != Phase.DONE && myLocalOptima.isNotEmpty()) {
            runCleanUp()
        }
        // D3: guarantee a finite confidence interval even when no clean-up ran (e.g. maxIterations
        // too small to reach the local phase). The default Interval() is (-inf, +inf), which would
        // otherwise leak into extractSolverSpecificState; report a point interval at the incumbent's
        // objective instead.
        if (!confidenceInterval.lowerLimit.isFinite() || !confidenceInterval.upperLimit.isFinite()) {
            val obj = currentSolution.estimatedObjFncValue
            confidenceInterval = if (obj.isFinite()) Interval(obj, obj) else Interval(0.0, 0.0)
        }
    }

    override fun extractSolverSpecificState(): Map<String, Double> = linkedMapOf(
        "phase" to phase.ordinal.toDouble(),
        "nicheCount" to (activeGlobalPhase?.currentNiches?.count?.toDouble() ?: 0.0),
        "localOptimaCount" to myLocalOptima.size.toDouble(),
        "pendingSeeds" to seedQueue.size.toDouble(),
        "ciLower" to confidenceInterval.lowerLimit,
        "ciUpper" to confidenceInterval.upperLimit
    )

    override fun toString(): String {
        return """
        ISCSolver(
            deltaC = $deltaC,
            deltaL = $deltaL,
            skipGlobalPhase = $skipGlobalPhase,
            phase = $phase,
            localOptima = ${myLocalOptima.size},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "deltaC" to deltaC.toString(),
            "deltaL" to deltaL.toString(),
            "skipGlobalPhase" to skipGlobalPhase.toString(),
            "globalBudget" to (globalBudget?.toString() ?: "None"),
            "maxLocalPhaseReplications" to maxLocalPhaseReplications.toString(),
            "maxCleanUpReplicationsPerSystem" to maxCleanUpReplicationsPerSystem.toString()
        )

    companion object {
        /** The default maximum number of orchestration macro-steps. */
        @JvmStatic
        var iscDefaultMaxIterations: Int = 1000
            set(value) {
                require(value >= 1) { "The default maximum number of iterations must be >= 1" }
                field = value
            }
    }
}
