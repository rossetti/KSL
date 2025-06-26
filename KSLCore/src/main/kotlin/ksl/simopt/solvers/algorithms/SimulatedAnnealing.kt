package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom

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

}