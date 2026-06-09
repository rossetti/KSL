package ksl.modeling.supplychain.spec

import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.InventoryHoldingPoint
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity tests for [SupplyChainBuilder]: a network instantiated from a
 * [NetworkSpec] must simulate **identically** to the equivalent
 * hand-coded network.  Because stream numbers are explicit and the
 * topology / policies are the same, the two runs draw the same
 * sequences and produce bit-identical statistics; the test asserts
 * exact equality of every response and counter average.
 *
 * The reference topology mirrors `MultiEchelonNetworkSSPolicyExample`
 * (PerIHPTimeBased; a warehouse on (s, Q) serving five retailers on
 * (s, S), under an external supplier).  A second case exercises the
 * SharedCarrier strategy and a cross-dock, end-to-end.
 */
class SupplyChainBuilderParityTest {

    // -- (s, S) example data, shared by both build paths -----------------

    private val leadStreams = listOf(1, 2, 3, 4)
    private val leadMeans = listOf(1.0, 0.5, 1.5, 2.0)

    // warehouse (s, Q): reorderPoint to reorderQty, initialOnHand 20
    private val warehouseSQ = listOf(4 to 1, 5 to 1, 3 to 2, 4 to 2)

    // retailer (s, S) per (retailer, type), initialOnHand 10
    private val rsTable = listOf(
        listOf(2 to 3, 1 to 2, 2 to 4, 3 to 6), // R1
        listOf(1 to 3, 2 to 4, 2 to 5, 2 to 3), // R2
        listOf(2 to 4, 1 to 2, 2 to 3, 2 to 3), // R3
        listOf(3 to 6, 3 to 4, 1 to 2, 2 to 3), // R4
        listOf(2 to 3, 0 to 1, 3 to 6, 1 to 2), // R5
    )
    private val demandMeans = listOf(
        listOf(2.0, 1.0, 1.5, 3.0), // R1
        listOf(1.0, 2.0, 2.5, 1.5), // R2
        listOf(2.5, 1.5, 2.0, 2.0), // R3
        listOf(3.0, 2.5, 1.0, 2.5), // R4
        listOf(1.5, 0.5, 3.0, 0.5), // R5
    )

