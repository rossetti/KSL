package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver

fun main() {
  //  val modelIdentifier = "RQInventoryModel"
    val modelIdentifier = "LKInventoryModel"
    val problemDefinition = makeProblemDefinition(modelIdentifier)
    val modelBuilder = selectBuilder(modelIdentifier)
    val printer = selectPrinter(modelIdentifier)
    val solver = Solver.stochasticHillClimbingSolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
        replicationsPerEvaluation = 50,
        printer = printer,
    )
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())
}

fun selectPrinter(modelIdentifier: String): (Solver) -> Unit {
    return when (modelIdentifier) {
        "LKInventoryModel" -> ::printLKInventoryModel
        "RQInventoryModel" -> ::printRQInventoryModel
        else -> {
            throw Exception("Unknown model identifier")
        }
    }
}

fun printLKInventoryModel(solver: Solver) {
    val solution = solver.currentSolution
    val q = solution.inputMap["Inventory.orderQuantity"]
    val rp = solution.inputMap["Inventory.reorderPoint"]
    println("iteration = ${solver.iterationCounter} : id = ${solution.id} : n = ${solution.count} : objFnc = ${solution.estimatedObjFncValue} \t q = $q \t r = $rp \t penalized objFnc = ${solution.penalizedObjFncValue}")
}

fun printRQInventoryModel(solver: Solver) {
    val solution = solver.currentSolution
    val q = solution.inputMap["Inventory:Item.initialReorderQty"]
    val rp = solution.inputMap["Inventory:Item.initialReorderPoint"]
    val fillRate = solution.responseEstimatesMap["Inventory:Item:FillRate"]!!.average
    println("iteration = ${solver.iterationCounter} : id = ${solution.id} : n = ${solution.count} : objFnc = ${solution.estimatedObjFncValue} \t q = $q \t r = $rp \t fillrate = $fillRate \t penalized objFnc = ${solution.penalizedObjFncValue}")
}

//
//fun makeSimulationService(modelIdentifier: String): SimulationService {
//    return when (modelIdentifier) {
//        "LKInventoryModel" -> {
//            SimulationService.createCachedSimulationServiceForModel(
//                modelIdentifier,
//                BuildLKModel)
//        }
//
//        "RQInventoryModel" -> {
//            SimulationService.createCachedSimulationServiceForModel(
//                modelIdentifier,
//                BuildRQModel)
//        }
//
//        else -> {
//            throw Exception("Unknown model identifier")
//        }
//    }
//}

