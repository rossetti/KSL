package ksl.simopt.solvers.algorithms

/**
 * Defines the strategy for setting the initial temperature of a Simulated Annealing solver.
 */
sealed class TemperatureConfiguration {
    /**
     * Uses a statically defined initial temperature.
     * @param temperature Must be strictly greater than 0.0.
     */
    data class Fixed(val temperature: Double) : TemperatureConfiguration() {
        init {
            require(temperature > 0.0) { "Temperature must be positive" }
        }
    }

    /**
     * Instructs the solver to autonomously estimate a sensible initial temperature
     * by executing a random walk during its initialization phase.
     * @param targetProbability The desired initial probability of accepting a worse solution (e.g., 0.8).
     * @param numRandomWalkSteps The number of random walk steps to take to estimate the cost landscape.
     * @param numRepsPerStep The number of replications per step. This defaults to 0. If 0, then the
     * replications are determined by the settings associated with the simulation model. If greater than
     * zero, the requested replications for each step is based on this value and overrides what the model specifies.
     * @param useBestStepForStartingPoint The random walk will cover so many steps. This flag (if true) indicates that
     * the best solution found over the steps should be used to initialize the solver run. If false, the starting
     * point is either that provided by the user or randomly determined. The default is true.
     */
    data class AutoCalibrate(
        val targetProbability: Double = 0.8,
        val numRandomWalkSteps: Int = 100,
        val numRepsPerStep: Int = 0,
        val useBestStepForStartingPoint: Boolean = true
    ) : TemperatureConfiguration() {
        init {
            require(targetProbability > 0.0 && targetProbability < 1.0) {
                "Target probability must be strictly between 0 and 1"
            }
            require(numRandomWalkSteps > 0) {
                "Number of random walk steps must be strictly positive"
            }
            require(numRepsPerStep >= 0) {
                "Number of replications per step must be greater than or equal to 0"
            }
        }
    }
}