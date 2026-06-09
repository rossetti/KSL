package ksl.modeling.supplychain.report

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
import ksl.utilities.io.report.toMarkdown
import ksl.utilities.io.report.toText
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Tests for the supply-chain results report: it must render the
 * expected sections, name each cost formulation, collapse the cost
 * matrix to only the produced tiers/lines, and include the
 * multi-formulation comparison.
 */
class SupplyChainReportTest {

    private fun simulatedNetwork() = run {
        val spec = NetworkSpec(
            name = "ReportNet",
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            items = listOf(ItemSpec("Widget", RVSpec.Exponential(1.0, 1), unitCost = 10.0)),
            nodes = listOf(
                NodeSpec(
                    "Warehouse", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                    transportTimeFromParent = RVSpec.Constant(3.0),
                    inventory = listOf(InventorySpec("Widget", PolicySpec.SQ(5, 20), 30)),
                ),
                NodeSpec(
                    "R1", NodeType.IHP, "Warehouse",
                    transportTimeFromParent = RVSpec.Constant(1.0),
                    inventory = listOf(InventorySpec("Widget", PolicySpec.SS(3, 10), 10)),
                ),
                NodeSpec(
                    "R2", NodeType.IHP, "Warehouse",
                    transportTimeFromParent = RVSpec.Constant(1.0),
                    inventory = listOf(InventorySpec("Widget", PolicySpec.SS(2, 8), 10)),
                ),
            ),
            demandGenerators = listOf(
                DemandGeneratorSpec("R1", "Widget", RVSpec.Exponential(2.0, 10)),
                DemandGeneratorSpec("R2", "Widget", RVSpec.Exponential(1.5, 11)),
            ),
            costFormulations = listOf(
                CostFormulationSpec.Default(name = "baseline", params = CostParamsSpec(carryingRate = 0.10)),
                CostFormulationSpec.PerNodeIHP(
                    name = "whHeavy",
                    default = CostParamsSpec(carryingRate = 0.10),
                    overrides = mapOf("Warehouse" to CostParamsSpec(carryingRate = 0.40)),
                ),
            ),
        )
        val m = Model("report")
        val result = SupplyChainBuilder.build(m, spec)
        m.numberOfReplications = 4
        m.lengthOfReplication = 1500.0
        m.lengthOfReplicationWarmUp = 300.0
        m.simulate()
        result.network
    }

    @Test
    fun `results report renders the expected sections and content`() {
        val md = simulatedNetwork().resultsReport().toMarkdown()

        // Sections.
        assertTrue("Network Overview" in md, "missing overview section")
        assertTrue("Cost Summary" in md, "missing cost section")
        assertTrue("Inventory Performance" in md, "missing inventory section")

        // Both formulations named, and the comparison table.
        assertTrue("baseline" in md, "missing baseline formulation")
        assertTrue("whHeavy" in md, "missing whHeavy formulation")
        assertTrue("Comparison" in md, "missing multi-formulation comparison")

        // Cost matrix produced the flow + holding lines, with a grand total.
        assertTrue("HOLDING" in md, "missing HOLDING line")
        assertTrue("LOADING" in md, "missing LOADING line")
        assertTrue("TOTAL" in md, "missing TOTAL row")
        assertTrue("Grand total" in md, "missing grand total")

        // Topology + inventory rows are present.
        assertTrue("Warehouse" in md && "R1" in md && "R2" in md, "missing nodes")
        assertTrue("Fill rate" in md, "missing inventory fill-rate column")
    }

    @Test
    fun `the cost matrix omits structurally-absent tiers`() {
        // No cross-docks in this network, so the CD tier column must not appear.
        val md = simulatedNetwork().resultsReport().toMarkdown()
        // The matrix header lists tier columns; CD should be collapsed out.
        // (ES appears as a tier via ES loading; IHP always; CD never.)
        val costSection = md.substringAfter("Cost Summary").substringBefore("Inventory Performance")
        assertTrue("| CD " !in costSection && "CD |" !in costSection, "CD tier column should be omitted")
    }

    @Test
    fun `report also renders as plain text without error`() {
        val text = simulatedNetwork().resultsReport(title = "Plain").toText()
        assertTrue(text.isNotBlank())
        assertTrue("Cost Summary" in text)
    }
}
