package ksl.simopt.solvers.algorithms

import ksl.simopt.solvers.Solver.Companion.defaultMaxNumberIterations
import ksl.simopt.solvers.algorithms.SimulatedAnnealing.Companion.defaultCoolingRate
import ksl.simopt.solvers.algorithms.SimulatedAnnealing.Companion.defaultInitialTemperature
import ksl.simopt.solvers.algorithms.SimulatedAnnealing.Companion.defaultStoppingTemperature
import kotlin.math.ln
import kotlin.math.pow

/**
 * Defines the interface for a cooling schedule used in optimization algorithms such as simulated annealing.
 * A cooling schedule is responsible for managing the temperature reduction process as the algorithm iterates.
 */
interface CoolingScheduleIfc {

    var initialTemperature: Double

    /**
     * Computes the next temperature value based on the given iteration.
     * This method is typically used in optimization algorithms to manage
     * the cooling schedule of a process.
     *
     * @param iteration The current iteration number of the optimization process.
     *                   Must be a non-negative integer.
     * @return The calculated temperature value for the specified iteration.
     */
    fun nextTemperature(iteration: Int): Double

}

/**
 * Abstract base class for defining cooling schedules in optimization algorithms like simulated annealing.
 * A cooling schedule manages the temperature reduction process as the algorithm iterates.
 *
 * @constructor Initializes the cooling schedule with the specified initial temperature.
 * @param initialTemperature The starting temperature for the cooling schedule. Must be positive.
 *
 * @throws IllegalArgumentException if the provided initial temperature is not positive.
 */
abstract class CoolingSchedule(initialTemperature: Double) : CoolingScheduleIfc {
    init {
        require(initialTemperature > 0.0) { "The initial temperature must be positive" }
    }

    override var initialTemperature: Double = initialTemperature
        set(value) {
            require(value > 0.0) { "The initial temperature must be positive" }
            field = value
        }
}

/**
 * Represents a linear cooling schedule used in optimization algorithms like simulated annealing.
 * The temperature decreases linearly with each iteration based on the specified parameters.
 *
 * @constructor Initializes a LinearCoolingSchedule instance with the given parameters.
 * @param initialTemperature The starting temperature for the cooling schedule. Must be positive and greater than the final temperature.
 * @param stoppingTemperature The minimum temperature to which the schedule decreases. Must be positive and less than the initial temperature.
 * @param maxIterations The total number of iterations over which the temperature decreases. Must be positive.
 *
 * @throws IllegalArgumentException if maxIterations is not positive,
 * if finalTemperature is not positive,
 * or if finalTemperature is not less than initialTemperature.
 */
@Suppress("unused")
class LinearCoolingSchedule(
    initialTemperature: Double = defaultInitialTemperature,
    val stoppingTemperature: Double = defaultStoppingTemperature,
    val maxIterations: Int = defaultMaxNumberIterations,
) : CoolingSchedule (initialTemperature) {
    init {
        require(maxIterations > 0) { "The maximum number of iterations must be positive" }
        require(stoppingTemperature > 0.0) { "The initial temperature must be positive" }
        require(stoppingTemperature < initialTemperature) { "The final temperature must be less than the initial temperature" }
    }

    override var initialTemperature: Double = initialTemperature
        set(value) {
            require(value > 0.0) { "The initial temperature must be positive" }
            field = value
        }

    val temperatureDecreasePerIteration
        get() = (initialTemperature - stoppingTemperature) / maxIterations

    override fun nextTemperature(iteration: Int): Double {
        require(iteration > 0) { "The iteration must be positive" }
        if (iteration >= maxIterations) return stoppingTemperature
        return initialTemperature - (temperatureDecreasePerIteration * iteration)
    }
}

/**
 * Represents a cooling schedule where the temperature decreases exponentially
 * at each iteration according to a specified cooling rate.
 *
 * @constructor Creates an exponential cooling schedule with the provided initial temperature and cooling rate.
 * @param initialTemperature The starting temperature for the cooling schedule. Must be positive.
 * @param coolingRate The rate at which the temperature decreases in each iteration. Defaults to 0.95.
 * Must be a value between 0.0 (exclusive) and 1.0 (exclusive).
 *
 * @throws IllegalArgumentException if the cooling rate is not between 0.0 and 1.0, or if the initial temperature is not positive.
 */
class ExponentialCoolingSchedule(
    initialTemperature: Double,
    val coolingRate: Double = defaultCoolingRate
) : CoolingSchedule (initialTemperature)  {
    init {
        require(coolingRate > 0.0) { "Cooling rate must be positive" }
        require(coolingRate < 1.0) { "Cooling rate must be less than 1.0" }
    }

    override fun nextTemperature(iteration: Int): Double {
        require(iteration > 0) { "The iteration must be positive. It was $iteration." }
        return initialTemperature * coolingRate.pow(iteration)
    }
}

/**
 * Implements a logarithmic cooling schedule for optimization algorithms such as simulated annealing.
 * The temperature decreases proportionally to the inverse of the natural logarithm of the iteration number.
 *
 * @constructor Creates a logarithmic cooling schedule with the specified initial temperature.
 * @param initialTemperature The starting temperature for the cooling schedule. Must be positive.
 *
 * @throws IllegalArgumentException if the provided initial temperature is not positive.
 */
@Suppress("unused")
class LogarithmicCoolingSchedule(
    initialTemperature: Double,
) : CoolingSchedule (initialTemperature) {

    override fun nextTemperature(iteration: Int): Double {
        return initialTemperature / ln(iteration.toDouble() + 1.0)
    }
}