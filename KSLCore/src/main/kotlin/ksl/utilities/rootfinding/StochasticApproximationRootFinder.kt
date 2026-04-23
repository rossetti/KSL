package ksl.utilities.rootfinding

import ksl.utilities.Interval
import ksl.utilities.math.FunctionIfc
import ksl.utilities.math.KSLMath
import ksl.utilities.random.rng.RNStreamIfc
import ksl.utilities.random.rng.RNStreamProviderIfc
import ksl.utilities.random.rvariable.KSLRandom
import kotlin.math.abs
import kotlin.math.sign

/**
 *  Implements the Robbins-Monroe stochastic approximation root-finding algorithm
 *  with Kesten acceleration and an exponentially weighted stopping criterion.
 *
 *  ### Algorithm
 *  ```
 *  x_{n+1} = xn - scaleFactor * an * f(xn)
 *  an <- 1 / (1/a_{n-1} + 1)   [when sign of f(x) changes — Kesten acceleration]
 *  rsc = alpha * rsc + (1 - alpha) * f(x)
 *  stoppingCriteria = |scaleFactor * an * rsc|
 *  ```
 *  Convergence declared when stoppingCriteria < desiredPrecision.
 *
 *  ### Boundary bounce
 *  Overshoot draws x ~ Uniform from the sub-interval [lowerLimit, currentX] or
 *  [currentX, upperLimit] using [rnStream]. If currentX already sits at the violated
 *  boundary (degenerate case), the process hard-stops and emits DEGENERATE_BOUNCE.
 *
 *  ### Observability
 *  - [stepEmitter]      Emitter<SAStep>   — per-iteration snapshots, frequency-gated.
 *  - [lifeCycleEmitter] Emitter<SAStatus> — unconditional at each lifecycle transition.
 *  No SAStep is allocated when nothing is listening and saveSteps is false.
 *
 *  @param func             Function with a sign-change root within [interval].
 *  @param interval         Bracketing search interval.
 *  @param initialPoint     Starting candidate root. Defaults to interval midpoint.
 *  @param scaleFactor      Robbins-Monroe step scaling constant. Must be > 0.
 *  @param alpha            RSC smoothing parameter. Must be in [0.0, 1.0].
 *  @param maxIterations    Iteration budget. Default: DEFAULT_MAX_ITER.
 *  @param desiredPrecision Convergence threshold. Default: DEFAULT_PRECISION.
 *  @param streamNum        Stream number from [streamProvider]. 0 = next available,
 *                          matching the ExponentialRV convention.
 *  @param streamProvider   Random number stream provider. Default: KSLRandom.DefaultRNStreamProvider.
 */
