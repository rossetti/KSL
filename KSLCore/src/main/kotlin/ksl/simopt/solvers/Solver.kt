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
    evaluator: Evaluator,
    name: String? = null
): IdentityIfc by Identity(name) {

    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
    }

    private val mySolverIterativeProcess = SolverIterativeProcess()

    internal var myEvaluator: Evaluator = evaluator

    internal val myOriginalEvaluator = evaluator

    var maximumIterations = maximumIterations
        set(value) {
            require(value > 0) { "maximum number of iterations must be > 0" }
            field = value
        }

    var iterationCounter = 0
        private set

    val problemDefinition: ProblemDefinition
        get() = myEvaluator.problemDefinition

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

    protected abstract fun hasMoreIterations(): Boolean

    protected abstract fun runIteration()

    protected abstract fun afterIterations()

    private inner class SolverIterativeProcess : IterativeProcess<SolverIterativeProcess>("${name}:SolverIterativeProcess") {
        //TODO add some logging

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            this@Solver.initializeIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreIterations()
        }

        override fun nextStep(): SolverIterativeProcess? {
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