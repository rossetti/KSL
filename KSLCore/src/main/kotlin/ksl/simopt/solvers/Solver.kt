package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.*
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.ProblemDefinition.Companion.defaultMaximumFeasibleSamplingIterations
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.IterativeProcess
import ksl.simulation.IterativeProcessStatusIfc
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.statistic.Statistic
import ksl.utilities.statistic.StatisticIfc

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
 *  for its evaluation requests. The number of replications may dynamically change, and thus the user needs to
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
    evaluator: EvaluatorIfc,
    maximumIterations: Int,
    var replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : IdentityIfc by Identity(name), CompareSolutionsIfc, SolutionEmitterIfc by SolutionEmitter() {

    init {
        require(maximumIterations > 0) { "maximum number of iterations must be > 0" }
    }

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
     *  for its evaluation requests. The number of replications may dynamically change, and thus the user needs to
     *  supply a function to determine the number of replications per evaluation.  Within the framework of the
     *  hooks for subclasses the user could specify more complex procedures for determining the number of replications per
     *  evaluation.
     *
     *  @param maximumIterations the maximum number of iterations permitted for the main loop. This must be
     *  greater than 0.
     *  @param replicationsPerEvaluation a fixed number of replications for each evaluation
     *  @param name a name to help with identifying the solver when multiple solvers are used on a problem
     */
    constructor(
        evaluator: EvaluatorIfc,
        maximumIterations: Int,
        replicationsPerEvaluation: Int,
        name: String? = null
    ) : this(evaluator, maximumIterations, FixedReplicationsPerEvaluation(replicationsPerEvaluation), name)

    /**
     *  The outer iterative process. See [IterativeProcess] for
     *  the iterative process pattern.
     */
    private val myMainIterativeProcess = MainIterativeProcess()

    /**
     * Statistic object to track the fraction of iterations in which the solver
     * successfully improves upon the current solution using an iterative process.
     * This metric helps evaluate the effectiveness of the solver's approach in
     * generating better solutions over time.
     */
    private val myImprovingStepFraction = Statistic("ImprovingStepFraction")

    /**
     * Statistic object to track the fraction of iterations in which the solver
     * successfully improves upon the current solution using an iterative process.
     * This metric helps evaluate the effectiveness of the solver's approach in
     * generating better solutions over time.
     */
    val improvingStepFraction: StatisticIfc
        get() = myImprovingStepFraction

    /**
     * Represents the count of successfully improving steps achieved during the solver's iterative process.
     * This value is calculated based on the sum of improving step fractions, converted to an integer.
     * It is used to track the progress and effectiveness of the solver in generating better solutions.
     */
    @Suppress("unused")
    val successCount: Int
        get() = myImprovingStepFraction.sum.toInt()

    /**
     *  Allow the status of the iterative process to be accessible
     */
    @Suppress("unused")
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
     *  Permits capture of evaluated solutions locally by the solver.
     *  Not all solvers retain past solutions. Also, in general,
     *  the evaluator will have access to a cache of solutions.
     */
    protected val mySolutions: Solutions = Solutions()

    /**
     *  If true, updates to the current solution will be captured
     *  automatically to memory. The default is false.
     */
    var saveSolutions: Boolean = false

    /**
     *  Indicates whether the solver allows infeasible requests
     *  to be sent to the evaluator. The default is false. That is,
     *  the solver is allowed to send infeasible problem requests for
     *  evaluation by the evaluator.
     */
    var ensureProblemFeasibleRequests: Boolean = false

    /**
     *  A read-only view of the solutions evaluated by the solver.
     *  Not all solvers retain past solutions. Also, in general,
     *  the evaluator will have access to a cache of solutions.
     */
    val solutions: SolutionsIfc
        get() = mySolutions

    /**
     *  The user can supply a comparator for comparing whether one
     *  solution is smaller, equal to, or larger than another solution.
     *  If supplied, this function will be used instead of the implemented compare()
     *  function. The user can supply a function or override the
     *  compare function to specialize how solutions are compared.
     */
    var solutionComparer: CompareSolutionsIfc? = null

    /**
     *  The user can supply a function that will generate a neighbor
     *  for the evaluation process. If supplied, this function will be used
     *  instead of the pre-defined generateNeighbor() function. The user
     *  may also override the generateNeighbor() function when
     *  developing subclasses.
     */
    var neighborGenerator: GenerateNeighborIfc? = null


    /**
     * A variable representing an instance of the `SolutionQualityEvaluatorIfc` interface.
     * It is used to assess and evaluate the quality of a given solution.
     * The variable can hold a nullable implementation of the interface.
     */
    var solutionQualityEvaluator: SolutionQualityEvaluatorIfc? = null

    /**
     *  The maximum number of iterations permitted for the main loop. This must be
     *  greater than 0.
     */
    var maximumNumberIterations: Int = maximumIterations
        set(value) {
            require(value > 0) { "maximum number of main iterations must be > 0" }
            field = value
        }

    /**
     * The maximum number of iterations when sampling for an input feasible point
     */
    var maxFeasibleSamplingIterations = defaultMaximumFeasibleSamplingIterations
        set(value) {
            require(value > 0) { "The maximum number of samples is $value, must be > 0" }
            field = value
        }

    /**
     *  Returns how many iterations of the main loop have been executed.
     */
    var iterationCounter: Int = 0
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
     *  An initial starting point for the solver. If supplied, this point
     *  will be used instead of the returned value of the startingPoint() function.
     *  The default is null, which indicates that the function should be called
     *  to obtain the initial starting point.
     *
     *  The starting point must be a valid point in the input space.
     *  It must also be input range-feasible.
     *
     */
    var startingPoint: InputMap? = null
        set(value) {
            if (value != null) {
                require(value.isNotEmpty()) { "Starting point must not be empty" }
                require(problemDefinition == value.problemDefinition) { "Starting point must be of the same problem as the evaluator" }
                require(problemDefinition.validate(value)) { "Starting point is not valid" }
                field = value
            }
        }

    /**
     *  The initial point associated with the initial solution.
     */
    @Suppress("unused")
    val initialPoint: InputMap?
        get() = if (::initialSolution.isInitialized) initialSolution.inputMap else null

    /**
     *  The previous solution in the sequence of solutions.
     */
    var previousSolution: Solution = problemDefinition.badSolution()
        protected set

    /**
     *  The previous point in the solution process. It is associated with the
     *  previous solution.
     */
    @Suppress("unused")
    val previousPoint: InputMap
        get() = previousSolution.inputMap

    /**
     *  The difference between the previous solution's penalized objective function value
     *  and the current solution's penalized objective function value.
     */
    var penalizedSolutionGap: Double = Double.NaN
        private set

    /**
     *  The difference between the previous solution's unpenalized objective function value
     *  and the current solution's unpenalized objective function value.
     */
    var unPenalizedSolutionGap: Double = Double.NaN
        private set

    /**
     *  The current (or last) solution that was accepted as a possible
     *  solution to recommend for the solver. It is the responsibility
     *  of the subclass to determine the current solution.
     */
    var currentSolution: Solution = problemDefinition.badSolution()
        protected set(value) {
            previousSolution = field
            field = value
            penalizedSolutionGap = value.penalizedObjFncValue - previousSolution.penalizedObjFncValue
            unPenalizedSolutionGap = value.estimatedObjFncValue - previousSolution.estimatedObjFncValue
            if (saveSolutions) {
                mySolutions.add(value)
            }
            // if the new current solution is better than all previous solutions
            // capture the best solution
            if (compare(field, bestSolution) < 0) {
                bestSolution = field
                logger.trace { "Solver: $name : best solution set to $bestSolution" }
            }
        }

    /**
     * Represents the current point (input settings) of the solver
     * during its iterative process.
     *
     * This value is derived from the `inputMap` of the currently active solution,
     * which reflects the solver's progress in finding optimal or improved solutions.
     * It is critical in defining the context for later operations such as
     * generating neighbors, evaluating solutions, or updating the current solution.
     *
     * The property is read-only and provides an up-to-date snapshot of the solver's
     * current position in the problem's solution space.
     */
    val currentPoint: InputMap
        get() = currentSolution.inputMap

    /**
     *  The best solution found so far in the search. Some algorithms may allow
     *  the current solution to vary from the best solution due to randomness
     *  or other search needs (e.g., explore bad areas with the hope of getting better).
     *  The algorithm should ensure the updating of the best solution found
     *  across any iteration.
     */
    var bestSolution: Solution = problemDefinition.badSolution()

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
    fun runNextIteration() {
        myMainIterativeProcess.runNext()
    }

    /**
     *   Causes the solver to run all iterations until its stopping
     *   criteria is met or the maximum number of iterations has been reached.
     */
    @Suppress("unused")
    fun runAllIterations() {
        myMainIterativeProcess.run()
    }

    /**
     *  Causes a graceful stopping of the iterative processes of the solver.
     *  The inner process will complete its current iteration, and then
     *  no more outer iterations will start.
     *  @param msg a message can be captured concerning why the stoppage occurred.
     */
    @Suppress("unused")
    fun stopIterations(msg: String? = null) {
        myMainIterativeProcess.stop(msg)
    }

    /**
     *  Note that the iterations can only be ended before running all iterations or
     *  before running the next iteration. Use stopIterations() to cause a graceful
     *  completion of inner and outer iterations.
     *  @param msg a message to capture for why the iterations were ended
     */
    fun endIterations(msg: String? = null) {
        myMainIterativeProcess.end(msg)
    }

    /**
     * Recognizing the need to be able to compare solutions that may have sampling error,
     * the user can override this function to provide more extensive comparison or supply
     * an instance of the [CompareSolutionsIfc] interface via the [solutionComparer] property
     * Returns -1 if first is less than the second solution, 0 if the solutions are to be considered
     * equivalent, and 1 if the first is larger than the second solution.
     *
     * @param first the first solution within the comparison
     * @param second the second solution within the comparison
     * @return -1 if first is less than the second solution, 0 if the solutions are to be considered
     *   equivalent, and 1 if the first is larger than the second solution.
     */
    override fun compare(first: Solution, second: Solution): Int {
        return solutionComparer?.compare(first, second) ?: first.compareTo(second)
    }

    /**
     * Updates the current solution based on a new solution. If the new solution
     * is determined to be better than the current solution, the current solution
     * is updated, and the improving step fraction is updated accordingly.
     *
     * @param newSolution the new solution to be compared with the current solution
     */
    protected fun updateCurrentSolution(newSolution: Solution) {
        if (compare(newSolution, currentSolution) < 0) {
            currentSolution = newSolution
            myImprovingStepFraction.collect(1.0)
            logger.trace { "Solver: $name : solution improved to $newSolution" }
        } else {
            myImprovingStepFraction.collect(0.0)
        }
    }

    /**
     * Defines and specifies the starting point for the solver's iterative process.
     * This function is intended to be overridden in subclasses to provide an
     * initial setting of input values for the solver.
     *
     * @return an instance of InputMap representing the initial state or starting point
     * for the solver's process.
     */
    protected abstract fun startingPoint(): InputMap

    /**
     * Defines and specifies how to get the next point for the solver's iterative process.
     * This function is intended to be overridden in subclasses to provide a
     * method for determining the next point in the search process.
     *
     * @return an instance of InputMap representing the next point
     * for the solver's process.
     */
    protected abstract fun nextPoint(): InputMap

    /**
     *  Subclasses may implement this function to prepare the solver
     *  before running the first iteration. Generally, it is sufficient
     *  to just implement the startingPoint() function.
     */
    protected open fun initializeIterations() {
        val initialPoint = startingPoint ?: startingPoint()
        initialSolution = requestEvaluation(initialPoint)
        currentSolution = initialSolution
    }

    /**
     *  This function should contain the logic that iteratively executes until the
     *  maximum number of iterations is reached or until the stopping
     *  criteria is met.  The base implementation calls nextPoint()
     *  to determine the next point to evaluate, requests an evaluation
     *  of the point, and then updates the current solution if the
     *  resulting solution is better than the current solution. Generally,
     *  implementing startingPoint() and nextPoint() should be adequate.
     */
    protected open fun mainIteration() {
        // generate a random neighbor of the current solution
        val nextPoint = nextPoint()
        // evaluate the solution
        val nextSolution = requestEvaluation(nextPoint)
        updateCurrentSolution(nextSolution)
    }

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running iterations. This will likely include some
     *  implementation of stopping criteria. This function should implement
     *  stopping criteria based on the quality of the solution. The number
     *  of iterations, compared to the maximum number of iterations is automatically
     *  checked after each step in the iterative process. Unless overridden, this
     *  function returns false by default, which indicates that the solution
     *  quality criteria has not been satisfied.  This will cause the solver
     *  to iterate through all iterations of the solution process up to the
     *  maximum number of iterations. Alternatively, the user can specify
     *  an instance of the SolutionQualityEvaluatorIfc interface to
     *  determine if the solution quality has been reached.
     */
    protected open fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: false
    }

    /**
     *  This function is called before the function mainIteration() executes.
     *  Provides a hook for additional pre-iteration logic, which could
     *  be placed here instead of at the beginning of the mainIteration()
     *  function.
     */
    protected open fun beforeMainIteration() {

    }

    /**
     *  This function is called after the function mainIteration() executes.
     *  Provides a hook for additional after-iteration logic, which could
     *  be placed here instead of at the end of the mainIteration()
     *  function.
     */
    protected open fun afterMainIteration() {

    }

    /**
     *  Subclasses should implement this function to clean up after
     *  running **all** iterations.  That is, after the main iteration
     *  has stopped.  This may include such concepts as selecting
     *  the best once all iterations have completed.
     */
    protected open fun mainIterationsEnded() {

    }

    /**
     * Generates a neighboring point based on the current point represented by the input map.
     * This method determines the next potential point in the iterative process, either through a
     * neighbor generator or by randomizing the input variables.
     *
     * @param currentPoint the current point represented as an instance of InputMap
     * @param rnStream an instance of RNStreamIfc used for generating random values if no neighbor generator is provided
     * @return an instance of InputMap representing the newly generated neighboring point.
     */
    protected open fun generateNeighbor(
        currentPoint: InputMap,
        rnStream: RNStreamIfc
    ): InputMap {

        val nextPoint = if (neighborGenerator != null) {
            neighborGenerator!!.generateNeighbor(currentPoint, this, ensureProblemFeasibleRequests)
        } else {
            currentPoint.randomizeInputVariable(rnStream)
        }
        logger.trace { "Solver: $name : generated neighbor $nextPoint" }
        return nextPoint
    }

    /**
     * Generates a random neighbor of the current point that satisfies input feasibility constraints.
     * The method attempts to generate a feasible point by randomizing the input variables of the current point.
     * If a feasible point cannot be generated within a maximum number of iterations, an exception is thrown.
     *
     * @param currentPoint The current point in the input space from which a neighbor will be generated.
     * @param rnStream A stream of random numbers used for randomization of the input variables.
     * @return An input feasible point representing a random neighbor of the given point.
     * @throws IllegalStateException If a feasible neighbor cannot be generated within the allowed iterations.
     */
    @Suppress("unused")
    fun generateInputFeasibleNeighbor(currentPoint: InputMap, rnStream: RNStreamIfc): InputMap {
        var nextPoint = currentPoint.randomizeInputVariable(rnStream)
        var count = 0
        while (!problemDefinition.isInputFeasible(nextPoint)) {
            // the point is infeasible, so try again
            nextPoint = currentPoint.randomizeInputVariable(rnStream)
            count++
            if (count > maxFeasibleSamplingIterations) {
                // we tried a lot and were still unsuccessful
                logger.error { "Solver: $name : could not generate an input feasible random neighbor after $maxFeasibleSamplingIterations attempts, when sampling for an input feasible point." }
                logger.error { "Solver: $name : Increase the max feasible sampling iterations for this problem, don't require input feasibility, or use a different neighbor generator" }
                throw IllegalStateException("Could not generate an input feasible random neighbor after $maxFeasibleSamplingIterations attempts, when sampling for an input feasible point.")
            }
        }
        // if we get here, the point is feasible
        return nextPoint
    }

    /**
     *  Creates a request for evaluation from the input map. The number of replications
     *  for the request will be based on the property [replicationsPerEvaluation] for the
     *  solver. The resulting request will be input range-feasible but may be infeasible
     *  with respect to the problem. If the user does not allow infeasible requests by
     *  setting the [ensureProblemFeasibleRequests] to false, then this function will throw
     *  an exception if the supplied input is infeasible with respect to the deterministic
     *  constraints of the problem.
     *
     *  @param inputMap the input variables and their values for the request
     *  @return the instance of RequestData that can be sent for evaluation
     */
    protected fun createRequest(inputMap: InputMap): RequestData {
        if (ensureProblemFeasibleRequests) {
            require(inputMap.isInputFeasible()) { "The input settings were infeasible for the problem when preparing requests." }
        }
        // the input map will be range-feasible but may not be problem-feasible.
        val numReps = replicationsPerEvaluation.numReplicationsPerEvaluation(this)
        // since the input map is immutable so is the RequestData instance
        return RequestData(
            problemDefinition.modelIdentifier,
            numReps,
            inputMap,
            problemDefinition.allResponseNames.toSet(),
            experimentRunParameters = null
        )
    }

    /**
     * Requests evaluations for a set of input maps. The function prepares the evaluation requests
     * from the provided inputs and then performs evaluations to generate solutions.
     *
     * @param inputs a set of input maps, where each map contains input variables and their respective values
     * @return a list of solutions obtained after performing evaluations on the inputs
     */
    @Suppress("unused")
    protected fun requestEvaluations(inputs: Set<InputMap>): List<Solution> {
        val requests = prepareEvaluationRequests(inputs)
        return requestEvaluations(requests)
    }

    /**
     * Requests an evaluation for a single input map and returns the resulting solution.
     * The function prepares the input as an evaluation request, performs the evaluation,
     * and subsequently emits and logs the resulting solution.
     *
     * @param input an instance of InputMap representing the input variables and their values to be evaluated
     * @return the solution obtained after evaluating the input map
     */
    protected fun requestEvaluation(input: InputMap): Solution {
        val requests = prepareEvaluationRequests(setOf(input))
        val solutions = requestEvaluations(requests)
        val solution = solutions.first()
        logger.trace { "Solver: $name : requested evaluation of $input and received $solution" }
        emitter.emit(solution)
        return solution
    }

    /**
     *  Uses the supplied [replicationsPerEvaluation] property to prepare the
     *  inputs as evaluation requests.
     *  @param inputs the input (point) values to prepare
     *  @return the prepared requests
     */
    private fun prepareEvaluationRequests(inputs: Set<InputMap>): List<RequestData> {
        val list = mutableListOf<RequestData>()
        for (input in inputs) {
            list.add(createRequest(input))
        }
        return list
    }

    private fun requestEvaluations(requests: List<RequestData>): List<Solution> {
        //TODO this is a long running call, consider coroutines to support this
        //TODO get rid of mySolverRunner
        return mySolverRunner?.receiveEvaluationRequests(this, requests) ?: myEvaluator.evaluate(requests)
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("Solver name = $name")
            appendLine("Replications Per Evaluation = $replicationsPerEvaluation")
            appendLine("Ensure Problem Feasible Requests = $ensureProblemFeasibleRequests")
            appendLine("Maximum Number Iterations = $maximumNumberIterations")
            appendLine("Begin Execution Time = ${myMainIterativeProcess.beginExecutionTime}")
            appendLine("End Execution Time = ${myMainIterativeProcess.endExecutionTime}")
            appendLine("Elapsed Execution Time = ${myMainIterativeProcess.elapsedExecutionTime}")
            appendLine("Initial Solution:")
            appendLine("$initialSolution")
            appendLine("Current Solution:")
            appendLine("$currentSolution")
            appendLine("Unpenalized Solution Gap = $unPenalizedSolutionGap")
            appendLine("Penalized Solution Gap = $penalizedSolutionGap")
            appendLine("Fraction of Improving Step Statistics = ${improvingStepFraction.average}")
            appendLine("Number of Iterations Completed = $iterationCounter")
            appendLine("Best Solution:")
            appendLine("$bestSolution")
        }
        return sb.toString()
    }

    protected inner class MainIterativeProcess :
        IterativeProcess<MainIterativeProcess>("${name}:SolverMainIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            myImprovingStepFraction.reset()
            iterationCounter = 0
            logger.info { "Resetting solver $name's evaluation counters in solver $name" }
            mySolverRunner?.resetEvaluator() ?: myEvaluator.resetEvaluationCounts()
            logger.trace { "Initializing solver $name" }
            this@Solver.initializeIterations()
            logger.info { "Initialized solver $name" }
            logger.trace { "Initial solution = $initialSolution" }
        }

        override fun hasNextStep(): Boolean {
            return (iterationCounter < maximumNumberIterations)
        }

        /**
         *  This function is called after each iteration (step) is completed.
         *  The number of iterations is automatically checked. This function allows
         *  for the inclusion of additional stopping criteria through the implementation
         *  of the isStoppingCriteriaSatisfied() function, which subclasses can
         *  provide.
         */
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
            logger.trace { "Executing beforeMainIteration(): iteration = $iterationCounter of solver $name" }
            beforeMainIteration()
            logger.trace { "Running: iteration = $iterationCounter of solver $name" }
            mainIteration()
            iterationCounter++
            logger.info { "Completed: iteration = $iterationCounter of $maximumNumberIterations iterations for solver $name" }
            logger.trace { "Executing afterMainIteration(): iteration = $iterationCounter of solver $name" }
            afterMainIteration()
        }

        override fun endIterations() {
            logger.trace { "Executing mainIterationsEnded(): iteration = $iterationCounter of $maximumNumberIterations" }
            mainIterationsEnded()
            logger.trace { "Executed mainIterationsEnded(): iteration = $iterationCounter of $maximumNumberIterations" }
            super.endIterations()
            logger.info { "Ended: solver $name iterations." }
        }

    }

    companion object {
        val logger: KLogger = KotlinLogging.logger {}

        /**
         * Creates and configures a stochastic hill climber to solve the given problem definition using
         * a simulation-based evaluation approach. The default configuration has the evaluator
         * configured to use a solution cache.
         *
         * @param problemDefinition The definition of the optimization problem to solve, including parameters and constraints.
         * @param modelBuilder An interface for building the simulation model required for evaluations.
         * @param startingPoint An optional initial solution to start the search from. If null, a default starting point
         * is randomly generated from the problem definition.
         * @param maxIterations The maximum number of hill climbing iterations to perform.
         * @param replicationsPerEvaluation The number of simulations or evaluations performed per solution to estimate its quality.
         * @param printer An optional function to receive updates about solutions found during the search.
         * @return A configured instance of the `StochasticHillClimber` ready to begin optimization.
         */
        fun stochasticHillClimber(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = 100,
            replicationsPerEvaluation: Int = 50,
            printer: ((Solution) -> Unit)? = null
        ) : StochasticHillClimber {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val sp = startingPoint ?: problemDefinition.startingPoint().toMutableMap()
            val shc = StochasticHillClimber(
                evaluator = evaluator,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            printer?.let { shc.emitter.attach(it) }
            return shc
        }
    }
}