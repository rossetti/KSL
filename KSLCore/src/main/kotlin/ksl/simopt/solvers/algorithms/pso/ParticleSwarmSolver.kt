package ksl.simopt.solvers.algorithms.pso

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.InputsAndConfidenceIntervalEquality
import ksl.simopt.evaluator.Solution
import ksl.simopt.evaluator.SolutionChecker
import ksl.simopt.evaluator.SolutionEqualityIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver.Companion.defaultReplicationsPerEvaluation
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.utilities.random.rng.RNStreamProvider
import ksl.utilities.random.rng.RNStreamProviderIfc
import kotlin.math.sqrt

/**
 *  If supplied, this function determines the swarm size during the particle swarm process,
 *  overriding the scalar [ParticleSwarmSolver.swarmSize].
 */
fun interface SwarmSizeFnIfc {
    fun swarmSize(pso: ParticleSwarmSolver): Int
}

/**
 *  If supplied, this function determines the cognitive and social acceleration coefficients
 *  (c1, c2) each iteration, overriding the scalar [ParticleSwarmSolver.cognitiveCoefficient] and
 *  [ParticleSwarmSolver.socialCoefficient]. The returned pair is (c1, c2).
 */
fun interface CoefficientScheduleIfc {
    fun coefficients(pso: ParticleSwarmSolver): Pair<Double, Double>
}

/**
 *  A global-best (gbest) Particle Swarm Optimization (PSO) solver for simulation optimization.
 *
 *  Each call to [mainIteration] performs one swarm move: every particle's velocity is updated from
 *  its inertia, its pull toward its personal best, and its pull toward the single shared global
 *  best; the velocity is clamped to a per-coordinate maximum; the particle's continuous position is
 *  advanced and brought back within the input ranges by the [boundaryHandler]; and the whole swarm
 *  is then evaluated in a **single batch** oracle call (via the inherited [requestEvaluations]),
 *  so a parallel evaluator fans the particles across workers with no concurrency code here. Per
 *  particle, the personal best is refreshed and the global best (the inherited [currentSolution],
 *  hence [bestSolution]) is updated.
 *
 *  Particles move in continuous space; only the evaluated position is snapped to granularity (via
 *  [ProblemDefinition.toInputMap]), avoiding granularity lock-in. All randomness is drawn through
 *  the solver's single random number stream ([rnStream]), so a run is reproducible for a fixed
 *  stream number.
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the evaluator responsible for assessing the quality of solutions
 *  @param streamNum the random number stream number; 0 (the default) means the next available stream
 *  @param streamProvider the provider of random number streams; defaults to a fresh RNStreamProvider
 *  @param swarmSize the number of particles in the swarm
 *  @param inertiaSchedule the inertia-weight schedule; defaults to [LinearDecreasingInertia]
 *  @param cognitiveCoefficient the cognitive acceleration coefficient c1 (pull toward personal best)
 *  @param socialCoefficient the social acceleration coefficient c2 (pull toward global best)
 *  @param boundaryHandler how out-of-range positions are handled; defaults to [ClampToBounds]
 *  @param velocityInitializer how initial velocities are set; defaults to [ZeroVelocity]
 *  @param maxIterations the maximum number of iterations
 *  @param replicationsPerEvaluation strategy to determine the number of replications per evaluation
 *  @param solutionEqualityChecker used to detect no-improvement convergence. The default is
 *  [InputsAndConfidenceIntervalEquality].
 *  @param name an optional name for the solver
 */
