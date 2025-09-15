package ksl.examples.book.chapter10

import ksl.examples.book.chapter7.RQInventorySystem
import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.jetbrains.kotlinx.dataframe.api.print
import org.jetbrains.kotlinx.dataframe.api.schema


fun main(){
    runSolver()
}

fun runSolver(
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null,
    defaultKSLDatabaseObserverOption: Boolean = false
) {
    val problemDefinition = makeRQInventoryModelProblemDefinition()
    val modelBuilder = BuildRQModel
    val printer = ::printRQInventoryModel

    val solver = Solver.crossEntropySolver(
        problemDefinition = problemDefinition,
        modelBuilder = modelBuilder,
        startingPoint = null,
        maxIterations = 100,
        replicationsPerEvaluation = 50,
        printer = printer,
        simulationRunCache = simulationRunCache,
        experimentRunParameters = experimentRunParameters,
        defaultKSLDatabaseObserverOption = defaultKSLDatabaseObserverOption
    )
    solver.runAllIterations()
    println()
    println("Solver Results:")
    println(solver)
    println()
    println("Final Solution:")
    println(solver.bestSolution.asString())
    println()
    println("Approximate screening:")
    val solutions = solver.bestSolutions.possiblyBest()
    println(solutions)
    println("Dataframe")
    val df = solver.bestSolutions.toDataFrame()
    df.schema().print()
    df.print()
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

object BuildRQModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?,
        defaultKSLDatabaseObserverOption: Boolean
    ): Model {
        val reorderQty: Int = 2
        val reorderPoint: Int = 1
        val model = Model("RQInventoryModel")
        val rqModel = RQInventorySystem(model, reorderPoint, reorderQty, "Inventory")
        rqModel.initialOnHand = 0
        rqModel.demandGenerator.initialTimeBtwEvents = ExponentialRV(1.0 / 3.6)
        rqModel.leadTime.initialRandomSource = ConstantRV(0.5)
        model.lengthOfReplication = 20000.0
        model.lengthOfReplicationWarmUp = 10000.0
        model.numberOfReplications = 40
        if (defaultKSLDatabaseObserverOption) {
            model.createDefaultDatabaseObserver()
        }
        return model
    }
}