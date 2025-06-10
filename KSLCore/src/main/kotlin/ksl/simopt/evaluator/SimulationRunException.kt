package ksl.simopt.evaluator

import ksl.controls.experiments.SimulationRun

class SimulationRunException(
    val simulationRun: SimulationRun,
    message: String? = simulationRun.runErrorMsg,
    cause: Throwable? = null
) : Exception(message, cause)