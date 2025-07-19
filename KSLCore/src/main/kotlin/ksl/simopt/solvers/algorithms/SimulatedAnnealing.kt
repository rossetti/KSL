package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import kotlin.math.exp

//TODO default initial temperature

/**
 * A class that implements the simulated annealing optimization algorithm for solving stochastic or deterministic problems.
 * Simulated annealing is an iterative optimization method inspired by the physical process of annealing in metallurgy.
 * It uses a probabilistic approach to escape local optima by accepting worse solutions with a certain probability, which decreases
 * over time according to a cooling schedule.
 *
 * @constructor
 * Constructs a SimulatedAnnealing solver with the specified parameters.
 *
 * @param evaluator The evaluator responsible for calculating the objective function value of a solution.
 *                  It must implement the [EvaluatorIfc] interface.
 * @param initialTemperature The starting temperature for the simulated annealing algorithm. Must be greater than 0.0.
 * @param coolingSchedule the cooling schedule for the annealing process
 * @param stoppingTemperature the temperature used to stop the annealing process. If the current temperature goes
 * below this temperature, the search process stops.
 * @param maxIterations the maximum number of iterations permitted for the search process
 * @param replicationsPerEvaluation An instance of `ReplicationPerEvaluationIfc`
 * defining the strategy for determining the number of replications per evaluation.
 * @param rnStream An optional random number stream used for stochastic behavior.
 * Defaults to `KSLRandom.defaultRNStream()`.
 * @param name An optional name for this solver instance.
 *
 */
