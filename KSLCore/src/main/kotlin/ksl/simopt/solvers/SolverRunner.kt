package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess

class SolverRunner(
    maximumIterations: Int,
    private val evaluator: Evaluator,
    val solvers: Set<Solver>
) {
    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
        require(solvers.isNotEmpty()) { "solvers must have at least one solver" }
    }

    private val mySolverIterativeProcess = SolverRunnerIterativeProcess()

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

    fun initialize() {
        mySolverIterativeProcess.initialize()
    }

    fun hasNextIteration(): Boolean {
        return mySolverIterativeProcess.hasNextStep()
    }

    fun runNextIteration(){
        mySolverIterativeProcess.runNext()
    }

    fun runAllIterations(){
        mySolverIterativeProcess.run()
    }

    fun stopIterations(){
        mySolverIterativeProcess.stop()
    }

    fun endIterations(){
        mySolverIterativeProcess.end()
    }

    private fun initializeSolvers(){
        for(solver in solvers) {
            TODO("Not yet implemented")
           // solver.initialize()
        }
    }

    private fun afterRunning() {
        TODO("Not yet implemented")
    }

    private inner class SolverRunnerIterativeProcess : IterativeProcess<Nothing>("SolverRunnerIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            initializeSolvers()
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