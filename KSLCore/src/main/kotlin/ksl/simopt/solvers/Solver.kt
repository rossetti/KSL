package ksl.simopt.solvers

import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

//TODO needs a lot more work
abstract class Solver(
    maximumIterations: Int,
    numReplicationsPerEvaluation: Int,
    evaluator: EvaluatorIfc,
    name: String? = null
): IdentityIfc by Identity(name) {

    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
        require(numReplicationsPerEvaluation > 0) { "The number of replications requested for each evaluation must be > 0" }
    }

    private val mySolverIterativeProcess = SolverIterativeProcess()

    internal var mySolverRunner: SolverRunner? = null

    private var myEvaluator: EvaluatorIfc = evaluator

    var maximumIterations = maximumIterations
        set(value) {
            require(value > 0) { "maximum number of iterations must be > 0" }
            field = value
        }

    var numReplicationsPerEvaluation = numReplicationsPerEvaluation
        set(value) {
            require(value > 0) { "The number of replications requested for each evaluation must be > 0" }
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

    /**
     *  Subclasses should implement this function to prepare the solver
     *  prior to running the first iteration.
     */
    protected abstract fun initializeIterations()

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running iterations. This will likely include some
     *  implementation of stopping criteria.
     */
    protected abstract fun hasMoreIterations(): Boolean

    /**
     *  Subclasses should implement this function to run a single
     *  iteration of the solver. In general, an iteration may have many
     *  sub-steps, but for the purposes of the framework an iteration
     *  of the solver performs some sampling via the evaluator and
     *  evaluates the quality of the solutions with the identification of
     *  the current best.
     */
    protected abstract fun runIteration()

    /**
     *  Subclasses should implement this function to clean up after
     *  running iterations.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected abstract fun afterIterations()

    /**
     *  Subclasses should implement this function to prepare requests for
     *  evaluation by the simulation oracle as part of running the iteration.
     */
    protected abstract fun prepareEvaluationRequests() : List<EvaluationRequest>

    protected fun requestEvaluations(requests: List<EvaluationRequest>) : List<Solution> {
       return mySolverRunner?.receiveEvaluationRequests(this, requests) ?: myEvaluator.evaluate(requests)
    }

    private inner class SolverIterativeProcess : IterativeProcess<SolverIterativeProcess>("${name}:SolverIterativeProcess") {
        //TODO add some logging

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            mySolverRunner?.resetEvaluator() ?: myEvaluator.resetEvaluationCounts()
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