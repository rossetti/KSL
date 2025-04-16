package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom


class RestartingStochasticHillClimber(
    maximumIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    evaluator: EvaluatorIfc,
    var rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
    name: String? = null
) : Solver(maximumIterations, replicationsPerEvaluation, evaluator, name) {

    override fun currentSolution(): Solution {
        TODO("Not yet implemented")
    }

    override fun initializeIterations() {
        TODO("Not yet implemented")
    }

    override fun mainIteration() {
        TODO("Not yet implemented")
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        TODO("Not yet implemented")
    }

    override fun prepareEvaluationRequests(): List<EvaluationRequest> {
        TODO("Not yet implemented")
    }
}