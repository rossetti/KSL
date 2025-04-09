package ksl.simopt.solvers

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

//TODO needs a lot more work
abstract class Solver(
    maximumIterations: Int,
    private val evaluator: Evaluator,
    name: String? = null
): IdentityIfc by Identity(name) {

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

    protected abstract fun initializeIterations()

    protected abstract fun runIteration()

    protected abstract fun afterIterations()

    protected abstract fun hasMoreIterations(): Boolean

    private inner class SolverIterativeProcess : IterativeProcess<Nothing>("${name}:SolverIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            this@Solver.initializeIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreIterations()
        }

        override fun nextStep(): Nothing? {
            TODO("Not yet implemented")
        }

        override fun runStep() {
            runIteration()
            iterationCounter++
        }

        override fun endIterations() {
            afterIterations()
            super.endIterations()
        }

    }
}