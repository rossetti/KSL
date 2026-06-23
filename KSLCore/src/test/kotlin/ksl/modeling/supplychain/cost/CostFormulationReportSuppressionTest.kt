package ksl.modeling.supplychain.cost

import ksl.modeling.supplychain.ItemType
import ksl.modeling.supplychain.SupplyChainModel
import ksl.modeling.supplychain.inventory.*
import ksl.modeling.supplychain.network.MultiEchelonNetwork
import ksl.modeling.supplychain.network.TransportStrategy
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the rollup-Response report-suppression logic in
 * [DefaultMultiEchelonCostFormulation].  The formulation pre-allocates
 * a [Response] for every `NodeTier × CostLine` cell of the rollup
 * matrix (3 × 12 = 36 cells), but most of those combinations are
 * structurally impossible (e.g. `ES × Holding`, `CD × Ordering`) or
 * topology-absent (e.g. CD-tier cells when no cross-docks exist).
 *
 * To keep the standard half-width summary report readable, the init
 * block walks the calculator list, computes which (tier, line)
 * combinations actually have a producer, and sets
 * [Response.defaultReportingOption] to `false` on the rest.  The
 * Responses still exist for programmatic access via
 * [DefaultMultiEchelonCostFormulation.byTierAndLineResponse] /
 * [byTierResponse] / [byLineResponse]; they just don't appear in the
 * standard report.
 */
class CostFormulationReportSuppressionTest {

    @Test
    fun `structurally impossible and topology-absent rollups are hidden from reports`() {
        // Topology: ES → 1 IHP → 1 retailer.  Single item, no
        // cross-docks, no shipment formation.
        val m = Model("Suppress")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(1.0))
        val ihp = net.addInventoryHoldingPoint("P")
        ihp.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(ihp, ConstantRV(0.25))
        net.attachDemandGenerator(
            ihp, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )
        val f = DefaultMultiEchelonCostFormulation(net)

        // -- Structurally impossible per-(tier, line) cells: ES tier
        //    has only ESLoading; CD tier has no inventory-side lines;
        //    IHP / CD tiers don't have ESLoading.
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.ES, CostLine.Holding)),
            "ES × Holding should be suppressed")
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.ES, CostLine.Loading)),
            "ES × Loading should be suppressed")
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.IHP, CostLine.ESLoading)),
            "IHP × ESLoading should be suppressed")
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.CD, CostLine.Holding)),
            "CD × Holding should be suppressed (CDs hold no inventory)")
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.CD, CostLine.ESLoading)),
            "CD × ESLoading should be suppressed")

        // -- Topology-absent: no cross-docks in this network → every
        //    CD-tier cell is suppressed.  And no shipment formation →
        //    IHP × ShipmentBuilderHolding is suppressed.
        for (line in CostLine.all) {
            assertFalse(rep(f.byTierAndLineResponse(NodeTier.CD, line)),
                "CD × $line should be suppressed (no cross-docks)")
        }
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.IHP, CostLine.ShipmentBuilderHolding)),
            "IHP × ShipmentBuilderHolding should be suppressed (no formation)")

        // -- Per-tier rollups: CD tier should be suppressed; IHP and
        //    ES tiers stay visible (they have calculators).
        assertFalse(rep(f.byTierResponse(NodeTier.CD)),
            "CD tier total should be suppressed (no CDs)")
        assertTrue(rep(f.byTierResponse(NodeTier.IHP)),
            "IHP tier total should be visible")
        assertTrue(rep(f.byTierResponse(NodeTier.ES)),
            "ES tier total should be visible")

        // -- Per-line rollups: ShipmentBuilderHolding should be
        //    suppressed (no formation → no builder calculator).
        assertFalse(rep(f.byLineResponse(CostLine.ShipmentBuilderHolding)),
            "ShipmentBuilderHolding per-line total should be suppressed")
        // Lines that DO have calculators stay visible.
        assertTrue(rep(f.byLineResponse(CostLine.Holding)),
            "Holding per-line total should be visible")
        assertTrue(rep(f.byLineResponse(CostLine.ESLoading)),
            "ESLoading per-line total should be visible")

        // -- Grand total stays visible.
        assertTrue(rep(f.totalCostResponse),
            "Grand total should be visible")

        // -- Suppression should not affect programmatic readability.
        //    Even suppressed Responses must still integrate to 0 at
        //    replication end (NaN would be a regression).
        m.numberOfReplications = 1
        m.lengthOfReplication = 10.0
        m.simulate()
        assertEquals(0.0,
            f.byTierAndLineResponse(NodeTier.CD, CostLine.Holding)!!.value, 1e-12)
        assertEquals(0.0,
            f.byTierResponse(NodeTier.CD)!!.value, 1e-12)
    }

    @Test
    fun `cross-dock topology leaves CD-tier visible`() {
        val m = Model("Suppress.CD")
        val sc = SupplyChainModel(m, name = "SC")
        val net = MultiEchelonNetwork(
            sc, name = "Net",
            transportStrategy = TransportStrategy.PerIHPTimeBased,
        )
        val item = net.addItemType("X", ConstantRV(1.0))
        val cd = net.addInventoryCrossDock("CD")
        val leaf = net.addInventoryHoldingPoint("Leaf")
        leaf.addReorderPointReorderQuantityInventory(
            item, reorderPoint = 1, reorderQty = 5, initialOnHand = 5,
        )
        net.attachToExternalSupplier(cd, ConstantRV(0.25))
        net.attachToSupplier(cd, leaf, ConstantRV(0.5))
        net.attachDemandGenerator(
            leaf, item, ConstantRV(1.0), name = "DG",
            transportTime = ConstantRV.ZERO,
        )
        val f = DefaultMultiEchelonCostFormulation(net)

        // -- CD-tier total should be visible (a CD exists → Loading,
        //    Shipping, Unloading calculators were built for it).
        assertTrue(rep(f.byTierResponse(NodeTier.CD)),
            "CD tier total should be visible when CDs exist")
        // -- And the CD × Loading specific cell should be visible.
        assertTrue(rep(f.byTierAndLineResponse(NodeTier.CD, CostLine.Loading)),
            "CD × Loading should be visible when CDs exist")
        // -- But CD-tier cells for inventory lines stay suppressed
        //    (CDs hold no inventory regardless of topology).
        assertFalse(rep(f.byTierAndLineResponse(NodeTier.CD, CostLine.Holding)),
            "CD × Holding should still be suppressed (CDs hold no inventory)")
    }

    private fun rep(r: ResponseCIfc?): Boolean =
        (r as Response).defaultReportingOption
}