    /** Hand-coded reference build — the example's `buildModel`, verbatim. */
    private fun handCoded(): Model {
        val m = Model("parity")
        val sc = SupplyChainModel(m, name = "ME")
        val net = MultiEchelonNetwork(
            sc, name = "ME-network",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val itemTypes = (0 until 4).map { i ->
            net.addItemType("Type-${i + 1}", ExponentialRV(leadMeans[i], streamNum = leadStreams[i]))
        }
        val warehouse: InventoryHoldingPoint = net.addInventoryHoldingPoint("Warehouse")
        for ((type, sq) in itemTypes.zip(warehouseSQ)) {
            warehouse.addReorderPointReorderQuantityInventory(type, sq.first, sq.second, 20)
        }
        val retailers = (1..5).map { net.addInventoryHoldingPoint("R$it") }
        for ((retailer, row) in retailers.zip(rsTable)) {
            for ((type, rs) in itemTypes.zip(row)) {
                retailer.addReorderPointOrderUpToLevelInventory(type, rs.first, rs.second, 10)
            }
        }
        net.attachIHPToExternalSupplier(warehouse, ConstantRV(3.0))
        for (retailer in retailers) {
            net.attachIHPToSupplier(warehouse, retailer, ConstantRV(1.0))
        }
        var streamNum = 10
        for ((retailer, means) in retailers.zip(demandMeans)) {
            for ((type, mean) in itemTypes.zip(means)) {
                net.attachDemandGeneratorToIHP(
                    retailer, type, ExponentialRV(mean, streamNum = streamNum++),
                )
            }
        }
        return m
    }

    /** The same network described as data and instantiated by the builder. */
    private fun specBuilt(): Model {
        val items = (0 until 4).map { i ->
            ItemSpec("Type-${i + 1}", RVSpec.Exponential(leadMeans[i], leadStreams[i]))
        }
        val warehouse = NodeSpec(
            name = "Warehouse",
            type = NodeType.IHP,
            parent = NodeSpec.EXTERNAL_SUPPLIER,
            transportTimeFromParent = RVSpec.Constant(3.0),
            inventory = items.zip(warehouseSQ).map { (item, sq) ->
                InventorySpec(item.name, PolicySpec.SQ(sq.first, sq.second), initialOnHand = 20)
            },
        )
        val retailers = (0 until 5).map { r ->
            NodeSpec(
                name = "R${r + 1}",
                type = NodeType.IHP,
                parent = "Warehouse",
                transportTimeFromParent = RVSpec.Constant(1.0),
                inventory = items.zip(rsTable[r]).map { (item, rs) ->
                    InventorySpec(item.name, PolicySpec.SS(rs.first, rs.second), initialOnHand = 10)
                },
            )
        }
        var streamNum = 10
        val generators = (0 until 5).flatMap { r ->
            items.zip(demandMeans[r]).map { (item, mean) ->
                DemandGeneratorSpec(
                    node = "R${r + 1}",
                    itemTypeName = item.name,
                    interArrival = RVSpec.Exponential(mean, streamNum++),
                )
            }
        }
        val spec = NetworkSpec(
            name = "ME",
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            items = items,
            nodes = listOf(warehouse) + retailers,
            demandGenerators = generators,
        )
        val m = Model("parity")
        SupplyChainBuilder.build(m, spec)
        return m
    }

    private fun run(m: Model) {
        m.numberOfReplications = 4
        m.lengthOfReplication = 2000.0
        m.lengthOfReplicationWarmUp = 500.0
        m.simulate()
    }

    /** Sorted multiset of across-replication response averages. */
    private fun responseAverages(m: Model): List<Double> =
        m.responses.map { it.acrossReplicationStatistic.average }.sorted()

    private fun counterAverages(m: Model): List<Double> =
        m.counters.map { it.acrossReplicationStatistic.average }.sorted()

    @Test
    fun `spec-built (s,S) network simulates identically to hand-coded`() {
        val hand = handCoded().also { run(it) }
        val spec = specBuilt().also { run(it) }

        val handResp = responseAverages(hand)
        val specResp = responseAverages(spec)
        assertEquals(
            handResp.size, specResp.size,
            "different number of responses (hand=${handResp.size}, spec=${specResp.size})",
        )
        assertTrue(handResp.isNotEmpty(), "no responses were produced")
        for (i in handResp.indices) {
            assertEquals(
                handResp[i], specResp[i], 0.0,
                "response average #$i differs: hand=${handResp[i]} spec=${specResp[i]}",
            )
        }

        val handCtr = counterAverages(hand)
        val specCtr = counterAverages(spec)
        assertEquals(handCtr.size, specCtr.size, "different number of counters")
        for (i in handCtr.indices) {
            assertEquals(
                handCtr[i], specCtr[i], 0.0,
                "counter average #$i differs: hand=${handCtr[i]} spec=${specCtr[i]}",
            )
        }
    }

    // -- SharedCarrier + cross-dock end-to-end --------------------------

    private fun handCodedCrossDock(): Model {
        val m = Model("cd-parity")
        val sc = SupplyChainModel(m, name = "CD")
        val net = MultiEchelonNetwork(sc, name = "CD-network") // SharedCarrier default
        val item = net.addItemType("A", ExponentialRV(1.0, streamNum = 1))
        val warehouse = net.addInventoryHoldingPoint("WH")
        warehouse.addReorderPointReorderQuantityInventory(item, 5, 20, 30)
        val cd = net.addInventoryCrossDock("XD")
        val retailer = net.addInventoryHoldingPoint("R")
        retailer.addReorderPointOrderUpToLevelInventory(item, 3, 10, 10)
        net.attachIHPToExternalSupplier(warehouse)
        net.attachToSupplier(warehouse, cd)
        net.attachToSupplier(cd, retailer)
        net.attachDemandGeneratorToIHP(retailer, item, ExponentialRV(2.0, streamNum = 5))
        return m
    }

    private fun specBuiltCrossDock(): Model {
        val spec = NetworkSpec(
            name = "CD",
            items = listOf(ItemSpec("A", RVSpec.Exponential(1.0, 1))),
            nodes = listOf(
                NodeSpec(
                    "WH", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                    inventory = listOf(InventorySpec("A", PolicySpec.SQ(5, 20), 30)),
                ),
                NodeSpec("XD", NodeType.CD, "WH"),
                NodeSpec(
                    "R", NodeType.IHP, "XD",
                    inventory = listOf(InventorySpec("A", PolicySpec.SS(3, 10), 10)),
                ),
            ),
            demandGenerators = listOf(
                DemandGeneratorSpec("R", "A", RVSpec.Exponential(2.0, 5)),
            ),
        )
        val m = Model("cd-parity")
        SupplyChainBuilder.build(m, spec)
        return m
    }

    @Test
    fun `spec-built cross-dock network simulates identically to hand-coded`() {
        val hand = handCodedCrossDock().also { run(it) }
        val spec = specBuiltCrossDock().also { run(it) }

        val handResp = responseAverages(hand)
        val specResp = responseAverages(spec)
        assertEquals(handResp.size, specResp.size, "different number of responses")
        assertTrue(handResp.isNotEmpty(), "no responses were produced")
        for (i in handResp.indices) {
            assertEquals(
                handResp[i], specResp[i], 0.0,
                "response average #$i differs: hand=${handResp[i]} spec=${specResp[i]}",
            )
        }
    }
}
