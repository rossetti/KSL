package ksl.examples.general.simopt

import ksl.examples.book.chapter7.RQInventorySystem
import ksl.examples.general.models.LKInventoryModel
import ksl.simopt.evaluator.Evaluator
import ksl.simopt.evaluator.SimulationService
import ksl.simopt.evaluator.Solution
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simulation.Model
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

fun main() {

  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    runSolver(modelIdentifier, maxIterations = 1000)

}

fun configureStochasticHillClimber(
    evaluator: Evaluator,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    printer: ((Solution) -> Unit)? = null
): StochasticHillClimber {
    val shc = StochasticHillClimber(
        evaluator,
        maxIterations = maxIterations, replicationsPerEvaluation = replicationsPerEvaluation
    )
    printer?.let { shc.emitter.attach(it) }
    return shc
}

fun runStochasticHillClimber(
    evaluator: Evaluator,
    inputs: MutableMap<String, Double>,
    maxIterations: Int = 100,
    replicationsPerEvaluation: Int = 50,
    printer: ((Solution) -> Unit)? = null
) {
    val shc = configureStochasticHillClimber(evaluator, maxIterations, replicationsPerEvaluation, printer)
    shc.startingPoint = evaluator.problemDefinition.toInputMap(inputs)
    shc.runAllIterations()
    println()
    println("Solver Results:")
    println(shc)
    println()
    println("Final Solution:")
    println(shc.bestSolution.asString())
}

fun runSolver(modelIdentifier: String, maxIterations: Int = 100) {
    val inputs = createInputs(modelIdentifier)
    val evaluator = makeEvaluator(modelIdentifier)
    val printer = if (modelIdentifier == "RQInventoryModel") ::printRQInventoryModel else ::printLKInventoryModel
    runStochasticHillClimber(evaluator, inputs, maxIterations, printer = printer)
}

fun createInputs(modelIdentifier: String): MutableMap<String, Double> {
    return when (modelIdentifier) {
        "LKInventoryModel" -> {
            mutableMapOf(
                "Inventory.reorderPoint" to 1.0,
                "Inventory.orderQuantity" to 4.0
            )
        }
        "RQInventoryModel" -> {
            mutableMapOf(
                "Inventory:Item.initialReorderPoint" to 1.0,
                "Inventory:Item.initialReorderQty" to 4.0
            )
        }

        else -> {throw Exception("Unknown model identifier")}
    }
}

fun printLKInventoryModel(solution: Solution) {
    val q = solution.inputMap["Inventory.orderQuantity"]
    val rp = solution.inputMap["Inventory.reorderPoint"]
    println("id = ${solution.id} objFnc = ${solution.estimatedObjFncValue} \t q = $q \t r = $rp \t penalized objFnc = ${solution.penalizedObjFncValue}")
}

fun printRQInventoryModel(solution: Solution) {
    val q = solution.inputMap["Inventory:Item.initialReorderQty"]
    val rp = solution.inputMap["Inventory:Item.initialReorderPoint"]
    val fillRate = solution.responseEstimatesMap["Inventory:Item:FillRate"]!!.average
    println("id = ${solution.id} objFnc = ${solution.estimatedObjFncValue} \t q = $q \t r = $rp \t fillrate = $fillRate \t penalized objFnc = ${solution.penalizedObjFncValue}")
}

fun buildRQInventoryModel(reorderQty: Int = 2, reorderPoint: Int = 1): Model {
    val model = Model("RQInventoryModel")
    val rqModel = RQInventorySystem(model, reorderPoint, reorderQty, "Inventory")
    rqModel.initialOnHand = 0
    rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
    rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
    model.lengthOfReplication = 20000.0
    model.lengthOfReplicationWarmUp = 10000.0
    model.numberOfReplications = 40
//    val controls = model.controls()
//    println("Model Controls:")
//    controls.printControls()
//    println()
    return model
}

fun buildLKInventoryModel(orderQuantity: Int = 20, reorderPoint: Int = 20): Model {
    val model = Model("LKInventoryModel")
    val lkInventoryModel = LKInventoryModel(model, "Inventory")
    model.lengthOfReplication = 120.0
    model.numberOfReplications = 1000
    model.lengthOfReplicationWarmUp = 20.0
    lkInventoryModel.orderQuantity = orderQuantity
    lkInventoryModel.reorderPoint = reorderPoint
//    val controls = model.controls()
//    println("Model Controls:")
//    controls.printControls()
//    println()
    return model
}

fun makeEvaluator(modelIdentifier: String): Evaluator {
    return when (modelIdentifier) {
        "LKInventoryModel" -> {
            Evaluator.createSimulationServiceProblemEvaluator(
                makeLKInventoryModelProblemDefinition(),
                modelIdentifier, { buildLKInventoryModel() }
            )
        }
        "RQInventoryModel" -> {
            Evaluator.createSimulationServiceProblemEvaluator(
                makeRQInventoryModelProblemDefinition(),
                modelIdentifier, { buildRQInventoryModel() }
            )
        }

        else -> {
            throw Exception("Unknown model identifier")
        }
    }
}

fun makeRQInventoryModelProblemDefinition(): ProblemDefinition {
    val problemDefinition = ProblemDefinition(
        problemName = "InventoryProblem",
        modelIdentifier = "RQInventoryModel",
        objFnResponseName = "Inventory:Item:OrderingAndHoldingCost",
        inputNames = listOf("Inventory:Item.initialReorderQty", "Inventory:Item.initialReorderPoint"),
        responseNames = listOf("Inventory:Item:FillRate")
    )
    problemDefinition.inputVariable(
        name = "Inventory:Item.initialReorderQty",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "Inventory:Item.initialReorderPoint",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    problemDefinition.responseConstraint(
        name = "Inventory:Item:FillRate",
        rhsValue = 0.90,
        inequalityType = InequalityType.GREATER_THAN
    )
    return problemDefinition
}

fun makeLKInventoryModelProblemDefinition(): ProblemDefinition {
    val problemDefinition = ProblemDefinition(
        problemName = "InventoryProblem",
        modelIdentifier = "LKInventoryModel",
        objFnResponseName = "TotalCost",
        inputNames = listOf("Inventory.orderQuantity", "Inventory.reorderPoint"),
    )
    problemDefinition.inputVariable(
        name = "Inventory.orderQuantity",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "Inventory.reorderPoint",
        interval = Interval(1.0, 100.0),
        granularity = 1.0
    )
    return problemDefinition
}

fun makeSimulationService(modelIdentifier: String): SimulationService {
    return when (modelIdentifier) {
        "LKInventoryModel" -> {
            SimulationService.createCachedSimulationServiceForModel(
                modelIdentifier,
                { buildLKInventoryModel() })
        }

        "RQInventoryModel" -> {
            SimulationService.createCachedSimulationServiceForModel(
                modelIdentifier,
                { buildRQInventoryModel() })
        }

        else -> {
            throw Exception("Unknown model identifier")
        }
    }
}

fun makeProblemDefinition(modelIdentifier: String): ProblemDefinition {
    return when (modelIdentifier) {
        "LKInventoryModel" -> {
            makeLKInventoryModelProblemDefinition()
        }

        "RQInventoryModel" -> {
            makeRQInventoryModelProblemDefinition()
        }

        else -> {
            throw Exception("Unknown model identifier")
        }
    }
}