package ksl.examples.general.simopt

import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver

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
    println("**** iteration = ${solver.iterationCounter} *************************************************************")
    if (solver is RandomRestartSolver){
        val rs = solver.restartingSolver
        val initialSolution = rs.initialSolution
        if (initialSolution != null) {
            val q = initialSolution.inputMap["Inventory.orderQuantity"]
            val rp = initialSolution.inputMap["Inventory.reorderPoint"]
            println("initial solution: id = ${initialSolution.id}")
            println("n = ${initialSolution.count} : objFnc = ${initialSolution.estimatedObjFncValue} \t q = $q \t r = $rp \t penalized objFnc = ${initialSolution.penalizedObjFncValue}")
        }
    }
    val solution = solver.currentSolution
    val q = solution.inputMap["Inventory.orderQuantity"]
    val rp = solution.inputMap["Inventory.reorderPoint"]
    println("solution: id = ${solution.id}")
    println("n = ${solution.count} : objFnc = ${solution.estimatedObjFncValue} \t q = $q \t r = $rp \t penalized objFnc = ${solution.penalizedObjFncValue}")
    println("********************************************************************************")
}


fun printRQInventoryModel(solver: Solver) {
    println("**** iteration = ${solver.iterationCounter} ************************************")
    if (solver is RandomRestartSolver){
        val rs = solver.restartingSolver
        val initialSolution = rs.initialSolution
        if (initialSolution != null) {
            val q = initialSolution.inputMap["Inventory:Item.initialReorderQty"]
            val rp = initialSolution.inputMap["Inventory:Item.initialReorderPoint"]
            val fillRate = initialSolution.responseEstimatesMap["Inventory:Item:FillRate"]!!.average
            println("initial solution: id = ${initialSolution.id}")
            println("n = ${initialSolution.count} : objFnc = ${initialSolution.estimatedObjFncValue} \t q = $q \t r = $rp \t penalized objFnc = ${initialSolution.penalizedObjFncValue} \t fillrate = $fillRate")
        }
    }
    val solution = solver.currentSolution
    val q = solution.inputMap["Inventory:Item.initialReorderQty"]
    val rp = solution.inputMap["Inventory:Item.initialReorderPoint"]
    val fillRate = solution.responseEstimatesMap["Inventory:Item:FillRate"]!!.average
    println("solution: id = ${solution.id}")
    println("n = ${solution.count} : objFnc = ${solution.estimatedObjFncValue} \t q = $q \t r = $rp \t penalized objFnc = ${solution.penalizedObjFncValue} \t fillrate = $fillRate ")
    println("********************************************************************************")
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

