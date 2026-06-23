package ksl.modeling.supplychain.spec

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Round-trip tests for the JSON / TOML codecs: `fromX(toX(spec))` must
 * reproduce the original [NetworkSpec] exactly (data-class structural
 * equality), for both formats and across every serializable shape —
 * sealed [RVSpec] / [PolicySpec] variants, [TransportStrategySpec]
 * `data object`s, nested optionals, [LimitsSpec], maps, and the
 * [CostFormulationSpec] family.
 */
class NetworkSpecSerializationTest {

    /** A kitchen-sink spec exercising as many serializable shapes as possible. */
    private fun kitchenSink() = NetworkSpec(
        name = "KitchenSink",
        transportStrategy = TransportStrategySpec.PerIHPTimeBased,
        items = listOf(
            ItemSpec("A", RVSpec.Exponential(1.0, 1), weight = 2.0, cube = 3.0, unitCost = 4.0),
            ItemSpec("B", RVSpec.Constant(2.0)),
            ItemSpec("C", RVSpec.Lognormal(1.0, 0.25, 2)),
        ),
        nodes = listOf(
            NodeSpec(
                "WH", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                transportTimeFromParent = RVSpec.Uniform(1.0, 3.0, 3),
                enableShipmentFormation = true,
                inventory = listOf(
                    InventorySpec("A", PolicySpec.SQ(4, 20), 20),
                    InventorySpec("B", PolicySpec.SSPeriodic(2, 8, RVSpec.Constant(5.0)), 8),
                ),
            ),
            NodeSpec(
                "XD", NodeType.CD, "WH",
                transportTimeFromParent = RVSpec.Triangular(1.0, 2.0, 4.0, 4),
                shipmentFormationFromParent = ShipmentFormationSpec(
                    FormingOption.WEIGHT, weightLimits = LimitsSpec(1.0, 10.0),
                ),
            ),
            NodeSpec(
                "R", NodeType.IHP, "XD",
                inventory = listOf(InventorySpec("A", PolicySpec.SS(2, 6), 10)),
            ),
        ),
        demandGenerators = listOf(
            DemandGeneratorSpec(
                "R", "A", RVSpec.Exponential(2.0, 10), name = "DG-A",
                shipmentFormation = ShipmentFormationSpec(FormingOption.CUBE, cubeLimits = LimitsSpec(0.0, 5.0)),
            ),
            DemandGeneratorSpec("R", "C", RVSpec.Exponential(1.0, 11)),
        ),
        costFormulations = listOf(
            CostFormulationSpec.Default(name = "base", params = CostParamsSpec(carryingRate = 0.2)),
            CostFormulationSpec.PerNodeIHP(
                name = "perNode",
                default = CostParamsSpec(),
                overrides = mapOf(
                    "WH" to CostParamsSpec(carryingRate = 0.3),
                    "R" to CostParamsSpec(backorderRate = 1.0),
                ),
            ),
        ),
    )

    private fun minimal() = NetworkSpec(
        name = "Minimal",
        items = listOf(ItemSpec("A", RVSpec.Constant(1.0))),
        nodes = listOf(
            NodeSpec(
                "WH", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                inventory = listOf(InventorySpec("A", PolicySpec.SQ(1, 5), 5)),
            ),
        ),
    )

    @Test
    fun `JSON round-trip is lossless for a kitchen-sink spec`() {
        val spec = kitchenSink()
        assertEquals(spec, NetworkSpec.fromJson(spec.toJson()))
    }

    @Test
    fun `TOML round-trip is lossless for a kitchen-sink spec`() {
        val spec = kitchenSink()
        assertEquals(spec, NetworkSpec.fromToml(spec.toToml()))
    }

    @Test
    fun `JSON round-trip is lossless for a minimal spec`() {
        val spec = minimal()
        assertEquals(spec, NetworkSpec.fromJson(spec.toJson()))
    }

    @Test
    fun `TOML round-trip is lossless for a minimal spec`() {
        val spec = minimal()
        assertEquals(spec, NetworkSpec.fromToml(spec.toToml()))
    }

    @Test
    fun `JSON and TOML decode to the same spec`() {
        val spec = kitchenSink()
        assertEquals(NetworkSpec.fromJson(spec.toJson()), NetworkSpec.fromToml(spec.toToml()))
    }
}
