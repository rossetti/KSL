package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.CompareSolutionsIfc
import ksl.simopt.evaluator.EvaluationRequest
import ksl.simopt.evaluator.EvaluatorIfc
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simulation.IterativeProcess
import ksl.simulation.IterativeProcessStatusIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc

/**
 *  A solver is an iterative algorithm that searches for the optimal solution to a defined problem.
 *  In this abstract base class, the algorithm is conceptualized as having a main iterative loop.
 *  The loop is the main loop that ultimately determines the convergence of the algorithm
 *  and recommended solution.  Some algorithms have "inner" loops". In general,
 *  inner loops are used to control localized search for solutions.  If an algorithm has additional inner loops,
 *  these can be embedded within the main loop via the subclassing process.
 *
 *  Specialized implementations may have specific methods for determining stopping criteria; however,
 *  to avoid the execution of a large number of iterations, the iterative process has a specified maximum
 *  number of iterations.
 *
 *  Within the context of simulation optimization, the supplied evaluator promises to execute requests
 *  for evaluations of the simulation model at particular design points (as determined by the algorithm).
 *  In addition, because of the stochastic nature of the evaluation, the solver may request one or more replications
 *  for its evaluation requests. The number of replications may dynamically change and thus the user needs to
 *  supply a function to determine the number of replications per evaluation.  Within the framework of the
 *  hooks for subclasses the user could specify more complex procedures for determining the number of replications per
 *  evaluation.
 *
 *  @param maximumIterations the maximum number of iterations permitted for the main loop. This must be
 *  greater than 0.
 *  @param replicationsPerEvaluation the function controlling how many replications are requested for each evaluation
 *  @param evaluator the reference to the evaluator for evaluating responses from the model
 *  @param name a name to help with identifying the solver when multiple solvers are used on a problem
 */
