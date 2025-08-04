package ksl.examples.general.simopt

import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.Solution
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultGrowthRate
import ksl.simopt.solvers.FixedGrowthRateReplicationSchedule.Companion.defaultMaxNumReplications
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.CENormalSampler
import ksl.simopt.solvers.algorithms.CESamplerIfc
import ksl.simopt.solvers.algorithms.CrossEntropySolver
import ksl.simopt.solvers.algorithms.RSplineSolver
import ksl.simopt.solvers.algorithms.RSplineSolver.Companion.defaultInitialSampleSize

fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    runRSPLINETest(modelIdentifier, maxIterations = 10)
}

fun configureRSPLINESolver(
    evaluator: Evaluator,
    initialNumReps: Int = defaultInitialSampleSize,
    sampleSizeGrowthRate: Double = defaultGrowthRate,
    maxNumReplications: Int = defaultMaxNumReplications,
    maxIterations: Int = 100,
    startingPoint: MutableMap<String, Double>? = null,
    printer: ((Solver) -> Unit)? = null
): RSplineSolver {
    val rspline = RSplineSolver(
        evaluator = evaluator,
        maxIterations = maxIterations,
        initialNumReps = initialNumReps,
        sampleSizeGrowthRate = sampleSizeGrowthRate,
        maxNumReplications = maxNumReplications
    )
    if (startingPoint != null) {
        rspline.startingPoint = evaluator.problemDefinition.toInputMap(startingPoint)
    }
    printer?.let { rspline.emitter.attach(it) }
    return rspline
}

fun runRSPLINESolver(
    evaluator: Evaluator,
    inputs: MutableMap<String, Double>,
    maxIterations: Int = 100,
    initialNumReps: Int = defaultInitialSampleSize,
    sampleSizeGrowthRate: Double = defaultGrowthRate,
    maxNumReplications: Int = defaultMaxNumReplications,
    printer: ((Solver) -> Unit)? = null
) {
    val shc = configureRSPLINESolver(evaluator,
        initialNumReps = initialNumReps,
        sampleSizeGrowthRate = sampleSizeGrowthRate,
        maxNumReplications = maxNumReplications,
        maxIterations, inputs, printer)
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

fun runRSPLINETest(
    modelIdentifier: String,
    maxIterations: Int = 100
) {
    val inputs = createInputs(modelIdentifier)
    val evaluator = makeEvaluator(modelIdentifier)
    val printer = if (modelIdentifier == "RQInventoryModel") ::printRQInventoryModel else ::printLKInventoryModel
    runRSPLINESolver(evaluator, inputs, maxIterations, printer = printer)
}

