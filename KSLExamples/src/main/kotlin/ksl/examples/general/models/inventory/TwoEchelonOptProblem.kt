package ksl.examples.general.models.inventory

import ksl.examples.general.simopt.randomRestartHillClimberCase
import ksl.examples.general.simopt.stochasticHillClimberCase
import ksl.simopt.benchmark.BenchmarkExperiment
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.benchmark.io.BenchmarkResultsDb
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.Interval
import ksl.utilities.io.KSL
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.RVariableIfc
import ksl.utilities.random.rvariable.ShiftedGeometricRV
import ksl.utilities.random.rvariable.TriangularRV

/**
 *  The two-echelon (DC to base) inventory optimization problem as benchmark-ready
 *  problem cases: 4 integer decision variables (reorder point and reorder quantity at
 *  the DC and at the base), minimizing total cost (unconstrained variant) or
 *  ordering-and-holding cost subject to fill-rate requirements at both echelons
 *  (constrained variant). Neither variant has a known optimum; runs are gapped against
 *  the best found within the experiment.
 *
 *  The `main` runs a small benchmark on the constrained variant — hill climbing versus
 *  random restarts under an equal replication budget — and records everything to the
 *  results database, including the verification stage that re-simulates the winning
 *  point at elevated replications (this problem's classic "check the recommendation"
 *  step, now captured in tblVerification instead of hand-rolled printing). NOTE: the
 *  underlying model is expensive (long replications); expect the demo to take a while.
 */
fun main() {
    val experiment = BenchmarkExperiment(
        name = "TwoEchelonBenchmark",
        problems = listOf(twoEchelonProblemCase(constrained = true)),
        solverCases = listOf(stochasticHillClimberCase(), randomRestartHillClimberCase()),
        macroReplications = 2,
        replicationBudgetPerRun = 1000,
        verificationReplications = 100
    )
    val summary = experiment.run()

    val db = BenchmarkResultsDb("twoEchelonBenchmark.db", KSL.dbDir)
    val expId = db.saveSummary(summary)
    println()
    println("Results database: ${KSL.dbDir.resolve("twoEchelonBenchmark.db")} (experiment id $expId)")
    val problemResult = summary.problemResults.single()
    for (run in problemResult.runs) {
        println(
            "   ${run.cellLabel}: best = ${run.bestObjective}, feasible violation = " +
                    "${run.responseConstraintViolation}, consumed = ${run.numReplicationsRequested}"
        )
    }
    println("   winner inputs = ${problemResult.winner?.inputMap}")
    println("   verified winner estimates:")
    val verification = problemResult.verification
    if (verification != null) {
        println("      ${verification.estimatedObjFnc}")
        for (estimate in verification.responseEstimates) {
            println("      $estimate")
        }
    }
}

/**
 *  The two-echelon problem as a benchmark problem case.
 *
 *  @param constrained true for the fill-rate-constrained variant (the default), false
 *  for the unconstrained total-cost variant
 */
fun twoEchelonProblemCase(constrained: Boolean = true): ProblemCase {
    return ProblemCase(
        name = if (constrained) "TwoEchelonConstrained" else "TwoEchelonUnconstrained",
        problemDefinitionFactory = {
            if (constrained) constrainedTwoEchelonProblemDefinition()
            else unconstrainedTwoEchelonProblemDefinition()
        },
        evaluatorFactoryProvider = { pd -> PooledMemberEvaluatorFactory(pd, BuildTwoEchelonModel) },
        tags = mapOf(
            "family" to "inventoryDEDS",
            "dimension" to "4",
            "constrained" to constrained.toString()
        )
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
