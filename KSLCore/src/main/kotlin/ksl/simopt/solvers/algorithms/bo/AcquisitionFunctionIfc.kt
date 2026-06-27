package ksl.simopt.solvers.algorithms.bo

import ksl.utilities.distributions.Normal

/**
 *  An acquisition function scores a candidate point from the surrogate's posterior prediction and
 *  the current incumbent value. Larger is better: the acquisition optimizer maximizes it. All
 *  acquisition functions here are written for minimization of the objective.
 */
fun interface AcquisitionFunctionIfc {
    /**
     *  The acquisition value (larger is better) for a candidate.
     *
     *  @param prediction the surrogate's posterior prediction at the candidate
     *  @param incumbent the current incumbent objective value (the value to improve upon)
     *  @param bo the solver requesting the score
     */
    fun value(prediction: SurrogateModelIfc.Prediction, incumbent: Double, bo: BayesianOptimizationSolver): Double
}

/**
 *  Expected Improvement (EI) for minimization. With incumbent `f+`, predictive mean `μ`, and
 *  predictive standard deviation `σ`, the improvement is `I = f+ - μ - xi`, and
 *  `EI = I·Φ(I/σ) + σ·φ(I/σ)` for `σ > 0`, else `max(I, 0)`. This is the default acquisition.
 *
 *  @param xi an exploration margin subtracted from the improvement (larger encourages exploration).
 *  Defaults to [BayesianOptimizationSolver.defaultXi].
 */
class ExpectedImprovement(
    var xi: Double = BayesianOptimizationSolver.defaultXi
) : AcquisitionFunctionIfc {

    override fun value(prediction: SurrogateModelIfc.Prediction, incumbent: Double, bo: BayesianOptimizationSolver): Double {
        val sd = prediction.standardDeviation
        val improvement = incumbent - prediction.mean - xi
        if (sd <= 0.0) return if (improvement > 0.0) improvement else 0.0
        val z = improvement / sd
        return improvement * Normal.stdNormalCDF(z) + sd * Normal.stdNormalPDF(z)
    }

    override fun toString(): String = "ExpectedImprovement(xi=$xi)"
}

/**
 *  Probability of Improvement (PI) for minimization: `Φ((f+ - μ - xi)/σ)`.
 *
 *  @param xi an exploration margin. Defaults to [BayesianOptimizationSolver.defaultXi].
 */
class ProbabilityOfImprovement(
    var xi: Double = BayesianOptimizationSolver.defaultXi
) : AcquisitionFunctionIfc {

    override fun value(prediction: SurrogateModelIfc.Prediction, incumbent: Double, bo: BayesianOptimizationSolver): Double {
        val sd = prediction.standardDeviation
        val improvement = incumbent - prediction.mean - xi
        if (sd <= 0.0) return if (improvement > 0.0) 1.0 else 0.0
        return Normal.stdNormalCDF(improvement / sd)
    }

    override fun toString(): String = "ProbabilityOfImprovement(xi=$xi)"
}

/**
 *  Lower Confidence Bound (LCB) for minimization. Minimizing `μ - β·σ` is equivalent to maximizing
 *  `β·σ - μ`, which is the value returned here. Larger [beta] favors exploration.
 *
 *  @param beta the exploration weight on the predictive standard deviation. Must be >= 0. Defaults
 *  to [BayesianOptimizationSolver.defaultBeta].
 */
class LowerConfidenceBound(
    beta: Double = BayesianOptimizationSolver.defaultBeta
) : AcquisitionFunctionIfc {

    var beta: Double = beta
        set(value) {
            require(value >= 0.0) { "The LCB beta must be >= 0" }
            field = value
        }

    init {
        require(beta >= 0.0) { "The LCB beta must be >= 0" }
    }

    override fun value(prediction: SurrogateModelIfc.Prediction, incumbent: Double, bo: BayesianOptimizationSolver): Double =
        beta * prediction.standardDeviation - prediction.mean

    override fun toString(): String = "LowerConfidenceBound(beta=$beta)"
}
