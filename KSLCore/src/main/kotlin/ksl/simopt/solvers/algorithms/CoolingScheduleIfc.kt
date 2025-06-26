package ksl.simopt.solvers.algorithms

import kotlin.math.pow

interface CoolingScheduleIfc {

    fun nextTemperature(currentTemperature: Double): Double

}

class LinearCoolingSchedule(
    val coolingStep: Double,
) : CoolingScheduleIfc {
    init {
        require(coolingStep > 0.0) { "Cooling rate must be positive" }
    }

    override fun nextTemperature(currentTemperature: Double): Double {
        require(currentTemperature > 0.0) { "The current temperature must be positive" }
        return currentTemperature - coolingStep
    }
}

class ExponentialCoolingSchedule(
    val coolingRate: Double = 0.95
) : CoolingScheduleIfc {
    init {
        require(coolingRate > 0.0) { "Cooling rate must be positive" }
        require(coolingRate < 1.0) { "Cooling rate must be less than 1.0" }
    }

    override fun nextTemperature(currentTemperature: Double): Double {
        require(currentTemperature > 0.0) { "The current temperature must be positive" }
        return currentTemperature * coolingRate
    }
}