abstract class Solver(
    maximumIterations: Int,
    var replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    evaluator: EvaluatorIfc,
    name: String? = null
): IdentityIfc by Identity(name), CompareSolutionsIfc {

    init {
        require(maximumIterations > 0) { "maximum number of outer iterations must be > 0" }
    }

    /**
     *  The outer iterative process. See [ksl.simulation.IterativeProcess] for
     *  the iterative process pattern.
     */
    private val myMainIterativeProcess = MainIterativeProcess()

    /**
     *  Allow the status of the outer iterative process to be accessible
     */
    val iterativeProcess: IterativeProcessStatusIfc
        get() = myMainIterativeProcess

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
     *  If supplied, this function will be used instead of the implemented compare()
     *  function. The user can supply a function or override the
     *  compare function to specialize how solutions are compared.
     */
    var solutionComparer: CompareSolutionsIfc? = null

    /**
     *  The maximum number of iterations permitted for the main loop. This must be
     *  greater than 0.
     */
    var maximumNumberIterations = maximumIterations
        set(value) {
            require(value > 0) { "maximum number of main iterations must be > 0" }
            field = value
        }

    /**
     *  Returns how many iterations of the main loop have been executed.
     */
    var iterationCounter = 0
        private set

    /**
     *  A convenience property to access the problem being solved
     */
    val problemDefinition: ProblemDefinition
        get() = myEvaluator.problemDefinition


    /**
     *  The initial starting solution for the algorithm. It is the responsibility
     *  of the subclass to initialize the initial solution.
     */
    protected lateinit var initialSolution: Solution

    /**
     *  Causes the solver to be initialized. It will then
     *  be in a state that allows for the running of the iterations.
     */
    fun initialize() {
        myMainIterativeProcess.initialize()
    }

    /**
     *  Checks if the iterative process has additional iterations to execute.
     *  This does not check other stopping criteria related to solution
     *  quality or convergence. This is about how many iterations have been
     *  executed from the maximum specified.
     */
    fun hasNextIteration(): Boolean {
        return myMainIterativeProcess.hasNextStep()
    }

    /**
     *  Runs the next iteration. Only valid if the solver has been
     *  initialized and there are additional iterations to run.
     */
    fun runNextIteration(){
        myMainIterativeProcess.runNext()
    }

    /**
     *   Causes the solver to run all iterations until its stopping
     *   criteria is met or the maximum number of iterations has been reached.
     */
    fun runAllIterations(){
        myMainIterativeProcess.run()
    }

    /**
     *  Causes a graceful stopping of the iterative processes of the solver.
     *  The inner process will complete its current iteration and then
     *  no more outer iterations will start.
     *  @param msg a message can be captured concerning why the stoppage occurred.
     */
    fun stopIterations(msg: String? = null){
        myMainIterativeProcess.stop(msg)
    }

    /**
     *  Note that the iterations can only be ended before running all iterations or
     *  before running the next iteration. Use stopIterations() to cause a graceful
     *  completion of inner and outer iterations.
     *  @param msg a message to capture for why the iterations were ended
     */
    fun endIterations(msg: String? = null){
        myMainIterativeProcess.end(msg)
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
     *  of the subclass to determine the current (best) solution.
     */
    abstract fun currentSolution(): Solution

    /**
     *  Subclasses should implement this function to prepare the solver
     *  prior to running the first iteration.
     */
    protected abstract fun initializeIterations()

    /**
     *  The critical function to implement. This function should
     *  contain the logic that iteratively executes until the
     *  maximum number of iterations is reached or until the stopping
     *  criteria is met.
     */
    protected abstract fun mainIteration()

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running iterations. This will likely include some
     *  implementation of stopping criteria.
     */
    protected abstract fun isStoppingCriteriaSatisfied(): Boolean


    /**
     *  This function is called before the function mainIteration() executes.
     *  Provides a hook for additional pre-iteration logic, which could
     *  be placed here instead of at the beginning of the mainIteration()
     *  function.
     */
    protected open fun beforeMainIteration(){

    }

    /**
     *  This function is called after the function mainIteration() executes.
     *  Provides a hook for additional after-iteration logic, which could
     *  be placed here instead of at the end of the mainIteration()
     *  function.
     */
    protected open fun afterMainIteration(){

    }

    /**
     *  Subclasses should implement this function to clean up after
     *  running **all** iterations.  That is, after the main iteration
     *  has stopped.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected open fun mainIterationsEnded(){

    }

    protected fun requestEvaluations(inputs: Set<InputMap>): List<Solution> {
        val requests = prepareEvaluationRequests(inputs)
        return requestEvaluations(requests)
    }

    protected fun requestEvaluation(input: InputMap): List<Solution> {
        val requests = prepareEvaluationRequests(setOf(input))
        return requestEvaluations(requests)
    }

    /**
     *  Uses the supplied [replicationsPerEvaluation] property to prepare the
     *  inputs as evaluation requests.
     *  @param inputs the input (point) values to prepare
     *  @return the prepared requests
     */
    private fun prepareEvaluationRequests(inputs: Set<InputMap>) : List<EvaluationRequest>{
        val list = mutableListOf<EvaluationRequest>()
        for(input in inputs){
            val numReps = replicationsPerEvaluation.numReplicationsPerEvaluation(this)
            list.add(EvaluationRequest(numReps, input))
        }
        return list
    }

    private fun requestEvaluations(requests: List<EvaluationRequest>) : List<Solution> {
        //TODO this is a long running call, consider coroutines to support this
       return mySolverRunner?.receiveEvaluationRequests(this, requests) ?: myEvaluator.evaluate(requests)
    }

    protected inner class MainIterativeProcess : IterativeProcess<MainIterativeProcess>("${name}:SolverMainIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            logger.info { "Resetting solver $name's evaluation counters in solver $name" }
            mySolverRunner?.resetEvaluator() ?: myEvaluator.resetEvaluationCounts()
            logger.info { "Initializing solver $name's outer iteration loop" }
            this@Solver.initializeIterations()
            logger.info { "Initialized solver $name's outer iteration loop" }
        }

        override fun hasNextStep(): Boolean {
            return (iterationCounter < maximumNumberIterations)
        }

        override fun checkStoppingCondition() {
            if (isStoppingCriteriaSatisfied()) {
                stop()
            }
        }

        override fun nextStep(): MainIterativeProcess? {
            return if (!hasNextStep()) {
                null
            } else this
        }

        override fun runStep() {
            myCurrentStep = nextStep()
            logger.info { "Executing beforeMainIteration(): iteration = $iterationCounter of solver $name's main iteration loop" }
            beforeMainIteration()
            logger.info { "Running: iteration = $iterationCounter of solver $name's main iteration loop" }
            mainIteration()
            logger.info { "Completed: iteration = $iterationCounter of $maximumNumberIterations iterations of solver $name's main iteration loop" }
            iterationCounter++
            logger.info { "Executing afterMainIteration(): iteration = $iterationCounter of solver $name's main iteration loop" }
            afterMainIteration()
        }

        override fun endIterations() {
            logger.info { "Executing mainIterationsEnded(): iteration = $iterationCounter of $maximumNumberIterations" }
            mainIterationsEnded()
            logger.info { "Executed mainIterationsEnded(): iteration = $iterationCounter of $maximumNumberIterations" }
            super.endIterations()
            logger.info { "Ended: solver $name's main iteration loop" }
        }

    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}