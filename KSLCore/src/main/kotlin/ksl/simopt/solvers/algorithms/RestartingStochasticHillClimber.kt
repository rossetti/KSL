package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.ReplicationPerEvaluationIfc
import ksl.simopt.solvers.Solver
import ksl.utilities.random.rng.RNStreamControlIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rvariable.KSLRandom


class RestartingStochasticHillClimber(
    evaluator: EvaluatorIfc,
    maxEvaluations: Int,
    maxIterations: Int = 1,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    val rnStream: RNStreamIfc = KSLRandom.defaultRNStream(),
    name: String? = null
) : Solver(evaluator, maxIterations, replicationsPerEvaluation, name), RNStreamControlIfc by rnStream {
    init {
        require(maxEvaluations > 0) { "maximum number of evaluations must be > 0" }
    }

    /**
     *  The maximum number of iterations permitted for the main loop. This must be
     *  greater than 0.
     */
    var maxEvaluations = maxEvaluations
        set(value) {
            require(value > 0) { "maximum number of evaluations must be > 0" }
            field = value
        }

    override fun initializeIterations() {
        val initialPoint = problemDefinition.startingPoint(rnStream)
        initialSolution = requestEvaluation(initialPoint)
        currentSolution = initialSolution
    }

    override fun mainIteration() {
        for (i in 1..maxEvaluations) {
            // pick point in neighborhood

            // evaluate the solution

            // if new solution is better (smaller) update the current solution
        }
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return false
    }


}