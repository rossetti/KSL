package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.EstimatedResponse
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.VonNeumannNeighborhoodFinder
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  Merges two solutions that share the same input point into one solution whose objective and
 *  response estimates pool the replications of both (via [EstimatedResponse.merge]). Used by COMPASS
 *  to accumulate simulation effort on a visited point across iterations.
 */
fun mergeSolutions(a: Solution, b: Solution): Solution {
    require(a.inputMap == b.inputMap) { "only solutions for the same input point can be merged" }
    val mergedObj = a.estimatedObjFnc.merge(b.estimatedObjFnc)
    val bByName = b.responseEstimates.associateBy { it.name }
    val mergedResponses = a.responseEstimates.map { ra ->
        val rb = bByName[ra.name]
        if (rb != null) ra.merge(rb) else ra
    }
    return Solution(a.inputMap, mergedObj, mergedResponses, maxOf(a.evaluationNumber, b.evaluationNumber))
}

/**
 *  The COMPASS local phase of Industrial Strength COMPASS (ISC): a noise-tolerant local search over
 *  integer-ordered variables that converges to a locally optimal solution. Each iteration:
 *
 *  1. forms the *most-promising area* (MPA) around the current sample-best `x*` from the points
 *     visited so far (see [MostPromisingArea]);
 *  2. draws [sampleSize] candidate points from the MPA with the [RmdSampler] and adds the feasible
 *     von Neumann neighbors of `x*`;
 *  3. allocates simulation replications to the candidates and to the previously visited points via
 *     the [SimulationAllocationRuleIfc] (an increasing schedule that drives estimation noise down);
 *  4. updates `x*` to the best of all visited solutions.
 *
 *  **Stopping (and the indifference-zone `δ_L`).** The search stops when the MPA collapses to a
 *  *singleton* — no feasible neighbor of `x*` lies inside the MPA. If [deltaL] is positive, that
 *  singleton condition triggers Kim's (2005) fully-sequential local-optimality test
 *  ([ComparisonWithStandardProcedure]) comparing `x*` against its feasible neighbors; the search
 *  terminates only when `x*` is confirmed locally optimal, otherwise it moves to the better neighbor
 *  and continues. If `deltaL == 0` (the **degraded** mode documented for ISC), the local-optimality
 *  guarantee is intentionally dropped: the search stops on the MPA-singleton condition alone, bounded
 *  by [maximumIterations] and the allocation schedule. A positive `δ_L` is required to obtain the
 *  local-optimality guarantee.
 *
 *  All randomness is drawn through the solver's single random number stream ([rnStream]), so a run is
 *  reproducible for a fixed stream number. The best solution found is tracked automatically by the
 *  base class through the [currentSolution] setter.
 *
 *  **Requires an integer-ordered problem definition.** COMPASS searches an integer lattice — its von
 *  Neumann neighborhood moves in unit steps — so every input must have granularity 1.0. The
 *  constructor throws an IllegalArgumentException when the problem is not integer-ordered.
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the evaluator responsible for assessing the quality of solutions
 *  @param streamNum the random number stream number; 0 (the default) means the next available stream
 *  @param streamProvider the provider of random number streams; defaults to a fresh RNStreamProvider
 *  @param sampleSize the number of MPA candidate points drawn each iteration (the paper's `m_L`)
 *  @param sar the simulation-allocation rule; defaults to [FixedScheduleSAR]
 *  @param redundancyChecker the constraint-pruning strategy; defaults to [BruteForceRedundancyChecker]
 *  @param pruneEvery prune the MPA's halfway hyperplanes every this many iterations (the paper's `c_p`)
 *  @param deltaL the local-optimality indifference zone `δ_L`; `0.0` (default) selects degraded mode
 *  @param localOptimalityTest the Kim (2005) test used when [deltaL] > 0; built from [deltaL] if null
 *  @param maximumIterations the maximum number of COMPASS iterations
 *  @param maxReplications the cap on the total replications a single COMPASS run may request; defaults
 *  to [defaultMaxReplications]. A best-effort safety valve bounding a run on a noisy, flat landscape.
 *  @param replicationsPerEvaluation strategy for the number of replications per (new-point) evaluation
 *  @param name an optional name for the solver
 */
class CompassSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    sampleSize: Int = defaultSampleSize,
    sar: SimulationAllocationRuleIfc = FixedScheduleSAR(),
    redundancyChecker: RedundantConstraintChecker = BruteForceRedundancyChecker(),
    pruneEvery: Int = defaultPruneEvery,
    deltaL: Double = 0.0,
    localOptimalityTest: ComparisonWithStandardProcedure? = null,
    maximumIterations: Int = compassDefaultMaxIterations,
    maxReplications: Int = defaultMaxReplications,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, maximumIterations,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    /**
     *  Constructs a COMPASS solver using a fixed number of replications per new-point evaluation.
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = RNStreamProvider(),
        sampleSize: Int = defaultSampleSize,
        sar: SimulationAllocationRuleIfc = FixedScheduleSAR(),
        redundancyChecker: RedundantConstraintChecker = BruteForceRedundancyChecker(),
        pruneEvery: Int = defaultPruneEvery,
        deltaL: Double = 0.0,
        localOptimalityTest: ComparisonWithStandardProcedure? = null,
        maxIterations: Int = compassDefaultMaxIterations,
        maxReplications: Int = defaultMaxReplications,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        name: String? = null
    ) : this(
        problemDefinition, evaluator, streamNum, streamProvider, sampleSize, sar,
        redundancyChecker, pruneEvery, deltaL, localOptimalityTest, maxIterations,
        maxReplications, FixedReplicationsPerEvaluation(replicationsPerEvaluation), name
    )

    /** The number of MPA candidate points drawn each iteration. Must be >= 1. */
    var sampleSize: Int = sampleSize
        set(value) {
            require(value >= 1) { "The MPA sample size must be >= 1" }
            require(!iterativeProcess.isRunning) { "The sample size cannot be changed while the solver is running." }
            field = value
        }

    /** The simulation-allocation rule. Cannot be changed while the solver is running. */
    var sar: SimulationAllocationRuleIfc = sar
        set(value) {
            require(!iterativeProcess.isRunning) { "The allocation rule cannot be changed while the solver is running." }
            field = value
        }

    /** The constraint-pruning strategy. Cannot be changed while the solver is running. */
    var redundancyChecker: RedundantConstraintChecker = redundancyChecker
        set(value) {
            require(!iterativeProcess.isRunning) { "The redundancy checker cannot be changed while the solver is running." }
            field = value
        }

    /** Prune the MPA's halfway hyperplanes every this many iterations. Must be >= 1. */
    var pruneEvery: Int = pruneEvery
        set(value) {
            require(value >= 1) { "pruneEvery must be >= 1" }
            field = value
        }

    /** The local-optimality indifference zone `δ_L`. Must be >= 0. */
    var deltaL: Double = deltaL
        set(value) {
            require(value >= 0.0) { "deltaL must be >= 0" }
            field = value
        }

    /**
     *  The cap on the total number of replications a single COMPASS run may request. When the
     *  accumulated request count reaches this cap the search stops (best-effort), bounding the run even
     *  when a noisy, flat landscape would otherwise keep the search moving. Must be >= 1. Complements
     *  the per-iteration [maxIterations] ceiling and the local-optimality test's own per-system cap.
     */
    var maxReplications: Int = maxReplications
        set(value) {
            require(value >= 1) { "maxReplications must be >= 1" }
            require(!iterativeProcess.isRunning) { "maxReplications cannot be changed while the solver is running." }
            field = value
        }

    /**
     *  An explicit starting point (e.g., a niche seed from the ISC global phase). When set, it takes
     *  precedence over the inherited `startingPoint`. When `seed` is null, the inherited `startingPoint`
     *  is used if it was supplied; otherwise the `startingPoint()` function provides a random feasible
     *  point or the configured generator's point.
     */
    var seed: InputMap? = null

    private var test: ComparisonWithStandardProcedure? = localOptimalityTest

    /** The von Neumann unit neighborhood used for the local-optimality test and MPA coverage. */
    private val neighborhood = VonNeumannNeighborhoodFinder(problemDefinition, 1)

    /** The RMD polytope sampler driven by the solver's stream. */
    private val rmdSampler = RmdSampler(problemDefinition, rnStream)

    /** All points visited, each with its accumulated solution. */
    private val visited = LinkedHashMap<InputMap, Solution>()

    /** The current sample-best `x*`. */
    private lateinit var sampleBest: Solution

    private var compassIteration: Int = 0

    /** A read-only snapshot of the visited points and their accumulated solutions. */
    @Suppress("unused")
    val visitedSolutions: Map<InputMap, Solution>
        get() = visited.toMap()

    /** The current locally-best solution discovered by COMPASS. */
    @Suppress("unused")
    val localOptimum: Solution
        get() = sampleBest

    init {
        require(problemDefinition.isIntegerOrdered) {
            "COMPASS requires that the problem definition be integer ordered!"
        }
        require(sampleSize >= 1) { "The MPA sample size must be >= 1" }
        require(pruneEvery >= 1) { "pruneEvery must be >= 1" }
        require(deltaL >= 0.0) { "deltaL must be >= 0" }
        require(maxReplications >= 1) { "maxReplications must be >= 1" }
    }

    private fun localOptimalityTest(): ComparisonWithStandardProcedure {
        val existing = test
        if (existing != null) return existing
        val created = ComparisonWithStandardProcedure(alpha = defaultAlphaL, delta = deltaL)
        test = created
        return created
    }

    private fun recordEvaluation(solution: Solution) {
        val prior = visited[solution.inputMap]
        visited[solution.inputMap] = if (prior == null) solution else mergeSolutions(prior, solution)
    }

    private fun feasibleNeighbors(center: InputMap): List<InputMap> =
        neighborhood.neighborhood(center, this).filter { it != center && it.isInputFeasible() }

    override fun initializeIterations() {
        visited.clear()
        compassIteration = 0
        // Precedence: an explicit seed (ISC niche seed), then a user-supplied startingPoint
        // (the inherited Solver contract), then the generated/random startingPoint() function.
        val start = seed ?: startingPoint ?: startingPoint()
        val startSolution = requestEvaluation(start)
        recordEvaluation(startSolution)
        sampleBest = visited.getValue(startSolution.inputMap)
        myInitialSolution = sampleBest
        currentSolution = sampleBest
        logger.info { "Solver: $name : COMPASS initialized at $start" }
    }

    override fun mainIteration() {
        compassIteration++
        val centerValues = sampleBest.inputMap.inputValues
        val otherVisited = visited.keys.filter { it != sampleBest.inputMap }.map { it.inputValues }
        val mpa = MostPromisingArea(problemDefinition, centerValues, otherVisited)
        val halfSpaces = if (compassIteration % pruneEvery == 0) {
            mpa.originalHalfSpaces + mpa.activeHalfwayHalfSpaces(redundancyChecker)
        } else {
            mpa.allHalfSpaces
        }

        // 1. Draw MPA candidates and add the feasible neighbors of x*.
        val candidates = LinkedHashSet<InputMap>()
        repeat(sampleSize) {
            val point = rmdSampler.sample(mpa, centerValues, halfSpaces)
            val inputMap = problemDefinition.toInputMap(point)
            if (inputMap.isInputFeasible()) candidates.add(inputMap)
        }
        candidates.addAll(feasibleNeighbors(sampleBest.inputMap))

        // 2. Evaluate brand-new candidate points in a single batch.
        val newPoints = candidates.filter { it !in visited }.toSet()
        if (newPoints.isNotEmpty()) {
            val evaluations = requestEvaluations(newPoints)
            for (solution in evaluations.values) recordEvaluation(solution)
        }

        // 3. Top up replications on the active set (candidates + center) per the allocation rule.
        val allocationTargets = LinkedHashSet(candidates).apply { add(sampleBest.inputMap) }
        val activeSolutions = allocationTargets.mapNotNull { visited[it] }
        val rule = sar
        if (rule is BatchAllocationRuleIfc) {
            // A batch rule (e.g., OCBA) redistributes a budget across the whole active set. Size the
            // budget from the rule's per-solution schedule, then let the rule reallocate it.
            val budget = activeSolutions.sumOf { rule.additionalReplications(it, compassIteration) }
            if (budget > 0) {
                for ((point, additional) in rule.allocate(activeSolutions, budget)) {
                    if (additional > 0) recordEvaluation(requestEvaluation(point, additional))
                }
            }
        } else {
            for (current in activeSolutions) {
                val additional = rule.additionalReplications(current, compassIteration)
                if (additional > 0) {
                    recordEvaluation(requestEvaluation(current.inputMap, additional))
                }
            }
        }

        // 4. Update the sample best.
        sampleBest = visited.values.minWithOrNull { a, b -> compare(a, b) } ?: sampleBest
        currentSolution = sampleBest
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        if (numReplicationsRequested >= maxReplications) {
            logger.warn {
                "Solver: $name : COMPASS replication cap (maxReplications=$maxReplications) reached at " +
                        "$numReplicationsRequested replications; stopping the local search (best-effort)."
            }
            return true
        }
        val centerValues = sampleBest.inputMap.inputValues
        val otherVisited = visited.keys.filter { it != sampleBest.inputMap }.map { it.inputValues }
        val mpa = MostPromisingArea(problemDefinition, centerValues, otherVisited)
        val neighbors = feasibleNeighbors(sampleBest.inputMap)
        val mpaIsSingleton = neighbors.none { mpa.contains(it.inputValues) }
        if (!mpaIsSingleton) return false
        if (deltaL <= 0.0) return true // degraded mode: stop on the MPA-singleton condition

        // δ_L > 0: confirm local optimality with Kim's comparison-with-a-standard test.
        val test = localOptimalityTest()
        ensureMinimumReplications(sampleBest.inputMap, test.n0)
        for (n in neighbors) ensureMinimumReplications(n, test.n0)
        val alternatives = neighbors.mapNotNull { visited[it] }
        val result = test.run(
            standard = visited.getValue(sampleBest.inputMap),
            alternatives = alternatives,
            sampleOneMore = { input -> requestEvaluation(input, 1) },
            merge = ::mergeSolutions
        )
        // Reflect any additional sampling done by the test back into the visited set.
        recordEvaluation(result.winner)
        return if (result.standardIsBest) {
            true
        } else {
            sampleBest = visited.getValue(result.winner.inputMap)
            currentSolution = sampleBest
            false
        }
    }

    private fun ensureMinimumReplications(point: InputMap, minCount: Int) {
        val current = visited[point] ?: run {
            recordEvaluation(requestEvaluation(point, minCount))
            return
        }
        val have = current.count.toInt()
        if (have < minCount) {
            recordEvaluation(requestEvaluation(point, minCount - have))
        }
    }

    override fun extractSolverSpecificState(): Map<String, Double> = linkedMapOf(
        "compassIteration" to compassIteration.toDouble(),
        "visitedCount" to visited.size.toDouble(),
        "sampleBestObjFnc" to (if (::sampleBest.isInitialized) sampleBest.penalizedObjFncValue else Double.NaN),
        "sampleBestReplications" to (if (::sampleBest.isInitialized) sampleBest.count else Double.NaN)
    )

    override fun toString(): String {
        return """
        CompassSolver(
            sampleSize = $sampleSize,
            deltaL = $deltaL,
            pruneEvery = $pruneEvery,
            maxReplications = $maxReplications,
            sar = ${sar::class.simpleName},
            redundancyChecker = ${redundancyChecker::class.simpleName},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "sampleSize" to sampleSize.toString(),
            "deltaL" to deltaL.toString(),
            "pruneEvery" to pruneEvery.toString(),
            "maxReplications" to maxReplications.toString(),
            "sar" to (sar::class.simpleName ?: ""),
            "redundancyChecker" to (redundancyChecker::class.simpleName ?: "")
        )

    companion object {
        /** The default number of MPA candidate points drawn each iteration (`m_L`). */
        @JvmStatic
        var defaultSampleSize: Int = 5
            set(value) {
                require(value >= 1) { "The default MPA sample size must be >= 1" }
                field = value
            }

        /** The default MPA pruning cadence (`c_p`). */
        @JvmStatic
        var defaultPruneEvery: Int = 5
            set(value) {
                require(value >= 1) { "The default pruneEvery must be >= 1" }
                field = value
            }

        /** The default error probability for the local-optimality test (`α_L`). */
        @JvmStatic
        var defaultAlphaL: Double = 0.05
            set(value) {
                require(value > 0.0 && value < 1.0) { "The default alphaL must be in (0,1)" }
                field = value
            }

        /** The default maximum number of COMPASS iterations. */
        @JvmStatic
        var compassDefaultMaxIterations: Int = 100
            set(value) {
                require(value >= 1) { "The default maximum number of iterations must be >= 1" }
                field = value
            }

        /**
         *  The default cap on the total replications a single COMPASS run may request. A best-effort
         *  safety valve — with the default iteration ceiling and allocation schedule a normal local
         *  search stays well under it; it bounds only the pathological case of a noisy, flat landscape.
         */
        @JvmStatic
        var defaultMaxReplications: Int = 50_000
            set(value) {
                require(value >= 1) { "The default maximum number of replications must be >= 1" }
                field = value
            }
    }
}
