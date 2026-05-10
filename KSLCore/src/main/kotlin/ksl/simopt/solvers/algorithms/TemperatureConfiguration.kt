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
            require(temperature.isFinite()) {"The temperature must be finite"}
            require(temperature > 0.0) { "Temperature must be positive" }
        }
    }

    /**
     * Instructs the solver to autonomously estimate a sensible initial temperature
     * by executing a random walk during its initialization phase.
     * @param targetProbability The desired initial probability of accepting a worse solution (e.g., 0.8).
     * @param sampleSize The number of random walk steps to take to estimate the cost landscape.
     */
    data class AutoCalibrate(
        val targetProbability: Double = 0.8,
        val sampleSize: Int = 100
    ) : TemperatureConfiguration() {
        init {
            require(targetProbability > 0.0 && targetProbability < 1.0) {
                "Target probability must be strictly between 0 and 1"
            }
            require(sampleSize > 0) {
                "Sample size must be strictly positive"
            }
        }
    }
}