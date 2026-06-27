package ksl.simopt.solvers.algorithms.bo

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.InputsAndConfidenceIntervalEquality
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.SolutionChecker
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver.Companion.defaultReplicationsPerEvaluation
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc

/**
 *  A Bayesian Optimization (BO) solver for stochastic simulation optimization. BO is a sequential,
 *  model-based method for expensive, noisy objectives: it fits a probabilistic surrogate to the
 *  observed data and, each iteration, optimizes a cheap acquisition function over the surrogate
 *  (no simulation) to choose the single most promising point to evaluate next.
 *
 *  The loop:
 *  1. [initializeIterations] evaluates a space-filling initial design (one batch oracle call), fits
 *     the surrogate (and its hyperparameters), and records the incumbent.
 *  2. Each [mainIteration]: (optionally) refit hyperparameters, refit the surrogate to the archive,
 *     compute the incumbent, maximize the acquisition over the surrogate, and evaluate the chosen
 *     point — the only oracle call of the iteration.
 *
 *  Per-point observation noise is taken from each [Solution]'s estimated response (sample variance
 *  divided by replication count), which is exactly what Gaussian-process regression needs for the
 *  stochastic setting. The best solution found is tracked automatically by the base class through
 *  the [currentSolution] setter. All randomness is drawn through the solver's single random number
 *  stream ([rnStream]).
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the evaluator responsible for assessing the quality of solutions
 *  @param streamNum the random number stream number; 0 (the default) means the next available stream
 *  @param streamProvider the provider of random number streams; defaults to a fresh RNStreamProvider
 *  @param surrogate the surrogate model; defaults to a [GaussianProcessModel]
 *  @param acquisition the acquisition function; defaults to [ExpectedImprovement]
 *  @param acquisitionOptimizer the acquisition optimizer; defaults to [SampledAcquisitionOptimizer]
 *  @param hyperparameterFitter the surrogate hyperparameter fitter; defaults to [FixedHyperparameters]
 *  @param initialDesign the initial design strategy; defaults to [LatinHyperCubeDesign]
 *  @param incumbentRule the incumbent rule; defaults to [BestPosteriorMeanIncumbent]
 *  @param initialDesignSize the number of initial design points
 *  @param maxIterations the maximum number of BO iterations (after the initial design)
 *  @param replicationsPerEvaluation strategy to determine the number of replications per evaluation
 *  @param name an optional name for the solver
 */
class BayesianOptimizationSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    surrogate: SurrogateModelIfc = GaussianProcessModel(problemDefinition),
    acquisition: AcquisitionFunctionIfc = ExpectedImprovement(),
    acquisitionOptimizer: AcquisitionOptimizerIfc = SampledAcquisitionOptimizer(),
    hyperparameterFitter: HyperparameterFitterIfc = FixedHyperparameters(),
    initialDesign: InitialDesignIfc = LatinHyperCubeDesign(),
    incumbentRule: IncumbentRuleIfc = BestPosteriorMeanIncumbent(),
    initialDesignSize: Int = defaultInitialDesignSize,
    maxIterations: Int = boDefaultMaxIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, maxIterations,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    /**
     *  Constructs a Bayesian optimization solver using a fixed number of replications per evaluation.
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
        surrogate: SurrogateModelIfc = GaussianProcessModel(problemDefinition),
        acquisition: AcquisitionFunctionIfc = ExpectedImprovement(),
        acquisitionOptimizer: AcquisitionOptimizerIfc = SampledAcquisitionOptimizer(),
        hyperparameterFitter: HyperparameterFitterIfc = FixedHyperparameters(),
        initialDesign: InitialDesignIfc = LatinHyperCubeDesign(),
        incumbentRule: IncumbentRuleIfc = BestPosteriorMeanIncumbent(),
        initialDesignSize: Int = defaultInitialDesignSize,
        maxIterations: Int = boDefaultMaxIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        name: String? = null
    ) : this(
        problemDefinition, evaluator, streamNum, streamProvider, surrogate, acquisition,
        acquisitionOptimizer, hyperparameterFitter, initialDesign, incumbentRule, initialDesignSize,
        maxIterations, FixedReplicationsPerEvaluation(replicationsPerEvaluation), name
    )

    /** The surrogate model. Cannot be changed while the solver is running. */
    var surrogate: SurrogateModelIfc = surrogate
        set(value) {
            require(!iterativeProcess.isRunning) { "The surrogate cannot be changed while the solver is running." }
            field = value
        }

    /** The acquisition function. Cannot be changed while the solver is running. */
    var acquisition: AcquisitionFunctionIfc = acquisition
        set(value) {
            require(!iterativeProcess.isRunning) { "The acquisition function cannot be changed while the solver is running." }
            field = value
        }

    /** The acquisition optimizer. Cannot be changed while the solver is running. */
    var acquisitionOptimizer: AcquisitionOptimizerIfc = acquisitionOptimizer
        set(value) {
            require(!iterativeProcess.isRunning) { "The acquisition optimizer cannot be changed while the solver is running." }
            field = value
        }

    /** The surrogate hyperparameter fitter. Cannot be changed while the solver is running. */
    var hyperparameterFitter: HyperparameterFitterIfc = hyperparameterFitter
        set(value) {
            require(!iterativeProcess.isRunning) { "The hyperparameter fitter cannot be changed while the solver is running." }
            field = value
        }

    /** The initial design strategy. Cannot be changed while the solver is running. */
    var initialDesign: InitialDesignIfc = initialDesign
        set(value) {
            require(!iterativeProcess.isRunning) { "The initial design cannot be changed while the solver is running." }
            field = value
        }

    /** The incumbent rule. Cannot be changed while the solver is running. */
    var incumbentRule: IncumbentRuleIfc = incumbentRule
        set(value) {
            require(!iterativeProcess.isRunning) { "The incumbent rule cannot be changed while the solver is running." }
            field = value
        }

    /** The number of initial design points. Must be >= 2 (recommended >= problem dimension + 1). */
    var initialDesignSize: Int = initialDesignSize
        set(value) {
            require(value >= 2) { "The initial design size must be >= 2" }
            field = value
        }

    /** How often (in iterations) the surrogate hyperparameters are refit. Must be >= 1. */
    var refitEvery: Int = defaultRefitEvery
        set(value) {
            require(value >= 1) { "refitEvery must be >= 1" }
            field = value
        }

    /**
     *  An optional cap on the surrogate's training-set (archive) size, bounding the GP's O(n^3)
     *  cost; when set, the best solutions are retained. Null (the default) means no cap. Must be
     *  >= 2 when set.
     */
    var maxArchiveSize: Int? = null
        set(value) {
            if (value != null) require(value >= 2) { "maxArchiveSize must be >= 2 when set" }
            field = value
        }

    init {
        require(initialDesignSize >= 2) { "The initial design size must be >= 2" }
    }

    /** Used to detect no-improvement convergence. */
    val solutionChecker: SolutionChecker =
        SolutionChecker(InputsAndConfidenceIntervalEquality(), defaultNoImproveThresholdForBO)

    private val archive: MutableList<Solution> = mutableListOf()
    private val comparator: Comparator<Solution> = Comparator { a, b -> compare(a, b) }
    private var myLastAcqValue: Double = Double.NaN

    /** A read-only view of the observed solutions (the surrogate's training set). */
    val observedSolutions: List<Solution>
        get() = archive.toList()

    /** The current incumbent value according to the configured [incumbentRule]. */
    @Suppress("unused")
    val currentIncumbentValue: Double
        get() = incumbentRule.incumbent(this)

    private fun noiseVarianceOf(solution: Solution): Double {
        val v = solution.variance
        val c = solution.count
        return if (v.isFinite() && v > 0.0 && c >= 1.0) v / c else defaultNoiseFloor
    }

    private fun addOrReplace(solution: Solution) {
        val idx = archive.indexOfFirst { it.inputMap == solution.inputMap }
        if (idx >= 0) archive[idx] = solution else archive.add(solution)
    }

    private fun trimArchiveIfNeeded() {
        val cap = maxArchiveSize ?: return
        if (archive.size <= cap) return
        val kept = archive.sortedWith(comparator).take(cap)
        archive.clear()
        archive.addAll(kept)
    }

    private fun fitHyperparameters() {
        val gp = surrogate as? GaussianProcessModel ?: return
        if (archive.isEmpty()) return
        val points = archive.map { it.inputMap.inputValues }
        val means = DoubleArray(archive.size) { archive[it].penalizedObjFncValue }
        val noiseVars = DoubleArray(archive.size) { noiseVarianceOf(archive[it]) }
        hyperparameterFitter.fit(gp, points, means, noiseVars, rnStream)
    }

    private fun fitSurrogate() {
        if (archive.isEmpty()) return
        val points = archive.map { it.inputMap.inputValues }
        val means = DoubleArray(archive.size) { archive[it].penalizedObjFncValue }
        val noiseVars = DoubleArray(archive.size) { noiseVarianceOf(archive[it]) }
        surrogate.fit(points, means, noiseVars)
    }

    override fun initializeIterations() {
        solutionChecker.clear()
        archive.clear()
        myLastAcqValue = Double.NaN
        val design = LinkedHashSet<InputMap>()
        startingPoint?.let { design.add(it) }
        design.addAll(initialDesign.generate(initialDesignSize, this))
        val evaluations = requestEvaluations(design)
        check(evaluations.isNotEmpty()) { "The initial design evaluation returned no solutions." }
        for (solution in evaluations.values) addOrReplace(solution)
        fitHyperparameters()
        fitSurrogate()
        val best = archive.minWithOrNull(comparator)!!
        myInitialSolution = best
        currentSolution = best
        solutionChecker.captureSolution(currentSolution)
        logger.info { "Solver: $name : initialized BO with ${archive.size} design points" }
    }

    override fun mainIteration() {
        if (archive.isEmpty()) return
        if (iterationCounter % refitEvery == 0) {
            fitHyperparameters()
        }
        fitSurrogate()
        val incumbent = incumbentRule.incumbent(this)
        val nextInput = acquisitionOptimizer.optimize(acquisition, surrogate, incumbent, this)
        myLastAcqValue = acquisition.value(surrogate.predict(nextInput.inputValues), incumbent, this)
        val next = requestEvaluation(nextInput)
        addOrReplace(next)
        trimArchiveIfNeeded()
        if (compare(next, currentSolution) < 0) {
            currentSolution = next
        }
        solutionChecker.captureSolution(currentSolution)
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: solutionChecker.checkSolutions()
    }

    override fun extractSolverSpecificState(): Map<String, Double> {
        if (archive.isEmpty()) {
            return linkedMapOf(
                "archiveSize" to 0.0,
                "incumbentValue" to Double.NaN,
                "lastAcqValue" to myLastAcqValue,
                "gpSignalVariance" to Double.NaN,
                "gpMeanLengthScale" to Double.NaN
            )
        }
        val gp = surrogate as? GaussianProcessModel
        return linkedMapOf(
            "archiveSize" to archive.size.toDouble(),
            "incumbentValue" to incumbentRule.incumbent(this),
            "lastAcqValue" to myLastAcqValue,
            "gpSignalVariance" to (gp?.kernel?.signalVariance ?: Double.NaN),
            "gpMeanLengthScale" to (gp?.kernel?.lengthScales?.average() ?: Double.NaN)
        )
    }

    override fun toString(): String {
        return """
        BayesianOptimizationSolver(
            initialDesignSize = $initialDesignSize,
            refitEvery = $refitEvery,
            maxArchiveSize = ${maxArchiveSize ?: "None"},
            surrogate = $surrogate,
            acquisition = $acquisition,
            acquisitionOptimizer = $acquisitionOptimizer,
            hyperparameterFitter = $hyperparameterFitter,
            initialDesign = $initialDesign,
            incumbentRule = $incumbentRule,
            noImproveThreshold = ${solutionChecker.noImproveThreshold},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "initialDesignSize" to initialDesignSize.toString(),
            "refitEvery" to refitEvery.toString(),
            "maxArchiveSize" to (maxArchiveSize?.toString() ?: "None"),
            "surrogate" to (surrogate::class.simpleName ?: ""),
            "acquisition" to (acquisition::class.simpleName ?: ""),
            "acquisitionOptimizer" to (acquisitionOptimizer::class.simpleName ?: ""),
            "hyperparameterFitter" to (hyperparameterFitter::class.simpleName ?: ""),
            "initialDesign" to (initialDesign::class.simpleName ?: ""),
            "incumbentRule" to (incumbentRule::class.simpleName ?: ""),
            "noImproveThreshold" to solutionChecker.noImproveThreshold.toString()
        )

    companion object {

        /** The default number of initial design points. By default, this is 10. */
        @JvmStatic
        var defaultInitialDesignSize: Int = 10
            set(value) {
                require(value >= 2) { "The default initial design size must be >= 2" }
                field = value
            }

        /** The default hyperparameter refit cadence (in iterations). By default, this is 1. */
        @JvmStatic
        var defaultRefitEvery: Int = 1
            set(value) {
                require(value >= 1) { "The default refitEvery must be >= 1" }
                field = value
            }

        /** The default maximum number of BO iterations after the initial design. By default, this is 50. */
        @JvmStatic
        var boDefaultMaxIterations: Int = 50
            set(value) {
                require(value >= 1) { "The default maximum number of iterations must be >= 1" }
                field = value
            }

        /** The default exploration margin for improvement-based acquisitions. By default, this is 0.0. */
        @JvmStatic
        var defaultXi: Double = 0.0

        /** The default exploration weight for the lower-confidence-bound acquisition. By default, this is 2.0. */
        @JvmStatic
        var defaultBeta: Double = 2.0
            set(value) {
                require(value >= 0.0) { "The default beta must be >= 0" }
                field = value
            }

        /** The default number of global candidates for the sampled acquisition optimizer. By default, this is 512. */
        @JvmStatic
        var defaultNumCandidates: Int = 512
            set(value) {
                require(value >= 1) { "The default number of candidates must be >= 1" }
                field = value
            }

        /** The default number of local candidates for the sampled acquisition optimizer. By default, this is 5. */
        @JvmStatic
        var defaultRestarts: Int = 5
            set(value) {
                require(value >= 0) { "The default number of restarts must be >= 0" }
                field = value
            }

        /** The default floor used for per-point noise variance when an estimate is unavailable. By default, 1.0E-6. */
        @JvmStatic
        var defaultNoiseFloor: Double = 1.0E-6
            set(value) {
                require(value > 0.0) { "The default noise floor must be > 0" }
                field = value
            }

        /**
         *  The default termination threshold for the largest number of iterations during which no
         *  improvement of the best solution is found. By default, this is 10.
         */
        @JvmStatic
        var defaultNoImproveThresholdForBO: Int = 10
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }
    }
}
