package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.CompareSolutionsIfc
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

//TODO needs a lot more work
abstract class Solver(
    maximumOuterIterations: Int,
    maximumInnerIterations: Int,
    var replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    evaluator: EvaluatorIfc,
    name: String? = null
): IdentityIfc by Identity(name), CompareSolutionsIfc {

    init {
        require(maximumOuterIterations > 0) { "maximum number of outer iterations must be > 0" }
        require(maximumInnerIterations > 0) { "maximum number of inner iterations must be > 0" }
    }

    protected val myOuterIterativeProcess = OuterIterativeProcess()
    protected val myInnerIterativeProcess = InnerIterativeProcess()

    internal var mySolverRunner: SolverRunner? = null

    private var myEvaluator: EvaluatorIfc = evaluator

    var solutionComparer: CompareSolutionsIfc? = null

    var maximumOuterIterations = maximumOuterIterations
        set(value) {
            require(value > 0) { "maximum number of outer iterations must be > 0" }
            field = value
        }

    var maximumInnerIterations = maximumInnerIterations
        set(value) {
            require(value > 0) { "maximum number of inner iterations must be > 0" }
            field = value
        }

    var outerIterationCounter = 0
        private set

    var innerIterationCounter = 0
        private set

    val problemDefinition: ProblemDefinition
        get() = myEvaluator.problemDefinition

    protected var currentSolution: Solution? = null

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
     * Recognizing the need to be able to compare solutions that may have sampling error
     * the user can override this function to provide more extensive comparison or supply
     * an instance of the CompareSolutionsIfc interface via the solutionComparer property
     * Returns -1 if first is less than second solution, 0 if the solutions are to be considered
     * equivalent, and 1 if the first is larger than the second solution.
     *
     * @param first the first solution within the comparison
     * @param second the second solution within the comparison
     */
    override fun compare(first: Solution, second: Solution) : Int {
        return solutionComparer?.compare(first, second) ?: first.compareTo(second)
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
    protected open fun hasMoreOuterIterations(): Boolean{
        return (outerIterationCounter < maximumOuterIterations) || !myOuterIterativeProcess.isDone
    }


    /**
     *  This function is called before the inner iterations are
     *  initialized and executed.
     */
    protected open fun beforeInnerIterations(){

    }

    /**
     *  This function is called after the inner iterations are
     *  initialized, executed, and ended and before returning
     *  to the outer loop
     */
    protected open fun afterInnerIterations(){

    }

    /**
     *  Subclasses should implement this function to clean up after
     *  running iterations.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected abstract fun outerIterationsEnded()

    /**
     *  Subclasses should implement this function to prepare the solver
     *  prior to running the first inner iteration.
     */
    protected abstract fun initializeInnerIterations()

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running inner iterations.
     */
    protected abstract fun hasMoreInnerIterations(): Boolean

    /**
     *  Subclasses should implement this function to specify the logic
     *  associated with the inner iterations.
     */
    protected abstract fun innerIteration()

    /**
     *  Subclasses should implement this function to clean up after
     *  the inner iterations have ended.
     */
    protected open fun innerIterationsEnded() {

    }

    /**
     *  Subclasses should implement this function to prepare requests for
     *  evaluation by the simulation oracle as part of running the iteration.
     */
    protected abstract fun prepareEvaluationRequests() : List<EvaluationRequest>

    protected fun requestEvaluations(requests: List<EvaluationRequest>) : List<Solution> {
       return mySolverRunner?.receiveEvaluationRequests(this, requests) ?: myEvaluator.evaluate(requests)
    }

    protected inner class OuterIterativeProcess : IterativeProcess<OuterIterativeProcess>("${name}:SolverOuterIterativeProcess") {
        //TODO add some logging

        override fun initializeIterations() {
            super.initializeIterations()
            outerIterationCounter = 0
            logger.info { "Resetting solver $name's evaluation counters in solver $name" }
            mySolverRunner?.resetEvaluator() ?: myEvaluator.resetEvaluationCounts()
            logger.info { "Initializing solver $name's outer iteration loop" }
            this@Solver.initializeOuterIterations()
            logger.info { "Initialized solver $name's outer iteration loop" }
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
            logger.info { "Running: iteration = $outerIterationCounter of solver $name's main iteration loop" }
            beforeInnerIterations()
            myInnerIterativeProcess.run()
            afterInnerIterations()
            logger.info { "Completed: iteration = $outerIterationCounter of $maximumOuterIterations iterations of solver $name's main iteration loop" }
            outerIterationCounter++
        }

        override fun endIterations() {
            logger.info { "Cleaning up after: iteration = $outerIterationCounter of $maximumOuterIterations" }
            outerIterationsEnded()
            logger.info { "Cleaned up after: iteration = $outerIterationCounter of $maximumOuterIterations" }
            super.endIterations()
            logger.info { "Ended: solver $name's main iteration loop" }
        }

    }

    protected inner class InnerIterativeProcess : IterativeProcess<InnerIterativeProcess>("${name}:SolverInnerIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            innerIterationCounter = 0
            logger.info { "Initializing solver $name's inner iteration loop" }
            this@Solver.initializeInnerIterations()
            logger.info { "Initialized solver $name's inner iteration loop" }
        }

        override fun hasNextStep(): Boolean {
            return hasMoreInnerIterations()
        }

        override fun nextStep(): InnerIterativeProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            logger.info { "Running: inner iteration = $innerIterationCounter of solver $name's inner iteration loop" }
            innerIteration()
            logger.info { "Completed: inner iteration = $innerIterationCounter of $maximumInnerIterations iterations of solver $name's main iteration loop" }
            innerIterationCounter++
        }

        override fun endIterations() {
            logger.info { "Cleaning up after: inner iteration = $innerIterationCounter of $maximumInnerIterations" }
            innerIterationsEnded()
            logger.info { "Cleaned up after: inner iteration = $innerIterationCounter of $maximumInnerIterations" }
            super.endIterations()
            logger.info { "Ended: solver $name's inner iteration loop" }
        }

    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}