package ksl.examples.general.simopt

import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.utilities.Interval

fun main() {
    testProblemDefinition()
}

fun testProblemDefinition(){
    val pd = makeLKInventoryModelProblemDefinition()
    val c = pd.toInputMap(doubleArrayOf(50.0, 75.0))
    val ng = pd.vonNeumannNeighborhoodFinder()
    val nc = ng.neighborhood(c, null)
    for(n in nc){
        println(n)
    }
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
        rhsValue = 0.95,
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