package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition

class SolverRunner(
    private val evaluator: Evaluator,
    val solvers: List<Solver>
) {
    //TODO

    private val myBestObjByIteration = mutableListOf<Double>()
    private val mySolverStatus = mutableListOf<String>()

    val problemDefinition: ProblemDefinition
        get() = evaluator.problemDefinition

    fun bestSolution(): Solution {
        TODO("Not implemented yet!")
    }
}