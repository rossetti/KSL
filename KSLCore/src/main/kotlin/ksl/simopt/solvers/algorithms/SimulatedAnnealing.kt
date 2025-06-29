package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom
import kotlin.math.exp

//TODO initial temperature, cooling schedule, override stopping criteria

class SimulatedAnnealing(
    evaluator: EvaluatorIfc,
    initialTemperature: Double,
    var coolingSchedule: CoolingScheduleIfc = ExponentialCoolingSchedule(initialTemperature),
    finalTemperature: Double = 0.001,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
    name: String? = null
) : StochasticSolver(evaluator, maxIterations, replicationsPerEvaluation, rnStream, name) {

    init {
        require(initialTemperature > 0.0) { "The initial temperature must be positive" }
        require(finalTemperature > 0.0) { "The final temperature must be positive" }
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

    var finalTemperature: Double = finalTemperature
        set(value) {
            require(value > 0.0) { "The final temperature must be positive" }
            field = value
        }

    var currentTemperature = initialTemperature
        private set

    constructor(
        evaluator: EvaluatorIfc,
        initialTemperature: Double,
        coolingSchedule: CoolingScheduleIfc = ExponentialCoolingSchedule(initialTemperature),
        finalTemperature: Double = 0.001,
        maxIterations: Int = defaultMaxNumberIterations,
        replicationsPerEvaluation: Int,
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String? = null
    ) : this(
        evaluator = evaluator,
        initialTemperature = initialTemperature,
        coolingSchedule = coolingSchedule,
        finalTemperature = finalTemperature,
        maxIterations = maxIterations,
        replicationsPerEvaluation = FixedReplicationsPerEvaluation(replicationsPerEvaluation),
        rnStream = rnStream, name = name
    )

    fun acceptanceProbability(costDifference: Double, temperature: Double) : Double {
        require(temperature > 0.0) { "The temperature must be positive" }
        return if (costDifference <= 0.0) {
            1.0
        } else {
            exp(-costDifference / temperature)
        }
    }

    override fun mainIteration() {
        // generate a random neighbor of the current solution
        val nextPoint = nextPoint()
        // evaluate the point to get the next solution
        val nextSolution = requestEvaluation(nextPoint)
        // calculate the cost difference
        val costDifference = nextSolution.penalizedObjFncValue - currentSolution.penalizedObjFncValue
        // if the cost difference is negative, it is automatically an acceptance
        if (costDifference < 0.0) {
            // no need to generate a random variate
            currentSolution = nextSolution
            logger.trace { "Solver: $name : solution improved to $nextSolution" }
        } else {
            // decide whether an increased cost should be accepted
            val u = rnStream.randU01()
            if (u < acceptanceProbability(costDifference, currentTemperature)) {
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
        return currentTemperature < finalTemperature
    }


}