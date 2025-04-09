package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess

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

    private inner class SolverProcess(name: String?) : IterativeProcess<SolverProcess> (name) {
        override fun hasNextStep(): Boolean {
            TODO("Not yet implemented")
        }

        override fun nextStep(): SolverProcess? {
            TODO("Not yet implemented")
        }

        override fun runStep() {
            TODO("Not yet implemented")
        }

    }
}