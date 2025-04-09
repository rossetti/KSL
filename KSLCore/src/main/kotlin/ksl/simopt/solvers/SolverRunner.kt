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

    private fun initializeIterations(){
        TODO("Not yet implemented")
    }

    private fun runIteration(){
        TODO("Not yet implemented")
    }

    private fun afterIterations(){
        TODO("Not yet implemented")
    }

    private fun hasMoreIterations(): Boolean{
        TODO("Not yet implemented")
    }

    private inner class SolverRunnerIterativeProcess : IterativeProcess<SolverRunnerIterativeProcess>("SolverRunnerIterativeProcess") {
        //TODO add some logging

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            this@SolverRunner.initializeIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreIterations()
        }

        override fun nextStep(): SolverRunnerIterativeProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            runIteration()
            iterationCounter++
        }

        override fun endIterations() {
            afterIterations()
            super.endIterations()
        }

    }
}