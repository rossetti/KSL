package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver


class RestartingStochasticHillClimber(
    maximumIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    evaluator: EvaluatorIfc,
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