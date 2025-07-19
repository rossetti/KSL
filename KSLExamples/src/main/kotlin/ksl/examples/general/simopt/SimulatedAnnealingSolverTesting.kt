package ksl.examples.general.simopt


import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.SimulatedAnnealing


fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    val initialTemperature = 1000.0
    runSASolverTest(modelIdentifier, initialTemperature, maxIterations = 10)
}

fun configureSimulatedAnnealingSolver(
    evaluator: Evaluator,
    initialTemperature: Double,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    printer: ((Solver) -> Unit)? = null
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
    printer: ((Solver) -> Unit)? = null
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

fun runSASolverTest(
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