class StochasticApproximationRootFinder(
    func: FunctionIfc,
    interval: Interval,
    initialPoint: Double = (interval.lowerLimit + interval.upperLimit) / 2.0,
    scaleFactor: Double = DEFAULT_SCALE_FACTOR,
    alpha: Double = DEFAULT_ALPHA,
    maxIterations: Int = DEFAULT_MAX_ITER,
    desiredPrecision: Double = DEFAULT_PRECISION,
    streamNum: Int = 0,
    streamProvider: RNStreamProviderIfc = KSLRandom.DefaultRNStreamProvider
) : BoundedIterativeProcess<SAStep>(func, interval, initialPoint, maxIterations, desiredPrecision),
    SAStepEmitterIfc by SAStepEmitter(),
    SALifeCycleEmitterIfc by SALifeCycleEmitter() {

    // -------------------------------------------------------------------------
    // Construction validation
    // -------------------------------------------------------------------------

    init {
        require(RootFinder.hasRoot(func, interval)) {
            "StochasticApproximationRootFinder requires a bracketing interval: " +
                    "f(${interval.lowerLimit}) and f(${interval.upperLimit}) must have opposite signs."
        }
        require(scaleFactor > 0.0) { "Scale factor must be > 0.0, was: $scaleFactor" }
        require(alpha in 0.0..1.0) { "Alpha must be in [0.0, 1.0], was: $alpha" }
        require(streamNum >= 0)    { "Stream number must be >= 0, was: $streamNum" }
    }

    // -------------------------------------------------------------------------
    // Random stream — obtained once at construction, following ExponentialRV
    // -------------------------------------------------------------------------

    /**
     *  Controlled random number stream for all boundary bounce draws.
     *  streamNum = 0 requests the next available stream from the provider,
     *  matching the convention of ExponentialRV and other KSL random variates.
     */
    val rnStream: RNStreamIfc = if (streamNum == 0)
        streamProvider.nextRNStream()
    else
        streamProvider.rnStream(streamNum)

    // -------------------------------------------------------------------------
    // Algorithm parameters
    // -------------------------------------------------------------------------

    var scaleFactor: Double = scaleFactor
        set(value) {
            require(value > 0.0) { "Scale factor must be > 0.0, was: $value" }
            field = value
        }

    var alpha: Double = alpha
        set(value) {
            require(value in 0.0..1.0) { "Alpha must be in [0.0, 1.0], was: $value" }
            field = value
        }

    // -------------------------------------------------------------------------
    // Emission frequency
    // -------------------------------------------------------------------------

    /**
     *  Controls how frequently [stepEmitter] fires. Value of N emits every Nth step.
     *  Does not affect [saveSteps] accumulation. Validated > 0 on assignment.
     */
    var snapShotFrequency: Int = 1
        set(value) {
            require(value > 0) { "snapShotFrequency must be > 0, was: $value" }
            field = value
        }

    // -------------------------------------------------------------------------
    // Step saving
    // -------------------------------------------------------------------------

    /**
     *  When true, every SAStep is appended to the internal list accessible via [steps].
     *  Independent of [stepEmitter] — set before calling [run].
     */
    var saveSteps: Boolean = false

    private val _steps: MutableList<SAStep> = mutableListOf()

    /** All SAStep instances from the most recent run. Defensive copy. */
    val steps: List<SAStep>
        get() = _steps.toList()

    // -------------------------------------------------------------------------
    // SA algorithm state
    // -------------------------------------------------------------------------

    /** Robbins-Monroe series value. Starts at 1.0, shrunk by Kesten at each sign change. */
    private var rmSeries: Double = 1.0

    /**
     *  Exponentially smoothed stopping criterion accumulator.
     *  Seeded from f(initialPoint). Updated each step: rsc = alpha*rsc + (1-alpha)*f(x).
     */
    private var rsc: Double = 0.0

    /** f(x) from the previous iteration, used by the Kesten sign-change test. */
    private var prevFOfX: Double = Double.NaN

    // -------------------------------------------------------------------------
    // Convergence signal
    // -------------------------------------------------------------------------

    /**
     *  Current stopping criterion: |scaleFactor * rmSeries * rsc|.
     *  Convergence declared by [checkStoppingCondition] when this < [desiredPrecision].
     */
    val stoppingCriteria: Double
        get() = abs(scaleFactor * rmSeries * rsc)

    // -------------------------------------------------------------------------
    // IterativeProcess lifecycle
    // -------------------------------------------------------------------------

    /**
     *  Resets all SA state for a fresh run.
     *  super.initializeIterations() resets IterativeProcess bookkeeping and
     *  calls setCurrentX(initialPoint), seeding currentX and currentFOfX,
     *  before this method reads them.
     */
    override fun initializeIterations() {
        super.initializeIterations()     // resets IterativeProcess + setCurrentX(initialPoint)
        rmSeries  = 1.0
        prevFOfX  = currentFOfX         // f(initialPoint), already evaluated by super
        rsc       = currentFOfX
        _steps.clear()
        lifeCycleEmitter.emit(SAStatus.INITIALIZED)
    }

    /**
     *  Emits CONVERGED and calls stop() when stoppingCriteria < desiredPrecision.
     *  Called by IterativeProcess.stoppingConditionCheck() after each step.
     */
    override fun checkStoppingCondition() {
        if (stoppingCriteria < desiredPrecision) {
            lifeCycleEmitter.emit(SAStatus.CONVERGED)
            stop(
                "SA converged: stoppingCriteria ($stoppingCriteria) < desiredPrecision " +
                        "($desiredPrecision) at iteration $numberStepsCompleted."
            )
        }
    }

    /**
     *  Emits EXHAUSTED when the process ends because all iterations were consumed
     *  without convergence. Delegates to super.endIterations() to complete the
     *  state-machine transition. Convergence and bounce statuses are emitted at
     *  their own sites and do not need re-emission here.
     */
    override fun endIterations() {
        if (allStepsCompleted) {
            lifeCycleEmitter.emit(SAStatus.EXHAUSTED)
        }
        super.endIterations()
    }

    // -------------------------------------------------------------------------
    // Core algorithm
    // -------------------------------------------------------------------------

    /**
     *  Advances all mutable SA state by one iteration.
     *
     *  1. Kesten check — shrink rmSeries if sign changed since last step.
     *  2. Compute candidate x = currentX - scaleFactor * rmSeries * currentFOfX.
     *  3. Boundary bounce:
     *     - Degenerate: emit DEGENERATE_BOUNCE, call stop(), return immediately.
     *     - Normal overshoot: draw from Uniform(lowerLimit, currentX) or
     *       Uniform(currentX, upperLimit) using rnStream.
     *  4. Update prevFOfX, setCurrentX(x), update rsc.
     *
     *  Private — called exclusively from runStep(). nextStep() is the snapshot
     *  factory called by IterativeProcess machinery after state is already advanced.
     */
    private fun advanceAlgorithmState() {
        // 1. Kesten acceleration
        if (sign(prevFOfX) != sign(currentFOfX)) {
            rmSeries = 1.0 / (1.0 / rmSeries + 1.0)
        }

        // 2. Candidate root value
        var x = currentX - scaleFactor * rmSeries * currentFOfX

        // 3. Boundary bounce
        when {
            x < interval.lowerLimit -> {
                if (KSLMath.equal(currentX, interval.lowerLimit)) {
                    lifeCycleEmitter.emit(SAStatus.DEGENERATE_BOUNCE)
                    stop(
                        "Degenerate lower boundary bounce at iteration $numberStepsCompleted: " +
                                "currentX ($currentX) already equals lowerLimit " +
                                "(${interval.lowerLimit}). Consider widening the interval " +
                                "or adjusting the initial point."
                    )
                    return      // leave currentX / currentFOfX / rsc unchanged
                }
                x = rnStream.rUniform(interval.lowerLimit, currentX)
            }
            x > interval.upperLimit -> {
                if (KSLMath.equal(currentX, interval.upperLimit)) {
                    lifeCycleEmitter.emit(SAStatus.DEGENERATE_BOUNCE)
                    stop(
                        "Degenerate upper boundary bounce at iteration $numberStepsCompleted: " +
                                "currentX ($currentX) already equals upperLimit " +
                                "(${interval.upperLimit}). Consider widening the interval " +
                                "or adjusting the initial point."
                    )
                    return
                }
                x = rnStream.rUniform(currentX, interval.upperLimit)
            }
        }

        // 4. Advance state
        prevFOfX = currentFOfX
        assignCandidateX(x)
        rsc = alpha * rsc + (1.0 - alpha) * currentFOfX
    }

    /**
     *  Constructs an immutable SAStep from current state. Pure — no mutations.
     *  Called from runStep() only when a consumer actually needs the snapshot,
     *  keeping the hot path allocation-free when nothing is listening.
     *
     *  iterationCount = numberStepsCompleted + 1 because this is called inside
     *  runStep() before IterativeProcess increments the counter.
     */
    private fun makeSAStep(): SAStep = SAStep(
        x                = currentX,
        fOfX             = currentFOfX,
        rmSeries         = rmSeries,
        rsc              = rsc,
        stoppingCriteria = stoppingCriteria,
        iterationCount   = numberStepsCompleted + 1
    )

    /**
     *  Called by IterativeProcess machinery for the Observable<SAStep> notification path.
     *  Algorithm state is already advanced by runStep() before this is called;
     *  nextStep() here is a pure snapshot factory.
     */
    override fun nextStep(): SAStep? = if (!hasNextStep()) null else makeSAStep()

    /**
     *  Advances algorithm state, then constructs and routes a snapshot only when
     *  a consumer actually needs it.
     *
     *  Consumer check — evaluated once per iteration:
     *  ```kotlin
     *  val needsStep = saveSteps || stepEmitter.isObserved
     *  ```
     *  When false: tight arithmetic loop with zero per-iteration heap allocation.
     *  When true:
     *  - myCurrentStep is set (feeds Observable notification from IterativeProcess).
     *  - If saveSteps: snapshot appended to _steps.
     *  - If stepEmitter.isObserved and step matches snapShotFrequency: snapshot emitted.
     */
    override fun runStep() {
        advanceAlgorithmState()

        val needsStep = saveSteps || stepEmitter.isObserved
        if (needsStep) {
            val step = makeSAStep()
            myCurrentStep = step
            if (saveSteps) {
                _steps.add(step)
            }
            if (stepEmitter.isObserved && (numberStepsCompleted + 1) % snapShotFrequency == 0) {
                stepEmitter.emit(step)
            }
        } else {
            myCurrentStep = null
        }
    }

    // -------------------------------------------------------------------------
    // toString
    // -------------------------------------------------------------------------

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append(super.toString()).appendLine()
        sb.appendLine("Scale Factor           : $scaleFactor")
        sb.appendLine("Alpha (smoothing)      : $alpha")
        sb.appendLine("Snapshot Frequency     : $snapShotFrequency")
        sb.appendLine("RM Series              : $rmSeries")
        sb.appendLine("RSC                    : $rsc")
        sb.appendLine("Stopping Criteria      : $stoppingCriteria")
        sb.appendLine("Converged?             : $stoppedByCondition")
        sb.appendLine("Steps Saved            : ${_steps.size}")
        return sb.toString()
    }

    // -------------------------------------------------------------------------
    // Companion object
    // -------------------------------------------------------------------------

    companion object {

        const val DEFAULT_PRECISION: Double    = 0.0001
        const val DEFAULT_ALPHA: Double        = 0.7
        const val DEFAULT_SCALE_FACTOR: Double = 100.0
        const val DEFAULT_MAX_ITER: Int        = 10_000

        /**
         *  Recommends a scaleFactor by fitting a linear approximation to [func]
         *  near [initialPt] over a +/-[delta] neighbourhood.
         *
         *  ```
         *  factor = min(1/|f(initialPt)|, 1/|slope|)
         *  ```
         *  Clamped to [1.0, DEFAULT_SCALE_FACTOR]. Floored to 1.0 if the computed
         *  value falls below DEFAULT_SCALE_FACTOR * DEFAULT_PRECISION.
         *
         *  This is a heuristic. Verify convergence and adjust manually if needed.
         *
         *  @param func      Function being searched.
         *  @param interval  Used to clamp the evaluation neighbourhood to the valid domain.
         *  @param initialPt Point around which the approximation is made. Must be in [interval].
         *  @param delta     Neighbourhood half-width. Must be > 0.
         *  @return Recommended scale factor in [1.0, DEFAULT_SCALE_FACTOR].
         */
        fun recommendScalingFactor(
            func: FunctionIfc,
            interval: Interval,
            initialPt: Double,
            delta: Double
        ): Double {
            require(interval.contains(initialPt)) {
                "Initial point $initialPt is not within interval $interval"
            }
            require(delta > 0.0) { "delta must be > 0.0, was: $delta" }

            val xu = minOf(initialPt + delta, interval.upperLimit)
            val xl = maxOf(initialPt - delta, interval.lowerLimit)
            val fu = func.f(xu)
            val fl = func.f(xl)
            val fp = func.f(initialPt)

            val slope = if (KSLMath.equal(xu, xl)) Double.MAX_VALUE
            else (fu - fl) / (xu - xl)

            val factor = minOf(1.0 / abs(fp), 1.0 / abs(slope))

            return when {
                factor < DEFAULT_SCALE_FACTOR * DEFAULT_PRECISION -> 1.0
                factor > DEFAULT_SCALE_FACTOR                     -> DEFAULT_SCALE_FACTOR
                else                                              -> factor
            }
        }
    }
}

