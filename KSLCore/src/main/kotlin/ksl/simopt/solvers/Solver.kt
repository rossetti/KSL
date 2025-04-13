package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

//TODO needs a lot more work
abstract class Solver(
    maximumIterations: Int,
    replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    evaluator: EvaluatorIfc,
    name: String? = null
): IdentityIfc by Identity(name) {

    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
    }

    private val myOuterIterativeProcess = OuterIterativeProcess()
    private val myInnerIterativeProcess = InnerIterativeProcess()

    internal var mySolverRunner: SolverRunner? = null

    private var myEvaluator: EvaluatorIfc = evaluator

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
        myOuterIterativeProcess.initialize()
    }

    fun hasNextIteration(): Boolean {
        return myOuterIterativeProcess.hasNextStep()
    }

    fun runNextIteration(){
        myOuterIterativeProcess.runNext()
    }

    fun runAllIterations(){
        myOuterIterativeProcess.run()
    }

    fun stopIterations(){
        myOuterIterativeProcess.stop()
    }

    fun endIterations(){
        myOuterIterativeProcess.end()
    }

    /**
     *  Subclasses should implement this function to prepare the solver
     *  prior to running the first iteration.
     */
    protected abstract fun initializeOuterIterations()

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running iterations. This will likely include some
     *  implementation of stopping criteria.
     */
    protected abstract fun hasMoreOuterIterations(): Boolean

    /**
     *  Subclasses should implement this function to run a single
     *  iteration of the solver. In general, an iteration may have many
     *  sub-steps, but for the purposes of the framework an iteration
     *  of the solver performs some sampling via the evaluator and
     *  evaluates the quality of the solutions with the identification of
     *  the current best.
     */
    protected abstract fun runOuterIteration()

    /**
     *  Subclasses should implement this function to clean up after
     *  running iterations.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected abstract fun afterOuterIterations()

    /**
     *  Subclasses should implement this function to prepare the solver
     *  prior to running the first inner iteration.
     */
    protected abstract fun initializeInnerIterations()

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running iterations. This will likely include some
     *  implementation of stopping criteria.
     */
    protected abstract fun hasMoreInnerIterations(): Boolean

    /**
     *  Subclasses should implement this function to run a single
     *  iteration of the solver. In general, an iteration may have many
     *  sub-steps, but for the purposes of the framework an iteration
     *  of the solver performs some sampling via the evaluator and
     *  evaluates the quality of the solutions with the identification of
     *  the current best.
     */
    protected abstract fun runInnerIteration()

    /**
     *  Subclasses should implement this function to clean up after
     *  running iterations.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected abstract fun afterInnerIterations()

    /**
     *  Subclasses should implement this function to prepare requests for
     *  evaluation by the simulation oracle as part of running the iteration.
     */
    protected abstract fun prepareEvaluationRequests() : List<EvaluationRequest>

    protected fun requestEvaluations(requests: List<EvaluationRequest>) : List<Solution> {
       return mySolverRunner?.receiveEvaluationRequests(this, requests) ?: myEvaluator.evaluate(requests)
    }

    private inner class OuterIterativeProcess : IterativeProcess<OuterIterativeProcess>("${name}:SolverOuterIterativeProcess") {
        //TODO add some logging

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            logger.info { "Resetting the solver's evaluation counters" }
            mySolverRunner?.resetEvaluator() ?: myEvaluator.resetEvaluationCounts()
            this@Solver.initializeOuterIterations()
        }

        override fun hasNextStep(): Boolean {
            return hasMoreOuterIterations()
        }

        override fun nextStep(): OuterIterativeProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            runOuterIteration()
            iterationCounter++
        }

        override fun endIterations() {
            afterOuterIterations()
            super.endIterations()
        }

    }

    private inner class InnerIterativeProcess : IterativeProcess<InnerIterativeProcess>("${name}:SolverInnerIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            TODO("Not yet implemented")
        }

        override fun hasNextStep(): Boolean {
            TODO("Not yet implemented")
        }

        override fun nextStep(): InnerIterativeProcess? {
            TODO("Not yet implemented")
        }

        override fun runStep() {
            TODO("Not yet implemented")
        }

        override fun endIterations() {
            super.endIterations()
            TODO("Not yet implemented")
        }

    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}