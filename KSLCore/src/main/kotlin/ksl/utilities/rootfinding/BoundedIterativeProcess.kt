package ksl.utilities.rootfinding

import ksl.simulation.IterativeProcess
import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc

/**
 *  Abstract base for root-finding algorithms operating within a bounded search
 *  interval. Parallel branch to [RootFinder] directly under [IterativeProcess].
 *  Does not impose the sign-change (bracketing) constraint — concrete subclasses
 *  that require it assert it in their own init blocks via [RootFinder.Companion.hasRoot].
 *
 *  ### Hierarchy
 *  ```
 *  IterativeProcess<T>
 *      └── FunctionalIterator          (existing — untouched)
 *              └── RootFinder          (existing — untouched)
 *      └── BoundedIterativeProcess<T>  (this class)
 *              └── StochasticApproximationRootFinder
 *  ```
 *
 *  ### Stopping
 *  - [hasNextStep] returns false once [maxIterations] is reached → COMPLETED_ALL_STEPS.
 *  - Convergence is signalled by calling [stop] in [checkStoppingCondition] → MET_STOPPING_CONDITION.
 *  - Degenerate boundary conditions call [stop] with a diagnostic message → same status.
 *
 *  ### Observability note
 *  Concrete subclasses may supplement IterativeProcess's Observable<T> notification
 *  with typed Emitter pairs via Kotlin interface delegation — one for per-step data,
 *  one for lifecycle transitions. See [StochasticApproximationRootFinder].
 *
 *  @param T               Step type for Observable<T>.
 *  @param func            Function whose root is sought.
 *  @param interval        Bounded search interval. lowerLimit must be < upperLimit.
 *  @param initialPoint    Starting candidate root. Must lie within interval.
 *  @param maxIterations   Max iterations before termination. Must be > 0.
 *  @param desiredPrecision Convergence target. Must be > 0.
 */
abstract class BoundedIterativeProcess<T>(
    func: FunctionIfc,
    interval: Interval,
    initialPoint: Double = (interval.lowerLimit + interval.upperLimit) / 2.0,
    maxIterations: Int = 10_000,
    desiredPrecision: Double = 1e-4
) : IterativeProcess<T>() {

    init {
        require(interval.lowerLimit < interval.upperLimit) {
            "Interval lower limit must be strictly less than upper limit: $interval"
        }
        require(interval.contains(initialPoint)) {
            "Initial point $initialPoint must lie within interval $interval"
        }
        require(maxIterations > 0) {
            "Maximum iterations must be > 0, was: $maxIterations"
        }
        require(desiredPrecision > 0.0) {
            "Desired precision must be > 0.0, was: $desiredPrecision"
        }
    }

    protected var func: FunctionIfc = func
    protected var interval: Interval = interval

    var initialPoint: Double = initialPoint
        protected set

    var maxIterations: Int = maxIterations
        set(value) {
            require(value > 0) { "Maximum iterations must be > 0, was: $value" }
            field = value
        }

    var desiredPrecision: Double = desiredPrecision
        set(value) {
            require(value > 0.0) { "Desired precision must be > 0.0, was: $value" }
            field = value
        }

    /** Current candidate root. Always updated atomically with [currentFOfX] via [assignCandidateX]. */
    var currentX: Double = initialPoint
        protected set

    /** f([currentX]). Always updated atomically with [currentX] via [assignCandidateX]. */
    var currentFOfX: Double = func.f(initialPoint)
        protected set

    /**
     *  Atomically advances the candidate root to [x] and recomputes [currentFOfX] = f(x).
     *  All subclass code that moves the candidate root must use this method.
     */
    protected fun assignCandidateX(x: Double) {
        currentX = x
        currentFOfX = func.f(x)
    }

    val intervalLowerLimit: Double get() = interval.lowerLimit
    val intervalUpperLimit: Double get() = interval.upperLimit

    /** Returns true while not stopped and iteration count has not reached [maxIterations]. */
    override fun hasNextStep(): Boolean = !isDone && numberStepsCompleted < maxIterations

    /**
     *  Resets process bookkeeping and re-seeds [currentX]/[currentFOfX] from [initialPoint].
     *  Subclasses must call super.initializeIterations() first.
     */
    override fun initializeIterations() {
        super.initializeIterations()
        assignCandidateX(initialPoint)
    }

    /**
     *  Tests whether [func] has a bracketing sign-change root within [anInterval].
     *  Delegates to [RootFinder.Companion.hasRoot] so subclasses do not need a
     *  direct RootFinder import for the bracketing check.
     */
    fun hasRoot(anInterval: Interval): Boolean = RootFinder.hasRoot(func, anInterval)

    override fun toString(): String {
        val sb = StringBuilder()
        sb.appendLine(super.toString())
        sb.appendLine("Search Interval        : $interval")
        sb.appendLine("Initial Point          : $initialPoint")
        sb.appendLine("Maximum Iterations     : $maxIterations")
        sb.appendLine("Desired Precision      : $desiredPrecision")
        sb.appendLine("Current x              : $currentX")
        sb.appendLine("Current f(x)           : $currentFOfX")
        return sb.toString()
    }
}