class SimulatedAnnealing @JvmOverloads constructor(
    evaluator: EvaluatorIfc,
    initialTemperature: Double = defaultInitialTemperature,
    var coolingSchedule: CoolingScheduleIfc = ExponentialCoolingSchedule(initialTemperature),
    stoppingTemperature: Double = defaultStoppingTemperature,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
    name: String? = null
) : StochasticSolver(evaluator, maxIterations, replicationsPerEvaluation, streamNum, streamProvider, name) {

    init {
        require(initialTemperature > 0.0) { "The initial temperature must be positive" }
        require(stoppingTemperature > 0.0) { "The final temperature must be positive" }
    }

    /**
     *  Changing the initial temperature will also change it for the associated cooling schedule.
     */
    var initialTemperature: Double = initialTemperature
        set(value) {
            require(value > 0.0) { "The initial temperature must be positive" }
            field = value
            coolingSchedule.initialTemperature = value
        }

    /**
     * Represents the temperature threshold at which the simulated annealing algorithm will stop iterating.
     * The stopping temperature serves as a termination criterion, ensuring the optimization process concludes
     * when the system has sufficiently cooled.
     *
     * This value must always be positive. An exception will be thrown if a non-positive temperature is set.
     *
     * @property stoppingTemperature The target temperature below which the optimization process should stop.
     *                                Must be greater than 0.0.
     * @throws IllegalArgumentException if a value less than or equal to 0.0 is assigned.
     */
    var stoppingTemperature: Double = stoppingTemperature
        set(value) {
            require(value > 0.0) { "The final temperature must be positive" }
            field = value
        }

    /**
     * Represents the current temperature in the simulated annealing process.
     * It is initialized to the value of `initialTemperature` and dynamically
     * updated during each iteration of the algorithm based on the cooling schedule.
     *
     * The temperature is used to control the probability of accepting worse solutions
     * as the optimization progresses. A higher temperature allows more flexibility
     * in solution acceptance, promoting exploration, while a lower temperature
     * emphasizes exploitation.
     *
     * This property is private-set, meaning it can only be modified internally
     * within the class, ensuring controlled updates adhering to the optimization logic.
     */
    var currentTemperature = initialTemperature
        private set

    /**
     * Tracks the last computed acceptance probability in the simulated annealing process.
     *
     * This value represents the probability of accepting a new solution during the most recent
     * iteration of the algorithm, based on the current temperature and the difference in cost
     * between the current and new solutions. It is initially set to 1.0 and is updated internally
     * during each iteration.
     *
     * The property is immutable from outside the class to ensure the integrity of the algorithm's
     * state. It serves as a diagnostic tool for understanding the behavior of the acceptance
     * step in the optimization process.
     */
    var lastAcceptanceProbability = 1.0
        private set

    /**
     * Represents the difference in cost between the current solution and a potential new solution
     * in the simulated annealing process. This value directly influences the acceptance probability
     * of new solutions as the algorithm progresses.
     *
     * The value is initialized to `Double.NaN` and updated during the computation of the algorithm.
     * It can only be modified internally within the containing class to ensure controlled updates with
     * calculated values.
     */
    var costDifference: Double = Double.NaN
        private set

    /**
     * Secondary constructor for the SimulatedAnnealing class. This constructor provides a simplified way
     * to initialize the Simulated Annealing algorithm with configurable parameters, while delegating
     * certain default parameters to their respective values or functional objects.
     *
     * @param evaluator An implementation of the EvaluatorIfc interface, used to evaluate candidate solutions.
     * @param initialTemperature The starting temperature for the simulated annealing process. Must be a positive value.
     * @param coolingSchedule Defines the cooling mechanism to reduce the temperature during the iteration process.
     * Defaults to an ExponentialCoolingSchedule with the specified initialTemperature.
     * @param stoppingTemperature The temperature at which the simulated annealing process stops. Defaults to 0.001.
     * @param maxIterations The maximum number of iterations for the simulated annealing process. Defaults to a predefined constant.
     * @param replicationsPerEvaluation The number of replications to be performed for each evaluation of the objective function.
     * Ensures robustness in the evaluation process.
     * @param streamNum The stream number used for managing random number generation streams. Defaults to 0.
     * @param streamProvider Provides the random number generator streams. Defaults to the KSLRandom.DefaultRNStreamProvider.
     * @param name An optional name for the simulated annealing process, useful for identification or debugging. Defaults to null.
     */
    @JvmOverloads
    constructor(
        evaluator: EvaluatorIfc,
        initialTemperature: Double = defaultInitialTemperature,
        coolingSchedule: CoolingScheduleIfc = ExponentialCoolingSchedule(initialTemperature),
        stoppingTemperature: Double = defaultStoppingTemperature,
        maxIterations: Int = defaultMaxNumberIterations,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        streamNum: Int = 0,
        streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider,
        name: String? = null
    ) : this(
        evaluator = evaluator,
        initialTemperature = initialTemperature,
        coolingSchedule = coolingSchedule,
        stoppingTemperature = stoppingTemperature,
        maxIterations = maxIterations,
        replicationsPerEvaluation = FixedReplicationsPerEvaluation(replicationsPerEvaluation),
       streamNum, streamProvider, name = name
    )

    /**
     * Calculates the probability of accepting a new solution in the simulated annealing algorithm.
     * The probability is determined based on the difference in cost between the current and new solutions,
     * as well as the current temperature of the system.
     *
     * @param costDifference The difference in cost between the current solution and the new solution.
     *                       A positive value indicates the new solution is worse, and a negative value indicates it is better.
     * @param temperature The current temperature in the simulated annealing process. Must be greater than zero.
     * @return The acceptance probability, which is a value between 0.0 and 1.0. A higher probability indicates a greater likelihood of accepting the new solution.
     *         If `costDifference` is less than or equal to 0, the probability is 1.0. Otherwise, it is calculated as `exp(-costDifference / temperature)`.
     * @throws IllegalArgumentException If the `temperature` is not positive.
     */
    fun acceptanceProbability(costDifference: Double, temperature: Double) : Double {
        require(temperature > 0.0) { "The temperature must be positive" }
        return if (costDifference <= 0.0) {
            1.0
        } else {
            exp(-costDifference / temperature)
        }
    }

    override fun initializeIterations() {
        super.initializeIterations()
        currentTemperature = initialTemperature
        lastAcceptanceProbability = 1.0
        costDifference = Double.NaN
        logger.trace { "Solver: $name : initialized with temperature $currentTemperature" }
    }

    override fun mainIteration() {
        // generate a random neighbor of the current solution
        val nextPoint = nextPoint()
        // evaluate the point to get the next solution
        val nextSolution = requestEvaluation(nextPoint)
        // calculate the cost difference
        costDifference = nextSolution.penalizedObjFncValue - currentSolution.penalizedObjFncValue
        // if the cost difference is negative, it is automatically an acceptance
        if (costDifference < 0.0) {
            // no need to generate a random variate
            currentSolution = nextSolution
            lastAcceptanceProbability = 1.0
            logger.trace { "Solver: $name : solution improved to $nextSolution" }
        } else {
            // decide whether an increased cost should be accepted
            val u = rnStream.randU01()
            lastAcceptanceProbability = acceptanceProbability(costDifference, currentTemperature)
            if (u < lastAcceptanceProbability) {
                currentSolution = nextSolution
                logger.trace { "Solver: $name : non-improving solution was accepted: $nextSolution" }
            } else {
                // stay at the current solution
                logger.trace { "Solver: $name : rejected solution $nextSolution" }
            }
        }
        // update the current temperature based on the cooling schedule
        currentTemperature = coolingSchedule.nextTemperature(iterationCounter)
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: checkTemperature()
    }

    private fun checkTemperature() : Boolean {
        return currentTemperature < stoppingTemperature
    }

    override fun toString(): String {
        val sb = StringBuilder("Simulated Annealing solver with parameters: \n")
        sb.append("Initial temperature: $initialTemperature\n")
        sb.append("Stopping temperature: $stoppingTemperature\n")
        sb.append(super.toString())
        sb.append("Current temperature: $currentTemperature\n")
        sb.append("Last acceptance probability: $lastAcceptanceProbability\n")
        return sb.toString()
    }

    companion object {

        /**
         *  The default initial temperature for the annealing process. The default value is 1000.0
         */
        @JvmStatic
        var defaultInitialTemperature = 1000.0
            set(value) {
                require(value > 0.0) { "The initial temperature must be positive" }
                field = value
            }

        /**
         * The default cooling rate used during the annealing process. The default value is 0.95.
         */
        @JvmStatic
        var defaultCoolingRate = 0.95
            set(value) {
                require(value > 0.0 && value < 1.0) { "The cooling rate must be in (0,1)" }
                field = value
            }

        /**
         * The default temperature used to stop the annealing process. If the current temperature goes
         *  * below this temperature, the search process stops. The default value is 0.001.
         */
        @JvmStatic
        var defaultStoppingTemperature = 0.001
            set(value) {
                require(value > 0.0) { "The default stopping temperature must be positive" }
                field = value
            }

    }


}