package ksl.simopt.solvers.algorithms.genetic

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.InputsAndConfidenceIntervalEquality
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.SolutionChecker
import ksl.simopt.evaluator.SolutionEqualityIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import kotlin.math.sqrt

/**
 *  If supplied, this function determines the size of the population (per generation) during
 *  the genetic algorithm. Supplying a function permits dynamic changes to the population size
 *  as the search progresses.
 */
fun interface PopulationSizeFnIfc {
    fun populationSize(ga: GeneticAlgorithmSolver): Int
}

/**
 *  If supplied, this function determines the per-individual mutation rate during the genetic
 *  algorithm. Supplying a function permits dynamic changes (e.g., annealing the mutation rate)
 *  as the search progresses.
 */
fun interface MutationRateFnIfc {
    fun mutationRate(ga: GeneticAlgorithmSolver): Double
}

/**
 *  A generational genetic algorithm (GA) solver with elitism for simulation optimization.
 *
 *  Each call to [mainIteration] produces one generation: the best [eliteCount] solutions are
 *  carried forward unchanged; parents are chosen by the [selectionOperator]; offspring are
 *  produced by the [crossoverOperator] (gated by [crossoverRate]) and the [mutationOperator]
 *  (gated by the per-individual [mutationRate]); the whole offspring set is evaluated in a
 *  single batch oracle call (via the inherited [requestEvaluations]); and the next generation
 *  is the best of the elites plus the offspring. The best solution found is tracked
 *  automatically by the base class through the [currentSolution] setter.
 *
 *  All randomness is drawn through the solver's single random number stream
 *  ([rnStream]); the genetic operators draw from it as well, so a run is reproducible for a
 *  fixed stream number.
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the evaluator responsible for assessing the quality of solutions
 *  @param streamNum the random number stream number; 0 (the default) means the next available stream
 *  @param streamProvider the provider of random number streams; defaults to a fresh RNStreamProvider
 *  @param populationSize the number of individuals per generation
 *  @param selectionOperator the parent-selection strategy; defaults to [TournamentSelection]
 *  @param crossoverOperator the recombination strategy; defaults to [BlendCrossover]
 *  @param mutationOperator the mutation strategy; defaults to [GaussianMutation]
 *  @param maximumIterations the maximum number of generations
 *  @param replicationsPerEvaluation strategy to determine the number of replications per evaluation
 *  @param solutionEqualityChecker used to detect convergence (no-improvement). The default is
 *  [InputsAndConfidenceIntervalEquality].
 *  @param name an optional name for the solver
 */
class GeneticAlgorithmSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    populationSize: Int = defaultPopulationSize,
    selectionOperator: SelectionOperatorIfc = TournamentSelection(),
    crossoverOperator: CrossoverOperatorIfc = BlendCrossover(),
    mutationOperator: MutationOperatorIfc = GaussianMutation(problemDefinition),
    maximumIterations: Int = gaDefaultMaxIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality(),
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, maximumIterations,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    /**
     *  Constructs a genetic algorithm solver using a fixed number of replications per evaluation.
     *
     *  @param replicationsPerEvaluation the fixed number of replications per evaluation
     */
    @JvmOverloads
    @Suppress("unused")
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = RNStreamProvider(),
        populationSize: Int = defaultPopulationSize,
        selectionOperator: SelectionOperatorIfc = TournamentSelection(),
        crossoverOperator: CrossoverOperatorIfc = BlendCrossover(),
        mutationOperator: MutationOperatorIfc = GaussianMutation(problemDefinition),
        maximumIterations: Int = gaDefaultMaxIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality(),
        name: String? = null
    ) : this(
        problemDefinition, evaluator, streamNum, streamProvider, populationSize,
        selectionOperator, crossoverOperator, mutationOperator, maximumIterations,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation), solutionEqualityChecker, name
    )

    /**
     *  The parent-selection strategy. Cannot be changed while the solver is running.
     */
    var selectionOperator: SelectionOperatorIfc = selectionOperator
        set(value) {
            require(!iterativeProcess.isRunning) { "The selection operator cannot be changed while the solver is running." }
            field = value
        }

    /**
     *  The recombination (crossover) strategy. Cannot be changed while the solver is running.
     */
    var crossoverOperator: CrossoverOperatorIfc = crossoverOperator
        set(value) {
            require(!iterativeProcess.isRunning) { "The crossover operator cannot be changed while the solver is running." }
            field = value
        }

    /**
     *  The mutation strategy. Cannot be changed while the solver is running.
     */
    var mutationOperator: MutationOperatorIfc = mutationOperator
        set(value) {
            require(!iterativeProcess.isRunning) { "The mutation operator cannot be changed while the solver is running." }
            field = value
        }

    /**
     *  If supplied, this function determines the population size, overriding [populationSize].
     */
    var populationSizeFn: PopulationSizeFnIfc? = null

    /**
     *  If supplied, this function determines the per-individual mutation rate, overriding [mutationRate].
     */
    var mutationRateFn: MutationRateFnIfc? = null

    /**
     *  The number of individuals per generation. Must be at least [defaultMinPopulationSize].
     */
    var populationSize: Int = populationSize
        set(value) {
            require(value >= defaultMinPopulationSize) { "The population size must be >= $defaultMinPopulationSize" }
            field = value
        }

    /**
     *  The probability that a selected pair of parents is recombined via crossover (otherwise the
     *  parents are copied). Must be in [0,1].
     */
    var crossoverRate: Double = defaultCrossoverRate
        set(value) {
            require((value >= 0.0) && (value <= 1.0)) { "The crossover rate must be in [0,1]" }
            field = value
        }

    /**
     *  The probability that an offspring individual is mutated — i.e. that the mutation operator is
     *  applied to it at all. Must be in [0,1].
     *
     *  This per-individual gate **compounds** with any per-gene gating the operator itself does. The
     *  default `GaussianMutation` mutates each coordinate independently with its own `perGeneRate`,
     *  so the effective per-gene, per-generation mutation probability is about
     *  `mutationRate * perGeneRate` — roughly 1% at the defaults (0.1 × 0.1), not the 10% either
     *  value suggests on its own. To change how much mutation actually happens, adjust the operator's
     *  per-gene rate; `mutationRate` only controls how often the operator runs, not how far it
     *  perturbs when it does.
     */
    var mutationRate: Double = defaultMutationRate
        set(value) {
            require((value >= 0.0) && (value <= 1.0)) { "The mutation rate must be in [0,1]" }
            field = value
        }

    /**
     *  The number of best individuals carried forward unchanged into the next generation. Must be
     *  >= 0. At use, the effective elite count is clamped to populationSize - 1 so that at least one
     *  offspring is produced each generation; a value >= populationSize would otherwise freeze the
     *  search by carrying the entire population forward unchanged.
     *
     *  Keep this well below populationSize for healthy exploration. A value at or near populationSize
     *  leaves only a handful of offspring (as few as one) per generation, so the search still runs
     *  but progresses very slowly — the clamp prevents a hard freeze, not this near-stall.
     */
    var eliteCount: Int = defaultEliteCount
        set(value) {
            require(value >= 0) { "The elite count must be >= 0" }
            if (value >= populationSize) logger.warn { "Solver: $name : eliteCount ($value) >= populationSize ($populationSize) — it will be clamped to populationSize - 1 at use, leaving very few offspring and near-stalled evolution." }
            field = value
        }

    init {
        require(populationSize >= defaultMinPopulationSize) { "The population size must be >= $defaultMinPopulationSize" }
        warnIfSizeExceedsInputLattice(populationSize, "populationSize")
    }

    /**
     *  Used to check whether the most recent best solutions have converged (no improvement).
     */
    val solutionChecker: SolutionChecker = SolutionChecker(solutionEqualityChecker, defaultNoImproveThresholdForGA)

    private var myPopulation: MutableList<Solution> = mutableListOf()

    /**
     *  A read-only, best-first ordered view of the current population.
     */
    @Suppress("unused")
    val population: List<Solution>
        get() = myPopulation.toList()

    /**
     *  The effective population size: the value from [populationSizeFn] if supplied, otherwise
     *  [populationSize].
     */
    fun populationSizeValue(): Int = populationSizeFn?.populationSize(this) ?: populationSize

    /**
     *  The effective per-individual mutation rate: the value from [mutationRateFn] if supplied,
     *  otherwise [mutationRate].
     */
    fun mutationRateValue(): Double = mutationRateFn?.mutationRate(this) ?: mutationRate

    /**
     *  Orders the supplied solutions best-first using the solver's [compare] (minimization of the
     *  penalized objective by default).
     */
    private fun bestFirst(solutions: List<Solution>): List<Solution> =
        solutions.sortedWith { a, b -> compare(a, b) }

    override fun initializeIterations() {
        solutionChecker.clear()
        val n = populationSizeValue()
        val initialInputs = LinkedHashSet<InputMap>()
        startingPoint?.let { initialInputs.add(it) }
        if (initialInputs.size < n) {
            initialInputs.addAll(sampleInputFeasiblePoints(n - initialInputs.size))
        }
        val evaluations = requestEvaluations(initialInputs)
        check(evaluations.isNotEmpty()) { "The initial population evaluation returned no solutions." }
        myPopulation = bestFirst(evaluations.values.toList()).take(n).toMutableList()
        val best = myPopulation.first()
        myInitialSolution = best
        currentSolution = best
        solutionChecker.captureSolution(currentSolution)
        logger.debug { "Solver: $name : initialized GA population of size ${myPopulation.size}" }
    }

    override fun mainIteration() {
        if (myPopulation.isEmpty()) return
        val popSize = populationSizeValue()
        val sorted = bestFirst(myPopulation)
        // Clamp the effective elite count to popSize - 1 so at least one offspring is produced each
        // generation; eliteCount >= populationSize would otherwise freeze the search by carrying the
        // whole population forward unchanged.
        val eCount = minOf(eliteCount, sorted.size, maxOf(popSize - 1, 0))
        val elites = sorted.take(eCount)
        val target = maxOf(popSize - eCount, 0)
        if (target == 0) {
            // Defensive fallback: only reachable if the effective population size is < 1.
            myPopulation = sorted.take(popSize).toMutableList()
            currentSolution = myPopulation.first()
            solutionChecker.captureSolution(currentSolution)
            return
        }
        // Selection: produce a mating pool large enough to pair up into the offspring target.
        val parents = selectionOperator.select(myPopulation, maxOf(target, 2), this)
        // Crossover + mutation to build the offspring points.
        val offspringPoints = ArrayList<DoubleArray>(target)
        var i = 0
        while (offspringPoints.size < target) {
            val p1 = parents[i % parents.size]
            val p2 = parents[(i + 1) % parents.size]
            i += 2
            val children = if (rnStream.randU01() < crossoverRate) {
                crossoverOperator.crossover(p1.inputMap.inputValues, p2.inputMap.inputValues, this)
            } else {
                listOf(p1.inputMap.inputValues.copyOf(), p2.inputMap.inputValues.copyOf())
            }
            val mRate = mutationRateValue()
            for (child in children) {
                if (offspringPoints.size >= target) break
                val mutated = if (rnStream.randU01() < mRate) mutationOperator.mutate(child, this) else child
                offspringPoints.add(mutated)
            }
        }
        // Convert to range-feasible, granularity-rounded inputs and evaluate as a single batch.
        val offspringInputs = offspringPoints.map { problemDefinition.toInputMap(it) }
        val uniqueInputs = offspringInputs.toSet()
        val evaluations = requestEvaluations(uniqueInputs)
        if (evaluations.isEmpty()) {
            // No results this generation; leave the population unchanged and try again next time.
            return
        }
        val byInput = evaluations.values.associateBy { it.inputMap }
        val offspringSolutions = offspringInputs.mapNotNull { byInput[it] }
        // The next generation is the best of the elites plus the offspring.
        myPopulation = bestFirst(elites + offspringSolutions).take(popSize).toMutableList()
        currentSolution = myPopulation.first()
        solutionChecker.captureSolution(currentSolution)
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: solutionChecker.checkSolutions()
    }

    override fun extractSolverSpecificState(): Map<String, Double> {
        if (myPopulation.isEmpty()) {
            return linkedMapOf(
                "populationSize" to 0.0,
                "bestFitness" to Double.NaN,
                "avgFitness" to Double.NaN,
                "worstFitness" to Double.NaN,
                "diversity" to Double.NaN
            )
        }
        val fitness = myPopulation.map { it.penalizedObjFncValue }
        return linkedMapOf(
            "populationSize" to myPopulation.size.toDouble(),
            "bestFitness" to fitness.min(),
            "avgFitness" to fitness.average(),
            "worstFitness" to fitness.max(),
            "diversity" to populationDiversity()
        )
    }

    /**
     *  A simple population-diversity measure: the average, across input dimensions, of the
     *  per-dimension sample standard deviation of the population's coordinate values. Returns 0.0
     *  for populations with fewer than two members.
     */
    private fun populationDiversity(): Double {
        val n = myPopulation.size
        if (n < 2) return 0.0
        val d = problemDefinition.inputSize
        val points = myPopulation.map { it.inputMap.inputValues }
        var total = 0.0
        for (j in 0 until d) {
            var mean = 0.0
            for (p in points) mean += p[j]
            mean /= n
            var ss = 0.0
            for (p in points) {
                val dev = p[j] - mean
                ss += dev * dev
            }
            total += sqrt(ss / (n - 1))
        }
        return total / d
    }

    override fun toString(): String {
        return """
        GeneticAlgorithmSolver(
            populationSize = $populationSize,
            crossoverRate = $crossoverRate,
            mutationRate = $mutationRate,
            eliteCount = $eliteCount,
            selectionOperator = $selectionOperator,
            crossoverOperator = $crossoverOperator,
            mutationOperator = $mutationOperator,
            populationSizeFn = ${if (populationSizeFn != null) "Provided" else "None"},
            mutationRateFn = ${if (mutationRateFn != null) "Provided" else "None"},
            noImproveThreshold = ${solutionChecker.noImproveThreshold},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "populationSize" to populationSize.toString(),
            "crossoverRate" to crossoverRate.toString(),
            "mutationRate" to mutationRate.toString(),
            "eliteCount" to eliteCount.toString(),
            "selectionOperator" to (selectionOperator::class.simpleName ?: ""),
            "crossoverOperator" to (crossoverOperator::class.simpleName ?: ""),
            "mutationOperator" to (mutationOperator::class.simpleName ?: ""),
            "populationSizeFn" to if (populationSizeFn != null) "Provided" else "None",
            "mutationRateFn" to if (mutationRateFn != null) "Provided" else "None",
            "noImproveThreshold" to solutionChecker.noImproveThreshold.toString()
        )

    companion object {

        /**
         *  The default number of individuals per generation. By default, this is 30.
         */
        @JvmStatic
        var defaultPopulationSize: Int = 30
            set(value) {
                require(value >= defaultMinPopulationSize) { "The default population size must be >= $defaultMinPopulationSize" }
                field = value
            }

        /**
         *  The minimum permissible population size. By default, this is 4.
         */
        @JvmStatic
        var defaultMinPopulationSize: Int = 4
            set(value) {
                require(value >= 2) { "The default minimum population size must be >= 2" }
                field = value
            }

        /**
         *  The default crossover rate. By default, this is 0.9.
         */
        @JvmStatic
        var defaultCrossoverRate: Double = 0.9
            set(value) {
                require((value >= 0.0) && (value <= 1.0)) { "The default crossover rate must be in [0,1]" }
                field = value
            }

        /**
         *  The default per-individual mutation rate. By default, this is 0.1.
         */
        @JvmStatic
        var defaultMutationRate: Double = 0.1
            set(value) {
                require((value >= 0.0) && (value <= 1.0)) { "The default mutation rate must be in [0,1]" }
                field = value
            }

        /**
         *  The default number of elite individuals carried forward unchanged. By default, this is 1.
         */
        @JvmStatic
        var defaultEliteCount: Int = 1
            set(value) {
                require(value >= 0) { "The default elite count must be >= 0" }
                field = value
            }

        /**
         *  The default termination threshold for the largest number of generations during which no
         *  improvement of the best solution is found. By default, this is 10.
         */
        @JvmStatic
        var defaultNoImproveThresholdForGA: Int = 10
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }

        /**
         *  The default maximum number of generations for the genetic algorithm. By default, this is 100.
         */
        @JvmStatic
        var gaDefaultMaxIterations: Int = 100
            set(value) {
                require(value >= 1) { "The default maximum number of iterations must be >= 1" }
                field = value
            }
    }
}
