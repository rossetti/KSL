package ksl.simopt.solvers.algorithms

import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.problem.InputMap
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

    override fun initializeIterations() {
        val initialPoint = problemDefinition.startingPoint(rnStream)
        initialSolution = requestEvaluation(initialPoint)
        currentSolution = initialSolution
    }

    override fun mainIteration() {
        // generate a random neighbor of the current solution
        val currentPoint = currentSolution.inputMap
        val nextPoint = generateNeighbor(currentPoint, rnStream)
        // evaluate the solution
        val nextSolution = requestEvaluation(nextPoint)
        // if new solution is better (smaller) update the current solution

    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return false
    }


}