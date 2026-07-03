package ksl.simopt.solvers.algorithms.isc

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver.Companion.defaultReplicationsPerEvaluation
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  The Niching Genetic Algorithm (NGA), the global exploration phase of Industrial Strength COMPASS
 *  (ISC Algorithm 1). It maintains a population of integer-feasible solutions and, each generation:
 *
 *  1. identifies *niches* in the population ([NicheIdentifier], Algorithm 2);
 *  2. computes niche-shared fitness ([FitnessSharing], §A.4);
 *  3. groups statistically-indistinguishable members ([NoiseGroupingProcedure], Algorithm 3) and
 *     assigns group-averaged linear-rank selection probabilities ([LinearRankingSelection], §A.5);
 *  4. draws a parent pool by Stochastic Universal Sampling ([StochasticUniversalSampling]);
 *  5. mates each parent under a mating restriction ([MatingRestrictionIfc], Algorithm 4), recombines
 *     ([NgaCrossoverIfc], §A.8), and mutates ([NgaMutationIfc], §A.9) to form offspring;
 *  6. evaluates the offspring and forms the next generation (optionally conserving niche centers,
 *     Algorithm 5).
 *
 *  The phase ends when any configured transition rule fires ([NgaTransitionRuleIfc], §A.12). The
 *  discovered [niches] — each a center "seed" plus its surrounding members — are the starting points
 *  for the COMPASS local phase. With uniform mutation enabled (the default), every feasible point
 *  remains reachable with positive probability, preserving the NGA's global-convergence guarantee.
 *
 *  All randomness flows through the solver's single random number stream ([rnStream]), so a run is
 *  reproducible for a fixed stream number.
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the evaluator responsible for assessing the quality of solutions
 *  @param streamNum the random number stream number; 0 (the default) means the next available stream
 *  @param streamProvider the provider of random number streams; defaults to a fresh RNStreamProvider
 *  @param populationSize the population size `m_G`. It must not exceed the number of distinct
 *  input-feasible points of the problem: the initial population is filled with *unique* feasible
 *  points, so requesting more than exist cannot terminate.
 *  @param nicheIdentifier the niche-identification strategy
 *  @param fitnessSharing the fitness-sharing strategy
 *  @param grouping the noise-aware grouping strategy
 *  @param ranking the linear-ranking selection-probability strategy
 *  @param sampling the stochastic-universal-sampling strategy
 *  @param mating the mating-restriction strategy
 *  @param crossover the recombination strategy
 *  @param mutation the mutation strategy (default [UniformMutation], which preserves convergence)
 *  @param conserveNicheCenters whether to carry niche centers forward unchanged (elitism, Algorithm 5)
 *  @param transitionRules the global→local transition rules; the phase ends when any fires
 *  @param maxIterations the maximum number of generations
 *  @param replicationsPerEvaluation strategy for the number of replications per evaluation
 *  @param name an optional name for the solver
 */
class NichingGeneticAlgorithmSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    populationSize: Int = defaultPopulationSize,
    val nicheIdentifier: NicheIdentifier = NicheIdentifier(),
    val fitnessSharing: FitnessSharing = FitnessSharing(),
    val grouping: NoiseGroupingProcedure = NoiseGroupingProcedure(),
    val ranking: LinearRankingSelection = LinearRankingSelection(),
    val sampling: StochasticUniversalSampling = StochasticUniversalSampling(),
    val mating: MatingRestrictionIfc = DynamicInbreeding(),
    val crossover: NgaCrossoverIfc = ArithmeticalCrossover(),
    val mutation: NgaMutationIfc = UniformMutation(),
    var conserveNicheCenters: Boolean = false,
    transitionRules: List<NgaTransitionRuleIfc> = listOf(SingleNicheRule(), ImprovementRule()),
    maxIterations: Int = ngaDefaultMaxIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, maxIterations,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    /**
     *  Constructs a Niching GA solver using a fixed number of replications per evaluation.
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = RNStreamProvider(),
        populationSize: Int = defaultPopulationSize,
        nicheIdentifier: NicheIdentifier = NicheIdentifier(),
        fitnessSharing: FitnessSharing = FitnessSharing(),
        grouping: NoiseGroupingProcedure = NoiseGroupingProcedure(),
        ranking: LinearRankingSelection = LinearRankingSelection(),
        sampling: StochasticUniversalSampling = StochasticUniversalSampling(),
        mating: MatingRestrictionIfc = DynamicInbreeding(),
        crossover: NgaCrossoverIfc = ArithmeticalCrossover(),
        mutation: NgaMutationIfc = UniformMutation(),
        conserveNicheCenters: Boolean = false,
        transitionRules: List<NgaTransitionRuleIfc> = listOf(SingleNicheRule(), ImprovementRule()),
        maxIterations: Int = ngaDefaultMaxIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        name: String? = null
    ) : this(
        problemDefinition, evaluator, streamNum, streamProvider, populationSize, nicheIdentifier,
        fitnessSharing, grouping, ranking, sampling, mating, crossover, mutation,
        conserveNicheCenters, transitionRules, maxIterations,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation), name
    )

    /** The population size `m_G`. Must be >= [defaultMinPopulationSize]. */
    var populationSize: Int = populationSize
        set(value) {
            require(value >= defaultMinPopulationSize) { "The population size must be >= $defaultMinPopulationSize" }
            require(!iterativeProcess.isRunning) { "The population size cannot be changed while the solver is running." }
            field = value
        }

    /** The global→local transition rules. Cannot be changed while the solver is running. */
    var transitionRules: List<NgaTransitionRuleIfc> = transitionRules
        set(value) {
            require(value.isNotEmpty()) { "There must be at least one transition rule." }
            require(!iterativeProcess.isRunning) { "The transition rules cannot be changed while the solver is running." }
            field = value
        }

    init {
        require(populationSize >= defaultMinPopulationSize) { "The population size must be >= $defaultMinPopulationSize" }
        require(transitionRules.isNotEmpty()) { "There must be at least one transition rule." }
    }

    private var myPopulation: MutableList<Solution> = mutableListOf()
    private var myNiches: NicheResult = NicheResult(emptyList(), 0.0, 0)
    private var lastBestValue: Double = Double.POSITIVE_INFINITY

    /** The current generation index (0 after initialization). */
    var currentGeneration: Int = 0
        private set

    /** The number of consecutive generations with no improvement of the incumbent. */
    var generationsSinceImprovement: Int = 0
        private set

    /** The niche structure identified for the current population. */
    val currentNiches: NicheResult
        get() = myNiches

    /** The niches discovered by the global phase (COMPASS seeds). */
    @Suppress("unused")
    val niches: List<Niche>
        get() = myNiches.niches

    /** A read-only, best-first view of the current population. */
    @Suppress("unused")
    val population: List<Solution>
        get() = myPopulation.sortedWith { a, b -> compare(a, b) }

    private fun bestFirst(solutions: List<Solution>): List<Solution> =
        solutions.sortedWith { a, b -> compare(a, b) }

    private fun identifyNiches(pop: List<Solution>): NicheResult =
        nicheIdentifier.identify(pop, problemDefinition) { a, b -> compare(a, b) }

    override fun initializeIterations() {
        currentGeneration = 0
        generationsSinceImprovement = 0
        val inputs = LinkedHashSet<InputMap>()
        startingPoint?.let { inputs.add(it) }
        if (inputs.size < populationSize) {
            inputs.addAll(sampleInputFeasiblePoints(populationSize - inputs.size))
        }
        val evaluations = requestEvaluations(inputs)
        check(evaluations.isNotEmpty()) { "The initial population evaluation returned no solutions." }
        myPopulation = bestFirst(evaluations.values.toList()).take(populationSize).toMutableList()
        myNiches = identifyNiches(myPopulation)
        val best = myPopulation.first()
        myInitialSolution = best
        currentSolution = best
        lastBestValue = best.penalizedObjFncValue
        logger.info { "Solver: $name : NGA initialized with population ${myPopulation.size}, niches ${myNiches.count}" }
    }

    override fun mainIteration() {
        if (myPopulation.isEmpty()) return
        currentGeneration++
        // Niches for this generation (selection + mating restriction).
        myNiches = identifyNiches(myPopulation)
        val shared = fitnessSharing.share(myPopulation, myNiches)
        val groups = grouping.group(shared)
        val weighted = ranking.selectionProbabilities(groups)
        val parents = sampling.sample(weighted, populationSize, rnStream)
        if (parents.isEmpty()) return

        // Recombine and mutate to build offspring coordinate vectors.
        val offspringPoints = ArrayList<DoubleArray>(populationSize)
        var i = 0
        while (offspringPoints.size < populationSize) {
            val p1 = parents[i % parents.size]
            val p2 = mating.selectMate(p1, parents, myNiches, { a, b -> compare(a, b) }, rnStream)
            i++
            val children = crossover.crossover(p1.inputMap.inputValues, p2.inputMap.inputValues, this)
            for (child in children) {
                if (offspringPoints.size >= populationSize) break
                offspringPoints.add(mutation.mutate(child, this))
            }
        }

        // Round to the integer grid; keep only feasible offspring.
        val offspringInputs = offspringPoints
            .map { problemDefinition.toInputMap(it) }
            .filter { it.isInputFeasible() }
            .toCollection(LinkedHashSet())
        if (offspringInputs.isEmpty()) {
            myNiches = identifyNiches(myPopulation)
            return
        }
        val evaluations = requestEvaluations(offspringInputs)
        val offspring = evaluations.values.toList()

        val candidates = if (conserveNicheCenters) offspring + myNiches.niches.map { it.center } else offspring
        myPopulation = bestFirst(candidates).take(populationSize).toMutableList()
        myNiches = identifyNiches(myPopulation)

        val best = myPopulation.first()
        currentSolution = best
        if (best.penalizedObjFncValue < lastBestValue - solutionPrecision) {
            lastBestValue = best.penalizedObjFncValue
            generationsSinceImprovement = 0
        } else {
            generationsSinceImprovement++
        }
    }

    override fun isStoppingCriteriaSatisfied(): Boolean =
        transitionRules.any { it.shouldTransition(this) }

    override fun extractSolverSpecificState(): Map<String, Double> = linkedMapOf(
        "generation" to currentGeneration.toDouble(),
        "populationSize" to myPopulation.size.toDouble(),
        "nicheCount" to myNiches.count.toDouble(),
        "nicheRadius" to myNiches.radius,
        "generationsSinceImprovement" to generationsSinceImprovement.toDouble(),
        "bestObjFnc" to (myPopulation.firstOrNull()?.penalizedObjFncValue ?: Double.NaN)
    )

    override fun toString(): String {
        return """
        NichingGeneticAlgorithmSolver(
            populationSize = $populationSize,
            conserveNicheCenters = $conserveNicheCenters,
            mating = ${mating::class.simpleName},
            crossover = ${crossover::class.simpleName},
            mutation = ${mutation::class.simpleName},
            transitionRules = ${transitionRules.map { it::class.simpleName }},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "populationSize" to populationSize.toString(),
            "conserveNicheCenters" to conserveNicheCenters.toString(),
            "mating" to (mating::class.simpleName ?: ""),
            "crossover" to (crossover::class.simpleName ?: ""),
            "mutation" to (mutation::class.simpleName ?: ""),
            "transitionRules" to transitionRules.joinToString { it::class.simpleName ?: "" }
        )

    companion object {
        /** The default population size `m_G`. */
        @JvmStatic
        var defaultPopulationSize: Int = 50
            set(value) {
                require(value >= defaultMinPopulationSize) { "The default population size must be >= $defaultMinPopulationSize" }
                field = value
            }

        /** The minimum permissible population size. */
        @JvmStatic
        var defaultMinPopulationSize: Int = 4
            set(value) {
                require(value >= 2) { "The default minimum population size must be >= 2" }
                field = value
            }

        /** The default maximum number of generations. */
        @JvmStatic
        var ngaDefaultMaxIterations: Int = 100
            set(value) {
                require(value >= 1) { "The default maximum number of iterations must be >= 1" }
                field = value
            }
    }
}
