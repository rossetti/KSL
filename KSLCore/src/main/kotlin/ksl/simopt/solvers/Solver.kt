package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.evaluator.*
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.ProblemDefinition.Companion.defaultMaximumFeasibleSamplingIterations
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultReplicationGrowthRate
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultMaxNumReplications
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simopt.solvers.algorithms.CESamplerIfc
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.RSplineSolver
import ksl.simopt.solvers.algorithms.RSplineSolver.Companion.defaultInitialSampleSize
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.RandomRestartSolver.Companion.defaultMaxRestarts
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.SimulatedAnnealing.Companion.defaultInitialTemperature
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.IterativeProcess
import ksl.simulation.IterativeProcessStatusIfc
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.Emitter
import ksl.utilities.random.rng.RNStreamIfc

interface SolverEmitterIfc {
    val emitter: Emitter<Solver>
}

class SolverEmitter : SolverEmitterIfc {
    override val emitter: Emitter<Solver> = Emitter()
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
 *  hooks for subclasses, the user could specify more complex procedures for determining the number of replications per
 *  evaluation.
 *
 *  @param problemDefinition the problem being solved
 *  @param evaluator the reference to the evaluator for evaluating responses from the model
 *  @param maximumIterations the maximum number of iterations permitted for the main loop. This must be
 *  greater than 0.
 *  @param replicationsPerEvaluation the function controlling how many replications are requested for each evaluation
 *  @param name a name to help with identifying the solver when multiple solvers are used on a problem
 */
abstract class Solver(
    val problemDefinition: ProblemDefinition,
    evaluator: EvaluatorIfc,
    maximumIterations: Int,
    var replicationsPerEvaluation: ReplicationPerEvaluationIfc,
    name: String? = null
) : IdentityIfc by Identity(name), Comparator<Solution>, SolverEmitterIfc by SolverEmitter() {

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
     *  hooks for subclasses, the user could specify more complex procedures for determining the number of replications per
     *  evaluation.
     *
     *  @param maximumIterations the maximum number of iterations permitted for the main loop. This must be
     *  greater than 0.
     *  @param replicationsPerEvaluation a fixed number of replications for each evaluation
     *  @param name a name to help with identifying the solver when multiple solvers are used on a problem
     */
    constructor(
        problemDefinition: ProblemDefinition,
        evaluator: EvaluatorIfc,
        maximumIterations: Int,
        replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
        name: String? = null
    ) : this(
        problemDefinition,
        evaluator,
        maximumIterations,
        FixedReplicationsPerEvaluation(replicationsPerEvaluation),
        name
    )

    /**
     *  The outer iterative process. See [IterativeProcess] for
     *  the iterative process pattern.
     */
    private val myMainIterativeProcess = MainIterativeProcess()

//    /**
//     * Counts the number of times that a new current solution replaced the current
//     * best solution. This can be used to measure how often an iteration results in
//     * a better solution being found.
//     */
//    @Suppress("unused")
//    var numTimesBestSolutionUpdated: Int = 0
//        private set

    /**
     *  Allow the status of the iterative process to be accessible
     */
    @Suppress("unused")
    val iterativeProcess: IterativeProcessStatusIfc
        get() = myMainIterativeProcess

    /**
     *  The evaluator used by the solver.
     */
    val evaluator: EvaluatorIfc = evaluator

    /**
     *  Permits capture of evaluated solutions locally by the solver.
     *  Not all solvers retain past solutions. Also, in general,
     *  the evaluator will have access to a cache of solutions.
     */
    protected val myBestSolutions: Solutions = Solutions()

    /**
     *  Whenever the current solution is assigned it is checked to see if it is better than the
     *  current best solution. If it is, then the best solution is updated.
     *  If true, updates to the best solution will be captured
     *  automatically to memory. The default is true.
     */
//    var saveBestSolutions: Boolean = true

    /**
     *  Indicates whether the solver allows infeasible requests
     *  to be sent to the evaluator. The default is false. That is,
     *  the solver is allowed to send infeasible problem requests for
     *  evaluation by the evaluator.
     */
    var ensureProblemFeasibleRequests: Boolean = false

    /**
     *  A read-only view of the best solutions evaluated by the solver.
     *  Not all solvers retain past solutions. Also, in general,
     *  the evaluator may have access to a cache of solutions.
     */
    @Suppress("unused")
    val bestSolutions: SolutionsIfc
        get() = myBestSolutions

    /**
     *  The best solution found so far in the search. Some algorithms may allow
     *  the current solution to vary from the best solution due to randomness
     *  or other search needs (e.g., explore bad areas with the hope of getting better).
     *  The algorithm should ensure the updating of the best solution found
     *  across any iteration.
     */
    val bestSolution: Solution
        get() = myBestSolutions.orderedSolutions.firstOrNull() ?: problemDefinition.badSolution()

    /**
     *  The user can supply a comparator for comparing whether one
     *  solution is smaller, equal to, or larger than another solution.
     *  If supplied, this function will be used instead of the implemented compare()
     *  function. The user can supply a function or override the
     *  compare function to specialize how solutions are compared.
     */
    var solutionComparer: Comparator<Solution>? = null

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
     *  A variable that tracks the total number of simulation oracle calls.
     */
    var numOracleCalls: Int = 0
        protected set

    /**
     *  A variable that tracks the total number of simulation replications requested.
     */
    var numReplicationsRequested: Int = 0
        protected set

    /**
     *  Returns the number of times the main iteration function was called.
     */
    var iterationCounter: Int = 0
        private set

    /**
     *  The initial starting solution for the algorithm. It is the responsibility
     *  of the subclass to initialize the initial solution.
     */
    protected lateinit var myInitialSolution: Solution

    @Suppress("unused")
    val initialSolution: Solution?
        get() = if (::myInitialSolution.isInitialized) myInitialSolution else null

    /**
     *  An initial starting point for the solver. If supplied, this point
     *  will be used instead of the returned value of the startingPoint() function.
     *  The default is null, which indicates that the function should be called
     *  to obtain the initial starting point.
     *
     *  The starting point must be a valid point in the input space.
     *  It must also be input-feasible.
     *
     */
    var startingPoint: InputMap? = null
        set(value) {
            if (value != null) {
                require(value.isNotEmpty()) { "Starting point must not be empty" }
                require(problemDefinition == value.problemDefinition) { "Starting point must be of the same problem as the evaluator" }
                require(problemDefinition.validate(value)) { "Starting point is not valid" }
                require(value.isInputFeasible()) { "The supplied starting point must be feasible with respect to the problem" }
            }
            field = value
//            println("In setStartingPoint(): value = ${value?.inputValues?.joinToString()}")
        }

    /**
     *  The initial point associated with the initial solution.
     */
    @Suppress("unused")
    val initialPoint: InputMap?
        get() = if (::myInitialSolution.isInitialized) myInitialSolution.inputMap else null

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
            // save the previous solution
            previousSolution = field
            // update the current solution
            field = value
            penalizedSolutionGap = value.penalizedObjFncValue - previousSolution.penalizedObjFncValue
            unPenalizedSolutionGap = value.estimatedObjFncValue - previousSolution.estimatedObjFncValue
            myBestSolutions.add(value)
            // if the new current solution is better than all previous solutions
            // capture the better solution
            //updateBestSolution(field)
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
     *  Many algorithms compare solutions. This factor serves as the criteria
     *  when comparing two solutions such that if the solutions are within this value
     *  they are considered equal. The default is [defaultSolutionPrecision].
     *  Algorithms may or may not use this criterion.
     */
    @Suppress("unused")
    var solutionPrecision: Double = defaultSolutionPrecision
        set(value) {
            require(value > 0) { "The default solution precision must be a positive value." }
            field = value
        }

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
    @Suppress("unused")
    fun hasNextIteration(): Boolean {
        return myMainIterativeProcess.hasNextStep()
    }

    /**
     *  Runs the next iteration. Only valid if the solver has been
     *  initialized and there are additional iterations to run.
     */
    @Suppress("unused")
    fun runNextIteration() {
        myMainIterativeProcess.runNext()
    }

    /**
     *   Causes the solver to run all iterations until its stopping
     *   criteria is met, or the maximum number of iterations has been reached.
     */
    @Suppress("unused")
    fun runAllIterations() {
        myMainIterativeProcess.run()
    }

    /**
     *  Causes a graceful stopping of the iterative processes for the solver.
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
    @Suppress("unused")
    fun endIterations(msg: String? = null) {
        myMainIterativeProcess.end(msg)
    }

    /**
     *  Clears the best solutions captured by the solver. The solver will retain
     *  all best solutions that have been observed until they are cleared, even
     *  with repeated use.
     */
    @Suppress("unused")
    fun clearBestSolutions() {
        myBestSolutions.clear()
    }

    /**
     * Recognizing the need to be able to compare solutions that may have sampling error,
     * the user can override this function to provide more extensive comparison or supply
     * an instance of the [Comparator<Solution>] interface via the [solutionComparer] property
     * Returns -1 if first is less than the second solution, 0 if the solutions are to be considered
     * equivalent, and 1 if the first is larger than the second solution.
     *
     * @param first the first solution within the comparison
     * @param second the second solution within the comparison
     * @return -1 if the first solution is less than the second solution, 0 if the solutions are to be considered
     *   equivalent, and 1 if the first is larger than the second solution.
     */
    override fun compare(first: Solution, second: Solution): Int {
        return solutionComparer?.compare(first, second) ?: first.compareTo(second)
    }

//    /**
//     *  This comparator is used to compare a new current solution to the previous best solution within
//     *  the updateBestSolution() function. The default behavior is to use a confidence interval
//     *  on the penalized objective function value to determine if the new solution is better. The
//     *  default is a 95 percent confidence interval with 0.0 indifference zone. The user can change
//     *  this comparator or revise how the updateBestSolution() function is implemented.
//     */
//    var bestSolutionComparator: Comparator<Solution> = PenalizedObjectiveFunctionConfidenceIntervalComparator()
//
//    /**
//     *  Used when the current solution is updated to (if necessary) update the current best solution.
//     *  If two solutions are considered statistically the same, then the one with more samples is used.
//     */
//    protected open fun updateBestSolution(possiblyBetter: Solution) {
//        if (bestSolutionComparator.compare(possiblyBetter, bestSolution) < 0) {
//            bestSolution = possiblyBetter
//            logger.trace { "Solver: $name : best solution set to $bestSolution" }
//        } else if (bestSolutionComparator.compare(possiblyBetter, bestSolution) == 0) {
//            // if statistically the same, prefer the one that has more samples
//            if (possiblyBetter.count > bestSolution.count) {
//                bestSolution = possiblyBetter
//                logger.trace { "Solver: $name : best solution set to $bestSolution" }
//            } else if (possiblyBetter.count == bestSolution.count) {
//                // if they have the same number of samples, prefer the one that has a lower penalized objective function value
//                bestSolution = minimumSolution(possiblyBetter, bestSolution)
//            }
//        }
//    }

    /** Returns the smaller of the two solutions. Ties result in the first solution
     * being returned. This function uses the supplied comparator.
     *
     * @param first the first solution within the comparison
     * @param second the second solution within the comparison
     * @param comparator the comparator to use for the comparison. By default, the
     * comparison is based on the values of the penalized objective function values.
     */
    @Suppress("unused")
    fun minimumSolution(
        first: Solution,
        second: Solution,
        comparator: Comparator<Solution> = PenalizedObjectiveFunctionComparator
    ): Solution {
        return if (comparator.compare(first, second) <= 0) {
            first
        } else {
            second
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
     *  This function should contain the logic that iteratively executes until the
     *  maximum number of iterations is reached or until the stopping
     *  criteria is met.  The base implementation calls nextPoint()
     *  to determine the next point to evaluate, requests an evaluation
     *  of the point, and then updates the current solution if the
     *  resulting solution is better than the current solution. Generally,
     *  implementing startingPoint() and nextPoint() should be adequate.
     *  The property [iterationCounter] represents the current iteration
     *  within the mainIteration() function. That is, the value of [iterationCounter]
     *  is incremented prior to the execution of the mainIteration() function.
     */
    protected abstract fun mainIteration()

    /**
     *  Subclasses may implement this function to prepare the solver
     *  before running the first iteration. Generally, it is sufficient
     *  to just implement the startingPoint() function.
     */
    protected open fun initializeIterations() {
        val initialPoint = startingPoint ?: startingPoint()
        myInitialSolution = requestEvaluation(initialPoint)
        //       println("In initializeIterations(): iteration = $iterationCounter : Initial solution: ${myInitialSolution.asString()}")
        currentSolution = myInitialSolution
    }

    /**
     *  Subclasses should implement this function to determine if the solver
     *  should continue running iterations. This will likely include some
     *  implementation of stopping criteria. This function should implement
     *  stopping criteria based on the quality of the solution. The number
     *  of iterations, compared to the maximum number of iterations, is automatically
     *  checked after each step in the iterative process. Unless overridden, this
     *  function returns false by default, which indicates that the solution
     *  quality criteria have not been satisfied.  This will cause the solver
     *  to iterate through all iterations of the solution process up to the
     *  maximum number of iterations. Alternatively, the user can specify
     *  an instance of the SolutionQualityEvaluatorIfc interface to
     *  determine if the solution quality has been reached.
     */
    protected open fun isStoppingCriteriaSatisfied(): Boolean {
        return solutionQualityEvaluator?.isStoppingCriteriaReached(this) ?: false
    }

    /**
     *  This function is called before the function mainIteration() is executed.
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
     * neighbor generator or by randomizing the value of a randomly selected input variable.
     * Unless a neighborhood generator is supplied, the resulting point will be input-range feasible.
     * Thus, it may be infeasible with respect to deterministic constraints.
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
     *  @return the instance of ModelInputs that can be sent for evaluation
     */
    protected fun createModelInputs(
        inputMap: InputMap,
        numReps: Int = replicationsPerEvaluation.numReplicationsPerEvaluation(this)
    ): ModelInputs {
        if (ensureProblemFeasibleRequests) {
            require(inputMap.isInputFeasible()) { "The input settings were infeasible for the problem when preparing requests." }
        }
        //TODO this is the only place where ModelInputs is being made
        numReplicationsRequested = numReplicationsRequested + numReps
        // the input map will be range-feasible but may not be problem-feasible.
        // since the input map is immutable, so is the RequestData instance
        return ModelInputs(
            problemDefinition.modelIdentifier,
            numReps,
            inputMap,
            problemDefinition.allResponseNames.toSet(),
        )
    }

    /**
     * Requests evaluations for a set of input maps. The function prepares the evaluation requests
     * from the provided inputs and then performs evaluations to generate solutions.
     *
     * @param inputs a set of input maps, where each map contains input variables and their respective values
     * @param numReps the number of replications for each of the requested evaluations
     * @param crnOption indicates if common random numbers should be used for the evaluations. The default
     * is false. If true, no caching is allowed.
     * @param cachingAllowed indicates whether the evaluations can be satisfied from a solution cache. The
     * default is true.  This will permit some (or all) the replications to be satisfied from a cache.
     * @return a list of solutions obtained after performing evaluations on the inputs
     */
    @Suppress("unused")
    protected fun requestEvaluations(
        inputs: Set<InputMap>,
        numReps: Int = replicationsPerEvaluation.numReplicationsPerEvaluation(this),
        crnOption: Boolean = false,
        cachingAllowed: Boolean = true
    ): Map<ModelInputs, Solution>{
        val caching = if (crnOption) false else cachingAllowed
        val requests = prepareModelInputs(inputs, numReps)
        return requestEvaluations(requests, crnOption, caching)
    }

    /**
     * Requests an evaluation for a single input map and returns the resulting solution.
     * The function prepares the input as an evaluation request, performs the evaluation,
     * and subsequently emits and logs the resulting solution.  CRN is not permitted for a single
     * evaluation.
     *
     * @param input an instance of InputMap representing the input variables and their values to be evaluated
     * @param numReps the number of replications for each of the requested evaluations
     * @param cachingAllowed indicates whether the evaluations can be satisfied from a solution cache. The
     * default is true.  This will permit some (or all) the replications to be satisfied from a cache.
     * @return the solution obtained after evaluating the input map
     */
    protected fun requestEvaluation(
        input: InputMap,
        numReps: Int = replicationsPerEvaluation.numReplicationsPerEvaluation(this),
        cachingAllowed: Boolean = true
    ): Solution {
        val requests = prepareModelInputs(setOf(input), numReps)
        val solutions = requestEvaluations(requests, false, cachingAllowed)
        // There was only one ModelInput prepared and it must be associated with the returned solution
        val solution = solutions[requests[0]]!!
        logger.trace { "Solver: $name : requested evaluation of $input and received $solution" }
        return solution
    }

    /**
     *  Uses the supplied [replicationsPerEvaluation] property to prepare the
     *  inputs as evaluation requests.
     *  @param inputs the input (point) values to prepare
     *  @param numReps the number of replications to be associated with each request
     *  @return the prepared requests
     */
    private fun prepareModelInputs(
        inputs: Set<InputMap>,
        numReps: Int
    ): List<ModelInputs> {
        val list = mutableListOf<ModelInputs>()
        for (input in inputs) {
            list.add(createModelInputs(input, numReps))
        }
        return list
    }

    /**
     *  Calls for the evaluation of the model inputs.
     */
    private fun requestEvaluations(
        modelInputs: List<ModelInputs>,
        crnOption: Boolean,
        cachingAllowed: Boolean
    ): Map<ModelInputs, Solution> {
        //TODO this is a long running call, consider coroutines to support this
        numOracleCalls = numOracleCalls + modelInputs.size
        val evaluationRequest = EvaluationRequest(problemDefinition.modelIdentifier, modelInputs, crnOption, cachingAllowed)
        return evaluator.evaluate(evaluationRequest)
    }

    override fun toString(): String {
        val sb = StringBuilder().apply {
            appendLine("==================================================================")
            appendLine("Solver name = $name")
            appendLine("Replications Per Evaluation = $replicationsPerEvaluation")
            appendLine("Ensure Problem Feasible Requests = $ensureProblemFeasibleRequests")
            appendLine("Maximum Number Iterations = $maximumNumberIterations")
            appendLine("Begin Execution Time = ${myMainIterativeProcess.beginExecutionTime}")
            appendLine("End Execution Time = ${myMainIterativeProcess.endExecutionTime}")
            appendLine("Elapsed Execution Time = ${myMainIterativeProcess.elapsedExecutionTime}")
            appendLine("Number of simulation calls = $numOracleCalls")
            appendLine("Number of replications requested = $numReplicationsRequested")
            appendLine("==================================================================")
            if (::myInitialSolution.isInitialized) {
                appendLine("Initial Solution:")
                appendLine("$myInitialSolution")
                appendLine("==================================================================")
            }
            if (currentSolution.isValid) {
                appendLine("Current Solution:")
                appendLine("$currentSolution")
                appendLine("Unpenalized Solution Gap = $unPenalizedSolutionGap")
                appendLine("Penalized Solution Gap = $penalizedSolutionGap")
//                appendLine("Number of times the best solution was updated = $numTimesBestSolutionUpdated")
//                appendLine("Number of Iterations Completed = $iterationCounter")
                appendLine("==================================================================")
                if (compare(bestSolution, currentSolution) < 0) {
                    appendLine("A better solution was found than the current solution.")
                    appendLine("Best Solution:")
                    appendLine("$bestSolution")
                    appendLine("==================================================================")
                }
            }
            appendLine("Best Solutions Found:")
            for (solution in myBestSolutions.orderedSolutions) {
                appendLine(solution.asString())
            }
            appendLine("==================================================================")
        }
        return sb.toString()
    }

    protected inner class MainIterativeProcess :
        IterativeProcess<MainIterativeProcess>("${name}:SolverMainIterativeProcess") {

        override fun initializeIterations() {
            super.initializeIterations()
            iterationCounter = 0
            //     numTimesBestSolutionUpdated = 0
            numOracleCalls = 0
            numReplicationsRequested = 0
            currentSolution = problemDefinition.badSolution()
            //  bestSolution = problemDefinition.badSolution()
            myBestSolutions.clear()
//            println("Solver: initializeIterations(): reset current solution, the best solution, and cleared best solutions")
//            println("current solution: ${currentSolution.asString()}")
//            println("best solution: ${bestSolution.asString()}")
//            println("best solutions size ${myBestSolutions.size}")
            logger.info { "Initializing Iterations: Resetting solver evaluation counters for solver: $name" }
            this@Solver.initializeIterations()
            if (::myInitialSolution.isInitialized) {
                val solution = myInitialSolution
                logger.info { "Initialized solver $name : penalized objective function value: ${solution.penalizedObjFncValue}" }
                logger.trace { "Initial solution = $solution" }
            }
            // emitter.emit(this@Solver)
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
            iterationCounter++
            logger.info { "Running: iteration = $iterationCounter of solver name: $name" }
            mainIteration()
            emitter.emit(this@Solver)
            logger.info { "Completed: iteration = $iterationCounter of $maximumNumberIterations iterations : penalized objective function value: ${currentSolution.penalizedObjFncValue}" }
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

        /**
         *  Many algorithms compare solutions. This factor serves as the default precision
         *  between two solutions such that if the solutions are within this value
         *  they are considered equal. The default is 0.001
         */
        var defaultSolutionPrecision: Double = 0.001
            set(value) {
                require(value > 0) { "The default solution precision must be a positive value." }
                field = value
            }

        /**
         * Represents the default maximum number of iterations to be executed
         * in a given process or algorithm. This value acts as a safeguard
         * to prevent indefinite looping or excessive computation.
         *
         * The default value is set to 1000, but it can be modified based
         * on specific requirements or constraints.
         */
        @JvmStatic
        var defaultMaxNumberIterations = 1000
            set(value) {
                require(value > 0) { "The default maximum number of iterations must be a positive value." }
                field = value
            }

        /**
         * Represents the default number of replications to be performed during an evaluation.
         *
         * This parameter defines the number of times a specific evaluation process should be repeated
         * to ensure consistency and reliability of the results. The value must always be a positive
         * integer greater than zero.
         *
         * A change to this value will affect all subsequent evaluations relying on
         * the default replication count.
         *
         * @throws IllegalArgumentException if the value set is not greater than zero.
         */
        @JvmStatic
        @Suppress("unused")
        var defaultReplicationsPerEvaluation = 30
            set(value) {
                require(value > 0) { "The default replications per evaluation must be a positive value." }
                field = value
            }

        /**
         * Logger instance used for logging messages.
         * Utilizes the KotlinLogging framework to facilitate structured and leveled logging.
         */
        @JvmStatic
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
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun stochasticHillClimbingSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            printer: ((Solver) -> Unit)? = null
        ): StochasticHillClimber {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val sp = startingPoint ?: problemDefinition.startingPoint().toMutableMap()
            val shc = StochasticHillClimber(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            shc.startingPoint = evaluator.problemDefinition.toInputMap(sp)
            printer?.let { shc.emitter.attach(it) }
            return shc
        }

        /**
         * Creates and configures a simulated annealing optimization algorithm for a given problem definition.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param maxNumRestarts The maximum number of restarts to be performed.
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param replicationsPerEvaluation The number of replications to use during each evaluation to reduce
         * stochastic noise. Defaults to 50.
         * @param restartPrinter Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the restart optimization process.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         *    observe the inner solver optimization process.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun stochasticHillClimbingSolverWithRestarts(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            restartPrinter: ((Solver) -> Unit)? = null,
            printer: ((Solver) -> Unit)? = null
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val shc = StochasticHillClimber(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            val restartSolver = RandomRestartSolver(
                shc, maxNumRestarts
            )
            restartPrinter?.let { restartSolver.emitter.attach(it) }
            printer?.let { shc.emitter.attach(it) }
            return restartSolver
        }

        /**
         * Creates and configures a simulated annealing optimization algorithm for a given problem definition.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param startingPoint Optional initial solution to start the optimization. Defaults to the starting point
         * provided by the problem definition.
         * @param initialTemperature The initial temperature for the annealing process. Determines the likelihood of
         * accepting worse solutions at the start of the process. Defaults to 1000.0.
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param replicationsPerEvaluation The number of replications to use during each evaluation to reduce
         * stochastic noise. Defaults to 50.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the optimization process.
         * @return An instance of SimulatedAnnealing that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun simulatedAnnealingSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            startingPoint: MutableMap<String, Double>? = null,
            initialTemperature: Double = defaultInitialTemperature,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            printer: ((Solver) -> Unit)? = null
        ): SimulatedAnnealing {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val sp = startingPoint ?: problemDefinition.startingPoint().toMutableMap()
            val sa = SimulatedAnnealing(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                initialTemperature = initialTemperature,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            sa.startingPoint = evaluator.problemDefinition.toInputMap(sp)
            printer?.let { sa.emitter.attach(it) }
            return sa
        }

        /**
         * Creates and configures a simulated annealing optimization algorithm for a given problem definition.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param maxNumRestarts The maximum number of restarts to be performed.
         * @param initialTemperature The initial temperature for the annealing process. Determines the likelihood of
         * accepting worse solutions at the start of the process. Defaults to 1000.0.
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param replicationsPerEvaluation The number of replications to use during each evaluation to reduce
         * stochastic noise. Defaults to 50.
         * @param restartPrinter Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the restart optimization process.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         *    observe the inner solver optimization process.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun simulatedAnnealingSolverWithRestarts(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            initialTemperature: Double = defaultInitialTemperature,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            restartPrinter: ((Solver) -> Unit)? = null,
            printer: ((Solver) -> Unit)? = null
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val sa = SimulatedAnnealing(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                initialTemperature = initialTemperature,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            val restartSolver = RandomRestartSolver(
                sa, maxNumRestarts
            )
            restartPrinter?.let { restartSolver.emitter.attach(it) }
            printer?.let { sa.emitter.attach(it) }
            return restartSolver
        }

        /**
         * Creates and configures a cross-entropy optimization algorithm for a given problem definition.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param startingPoint Optional initial solution to start the optimization. Defaults to the starting point
         * provided by the problem definition.
         * @param ceSampler The cross-entropy sampler. By default, it is [CENormalSampler]
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param replicationsPerEvaluation The number of replications to use during each evaluation to reduce
         * stochastic noise. Defaults to 50.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the optimization process.
         * @return An instance of CrossEntropySolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun crossEntropySolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            ceSampler: CESamplerIfc = CENormalSampler(problemDefinition),
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            printer: ((Solver) -> Unit)? = null
        ): CrossEntropySolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val ce = CrossEntropySolver(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                ceSampler = ceSampler,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            if (startingPoint != null) {
                ce.startingPoint = evaluator.problemDefinition.toInputMap(startingPoint)
            }
            printer?.let { ce.emitter.attach(it) }
            return ce
        }

        /**
         * Creates and configures a cross-entropy optimization algorithm for a given problem definition
         * that uses a random restart approach.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param maxNumRestarts The maximum number of restarts to be performed.
         * @param ceSampler The cross-entropy sampler. By default, it is [CENormalSampler]
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param replicationsPerEvaluation The number of replications to use during each evaluation to reduce
         * stochastic noise. Defaults to 50.
         * @param restartPrinter Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the restart optimization process.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         *    observe the inner solver optimization process.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun crossEntropySolverWithRestarts(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            ceSampler: CESamplerIfc = CENormalSampler(problemDefinition),
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            restartPrinter: ((Solver) -> Unit)? = null,
            printer: ((Solver) -> Unit)? = null
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val ce = CrossEntropySolver(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                ceSampler = ceSampler,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            val restartSolver = RandomRestartSolver(
                ce, maxNumRestarts
            )
            restartPrinter?.let { restartSolver.emitter.attach(it) }
            printer?.let { ce.emitter.attach(it) }
            return restartSolver
        }

        /**
         * Creates and configures an R-SPLINE optimization algorithm for a given problem definition.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param initialNumReps The initial number of replications to use during each evaluation. Defaults to defaultInitialSampleSize.
         * @param sampleSizeGrowthRate The growth rate of the sample size as the solver progresses. Defaults to defaultSampleSizeGrowthRate.
         * @param maxNumReplications The maximum number of replications by growth rate. Defaults to defaultMaxNumReplications.
         * @param startingPoint Optional initial solution to start the optimization. Defaults to the starting point
         * provided by the problem definition.
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the optimization process.
         * @return An instance of RSplineSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun rSplineSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            initialNumReps: Int = defaultInitialSampleSize,
            sampleSizeGrowthRate: Double = defaultReplicationGrowthRate,
            maxNumReplications: Int = defaultMaxNumReplications,
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = defaultMaxNumberIterations,
            printer: ((Solver) -> Unit)? = null
        ): RSplineSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val solver = RSplineSolver(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                maxIterations = maxIterations,
                initialNumReps = initialNumReps,
                sampleSizeGrowthRate = sampleSizeGrowthRate,
                maxNumReplications = maxNumReplications
            )
            if (startingPoint != null) {
                solver.startingPoint = evaluator.problemDefinition.toInputMap(startingPoint)
            }
            printer?.let { solver.emitter.attach(it) }
            return solver
        }

        /**
         * Creates and configures an R-SPLINE optimization algorithm for a given problem definition
         * that uses a random restart approach.
         *
         * @param problemDefinition The definition of the optimization problem, including constraints and objectives.
         * @param modelBuilder The model builder interface used to create models for evaluation.
         * @param maxNumRestarts The maximum number of restarts to be performed.
         * @param initialNumReps The initial number of replications to use during each evaluation. Defaults to defaultInitialSampleSize.
         * @param sampleSizeGrowthRate The growth rate of the sample size as the solver progresses. Defaults to defaultSampleSizeGrowthRate.
         * @param maxNumReplications The maximum number of replications by growth rate. Defaults to defaultMaxNumReplications.
         * @param maxIterations The maximum number of iterations the algorithm will run. Defaults to 1000.
         * @param replicationsPerEvaluation The number of replications to use during each evaluation to reduce
         * stochastic noise. Defaults to 50.
         * @param restartPrinter Optional callback function to print or handle intermediate solutions. Can be used to
         * observe the restart optimization process.
         * @param printer Optional callback function to print or handle intermediate solutions. Can be used to
         *    observe the inner solver optimization process.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun rSplineSolverWithRestarts(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            initialNumReps: Int = defaultInitialSampleSize,
            sampleSizeGrowthRate: Double = defaultReplicationGrowthRate,
            maxNumReplications: Int = defaultMaxNumReplications,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            restartPrinter: ((Solver) -> Unit)? = null,
            printer: ((Solver) -> Unit)? = null
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder
            )
            val solver = RSplineSolver(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                maxIterations = maxIterations,
                initialNumReps = initialNumReps,
                sampleSizeGrowthRate = sampleSizeGrowthRate,
                maxNumReplications = maxNumReplications
            )
            val restartSolver = RandomRestartSolver(
                solver, maxNumRestarts
            )
            restartPrinter?.let { restartSolver.emitter.attach(it) }
            printer?.let { solver.emitter.attach(it) }
            return restartSolver
        }
    }

}