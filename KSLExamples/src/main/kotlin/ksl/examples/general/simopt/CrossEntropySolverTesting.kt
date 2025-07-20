package ksl.examples.general.simopt

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simopt.solvers.algorithms.CESamplerIfc
import ksl.simopt.solvers.algorithms.CrossEntropySolver

fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    runCESolverTest(modelIdentifier, maxIterations = 100)
}

fun configureCrossEntropySolver(
    evaluator: Evaluator,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    startingPoint: MutableMap<String, Double>? = null,
    printer: ((Solver) -> Unit)? = null
): CrossEntropySolver {
    val ceSampler: CESamplerIfc = CENormalSampler(evaluator.problemDefinition)
    val ce = CrossEntropySolver(
        evaluator = evaluator,
        ceSampler = ceSampler,
        maxIterations = maxIterations,
        replicationsPerEvaluation = replicationsPerEvaluation
    )
    if (startingPoint != null) {
        ce.startingPoint = evaluator.problemDefinition.toInputMap(startingPoint)
    }
    printer?.let { ce.emitter.attach(it) }
    return ce
}

fun runCrossEntropySolver(
    evaluator: Evaluator,
    inputs: MutableMap<String, Double>,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    printer: ((Solver) -> Unit)? = null
) {
    val shc = configureCrossEntropySolver(evaluator,
        maxIterations, replicationsPerEvaluation, inputs, printer)
    shc.startingPoint = evaluator.problemDefinition.toInputMap(inputs)
    println("Setting up solver:")
    println(shc)
    println("Running solver:")
    shc.runAllIterations()
    println()
    println("Solver Results:")
    println(shc)
    println()
    println("Final Solution:")
    println(shc.bestSolution.asString())
}

fun runCESolverTest(
    modelIdentifier: String,
    maxIterations: Int = 100
) {
    val inputs = createInputs(modelIdentifier)
    val evaluator = makeEvaluator(modelIdentifier)
    val printer = if (modelIdentifier == "RQInventoryModel") ::printRQInventoryModel else ::printLKInventoryModel
    runCrossEntropySolver(evaluator, inputs, maxIterations, printer = printer)
}

