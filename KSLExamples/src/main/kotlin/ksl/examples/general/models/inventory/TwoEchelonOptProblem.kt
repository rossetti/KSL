package ksl.examples.general.models.inventory

import ksl.simopt.cache.SimulationRunCacheIfc
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.Solver
import ksl.simopt.solvers.algorithms.RandomRestartSolver
import ksl.simopt.solvers.algorithms.RandomRestartSolver.Companion.defaultMaxRestarts
import ksl.simopt.solvers.algorithms.StochasticHillClimber
import ksl.simopt.solvers.algorithms.StochasticSolver
import ksl.simopt.solvers.trackers.ConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.CsvSolverStateTracker
import ksl.simopt.solvers.trackers.NestedConsoleSolverStateTracker
import ksl.simopt.solvers.trackers.NestedCsvSolverStateTracker
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Interval
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.ShiftedGeometricRV
import ksl.utilities.random.rvariable.TriangularRV

enum class SolverType {
    SHC, SA, CE, R_SPLINE, SHC_RS, SA_RS, CE_RS, R_SPLINE_RS
}


fun main() {
    val solverType = SolverType.SHC
    val constrained = true
    val c = if (constrained) "Constrained" else "Unconstrained"

    var solver = setupSolver(constrained = constrained, solverType)

    //TODO If you are using a particular solver and want to tune its parameters
    // then uncomment the appropriate line. You can then access the properties of that solver.
    // solver = solver as StochasticHillClimber
    // solver = solver as SimulatedAnnealing
    // solver = solver as RSplineSolver
    // solver = solver as CrossEntropySolver
    // solver = solver as RandomRestartSolver

    //TODO  advance random number streams if you want to have different random numbers for a solver run
    //solver.rnStream.advanceToNextSubStream()

    //TODO attach trackers
    if (solver is RandomRestartSolver) {
        val tracker = NestedConsoleSolverStateTracker(solver, solver.restartingSolver)
        tracker.startTracking()
        val csvTracker =
            NestedCsvSolverStateTracker(
                solver, solver.restartingSolver,
                "${solverType}_Restart_$c"
            )
        csvTracker.startTracking()
    } else {
        val tracker = ConsoleSolverStateTracker(solver)
        tracker.startTracking()
        val csvTracker = CsvSolverStateTracker(solver, "${solverType}_$c")
        csvTracker.startTracking()
    }
    println()
    println("Running solver with type = $solverType and $c problem definition")
    solver.runAllIterations()

    println()
    println("###############################################################################")
    println("Solver Results:")
    println("solver type = $solverType")
    println(solver)
    println()
    println("Solver Results Summary:")
    solver.printResults()
    println()
    println("Final (Best) Solution:")
    println(solver.bestSolution.asString())
    println()
    println("Approximate screening:")
    val solutions = solver.bestSolutions.possiblyBest()
    println(solutions)
    println("###############################################################################")

    //TODO Run the model at the best solution found and print results.
    val model = BuildTwoEchelonModel.build(null, null)

    val inputs = solver.bestSolution.inputMap.asMutableMap()

    val controls = model.controls()
    println("Original Inputs:")
    for (name in inputs.keys) {
        val control = controls.control(name)
        if (control != null) {
            println("$name = ${control.value}")
        }
    }

    controls.setControlsFromMap(inputs)
    println("Best Solution Inputs:")
    for (name in inputs.keys) {
        val control = controls.control(name)
        if (control != null) {
            println("$name = ${control.value}")
        }
    }
    println()
    println("Simulating model at best solution found...")
    model.simulate()
    model.print()

}

fun setupStochasticHillClimber(
    constrained: Boolean = false,
    modelBuilder: ModelBuilderIfc = BuildTwoEchelonModel,
): StochasticHillClimber {
    val problemDefinition = if (constrained)
        constrainedTwoEchelonProblemDefinition()
    else unconstrainedTwoEchelonProblemDefinition()
    val solver = Solver.createStochasticHillClimbingSolver(
        problemDefinition, modelBuilder, startingPoint = null, maxIterations = 100, replicationsPerEvaluation = 50,
    )
    return solver
}

