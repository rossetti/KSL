package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.InputMap
import ksl.simopt.solvers.FixedReplicationsPerEvaluation
import ksl.simopt.solvers.GenerateNeighborIfc
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom


open class StochasticHillClimber(
    evaluator: EvaluatorIfc,
    maxIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    val rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
    name: String? = null
) : Solver(evaluator, maxIterations, replicationsPerEvaluation, name), RNStreamControlIfc by rnStream {

    constructor(
        evaluator: EvaluatorIfc,
        maxIterations: Int,
        replicationsPerEvaluation: Int,
        rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
        name: String? = null
    ) : this(evaluator, maxIterations, FixedReplicationsPerEvaluation(replicationsPerEvaluation), rnStream, name)

    override fun startingPoint(): InputMap {
        return problemDefinition.startingPoint(rnStream)
    }

    override fun nextPoint(): InputMap {
        return generateNeighbor(currentPoint, rnStream)
    }

}