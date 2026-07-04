package ksl.examples.general.supplychain

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.cost.DefaultMultiEchelonCostFormulation
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simopt.benchmark.ProblemCase
import ksl.simopt.problem.InequalityType
import ksl.simopt.problem.ProblemDefinition
import ksl.simopt.solvers.concurrent.PooledMemberEvaluatorFactory
import ksl.simulation.ExperimentRunParametersIfc
import ksl.simulation.Model
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV

/**
 *  The multi-echelon supply-chain network optimization problem: the benchmark ladder's
 *  expensive, high-dimensional DEDS anchor. A single item flows from an external
 *  supplier through a warehouse under an (R, Q) policy to three retailers under
 *  (r, S) policies; customer demand arrives at the retailers.
 *
 *  Decision variables (8, all integer, driven through the supply-chain controls):
 *  - warehouse policy: reorder-point delta (RDelta = R + Q) and reorder quantity Q
 *  - per retailer: reorder point r and order-up-to delta (SDelta = S - r)
 *  The delta parameterizations make every clamped combination valid, so the problem is
 *  purely box-constrained on the inputs.
 *
 *  Objective: the network-wide total cost surfaced by the cost formulation's
 *  grand-total response. Constraints: a first-fill-rate requirement at each retailer.
 *
 *  There is no known optimum; a best-known reference should be maintained as benchmark
 *  results accumulate (until then, runs are gapped against the best found within the
 *  experiment).
 *
 *  The `main` builds the model, verifies that the problem definition matches it
 *  (control keys and response names), and prints the problem — a cheap sanity check;
 *  actual optimization runs happen through a benchmark experiment.
 */
fun main() {
    val model = BuildMultiEchelonNetworkOptModel.build(null, null)
    val problemDefinition = multiEchelonNetworkProblemDefinition()
    println("Problem definition matches the model: ${problemDefinition.validateProblemDefinition(model)}")
    println()
    println(problemDefinition)
}

private const val MODEL_IDENTIFIER = "MultiEchelonNetworkOptModel"
private const val TOTAL_COST_RESPONSE = "Costs:GrandTotal"
private val retailerNames = listOf("R1", "R2", "R3")

/** The multi-echelon network problem as a benchmark problem case. */
fun multiEchelonNetworkProblemCase(): ProblemCase {
    return ProblemCase(
        name = "MultiEchelonNetwork",
        problemDefinitionFactory = { multiEchelonNetworkProblemDefinition() },
        evaluatorFactoryProvider = { pd ->
            PooledMemberEvaluatorFactory(pd, BuildMultiEchelonNetworkOptModel)
        },
        tags = mapOf(
            "family" to "supplychainDEDS",
            "dimension" to "8",
            "constrained" to "true"
        )
    )
}

/**
 *  The problem definition over the model built by `BuildMultiEchelonNetworkOptModel`.
 *  Input names are control keys (element name dot property name); response names come
 *  from the cost formulation and the retailer inventories' first-fill-rate responses.
 *
 *  @param retailerFillRateRequirement the minimum first fill rate required at each retailer
 */
fun multiEchelonNetworkProblemDefinition(
    retailerFillRateRequirement: Double = 0.80
): ProblemDefinition {
    val retailerFillRateResponses = retailerNames.map { "${it}Inv : First Fill Rate" }
    val inputNames = mutableListOf(
        "RQPolicy:WarehouseInv.initialReorderPointDelta",
        "RQPolicy:WarehouseInv.initialReorderQty"
    )
    for (retailer in retailerNames) {
        inputNames.add("RSPolicy:${retailer}Inv.initialReorderPoint")
        inputNames.add("RSPolicy:${retailer}Inv.initialOrderUpToPointDelta")
    }
    val problemDefinition = ProblemDefinition(
        problemName = "MultiEchelonNetworkOptProblem",
        modelIdentifier = MODEL_IDENTIFIER,
        objFnResponseName = TOTAL_COST_RESPONSE,
        inputNames = inputNames,
        responseNames = retailerFillRateResponses
    )
    // warehouse (R, Q): RDelta = R + Q (at least 1) and Q (at least 1)
    problemDefinition.inputVariable(
        name = "RQPolicy:WarehouseInv.initialReorderPointDelta",
        lowerBound = 1.0, upperBound = 150.0, granularity = 1.0
    )
    problemDefinition.inputVariable(
        name = "RQPolicy:WarehouseInv.initialReorderQty",
        lowerBound = 1.0, upperBound = 60.0, granularity = 1.0
    )
    // retailers (r, S): r and SDelta = S - r (at least 1)
    for (retailer in retailerNames) {
        problemDefinition.inputVariable(
            name = "RSPolicy:${retailer}Inv.initialReorderPoint",
            lowerBound = 0.0, upperBound = 40.0, granularity = 1.0
        )
        problemDefinition.inputVariable(
            name = "RSPolicy:${retailer}Inv.initialOrderUpToPointDelta",
            lowerBound = 1.0, upperBound = 40.0, granularity = 1.0
        )
    }
    for (responseName in retailerFillRateResponses) {
        problemDefinition.responseConstraint(
            name = responseName,
            rhsValue = retailerFillRateRequirement,
            inequalityType = InequalityType.GREATER_THAN
        )
    }
    return problemDefinition
}

/**
 *  Builds the reduced multi-echelon network model (one item, one warehouse, three
 *  retailers) with the cost formulation attached. Element names are explicit because
 *  the problem definition's input keys and response names are derived from them.
 */
object BuildMultiEchelonNetworkOptModel : ModelBuilderIfc {
    override fun build(
        modelConfiguration: Map<String, String>?,
        experimentRunParameters: ExperimentRunParametersIfc?
    ): Model {
        val model = Model(MODEL_IDENTIFIER)
        val sc = SupplyChainModel(model, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("Item", ExponentialRV(1.0, streamNum = 1))
        val warehouse = net.addInventoryHoldingPoint("Warehouse")
        warehouse.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 5, reorderQty = 10, initialOnHand = 15, name = "WarehouseInv",
        )
        val retailers = retailerNames.map { net.addInventoryHoldingPoint(it) }
        for ((index, retailer) in retailers.withIndex()) {
            retailer.addReorderPointOrderUpToLevelInventory(
                item, reorderPoint = 2, orderUpToPoint = 5, initialOnHand = 5,
                name = "${retailerNames[index]}Inv",
            )
        }
        net.attachIHPToExternalSupplier(warehouse, ConstantRV(3.0))
        val deliveryLeg = ConstantRV(1.0)
        for (retailer in retailers) {
            net.attachIHPToSupplier(warehouse, retailer, deliveryLeg)
        }
        val demandMeans = listOf(2.0, 1.5, 2.5)
        for ((index, retailer) in retailers.withIndex()) {
            net.attachDemandGeneratorToIHP(
                retailer, item,
                ExponentialRV(demandMeans[index], streamNum = 10 + index),
            )
        }
        // the objective function: network-wide cost rollups (attach after the topology)
        DefaultMultiEchelonCostFormulation(net, name = "Costs")
        model.lengthOfReplication = 5400.0
        model.lengthOfReplicationWarmUp = 1800.0
        model.numberOfReplications = 30
        return model
    }
}
