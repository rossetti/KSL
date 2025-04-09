package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess

class SolverRunner(
    maximumIterations: Int,
    private val evaluator: Evaluator,
    val solvers: List<Solver>
) {
    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
    }

    private val mySolverIterativeProcess = SolverIterativeProcess()

    var maximumIterations = maximumIterations
        set(value) {
            require(value > 0) { "maximum number of iterations must be > 0" }
            field = value
        }

    var iterationCounter = 0
        private set

    val problemDefinition: ProblemDefinition
        get() = evaluator.problemDefinition

    //TODO

    private fun initialize(){

    }

    private fun afterRunning() {

    }

    private inner class SolverIterativeProcess : IterativeProcess<Nothing>("SolverRunner") {

        override fun initializeIterations() {
            super.initializeIterations()
            initialize()
        }

        override fun hasNextStep(): Boolean {
            TODO("Not yet implemented")
        }

        override fun nextStep(): Nothing? {
            TODO("Not yet implemented")
        }

        override fun runStep() {
            TODO("Not yet implemented")
        }

        override fun endIterations() {
            super.endIterations()
            afterRunning()
        }

    }
}