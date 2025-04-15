package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.CompareSolutionsIfc
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess
import ksl.simulation.IterativeProcessStatusIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

/**
 *  A solver is an iterative algorithm that searches for the optimal solution to a defined problem.
 *  In this implementation, the algorithm is conceptualized as having two "loops", an outer loop
 *  and an inner loop. The outer loop is the main loop that ultimately determines the convergence
 *  and recommended solution.  While some algorithms do not utilize an inner loop, in general, the
 *  inner loop is used to control localized search for solutions.  If there is no "inner loop" then
 *  the structure of this abstract template allows multiple approaches to not executing an inner loop.
 *  In addition, if an algorithm has additional iterative loops, these can be embedded within the inner
 *  loop via the subclassing process.
 *
 *  Specialized implementations may have specific methods for determining stopping criteria; however,
 *  to avoid the execution of a large number of iterations, the iterative processes have a maximum
 *  number of iterations associated with them.  Within the context of simulation optimization, the
 *  supplied evaluator promises to execute requests for evaluations of the simulation model at
 *  particular design points (as determined by the algorithm). In addition, because of the stochastic
 *  nature of the evaluation, the solver may request one or more replications for its evaluation requests.
 *  The number of replications may dynamically change and thus the use needs to supply a function to
 *  determine the number of replications per evaluation.  Within the framework of the hooks for subclasses
 *  the user could specify more complex procedures for determining the number of replications per
 *  evaluation.
 *
 *  @param maximumOuterIterations the maximum number of iterations permitted for the outer loop. This must be
 *  greater than 0.
 *  @param maximumInnerIterations the maximum number of iterations permitted for the outer loop. This must be
 *  *  greater than 0.
 *  @param replicationsPerEvaluation the function controlling how many replications are requested for each evaluation
 *  @param evaluator the reference to the evaluator for evaluating responses from the model
 *  @param name a name to help with identifying the solver when multiple solvers are used on a problem
 */
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

    /**
     *  The outer iterative process. See [ksl.simulation.IterativeProcess] for
     *  the iterative process pattern.
     */
    private val myOuterIterativeProcess = OuterIterativeProcess()

    /**
     *  Allow the status of the outer iterative process to be accessible
     */
    val outerIterativeProcess: IterativeProcessStatusIfc
        get() = myOuterIterativeProcess

    /**
     *  The inner iterative process. See [ksl.simulation.IterativeProcess] for
     *  the iterative process pattern.
     */
    private val myInnerIterativeProcess = InnerIterativeProcess()

    /**
     *  Allow the status of the inner iterative process to be accessible
     */
    val innerIterativeProcess: IterativeProcessStatusIfc
        get() = myInnerIterativeProcess

    /**
     *  A solver may be controlled by a solver runner with other solvers.
     *  This provides an internal reference to solver runner
     */
    internal var mySolverRunner: SolverRunner? = null

    /**
     *  The evaluator used by the solver.
     */
    protected var myEvaluator: EvaluatorIfc = evaluator
        private set

    /**
     *  The user can supply a comparator for comparing whether one
     *  solution is smaller, equal to, or larger than another solution.
     *  If supplied, this function will be used instead of the compare
     *  function. The user can supply this function or override the
     *  compare function to specialize how solutions are compared.
     */
    var solutionComparer: CompareSolutionsIfc? = null

    /**
     *  The maximum number of iterations permitted for the outer loop. This must be
     *  greater than 0.
     */
    var maximumOuterIterations = maximumOuterIterations
        set(value) {
            require(value > 0) { "maximum number of outer iterations must be > 0" }
            field = value
        }

    /**
     *  The maximum number of iterations permitted for the inner loop. This must be
     *  greater than 0.
     */
    var maximumInnerIterations = maximumInnerIterations
        set(value) {
            require(value > 0) { "maximum number of inner iterations must be > 0" }
            field = value
        }

    /**
     *  Returns how many iterations of the outer loop have been executed.
     */
    var outerIterationCounter = 0
        private set

    /**
     *  Returns how many iterations of the inner loop have been executed.
     */
    var innerIterationCounter = 0
        private set

    /**
     *  A convenience property to access the problem being solved
     */
    val problemDefinition: ProblemDefinition
        get() = myEvaluator.problemDefinition


    /**
     *  The current (or last) solution that was accepted as a possible
     *  solution to recommend for the solver. It is the responsibility
     *  of the subclass to initialize the initial solution.
     */
    protected lateinit var initialSolution: Solution

    /**
     *  Causes the solver to be initialized. It will then
     *  be in a state that allows for the running of the iterations.
     */
    fun initialize() {
        myOuterIterativeProcess.initialize()
    }

    /**
     *  Checks if the iterative process has additional iterations to execute
     */
    fun hasNextIteration(): Boolean {
        return myOuterIterativeProcess.hasNextStep()
    }

    /**
     *  Runs the next iteration. Only valid if the solver has been
     *  initialized and there are additional iterations to run.
     */
    fun runNextIteration(){
        myOuterIterativeProcess.runNext()
    }

    /**
     *   Cause the solver to run all iterations until its stopping
     *   criteria is met or the maximum number of iterations has been reached.
     */
    fun runAllIterations(){
        myOuterIterativeProcess.run()
    }

    /**
     *  Causes a graceful stopping of the iterative processes of the solver.
     *  The inner process will complete its current iteration and then
     *  no more outer iterations will start.
     */
    fun stopIterations(msg: String? = null){
        myInnerIterativeProcess.stop(msg)
        myOuterIterativeProcess.stop(msg)
    }

    /**
     *  Note that the iterations can only be ended before running all iterations or
     *  before running the next iteration. Use stopIterations() to cause a graceful
     *  completion of inner and outer iterations.
     *  @param msg a message to capture for why the iterations were ended
     */
    fun endIterations(msg: String? = null){
        myOuterIterativeProcess.end(msg)
    }

    /**
     * Recognizing the need to be able to compare solutions that may have sampling error
     * the user can override this function to provide more extensive comparison or supply
     * an instance of the [CompareSolutionsIfc] interface via the [solutionComparer] property
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
     *  The current (or last) solution that was accepted as a possible
     *  solution to recommend for the solver. It is the responsibility
     *  of the subclass to determine the current solution
     */
    abstract fun currentSolution(): Solution

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
    protected open fun hasMoreIterations(): Boolean{
        //TODO need to check this, maybe stopping criteria can be used instead
        return (outerIterationCounter < maximumOuterIterations)
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
     *  to the outer loop of iterations
     */
    protected open fun afterInnerIterations(){

    }

    /**
     *  Subclasses should implement this function to clean up after
     *  running all iterations.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected open fun outerIterationsEnded(){

    }

    /**
     *  Subclasses should implement this function to prepare the solver
     *  prior to running the first inner iteration.
     */
    protected open fun initializeInnerIterations(){

    }

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running inner iterations.
     */
    protected open fun hasMoreInnerIterations(): Boolean {
        //TODO need to check this, maybe stopping criteria can be used instead
        return (innerIterationCounter < maximumInnerIterations)
    }

    /**
     *  Subclasses should implement this function to specify the logic
     *  associated with the inner iterations.
     */
    protected open fun innerIteration(){

    }

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
            this@Solver.initializeIterations()
            logger.info { "Initialized solver $name's outer iteration loop" }
        }

        override fun hasNextStep(): Boolean {
            return (outerIterationCounter < maximumOuterIterations)
        }

        override fun checkStoppingCondition() {
            TODO("Not implemented yet")
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
            if (myInnerIterativeProcess.isRunning){
                logger.info { "Stopping the inner iterations of $name's because the main iterations have been ended." }
                myInnerIterativeProcess.stop("Stopping the inner iterations because the outer iterations have been ended.")
            }
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
            return (innerIterationCounter < maximumInnerIterations)
        }

        override fun checkStoppingCondition() {
            TODO("Not implemented yet")
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