package ksl.utilities.rootfinding

import ksl.utilities.observers.Emitter

// ---------------------------------------------------------------------------
// Per-step emitter
// ---------------------------------------------------------------------------

/**
 *  Promises the ability to emit an [SAStep] snapshot after each completed
 *  iteration. Emission is gated by [StochasticApproximationRootFinder.snapShotFrequency]
 *  and [Emitter.isObserved]. When no subscriber is attached and saveSteps is false,
 *  no SAStep object is allocated for that iteration.
 *
 *  Usage:
 *  ```kotlin
 *  val conn = finder.stepEmitter.attach { step ->
 *      println("Iteration ${step.iterationCount}: x = ${step.x}")
 *  }
 *  finder.stepEmitter.detach(conn) // unsubscribe
 *  ```
 */
interface SAStepEmitterIfc {
    val stepEmitter: Emitter<SAStep>
}

/** Default implementation. Delegated to by [StochasticApproximationRootFinder]. */
class SAStepEmitter : SAStepEmitterIfc {
    override val stepEmitter: Emitter<SAStep> = Emitter()
}

// ---------------------------------------------------------------------------
// Lifecycle emitter
// ---------------------------------------------------------------------------

/**
 *  Promises the ability to emit an [SAStatus] at each process lifecycle
 *  transition. Fires unconditionally — at most four times per run with
 *  negligible allocation cost.
 *
 *  Usage:
 *  ```kotlin
 *  finder.lifeCycleEmitter.attach { status ->
 *      when (status) {
 *          SAStatus.CONVERGED         -> println("Root: ${finder.currentX}")
 *          SAStatus.EXHAUSTED         -> println("Max iterations reached.")
 *          SAStatus.DEGENERATE_BOUNCE -> println("Boundary degeneracy — result unreliable.")
 *          SAStatus.INITIALIZED       -> println("Ready.")
 *      }
 *  }
 *  ```
 */
interface SALifeCycleEmitterIfc {
    val lifeCycleEmitter: Emitter<SAStatus>
}

/** Default implementation. Delegated to by [StochasticApproximationRootFinder]. */
class SALifeCycleEmitter : SALifeCycleEmitterIfc {
    override val lifeCycleEmitter: Emitter<SAStatus> = Emitter()
}