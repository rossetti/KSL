package ksl.simopt.solvers.algorithms

import kotlin.math.ln
import kotlin.math.pow

interface CoolingScheduleIfc {

    fun nextTemperature(iteration: Int): Double

}

class LinearCoolingSchedule(
    val initialTemperature: Double,
    val finalTemperature: Double,
    val maxIterations: Int
) : CoolingScheduleIfc {
    init {
        require(maxIterations > 0) { "The maximum number of iterations must be positive" }
        require(initialTemperature > 0.0) { "The initial temperature must be positive" }
        require(finalTemperature > 0.0) { "The initial temperature must be positive" }
        require(finalTemperature < initialTemperature) { "The final temperature must be less than the initial temperature" }
    }

    val temperatureDecreasePerIteration = (initialTemperature - finalTemperature) / maxIterations

    override fun nextTemperature(iteration: Int): Double {
        require(iteration > 0) { "The iteration must be positive" }
        if (iteration >= maxIterations) return finalTemperature
        return initialTemperature - (temperatureDecreasePerIteration * iteration)
    }
}

class ExponentialCoolingSchedule(
    val initialTemperature: Double,
    val coolingRate: Double = 0.95
) : CoolingScheduleIfc {
    init {
        require(initialTemperature > 0.0) { "The initial temperature must be positive" }
        require(coolingRate > 0.0) { "Cooling rate must be positive" }
        require(coolingRate < 1.0) { "Cooling rate must be less than 1.0" }
    }

    override fun nextTemperature(iteration: Int): Double {
        require(iteration > 0) { "The iteration must be positive" }
        return initialTemperature * coolingRate.pow(iteration)
    }
}

class LogarithmicCoolingSchedule(
    val initialTemperature: Double,
) : CoolingScheduleIfc {
    init {
        require(initialTemperature > 0.0) { "The initial temperature must be positive" }
    }

    override fun nextTemperature(iteration: Int): Double {
        return initialTemperature / ln(iteration.toDouble() + 1.0)
    }
}