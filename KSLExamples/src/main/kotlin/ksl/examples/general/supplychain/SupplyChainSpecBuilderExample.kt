package ksl.examples.general.supplychain

import ksl.modeling.supplychain.spec.CostFormulationSpec
import ksl.modeling.supplychain.spec.CostParamsSpec
import ksl.modeling.supplychain.spec.DemandGeneratorSpec
import ksl.modeling.supplychain.spec.InventorySpec
import ksl.modeling.supplychain.spec.ItemSpec
import ksl.modeling.supplychain.spec.NetworkSpec
import ksl.modeling.supplychain.spec.NodeSpec
import ksl.modeling.supplychain.spec.NodeType
import ksl.modeling.supplychain.spec.PolicySpec
import ksl.modeling.supplychain.spec.RVSpec
import ksl.modeling.supplychain.spec.SupplyChainBuilder
import ksl.modeling.supplychain.spec.TransportStrategySpec
import ksl.simulation.Model

/**
 * Builds and runs a multi-echelon network described entirely as a
 * [NetworkSpec] — the data-driven authoring path (DSL plan Phase D2).
 *
 * The same warehouse-serves-two-retailers topology could instead be
 * authored as a `.toml`/`.json` file (Phase D3) or via the Kotlin DSL
 * (Phase D4); all three produce a [NetworkSpec] that this one
 * [SupplyChainBuilder.build] call turns into a running model.
 *
 * Run via
 * `gradle :KSLExamples:run -PmainClass=ksl.examples.general.supplychain.SupplyChainSpecBuilderExampleKt`
 * or by invoking `main` from an IDE.
 */
fun main() {
    // Describe the network as pure data. Stream numbers are explicit
    // (reproducibility is the spec author's responsibility).
    val spec = NetworkSpec(
        name = "SpecBuilt-ME",
        transportStrategy = TransportStrategySpec.PerIHPTimeBased,
        items = listOf(
            ItemSpec("Widget", leadTime = RVSpec.Exponential(mean = 1.0, stream = 1), unitCost = 12.5),
        ),
        nodes = listOf(
            NodeSpec(
                name = "Warehouse",
                type = NodeType.IHP,
                parent = NodeSpec.EXTERNAL_SUPPLIER,
                transportTimeFromParent = RVSpec.Constant(3.0),
                inventory = listOf(
                    InventorySpec("Widget", PolicySpec.SQ(reorderPoint = 4, reorderQty = 20), initialOnHand = 20),
                ),
            ),
            NodeSpec(
                name = "R1",
                type = NodeType.IHP,
                parent = "Warehouse",
                transportTimeFromParent = RVSpec.Constant(1.0),
                inventory = listOf(
                    InventorySpec("Widget", PolicySpec.SS(reorderPoint = 2, orderUpToLevel = 5), initialOnHand = 10),
                ),
            ),
            NodeSpec(
                name = "R2",
                type = NodeType.IHP,
                parent = "Warehouse",
                transportTimeFromParent = RVSpec.Constant(1.0),
                inventory = listOf(
                    InventorySpec("Widget", PolicySpec.SS(reorderPoint = 3, orderUpToLevel = 6), initialOnHand = 10),
                ),
            ),
        ),
        demandGenerators = listOf(
            DemandGeneratorSpec("R1", "Widget", interArrival = RVSpec.Exponential(2.0, stream = 10)),
            DemandGeneratorSpec("R2", "Widget", interArrival = RVSpec.Exponential(1.5, stream = 11)),
        ),
        costFormulations = listOf(
            CostFormulationSpec.Default(params = CostParamsSpec(carryingRate = 0.10)),
        ),
    )

    // One call turns the spec into a running MultiEchelonNetwork.
    val m = Model("SupplyChain-Spec-Builder")
    SupplyChainBuilder.build(m, spec)

    m.numberOfReplications = 30
    m.lengthOfReplication = 5400.0
    m.lengthOfReplicationWarmUp = 1800.0
    m.simulate()
    m.simulationReporter.printHalfWidthSummaryReport()
}
