package ksl.modeling.supplychain.spec

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [NetworkSpec.validate].  The contract is: a well-formed
 * spec yields an empty error list, and every distinct structural /
 * reference defect produces at least one [SpecError].  Each test starts
 * from a known-good baseline and introduces exactly one defect, so a
 * failure points at a single broken (or missing) check.
 */
class NetworkSpecValidationTest {

    // -- baseline: a valid 2-tier network -------------------------------
    // EXTERNAL_SUPPLIER -> warehouse (IHP) -> retailer (IHP), one item,
    // one demand generator at the retailer.  SharedCarrier (the default)
    // so no shipment-formation constraint is in play.

    private fun item(name: String = "widget") =
        ItemSpec(name = name, leadTime = RVSpec.Constant(5.0))

    private fun inv(item: String = "widget") =
        InventorySpec(itemTypeName = item, policy = PolicySpec.SQ(1, 10), initialOnHand = 50)

    private fun warehouse() = NodeSpec(
        name = "warehouse",
        type = NodeType.IHP,
        parent = NodeSpec.EXTERNAL_SUPPLIER,
        inventory = listOf(inv()),
    )

    private fun retailer() = NodeSpec(
        name = "retailer",
        type = NodeType.IHP,
        parent = "warehouse",
        inventory = listOf(inv()),
    )

    private fun dg() = DemandGeneratorSpec(
        node = "retailer",
        itemTypeName = "widget",
        interArrival = RVSpec.Exponential(2.0, 1),
    )

    private fun baseline() = NetworkSpec(
        name = "net",
        items = listOf(item()),
        nodes = listOf(warehouse(), retailer()),
        demandGenerators = listOf(dg()),
    )

    private fun assertHasError(errors: List<SpecError>, fragment: String) {
        assertTrue(
            errors.any { it.message.contains(fragment, ignoreCase = true) },
            "expected an error mentioning '$fragment', got: $errors",
        )
    }

    // -- the happy path -------------------------------------------------

    @Test
    fun `a well-formed spec produces no errors`() {
        assertEquals(emptyList(), baseline().validate())
    }

