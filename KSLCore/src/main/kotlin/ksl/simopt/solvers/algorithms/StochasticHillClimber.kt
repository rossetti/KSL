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


    override fun initializeIterations() {
        val initialPoint = problemDefinition.startingPoint(rnStream)
        initialSolution = requestEvaluation(initialPoint)
        currentSolution = initialSolution
        println("Initial solution = $currentSolution")
    }

    override fun mainIteration() {
        // generate a random neighbor of the current solution
        val currentPoint = currentSolution.inputMap
        val nextPoint = generateNeighbor(currentPoint, rnStream)
        println()
        println("Iteration = $iterationCounter : Next point = $nextPoint")
        // evaluate the solution
        val nextSolution = requestEvaluation(nextPoint)
        println("Iteration = $iterationCounter : next solution = $nextSolution")
        // If the new solution is better (smaller) update the current solution
        if (compare(nextSolution, currentSolution) < 0){
            currentSolution = nextSolution
            // improvement occurred
            println("Iteration = $iterationCounter : Improvement")
        } else {
            println("Iteration = $iterationCounter : No improvement")
        }
        //TODO()
    }

    override fun isStoppingCriteriaSatisfied(): Boolean {
        return false
    }


}