package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.solvers.ReplicationPerEvaluationIfc

class CrossEntropySolver(
    evaluator: EvaluatorIfc,
    maxIterations: Int = defaultMaxNumberIterations,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    ceSampler: CESamplerIfc,
    name: String? = null
) : StochasticSolver(evaluator, maxIterations,
    replicationsPerEvaluation, ceSampler.streamNumber,
    ceSampler.streamProvider, name){


    override fun mainIteration() {
        TODO("Not yet implemented")
    }


}