    @Test
    fun `formation under PerIHPTimeBased is valid`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            nodes = listOf(
                warehouse().copy(enableShipmentFormation = true),
                retailer().copy(
                    shipmentFormationFromParent =
                        ShipmentFormationSpec(FormingOption.COUNT, countLimit = 5),
                ),
            ),
        )
        assertEquals(emptyList(), spec.validate())
    }

    // -- names ----------------------------------------------------------

    @Test
    fun `duplicate item names are caught`() {
        val spec = baseline().copy(items = listOf(item(), item()))
        assertHasError(spec.validate(), "duplicate item name 'widget'")
    }

    @Test
    fun `duplicate node names are caught`() {
        val spec = baseline().copy(nodes = listOf(warehouse(), warehouse()))
        assertHasError(spec.validate(), "duplicate node name 'warehouse'")
    }

    @Test
    fun `a node named with the reserved sentinel is caught`() {
        val spec = baseline().copy(
            nodes = listOf(warehouse().copy(name = NodeSpec.EXTERNAL_SUPPLIER)),
        )
        assertHasError(spec.validate(), "reserved")
    }

    // -- supplier graph -------------------------------------------------

    @Test
    fun `an empty node list is caught`() {
        val spec = baseline().copy(nodes = emptyList(), demandGenerators = emptyList())
        assertHasError(spec.validate(), "no nodes")
    }

    @Test
    fun `no root node is caught`() {
        // retailer -> warehouse -> retailer: every node has a node parent,
        // so none attaches to the external supplier.
        val spec = baseline().copy(
            nodes = listOf(
                warehouse().copy(parent = "retailer"),
                retailer(),
            ),
        )
        assertHasError(spec.validate(), "no root node")
    }

    @Test
    fun `a dangling parent reference is caught`() {
        val spec = baseline().copy(
            nodes = listOf(warehouse(), retailer().copy(parent = "ghost")),
        )
        assertHasError(spec.validate(), "unknown parent 'ghost'")
    }

    @Test
    fun `a supplier cycle is caught`() {
        // warehouse -> retailer -> warehouse, plus a valid root so the
        // "no root" check does not mask the cycle check.
        val root = NodeSpec("root", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER, inventory = listOf(inv()))
        val spec = baseline().copy(
            nodes = listOf(
                root,
                warehouse().copy(parent = "retailer"),
                retailer().copy(parent = "warehouse"),
            ),
        )
        assertHasError(spec.validate(), "cycle")
    }

    // -- node contents --------------------------------------------------

    @Test
    fun `a cross-dock that carries inventory is caught`() {
        val spec = baseline().copy(
            nodes = listOf(
                warehouse(),
                retailer().copy(type = NodeType.CD, inventory = listOf(inv())),
            ),
        )
        assertHasError(spec.validate(), "cross-dock node 'retailer' must hold no inventory")
    }

    @Test
    fun `an inventory referencing an unknown item is caught`() {
        val spec = baseline().copy(
            nodes = listOf(warehouse(), retailer().copy(inventory = listOf(inv("gadget")))),
        )
        assertHasError(spec.validate(), "unknown item type 'gadget'")
    }

    // -- demand generators ----------------------------------------------

    @Test
    fun `a demand generator on an unknown node is caught`() {
        val spec = baseline().copy(demandGenerators = listOf(dg().copy(node = "ghost")))
        assertHasError(spec.validate(), "unknown node 'ghost'")
    }

    @Test
    fun `a demand generator for an unknown item is caught`() {
        val spec = baseline().copy(demandGenerators = listOf(dg().copy(itemTypeName = "gadget")))
        assertHasError(spec.validate(), "unknown item type 'gadget'")
    }

    // -- shipment formation ---------------------------------------------

    @Test
    fun `formation under the wrong transport strategy is caught`() {
        // SharedCarrier (default) + a formation-enabled node.
        val spec = baseline().copy(
            nodes = listOf(warehouse().copy(enableShipmentFormation = true), retailer()),
        )
        assertHasError(spec.validate(), "requires transportStrategy = PerIHPTimeBased")
    }

    @Test
    fun `COUNT formation without a positive countLimit is caught`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            nodes = listOf(
                warehouse().copy(enableShipmentFormation = true),
                retailer().copy(
                    shipmentFormationFromParent = ShipmentFormationSpec(FormingOption.COUNT),
                ),
            ),
        )
        assertHasError(spec.validate(), "COUNT formation requires countLimit")
    }

    @Test
    fun `WEIGHT formation without weightLimits is caught`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            nodes = listOf(
                warehouse().copy(enableShipmentFormation = true),
                retailer().copy(
                    shipmentFormationFromParent = ShipmentFormationSpec(FormingOption.WEIGHT),
                ),
            ),
        )
        assertHasError(spec.validate(), "WEIGHT formation requires weightLimits")
    }

    @Test
    fun `WEIGHT formation with inverted limits is caught`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            nodes = listOf(
                warehouse().copy(enableShipmentFormation = true),
                retailer().copy(
                    shipmentFormationFromParent = ShipmentFormationSpec(
                        FormingOption.WEIGHT,
                        weightLimits = LimitsSpec(min = 10.0, max = 1.0),
                    ),
                ),
            ),
        )
        assertHasError(spec.validate(), "weightLimits min > max")
    }

    @Test
    fun `CUBE formation without cubeLimits is caught`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            nodes = listOf(
                warehouse().copy(enableShipmentFormation = true),
                retailer().copy(
                    shipmentFormationFromParent = ShipmentFormationSpec(FormingOption.CUBE),
                ),
            ),
        )
        assertHasError(spec.validate(), "CUBE formation requires cubeLimits")
    }

    @Test
    fun `CUBE formation with inverted limits is caught`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            nodes = listOf(
                warehouse().copy(enableShipmentFormation = true),
                retailer().copy(
                    shipmentFormationFromParent = ShipmentFormationSpec(
                        FormingOption.CUBE,
                        cubeLimits = LimitsSpec(min = 10.0, max = 1.0),
                    ),
                ),
            ),
        )
        assertHasError(spec.validate(), "cubeLimits min > max")
    }

    @Test
    fun `formation on a demand generator is validated too`() {
        val spec = baseline().copy(
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            demandGenerators = listOf(
                dg().copy(shipmentFormation = ShipmentFormationSpec(FormingOption.COUNT)),
            ),
        )
        assertHasError(spec.validate(), "COUNT formation requires countLimit")
    }

    // -- cost formulations ----------------------------------------------

    @Test
    fun `a per-node cost override for an unknown node is caught`() {
        val spec = baseline().copy(
            costFormulations = listOf(
                CostFormulationSpec.PerNodeIHP(
                    overrides = mapOf("ghost" to CostParamsSpec()),
                ),
            ),
        )
        assertHasError(spec.validate(), "overrides unknown node 'ghost'")
    }

    // -- accumulation ---------------------------------------------------

    @Test
    fun `validate accumulates multiple independent errors`() {
        val spec = baseline().copy(
            items = listOf(item(), item()),                 // dup item
            nodes = listOf(warehouse(), retailer().copy(parent = "ghost")), // dangling parent
        )
        val errors = spec.validate()
        assertHasError(errors, "duplicate item name")
        assertHasError(errors, "unknown parent 'ghost'")
        assertTrue(errors.size >= 2, "expected at least 2 errors, got: $errors")
    }
}