// =============================================================================
// Demonstration
// =============================================================================

fun main() {

    // f(x) = x^3 + 4x^2 - 10 on [1, 2]   (true root ~1.3652)
    val f = FunctionIfc { x -> x * x * x + 4.0 * x * x - 10.0 }
    val searchInterval = Interval(1.0, 2.0)
    val midpoint = 1.5

    // ---- Recommend a scale factor ------------------------------------------------
    val recommended = StochasticApproximationRootFinder.recommendScalingFactor(
        func      = f,
        interval  = searchInterval,
        initialPt = midpoint,
        delta     = 0.1
    )
    println("Recommended scale factor : $recommended")
    println()

    // ---- Construct with constructor parameters only ------------------------------
    val sa = StochasticApproximationRootFinder(
        func             = f,
        interval         = searchInterval,
        initialPoint     = midpoint,
        scaleFactor      = recommended
    )

    // ---- Properties set after construction ---------------------------------------
//    sa.snapShotFrequency = 500
//    sa.saveSteps         = true

    // ---- Lifecycle subscriber: fires at most 4 times per run --------------------
    sa.lifeCycleEmitter.attach { status ->
        println("[lifecycle] $status  |  x = ${sa.currentX}  |  f(x) = ${sa.currentFOfX}")
    }

    // ---- Step subscriber: fires every snapShotFrequency iterations ---------------
    sa.stepEmitter.attach { step ->
        println(
            "[step ${step.iterationCount}]  " +
                    "x = ${step.x}  " +
                    "f(x) = ${step.fOfX}  " +
                    "criteria = ${step.stoppingCriteria}"
        )
    }

    // ---- Run --------------------------------------------------------------------
    sa.run()

    // ---- Results ----------------------------------------------------------------
    println()
    println(sa)
    println("Total steps saved : ${sa.steps.size}")
    println("Final root        : ${sa.currentX}")
    println("f(root)           : ${sa.currentFOfX}")
}