class ParticleSwarmSolver @JvmOverloads constructor(
    problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = RNStreamProvider(),
    swarmSize: Int = defaultSwarmSize,
    inertiaSchedule: InertiaWeightScheduleIfc = LinearDecreasingInertia(),
    cognitiveCoefficient: Double = defaultCognitiveCoefficient,
    socialCoefficient: Double = defaultSocialCoefficient,
    boundaryHandler: BoundaryHandlerIfc = ClampToBounds(),
    velocityInitializer: VelocityInitializerIfc = ZeroVelocity(),
    maxIterations: Int = psoDefaultMaxIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality(),
    name: String? = null
) : StochasticSolver(
    problemDefinition, evaluator, maxIterations,
    replicationsPerEvaluation, streamNum, streamProvider, name
) {

    /**
     *  Constructs a particle swarm solver using a fixed number of replications per evaluation.
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
        swarmSize: Int = defaultSwarmSize,
        inertiaSchedule: InertiaWeightScheduleIfc = LinearDecreasingInertia(),
        cognitiveCoefficient: Double = defaultCognitiveCoefficient,
        socialCoefficient: Double = defaultSocialCoefficient,
        boundaryHandler: BoundaryHandlerIfc = ClampToBounds(),
        velocityInitializer: VelocityInitializerIfc = ZeroVelocity(),
        maxIterations: Int = psoDefaultMaxIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        solutionEqualityChecker: SolutionEqualityIfc = InputsAndConfidenceIntervalEquality(),
        name: String? = null
    ) : this(
        problemDefinition, evaluator, streamNum, streamProvider, swarmSize, inertiaSchedule,
        cognitiveCoefficient, socialCoefficient, boundaryHandler, velocityInitializer, maxIterations,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation), solutionEqualityChecker, name
    )

    /** The inertia-weight schedule. Cannot be changed while the solver is running. */
    var inertiaSchedule: InertiaWeightScheduleIfc = inertiaSchedule
        set(value) {
            require(!iterativeProcess.isRunning) { "The inertia schedule cannot be changed while the solver is running." }
            field = value
        }

    /** The boundary handler for out-of-range positions. Cannot be changed while the solver is running. */
    var boundaryHandler: BoundaryHandlerIfc = boundaryHandler
        set(value) {
            require(!iterativeProcess.isRunning) { "The boundary handler cannot be changed while the solver is running." }
            field = value
        }

    /** The velocity initializer. Cannot be changed while the solver is running. */
    var velocityInitializer: VelocityInitializerIfc = velocityInitializer
        set(value) {
            require(!iterativeProcess.isRunning) { "The velocity initializer cannot be changed while the solver is running." }
            field = value
        }

    /** If supplied, this function determines the swarm size, overriding [swarmSize]. */
    var swarmSizeFn: SwarmSizeFnIfc? = null

    /** If supplied, this function determines (c1, c2) each iteration, overriding the scalars. */
    var coefficientSchedule: CoefficientScheduleIfc? = null

    /** The number of particles in the swarm. Must be at least [defaultMinSwarmSize]. */
    var swarmSize: Int = swarmSize
        set(value) {
            require(value >= defaultMinSwarmSize) { "The swarm size must be >= $defaultMinSwarmSize" }
            field = value
        }

    /** The cognitive acceleration coefficient c1 (pull toward the personal best). Must be >= 0. */
    var cognitiveCoefficient: Double = cognitiveCoefficient
        set(value) {
            require(value >= 0.0) { "The cognitive coefficient must be >= 0" }
            field = value
        }

    /** The social acceleration coefficient c2 (pull toward the global best). Must be >= 0. */
    var socialCoefficient: Double = socialCoefficient
        set(value) {
            require(value >= 0.0) { "The social coefficient must be >= 0" }
            field = value
        }

    /**
     *  The fraction of each input's range used as that coordinate's maximum speed (velocity clamp).
     *  Must be in (0,1].
     */
    var vMaxFraction: Double = defaultVMaxFraction
        set(value) {
            require((value > 0.0) && (value <= 1.0)) { "The vMax fraction must be in (0,1]" }
            field = value
        }

    /**
     *  When true, the whole swarm is evaluated under common random numbers within an iteration
     *  (which disables caching for that batch). Default is false.
     */
    var useCRNWithinIteration: Boolean = false

    /**
     *  When true, the search stops once the normalized swarm diameter falls to or below
     *  [diameterThreshold] (in addition to the no-improvement criterion). Enabled by default.
     */
    var diameterBasedStoppingEnabled: Boolean = true

    /**
     *  The normalized swarm-diameter threshold for convergence. The normalized diameter is the
     *  swarm's bounding-box diagonal divided by the search-space diagonal (in [0,1]). Must be > 0.
     */
    var diameterThreshold: Double = defaultDiameterThreshold
        set(value) {
            require(value > 0.0) { "The diameter threshold must be > 0" }
            field = value
        }

    init {
        require(swarmSize >= defaultMinSwarmSize) { "The swarm size must be >= $defaultMinSwarmSize" }
        require(cognitiveCoefficient >= 0.0) { "The cognitive coefficient must be >= 0" }
        require(socialCoefficient >= 0.0) { "The social coefficient must be >= 0" }
    }

    /** Used to check whether the most recent best solutions have converged (no improvement). */
    val solutionChecker: SolutionChecker = SolutionChecker(solutionEqualityChecker, defaultNoImproveThresholdForPSO)

    private val mySwarm: MutableList<Particle> = mutableListOf()
    private lateinit var myVMax: DoubleArray

    private val solutionComparator: Comparator<Solution> = Comparator { a, b -> compare(a, b) }

    /** A read-only snapshot of the current swarm. */
    @Suppress("unused")
    val swarm: List<Particle>
        get() = mySwarm.toList()

    /** The current global best solution (equal to the inherited [bestSolution]). */
    @Suppress("unused")
    val globalBest: Solution
        get() = bestSolution

    /** The effective swarm size: [swarmSizeFn] if supplied, otherwise [swarmSize]. */
    fun swarmSizeValue(): Int = swarmSizeFn?.swarmSize(this) ?: swarmSize

    override fun initializeIterations() {
        solutionChecker.clear()
        mySwarm.clear()
        val ranges = problemDefinition.inputRanges
        myVMax = DoubleArray(ranges.size) { vMaxFraction * ranges[it] }
        val n = swarmSizeValue()
        // Seed the swarm with the supplied starting point (if any) as the first particle, then
        // scatter the remaining particles over distinct feasible points.
        val initialInputs = LinkedHashSet<InputMap>()
        startingPoint?.let { initialInputs.add(it) }
        while (initialInputs.size < n) {
            initialInputs.addAll(sampleInputFeasiblePoints(n - initialInputs.size))
        }
        val seeds = initialInputs.toList()
        for (seed in seeds) {
            val position = seed.inputValues
            val velocity = velocityInitializer.initialVelocity(position, myVMax, this)
            mySwarm.add(Particle(position, velocity))
        }
        val positionSet = seeds.toSet()
        val crn = useCRNWithinIteration && positionSet.size >= 2
        val evaluations = requestEvaluations(positionSet, crnOption = crn)
        check(evaluations.isNotEmpty()) { "The initial swarm evaluation returned no solutions." }
        val byInput = evaluations.values.associateBy { it.inputMap }
        for ((i, seed) in seeds.withIndex()) {
            val solution = byInput[seed] ?: error("Missing solution for an initial particle position.")
            mySwarm[i].currentSolution = solution
            mySwarm[i].updatePersonalBest(solutionComparator)
        }
        val best = swarmBestSolution()
        myInitialSolution = best
        currentSolution = best
        solutionChecker.captureSolution(currentSolution)
        logger.info { "Solver: $name : initialized PSO swarm of size ${mySwarm.size}" }
    }

    override fun mainIteration() {
        if (mySwarm.isEmpty()) return
        val w = inertiaSchedule.nextInertia(iterationCounter)
        val (c1, c2) = coefficientSchedule?.coefficients(this) ?: Pair(cognitiveCoefficient, socialCoefficient)
        val gBest = currentSolution.inputMap.inputValues
        // 1. Move every particle: velocity update, velocity clamp, position update, boundary handling.
        for (particle in mySwarm) {
            val position = particle.position
            val velocity = particle.velocity
            val pBest = particle.bestPosition
            for (d in position.indices) {
                val r1 = rnStream.randU01()
                val r2 = rnStream.randU01()
                var v = w * velocity[d] + c1 * r1 * (pBest[d] - position[d]) + c2 * r2 * (gBest[d] - position[d])
                val vMax = myVMax[d]
                if (vMax > 0.0) {
                    if (v > vMax) v = vMax else if (v < -vMax) v = -vMax
                }
                velocity[d] = v
            }
            val moved = DoubleArray(position.size) { position[it] + velocity[it] }
            particle.position = boundaryHandler.enforce(moved, problemDefinition)
        }
        // 2. Batch-evaluate the whole swarm in one oracle call (the parallelism seam).
        val positionInputs = mySwarm.map { problemDefinition.toInputMap(it.position) }
        val positionSet = positionInputs.toSet()
        val crn = useCRNWithinIteration && positionSet.size >= 2
        val evaluations = requestEvaluations(positionSet, crnOption = crn)
        if (evaluations.isEmpty()) {
            // No results this iteration; leave the swarm in place and try again next time.
            return
        }
        // 3. Re-associate solutions to particles by position (duplicate positions share a solution).
        val byInput = evaluations.values.associateBy { it.inputMap }
        for ((i, particle) in mySwarm.withIndex()) {
            val solution = byInput[positionInputs[i]] ?: continue
            particle.currentSolution = solution
            particle.updatePersonalBest(solutionComparator)
        }
        // 4. Update the global best (drives the inherited best-solution tracking).
        val best = swarmBestSolution()
        if (compare(best, currentSolution) < 0) {
            currentSolution = best
        }
        solutionChecker.captureSolution(currentSolution)
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: checkForConvergence()
    }

    private fun checkForConvergence(): Boolean {
        if (solutionChecker.checkSolutions()) return true
        if (diameterBasedStoppingEnabled && mySwarm.size >= 2 && normalizedSwarmDiameter() <= diameterThreshold) {
            return true
        }
        return false
    }

    /** The swarm's best current solution, using the solver's [compare]. */
    private fun swarmBestSolution(): Solution {
        var best = mySwarm[0].currentSolution
        for (i in 1 until mySwarm.size) {
            val candidate = mySwarm[i].currentSolution
            if (compare(candidate, best) < 0) best = candidate
        }
        return best
    }

    /**
     *  The swarm's bounding-box diagonal divided by the search-space diagonal (in [0,1]). Returns
     *  0.0 for swarms with fewer than two particles.
     */
    private fun normalizedSwarmDiameter(): Double {
        if (mySwarm.size < 2) return 0.0
        val ranges = problemDefinition.inputRanges
        var spreadSq = 0.0
        var rangeSq = 0.0
        for (j in ranges.indices) {
            var min = Double.POSITIVE_INFINITY
            var max = Double.NEGATIVE_INFINITY
            for (particle in mySwarm) {
                val v = particle.position[j]
                if (v < min) min = v
                if (v > max) max = v
            }
            val spread = max - min
            spreadSq += spread * spread
            rangeSq += ranges[j] * ranges[j]
        }
        if (rangeSq <= 0.0) return 0.0
        return sqrt(spreadSq / rangeSq)
    }

    /** The average particle speed (Euclidean norm of velocity) across the swarm. */
    private fun averageSpeed(): Double {
        if (mySwarm.isEmpty()) return 0.0
        var total = 0.0
        for (particle in mySwarm) {
            var s = 0.0
            for (v in particle.velocity) s += v * v
            total += sqrt(s)
        }
        return total / mySwarm.size
    }

    override fun extractSolverSpecificState(): Map<String, Double> {
        if (mySwarm.isEmpty()) {
            return linkedMapOf(
                "swarmSize" to 0.0,
                "inertia" to inertiaSchedule.nextInertia(iterationCounter),
                "gBestFitness" to Double.NaN,
                "avgFitness" to Double.NaN,
                "swarmDiameter" to Double.NaN,
                "avgSpeed" to Double.NaN
            )
        }
        val fitness = mySwarm.map { it.currentSolution.penalizedObjFncValue }
        return linkedMapOf(
            "swarmSize" to mySwarm.size.toDouble(),
            "inertia" to inertiaSchedule.nextInertia(iterationCounter),
            "gBestFitness" to currentSolution.penalizedObjFncValue,
            "avgFitness" to fitness.average(),
            "swarmDiameter" to normalizedSwarmDiameter(),
            "avgSpeed" to averageSpeed()
        )
    }

    override fun toString(): String {
        return """
        ParticleSwarmSolver(
            swarmSize = $swarmSize,
            cognitiveCoefficient = $cognitiveCoefficient,
            socialCoefficient = $socialCoefficient,
            vMaxFraction = $vMaxFraction,
            inertiaSchedule = $inertiaSchedule,
            boundaryHandler = $boundaryHandler,
            velocityInitializer = $velocityInitializer,
            useCRNWithinIteration = $useCRNWithinIteration,
            diameterBasedStoppingEnabled = $diameterBasedStoppingEnabled,
            diameterThreshold = $diameterThreshold,
            swarmSizeFn = ${if (swarmSizeFn != null) "Provided" else "None"},
            coefficientSchedule = ${if (coefficientSchedule != null) "Provided" else "None"},
            noImproveThreshold = ${solutionChecker.noImproveThreshold},
            base = ${super.toString().prependIndent("    ").trimStart()}
        )
    """.trimIndent()
    }

    override val configurationProperties: Map<String, String>
        get() = super.configurationProperties + linkedMapOf(
            "swarmSize" to swarmSize.toString(),
            "cognitiveCoefficient" to cognitiveCoefficient.toString(),
            "socialCoefficient" to socialCoefficient.toString(),
            "vMaxFraction" to vMaxFraction.toString(),
            "inertiaSchedule" to (inertiaSchedule::class.simpleName ?: ""),
            "boundaryHandler" to (boundaryHandler::class.simpleName ?: ""),
            "velocityInitializer" to (velocityInitializer::class.simpleName ?: ""),
            "useCRNWithinIteration" to useCRNWithinIteration.toString(),
            "diameterBasedStoppingEnabled" to diameterBasedStoppingEnabled.toString(),
            "diameterThreshold" to diameterThreshold.toString(),
            "swarmSizeFn" to if (swarmSizeFn != null) "Provided" else "None",
            "coefficientSchedule" to if (coefficientSchedule != null) "Provided" else "None",
            "noImproveThreshold" to solutionChecker.noImproveThreshold.toString()
        )

    companion object {

        /** The default swarm size. By default, this is 30. */
        @JvmStatic
        var defaultSwarmSize: Int = 30
            set(value) {
                require(value >= defaultMinSwarmSize) { "The default swarm size must be >= $defaultMinSwarmSize" }
                field = value
            }

        /** The minimum permissible swarm size. By default, this is 2. */
        @JvmStatic
        var defaultMinSwarmSize: Int = 2
            set(value) {
                require(value >= 2) { "The default minimum swarm size must be >= 2" }
                field = value
            }

        /** The default cognitive acceleration coefficient c1. By default, this is 1.49445. */
        @JvmStatic
        var defaultCognitiveCoefficient: Double = 1.49445
            set(value) {
                require(value >= 0.0) { "The default cognitive coefficient must be >= 0" }
                field = value
            }

        /** The default social acceleration coefficient c2. By default, this is 1.49445. */
        @JvmStatic
        var defaultSocialCoefficient: Double = 1.49445
            set(value) {
                require(value >= 0.0) { "The default social coefficient must be >= 0" }
                field = value
            }

        /** The default starting inertia weight. By default, this is 0.9. */
        @JvmStatic
        var defaultInitialInertia: Double = 0.9
            set(value) {
                require(value > 0.0) { "The default initial inertia must be > 0" }
                field = value
            }

        /** The default final inertia weight. By default, this is 0.4. */
        @JvmStatic
        var defaultFinalInertia: Double = 0.4
            set(value) {
                require(value > 0.0) { "The default final inertia must be > 0" }
                field = value
            }

        /** The default fraction of an input's range used as its maximum speed. By default, this is 0.2. */
        @JvmStatic
        var defaultVMaxFraction: Double = 0.2
            set(value) {
                require((value > 0.0) && (value <= 1.0)) { "The default vMax fraction must be in (0,1]" }
                field = value
            }

        /**
         *  The default normalized swarm-diameter threshold for convergence. By default, this is 1.0E-3.
         */
        @JvmStatic
        var defaultDiameterThreshold: Double = 1.0E-3
            set(value) {
                require(value > 0.0) { "The default diameter threshold must be > 0" }
                field = value
            }

        /**
         *  The default termination threshold for the largest number of iterations during which no
         *  improvement of the best solution is found. By default, this is 10.
         */
        @JvmStatic
        var defaultNoImproveThresholdForPSO: Int = 10
            set(value) {
                require(value > 0) { "The default no improvement threshold must be greater than 0" }
                field = value
            }

        /** The default maximum number of iterations for the particle swarm. By default, this is 100. */
        @JvmStatic
        var psoDefaultMaxIterations: Int = 100
            set(value) {
                require(value >= 1) { "The default maximum number of iterations must be >= 1" }
                field = value
            }
    }
}
