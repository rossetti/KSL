package ksl.examples.general.simopt

import ksl.examples.book.chapter7.RQInventorySystem
import ksl.examples.general.models.LKInventoryModel
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.SimulationService
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.SimulatedAnnealing
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    val initialTemperature = 1000.0
    runSolverTest(modelIdentifier, initialTemperature, maxIterations = 1000)
}

fun configureSimulatedAnnealingSolver(
    evaluator: Evaluator,
    initialTemperature: Double,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    printer: ((Solution) -> Unit)? = null
): SimulatedAnnealing {
    val shc = SimulatedAnnealing(
        evaluator,
        initialTemperature,
        maxIterations = maxIterations, replicationsPerEvaluation = replicationsPerEvaluation
    )
    printer?.let { shc.emitter.attach(it) }
    return shc
}

fun runSimulatedAnnealingSolver(
    evaluator: Evaluator,
    initialTemperature: Double,
    inputs: MutableMap<String, Double>,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    printer: ((Solution) -> Unit)? = null
) {
    val shc = configureSimulatedAnnealingSolver(evaluator, initialTemperature,
        maxIterations, replicationsPerEvaluation, printer)
    shc.startingPoint = evaluator.problemDefinition.toInputMap(inputs)
    shc.runAllIterations()
    println()
    println("Solver Results:")
    println(shc)
    println()
    println("Final Solution:")
    println(shc.bestSolution.asString())
}

fun runSolverTest(
    modelIdentifier: String,
    initialTemperature: Double,
    maxIterations: Int = 100
) {
    val inputs = createInputs(modelIdentifier)
    val evaluator = makeEvaluator(modelIdentifier)
    val printer = if (modelIdentifier == "RQInventoryModel") ::printRQInventoryModel else ::printLKInventoryModel
    runSimulatedAnnealingSolver(evaluator, initialTemperature,
        inputs, maxIterations, printer = printer)
}