fun setupSolver(
    constrained: Boolean = false,
    solverType: SolverType = SolverType.SHC,
    problemDefinition: ProblemDefinition = if (constrained) constrainedTwoEchelonProblemDefinition() else unconstrainedTwoEchelonProblemDefinition(),
    modelBuilder: ModelBuilderIfc = BuildTwoEchelonModel,
    maxNumRestarts: Int = defaultMaxRestarts,
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null
): StochasticSolver {
    return solverFactory(
        solverType, problemDefinition, modelBuilder,
        maxNumRestarts, simulationRunCache, experimentRunParameters
    )
}

fun unconstrainedTwoEchelonProblemDefinition(
    dcReorderPointInterval: Interval = Interval(1.0, 200.0),
    dcReorderQtyInterval: Interval = Interval(1.0, 200.0),
    baseReorderPointInterval: Interval = Interval(1.0, 200.0),
    baseReorderQtyInterval: Interval = Interval(1.0, 200.0)
): ProblemDefinition {
    val problemDefinition = ProblemDefinition(
        problemName = "TwoEchelonOptProblem",
        modelIdentifier = "TwoEchelonRQModel",
        objFnResponseName = "TwoEchelon:TotalCost",
        inputNames = listOf(
            "TwoEchelon:DCInventory.initialReorderPoint",
            "TwoEchelon:DCInventory.initialReorderQty",
            "TwoEchelon:BaseInventory.initialReorderPoint",
            "TwoEchelon:BaseInventory.initialReorderQty"
        ),
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:DCInventory.initialReorderPoint",
        interval = dcReorderPointInterval,
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:DCInventory.initialReorderQty",
        interval = dcReorderQtyInterval,
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:BaseInventory.initialReorderPoint",
        interval = baseReorderPointInterval,
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:BaseInventory.initialReorderQty",
        interval = baseReorderQtyInterval,
        granularity = 1.0
    )
    return problemDefinition
}

fun constrainedTwoEchelonProblemDefinition(
    dcReorderPointInterval: Interval = Interval(1.0, 200.0),
    dcReorderQtyInterval: Interval = Interval(1.0, 200.0),
    baseReorderPointInterval: Interval = Interval(1.0, 200.0),
    baseReorderQtyInterval: Interval = Interval(1.0, 200.0),
    dcFillRateRequirement: Double = 0.90,
    baseFillRateRequirement: Double = 0.95,
): ProblemDefinition {
    val problemDefinition = ProblemDefinition(
        problemName = "TwoEchelonOptProblem",
        modelIdentifier = "TwoEchelonRQModel",
        objFnResponseName = "TwoEchelon:TotalOrderingAndHoldingCost",
        inputNames = listOf(
            "TwoEchelon:DCInventory.initialReorderPoint",
            "TwoEchelon:DCInventory.initialReorderQty",
            "TwoEchelon:BaseInventory.initialReorderPoint",
            "TwoEchelon:BaseInventory.initialReorderQty"
        ),
        responseNames = listOf("TwoEchelon:DCInventory:ItemA:FillRate", "TwoEchelon:BaseInventory:ItemA:FillRate"),
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:DCInventory.initialReorderPoint",
        interval = dcReorderPointInterval,
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:DCInventory.initialReorderQty",
        interval = dcReorderQtyInterval,
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:BaseInventory.initialReorderPoint",
        interval = baseReorderPointInterval,
        granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "TwoEchelon:BaseInventory.initialReorderQty",
        interval = baseReorderQtyInterval,
        granularity = 1.0
    )
    problemDefinition.responseConstraint(
        name = "TwoEchelon:DCInventory:ItemA:FillRate",
        rhsValue = dcFillRateRequirement,
        inequalityType = InequalityType.GREATER_THAN
    )
    problemDefinition.responseConstraint(
        name = "TwoEchelon:BaseInventory:ItemA:FillRate",
        rhsValue = baseFillRateRequirement,
        inequalityType = InequalityType.GREATER_THAN
    )
    return problemDefinition
}

object BuildTwoEchelonModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val m = Model("TwoEchelonRQModel")
        val itemType = ItemType("ItemA")
        itemType.unitCost = 555.56
        val supplierLeadTimeToDC: RVariableIfc = TriangularRV(50.0, 60.0, 70.0, streamNum = 1)
        val timeBtwDemandDC: RVariableIfc = ExponentialRV(7.6, 2)
        val demandAmountDC: RVariableIfc = ShiftedGeometricRV(0.2, 3)
        val reorderPointDC: Int = 117
        val reorderQtyDC: Int = 22
        val initialOnHandDC: Int = reorderPointDC + reorderQtyDC
        val shippingTimeDCToBase: RVariableIfc = TriangularRV(5.0, 7.0, 9.0, streamNum = 4)
        val timeBtwDemandBase: RVariableIfc = ExponentialRV(15.2, 5)
        val demandAmountBase: RVariableIfc = ShiftedGeometricRV(0.9, 6)
        val reorderPointBase: Int = 4
        val reorderQtyBase: Int = 5
        val initialOnHandBase: Int = reorderPointBase + reorderQtyBase
        val tem = TwoEchelonModel(
            m,
            itemType,
            supplierLeadTimeToDC,
            timeBtwDemandDC,
            demandAmountDC,
            reorderPointDC,
            reorderQtyDC,
            initialOnHandDC,
            shippingTimeDCToBase,
            timeBtwDemandBase,
            demandAmountBase,
            reorderPointBase,
            reorderQtyBase,
            initialOnHandBase,
            "TwoEchelon"
        )
        val dcCarryingCharge = 0.161
        val daysPerYear = 365.0
        val dcFillRateRequirement = 0.95
        tem.inventoryDC.costPerOrder = 80.0
        tem.inventoryDC.unitHoldingCost = itemType.unitCost * (dcCarryingCharge / daysPerYear)
        tem.inventoryDC.unitBackOrderCost =
            (dcFillRateRequirement / (1.0 - dcFillRateRequirement)) * tem.inventoryDC.unitHoldingCost
        tem.inventoryBase.costPerOrder = 40.0
        val baseCarryingCharge = 0.18
        tem.inventoryBase.unitHoldingCost = itemType.unitCost * (baseCarryingCharge / daysPerYear)
        val baseFillRateRequirement = 0.95
        tem.inventoryBase.unitBackOrderCost =
            (baseFillRateRequirement / (1.0 - baseFillRateRequirement)) * tem.inventoryBase.unitHoldingCost
        m.lengthOfReplication = 110000.0
        m.lengthOfReplicationWarmUp = 10000.0
        m.numberOfReplications = 40
        return m
    }

}

fun solverFactory(
    solverType: SolverType,
    problemDefinition: ProblemDefinition,
    modelBuilder: ModelBuilderIfc,
    maxNumRestarts: Int = defaultMaxRestarts,
    simulationRunCache: SimulationRunCacheIfc? = null,
    experimentRunParameters: ExperimentRunParametersIfc? = null
): StochasticSolver {
    return when (solverType) {
        SolverType.SHC -> {
            Solver.createStochasticHillClimbingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }

        SolverType.SA -> {
            val initialTemperature = 1000.0
            val solver = Solver.createSimulatedAnnealingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
            solver
        }

        SolverType.CE -> {
            Solver.createCrossEntropySolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }

        SolverType.R_SPLINE -> {
            Solver.createRsplineSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                startingPoint = null,
                maxIterations = 100,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }

        SolverType.SHC_RS -> {
            Solver.createRandomRestartStochasticHillClimbingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxNumRestarts = maxNumRestarts,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }

        SolverType.SA_RS -> {
            val initialTemperature = 1000.0
            Solver.createRandomRestartSimulatedAnnealingSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxNumRestarts = maxNumRestarts,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }

        SolverType.CE_RS -> {
            Solver.createRandomRestartCrossEntropySolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxNumRestarts = maxNumRestarts,
                maxIterations = 100,
                replicationsPerEvaluation = 50,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }

        SolverType.R_SPLINE_RS -> {
            Solver.createRandomRestartRsplineSolver(
                problemDefinition = problemDefinition,
                modelBuilder = modelBuilder,
                maxNumRestarts = maxNumRestarts,
                maxIterations = 100,
                simulationRunCache = simulationRunCache,
                experimentRunParameters = experimentRunParameters
            )
        }
    }
}

