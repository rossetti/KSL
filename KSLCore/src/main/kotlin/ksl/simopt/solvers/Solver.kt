package ksl.simopt.solvers

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simopt.cache.MemorySolutionCache
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.cache.SolutionCacheIfc
import ksl.simopt.evaluator.*
import ksl.simopt.problem.InputMap
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.problem.ProblemDefinition.Companion.defaultMaximumFeasibleSamplingIterations
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultReplicationGrowthRate
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultMaxNumReplications
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simopt.solvers.algorithms.CESamplerIfc
import ksl.simopt.solvers.algorithms.CoolingScheduleIfc
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.ExponentialCoolingSchedule
import ksl.simopt.solvers.algorithms.RSplineSolver
import ksl.simopt.solvers.algorithms.RSplineSolver.Companion.defaultInitialSampleSize
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.RandomRestartSolver.Companion.defaultMaxRestarts
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.SimulatedAnnealing.Companion.defaultInitialTemperature
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.algorithms.TemperatureConfiguration
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.IterativeProcess
import ksl.simulation.IterativeProcessStatusIfc
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Identity
import ksl.utilities.IdentityIfc
import ksl.utilities.observers.Emitter
import ksl.utilities.random.rng.RNStreamIfc

enum class SolverStatus {
    INITIALIZED, // Emitted after state reset, right before iteration 0
    STARTED,     // Emitted right as the algorithmic while-loop begins
    COMPLETED,   // Emitted when the run finishes naturally
    ERROR        // Emitted if an unhandled exception breaks the loop
}

/**
 *  A promise to emit the solver's state
 */
interface IterationEmitterIfc {
    val iterationEmitter: Emitter<SolverStateSnapshot>
}

class IterationEmitter : IterationEmitterIfc {
    override val iterationEmitter: Emitter<SolverStateSnapshot> = Emitter()
}

interface LifeCycleEmitterIfc {
    val lifeCycleEmitter: Emitter<SolverStatus>
}

class LifeCycleEmitter : LifeCycleEmitterIfc {
    override val lifeCycleEmitter: Emitter<SolverStatus> = Emitter()
}

/**
 * An immutable representation of a solver's state at a specific point in time during the optimization process.
 * * This class is designed to be emitted or recorded at the end of algorithmic iterations (or macro-steps).
 * Because it is strictly an immutable snapshot, it can be safely stored in historical lists or passed
 * across threads without risking the "live reference" mutation problem.
 *
 * @param iterationNumber The current algorithmic iteration or step at which this snapshot was taken.
 * @param numOracleCalls The cumulative total number of times the simulation oracle has been invoked by the solver up to this point.
 * @param numReplicationsRequested The cumulative total number of individual simulation replications requested across all oracle calls up to this point.
 * @param bestSolutionSoFar The best [Solution] discovered by the solver up to this iteration.
 * @param estimatedObjFncValue The objective function value associated with the [bestSolutionSoFar].
 * @param penalizedObjFncValue The penalized objective function value associated with the [bestSolutionSoFar].
 * @param currentSolution The latest solution found. It may not be the best due to algorithm trajectories.
 * @param solverSpecificState An optional map containing algorithm-specific metrics that do not apply generally to all solvers
 * (e.g., `mapOf("temperature" to 50.0)` for Simulated Annealing, or `"splineCalls"` for R-SPLINE). Defaults to `null`.
 */
data class SolverStateSnapshot(
    val iterationNumber: Int,
    val numOracleCalls: Int,
    val numReplicationsRequested: Int,
    val bestSolutionSoFar: Solution,
    val currentSolution: Solution,
    val estimatedObjFncValue: Double = bestSolutionSoFar.estimatedObjFncValue,
    val penalizedObjFncValue: Double = bestSolutionSoFar.penalizedObjFncValue,
    val solverSpecificState: Map<String, Double>? = null
)

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
) : IdentityIfc by Identity(name), Comparator<Solution>, IterationEmitterIfc by IterationEmitter(),
    LifeCycleEmitterIfc by LifeCycleEmitter() {

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
     *  If a listener is attached to the solver via its iterationEmitter property,
     *  then snapshots of the solver's state are captured at the end of each iteration
     *  according to this specified frequency. For example, if the frequency is 10,
     *  then every 10th iteration is emitted. The default is 1.  If nothing listens
     *  for emissions, then no snapshots are emitted.
     */
    var snapShotFrequency: Int = 1
        set(value) {
            require(value > 0) { "snapshot frequency must be > 0" }
            field = value
        }

    /**
     *  The outer iterative process. See [IterativeProcess] for
     *  the iterative process pattern.
     */
    private val myMainIterativeProcess = MainIterativeProcess()

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
     *  The difference between the current solution's penalized objective function value
     *  and the previous solution's penalized objective function value.
     */
    val penalizedSolutionGap: Double
        get() = currentSolution.penalizedObjFncValue - previousSolution.penalizedObjFncValue

    /**
     *  The difference between the current solution's unpenalized objective function value
     *  and the previous solution's unpenalized objective function value.
     */
    @Suppress("unused")
    val unPenalizedSolutionGap: Double
        get() = currentSolution.estimatedObjFncValue - previousSolution.estimatedObjFncValue

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
            myBestSolutions.add(value)
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
        try {
            myMainIterativeProcess.run()
        } catch (e: Exception) {
        // Catches anything that breaks the internal loop or initialization.
            lifeCycleEmitter.emit(SolverStatus.ERROR)
            // Re-throw so the framework user knows their run failed.
            throw e
        }
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
     * equivalent, 1 if the first is larger than the second solution.
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
     * Creates an immutable snapshot of the solver's current state.
     * This is typically called at the end of an iteration to broadcast the state
     * to any attached listeners safely.
     */
    protected open fun makeSolverStateSnapshot(): SolverStateSnapshot {
        // Grab the best solution currently tracked by the solver.
        return SolverStateSnapshot(
            iterationNumber = iterationCounter,
            numOracleCalls = numOracleCalls,
            numReplicationsRequested = numReplicationsRequested,
            bestSolutionSoFar = bestSolution,
            currentSolution = currentSolution,
            // Delegate to the subclass hook to fetch algorithm-specific state
            solverSpecificState = extractSolverSpecificState()
        )
    }

    /**
     * A hook for subclasses to inject their specific internal state metrics into the snapshot
     * without having to override the entire [makeSolverStateSnapshot] method.
     * * @return A map of algorithm-specific state variables, or null if none exist.
     */
    protected open fun extractSolverSpecificState(): Map<String, Double>? {
        return null
    }

    /**
     * Generates a neighboring point based on the current point represented by the input map.
     * This method determines the next potential point in the iterative process, either through a
     * neighbor generator or by randomizing the value of a randomly selected input variable.
     * Unless a neighborhood generator is supplied, the resulting point will be input-range feasible.
     * Thus, it may be infeasible with respect to deterministic constraints.
     * If a neighborhood generator is not supplied, the approach is to randomly select one
     * of the coordinates (inputs) and then randomly generating an input-range feasible value
     * for the selected input.
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
            // Note: this randomly picks a coordinate (input name) and then randomly generates an
            // input range value uniformly over its range.
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
    ): Map<ModelInputs, Solution> {
        val caching = if (crnOption) false else cachingAllowed
        val requests = prepareModelInputs(inputs, numReps)
        return requestEvaluations(requests, crnOption, caching)
    }

    /**
     * Requests evaluations for a set of input maps. The function prepares the evaluation requests
     * from the provided inputs and then performs evaluations to generate solutions. The
     * evaluations will be performed using the common random numbers.
     *
     * @param inputs a set of input maps, where each map contains input variables and their respective values
     * @param numReps the number of replications for each of the requested evaluations
     * @return a list of solutions obtained after performing evaluations on the inputs
     */
    @Suppress("unused")
    protected fun requestEvaluationsWithCRN(
        inputs: Set<InputMap>,
        numReps: Int = replicationsPerEvaluation.numReplicationsPerEvaluation(this),
    ): Map<ModelInputs, Solution> {
        return requestEvaluations(inputs, numReps, true)
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
        val evaluationRequest =
            EvaluationRequest(problemDefinition.modelIdentifier, modelInputs, crnOption, cachingAllowed)
        return evaluator.evaluate(evaluationRequest)
    }

    override fun toString(): String {
        return """
        Solver(
            name = $name,
            problemDefinition = ${problemDefinition.name},
            maximumNumberIterations = $maximumNumberIterations,
            snapShotFrequency = $snapShotFrequency,
            ensureProblemFeasibleRequests = $ensureProblemFeasibleRequests,
            maxFeasibleSamplingIterations = $maxFeasibleSamplingIterations,
            solutionPrecision = $solutionPrecision,
            replicationsPerEvaluation = ${replicationsPerEvaluation::class.simpleName},
            startingPoint = ${if (startingPoint != null) "Provided" else "Not Provided (Will Auto-Generate)"},
            neighborGenerator = ${neighborGenerator?.let { it::class.simpleName } ?: "None"},
            solutionComparer = ${solutionComparer?.let { it::class.simpleName } ?: "Default"},
            solutionQualityEvaluator = ${solutionQualityEvaluator?.let { it::class.simpleName } ?: "None"}
        )
    """.trimIndent()
    }

    /**
     * Prints the current results of the optimization run to the console.
     * This includes details about the solver's performance,
     */
    fun printResults() {
        println(solverResult)
    }

    /**
     * Captures the current results of the optimization run.
     * Guarantees a valid result object, returning a pending state if not yet executed.
     */
    val solverResult: SolverResult
        get() {
            val sName = this.name ?: this::class.simpleName ?: "UnknownSolver"
            val pName = this.problemDefinition.name

            // 1. Return the informative "NotExecuted" state if no work has been done
            if (iterationCounter == 0 && evaluator.totalRequestsReceived == 0) {
                return SolverResult.NotExecuted(sName, pName)
            }

            // 2. Otherwise, gather the metrics and return the "Completed" state
            val evalMetrics = EvaluatorMetrics(
                totalRequestsReceived = this.evaluator.totalRequestsReceived,
                totalEvaluations = this.evaluator.totalEvaluations,
                totalOracleEvaluations = this.evaluator.totalOracleEvaluations,
                totalCachedEvaluations = this.evaluator.totalCachedEvaluations,
                totalReplications = this.evaluator.totalReplications,
                totalOracleReplications = this.evaluator.totalOracleReplications,
                totalCachedReplications = this.evaluator.totalCachedReplications
            )

            return SolverResult.Completed(
                solverName = sName,
                problemName = pName,
                initialSolution = this.myInitialSolution,
                currentSolution = this.currentSolution,
                bestSolution = this.bestSolution,
                totalIterations = this.iterationCounter,
                evaluatorMetrics = evalMetrics,
                isStoppingCriteriaMet = this.isStoppingCriteriaSatisfied()
            )
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
            lifeCycleEmitter.emit(SolverStatus.INITIALIZED)
            if(iterationEmitter.isObserved){
                iterationEmitter.emit(makeSolverStateSnapshot())
            }
            lifeCycleEmitter.emit(SolverStatus.STARTED)
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
            if (iterationCounter % snapShotFrequency == 0 && iterationEmitter.isObserved) {
                val snapshot = makeSolverStateSnapshot()
                iterationEmitter.emit(snapshot)
            }

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
            lifeCycleEmitter.emit(SolverStatus.COMPLETED)
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
         * @param solutionCache Specifies if the evaluator uses a solution cache. By default, this is [MemorySolutionCache].
         * @param simulationRunCache Specifies if the simulation oracle will use a SimulationRunCache. The default
         * is null (no cache).
         * @param experimentRunParameters the run parameters to apply to the model during the building process
         * @param defaultKSLDatabaseObserverOption indicates if a default KSL database should be created and attached
         * to the model. The default is false.
         * @return A configured instance of the `StochasticHillClimber` ready to begin optimization.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createStochasticHillClimbingSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): StochasticHillClimber {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder, solutionCache = solutionCache,
                simulationRunCache = simulationRunCache, experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
            val sp = startingPoint ?: problemDefinition.startingPoint().toMutableMap()
            val shc = StochasticHillClimber(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            shc.startingPoint = problemDefinition.toInputMap(sp)
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
         * @param solutionCache Specifies if the evaluator uses a solution cache. By default, this is [MemorySolutionCache].
         * @param simulationRunCache Specifies if the simulation oracle will use a SimulationRunCache. The default
         * is null (no cache).
         * @param experimentRunParameters the run parameters to apply to the model during the building process
         * @param defaultKSLDatabaseObserverOption indicates if a default KSL database should be created and attached
         * to the model. The default is false.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createRandomRestartStochasticHillClimbingSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder, solutionCache = solutionCache,
                simulationRunCache = simulationRunCache, experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
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
            return restartSolver
        }

        /**
         * Creates a configured Simulated Annealing solver ready for execution.
         *
         * This factory handles the instantiation of the underlying [Evaluator] and binds it to the
         * [SimulatedAnnealing] algorithm. It also allows for the injection of a specific starting point
         * and delegates initial temperature calculations to the provided [TemperatureConfiguration].
         *
         * @param problemDefinition The formal definition of the optimization problem (variables, constraints, objectives).
         * @param modelBuilder The builder responsible for constructing the simulation model for evaluations.
         * @param startingPoint An optional [InputMap] specifying the exact starting coordinates for the solver.
         * If null, the solver will generate a random feasible starting point.
         * @param temperatureConfiguration Dictates whether the solver uses a statically defined temperature or
         * autonomously calibrates its starting temperature via a random walk.
         * Defaults to [TemperatureConfiguration.AutoCalibrate].
         * @param coolingSchedule The strategy for reducing the temperature over time. Defaults to an
         * [ExponentialCoolingSchedule].
         * @param stoppingTemperature The temperature threshold at which the algorithm will terminate.
         * @param maxIterations The maximum number of simulated annealing steps to perform.
         * @param replicationsPerEvaluation The default number of simulation replications to run per point evaluation.
         * @param solutionCache A cache to store evaluated solutions and prevent redundant simulation runs.
         * @param simulationRunCache An optional cache for individual simulation replication data.
         * @param experimentRunParameters Optional parameters defining the simulation run lengths, warmups, etc.
         * @param defaultKSLDatabaseObserverOption If true, automatically attaches default database observers to the evaluator.
         * @return A fully configured [SimulatedAnnealing] solver instance.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createSimulatedAnnealingSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            startingPoint: MutableMap<String, Double>? = null,
            temperatureConfiguration: TemperatureConfiguration = TemperatureConfiguration.AutoCalibrate(),
            coolingSchedule: CoolingScheduleIfc = ExponentialCoolingSchedule(defaultInitialTemperature),
            stoppingTemperature: Double = SimulatedAnnealing.defaultStoppingTemperature,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): SimulatedAnnealing {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                solutionCache = solutionCache,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )
            val sp = startingPoint ?: problemDefinition.startingPoint().toMutableMap()
            val solver = SimulatedAnnealing(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                temperatureConfiguration = temperatureConfiguration,
                coolingSchedule = coolingSchedule,
                stoppingTemperature = stoppingTemperature,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )
            // Inject the specific starting point if the user provided one
            solver.startingPoint = problemDefinition.toInputMap(sp)
            return solver
        }

        /**
         * Creates a Random Restart solver that utilizes Simulated Annealing for its inner optimization phases.
         * * **Architecture Note on Auto-Calibration:**
         * If [temperatureConfiguration] is set to [TemperatureConfiguration.AutoCalibrate], the inner SA solver
         * will dynamically recalculate a new starting temperature at the beginning of *every single restart*.
         * This ensures the initial temperature is perfectly tuned to the local landscape of each new random starting point.
         *
         * @param problemDefinition The formal definition of the optimization problem.
         * @param modelBuilder The builder responsible for constructing the simulation model.
         * @param maxNumRestarts The total number of macro-iterations (restarts) the outer solver should perform.
         * @param startingPoint An optional [InputMap] specifying the starting coordinates for the *first* restart.
         * All subsequent restarts will automatically generate random feasible starting points.
         * @param temperatureConfiguration The temperature strategy applied to each inner SA run. Defaults to AutoCalibrate.
         * @param coolingSchedule The cooling strategy applied to each inner SA run.
         * @param stoppingTemperature The stopping threshold for each inner SA run.
         * @param maxIterations The maximum number of SA steps per restart.
         * @param replicationsPerEvaluation The default number of simulation replications to run per evaluation.
         * @param solutionCache A cache to store evaluated solutions across all restarts.
         * @param simulationRunCache An optional cache for individual simulation replication data.
         * @param experimentRunParameters Optional parameters defining the simulation run properties.
         * @param defaultKSLDatabaseObserverOption If true, automatically attaches default database observers.
         * @return A [RandomRestartSolver] wrapping a dynamically configuring [SimulatedAnnealing] inner solver.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createRandomRestartSimulatedAnnealingSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            startingPoint: MutableMap<String, Double>? = null,
            temperatureConfiguration: TemperatureConfiguration = TemperatureConfiguration.AutoCalibrate(),
            coolingSchedule: CoolingScheduleIfc = ExponentialCoolingSchedule(defaultInitialTemperature),
            stoppingTemperature: Double = SimulatedAnnealing.defaultStoppingTemperature,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): RandomRestartSolver {

            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                solutionCache = solutionCache,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
            )

            val sp = startingPoint ?: problemDefinition.startingPoint().toMutableMap()

            val saSolver = SimulatedAnnealing(
                problemDefinition = problemDefinition,
                evaluator = evaluator,
                temperatureConfiguration = temperatureConfiguration,
                coolingSchedule = coolingSchedule,
                stoppingTemperature = stoppingTemperature,
                maxIterations = maxIterations,
                replicationsPerEvaluation = replicationsPerEvaluation
            )

            val restartSolver = RandomRestartSolver(
                saSolver, maxNumRestarts
            )

            // The random restart solver orchestrates the starting points. We pass the user's
            // specific point to the macro-solver, which feeds it to the SA solver on run #1.
            restartSolver.startingPoint = problemDefinition.toInputMap(sp)
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
         * @param solutionCache Specifies if the evaluator uses a solution cache. By default, this is [MemorySolutionCache].
         * @param simulationRunCache Specifies if the simulation oracle will use a SimulationRunCache. The default
         * is null (no cache).
         * @param experimentRunParameters the run parameters to apply to the model during the building process
         * @param defaultKSLDatabaseObserverOption indicates if a default KSL database should be created and attached
         * to the model. The default is false.
         * @return An instance of CrossEntropySolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createCrossEntropySolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            ceSampler: CESamplerIfc = CENormalSampler(problemDefinition),
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): CrossEntropySolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder, solutionCache = solutionCache,
                simulationRunCache = simulationRunCache, experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
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
         * @param solutionCache Specifies if the evaluator uses a solution cache. By default, this is [MemorySolutionCache].
         * @param simulationRunCache Specifies if the simulation oracle will use a SimulationRunCache. The default
         * is null (no cache).
         * @param experimentRunParameters the run parameters to apply to the model during the building process
         * @param defaultKSLDatabaseObserverOption indicates if a default KSL database should be created and attached
         * to the model. The default is false.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createRandomRestartCrossEntropySolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            ceSampler: CESamplerIfc = CENormalSampler(problemDefinition),
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                solutionCache = solutionCache,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
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
         * @param solutionCache Specifies if the evaluator uses a solution cache. By default, this is [MemorySolutionCache].
         * @param simulationRunCache Specifies if the simulation oracle will use a SimulationRunCache. The default
         * is null (no cache).
         * @param experimentRunParameters the run parameters to apply to the model during the building process
         * @param defaultKSLDatabaseObserverOption indicates if a default KSL database should be created and attached
         * to the model. The default is false.
         * @return An instance of RSplineSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createRsplineSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            initialNumReps: Int = defaultInitialSampleSize,
            sampleSizeGrowthRate: Double = defaultReplicationGrowthRate,
            maxNumReplications: Int = defaultMaxNumReplications,
            startingPoint: MutableMap<String, Double>? = null,
            maxIterations: Int = defaultMaxNumberIterations,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): RSplineSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder, solutionCache = solutionCache,
                simulationRunCache = simulationRunCache, experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
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
         * @param solutionCache Specifies if the evaluator uses a solution cache. By default, this is [MemorySolutionCache].
         * @param simulationRunCache Specifies if the simulation oracle will use a SimulationRunCache. The default
         * is null (no cache).
         * @param experimentRunParameters the run parameters to apply to the model during the building process
         * @param defaultKSLDatabaseObserverOption indicates if a default KSL database should be created and attached
         * to the model. The default is false.
         * @return An instance of RandomRestartSolver that encapsulates the optimization process and results.
         */
        @Suppress("unused")
        @JvmStatic
        @JvmOverloads
        fun createRandomRestartRsplineSolver(
            problemDefinition: ProblemDefinition,
            modelBuilder: ModelBuilderIfc,
            maxNumRestarts: Int = defaultMaxRestarts,
            initialNumReps: Int = defaultInitialSampleSize,
            sampleSizeGrowthRate: Double = defaultReplicationGrowthRate,
            maxNumReplications: Int = defaultMaxNumReplications,
            maxIterations: Int = defaultMaxNumberIterations,
            replicationsPerEvaluation: Int = defaultReplicationsPerEvaluation,
            solutionCache: SolutionCacheIfc = MemorySolutionCache(),
            simulationRunCache: SimulationRunCacheIfc? = null,
            experimentRunParameters: ExperimentRunParametersIfc? = null,
            defaultKSLDatabaseObserverOption: Boolean = false
        ): RandomRestartSolver {
            val evaluator = Evaluator.createProblemEvaluator(
                problemDefinition = problemDefinition, modelBuilder = modelBuilder, solutionCache = solutionCache,
                simulationRunCache = simulationRunCache, experimentRunParameters = experimentRunParameters,
                defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
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
            return restartSolver
        }
    }

}