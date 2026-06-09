package ksl.modeling.supplychain.spec

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the Kotlin DSL (DSL plan Phase D4): a DSL-authored spec
 * must equal the equivalent hand-built [NetworkSpec], the helpers
 * (`star`, `tier`, `tierFromTables`, `autoStream`, `StreamRange`) must
 * expand correctly, and a tier-generated network must simulate.
 *
 * Equality is compared order-independently (the spec's list order does
 * not affect building or validation), so the tests assert structural
 * sameness rather than emission order.
 */
class SupplyChainDslTest {

    /** Normalize a spec for order-independent structural comparison. */
    private fun normalize(s: NetworkSpec) = listOf(
        s.name,
        s.transportStrategy,
        s.items.toSet(),
        s.nodes.toSet(),
        s.demandGenerators.toSet(),
        s.costFormulations.toSet(),
    )

    private fun assertSameSpec(expected: NetworkSpec, actual: NetworkSpec) =
        assertEquals(normalize(expected), normalize(actual))

    // -- DSL lowers to the same spec as hand-built data -----------------

    @Test
    fun `nested DSL lowers to the equivalent flat hand-built spec`() {
        val dsl = supplyChain("Net") {
            transportStrategy = perIHPTimeBased
            val widget = item("Widget", exponential(1.0, stream = 1), unitCost = 12.5)
            holdingPoint("W") {
                attachedToExternalSupplier(constant(3.0))
                inventory(widget) { sQ(s = 4, Q = 20, initialOnHand = 20) }
                holdingPoint("R1") {
                    transportTimeFromParent = constant(1.0)
                    inventory(widget) { sS(s = 2, S = 5, initialOnHand = 10) }
                    demand(widget, exponential(1.5, stream = 10))
                }
                holdingPoint("R2") {
                    transportTimeFromParent = constant(1.0)
                    inventory(widget) { sS(s = 3, S = 6, initialOnHand = 10) }
                    demand(widget, exponential(2.0, stream = 11))
                }
            }
            defaultCost(params = CostParamsSpec(carryingRate = 0.2))
        }

        val hand = NetworkSpec(
            name = "Net",
            transportStrategy = TransportStrategySpec.PerIHPTimeBased,
            items = listOf(ItemSpec("Widget", RVSpec.Exponential(1.0, 1), unitCost = 12.5)),
            nodes = listOf(
                NodeSpec(
                    "W", NodeType.IHP, NodeSpec.EXTERNAL_SUPPLIER,
                    transportTimeFromParent = RVSpec.Constant(3.0),
                    inventory = listOf(InventorySpec("Widget", PolicySpec.SQ(4, 20), 20)),
                ),
                NodeSpec(
                    "R1", NodeType.IHP, "W",
                    transportTimeFromParent = RVSpec.Constant(1.0),
                    inventory = listOf(InventorySpec("Widget", PolicySpec.SS(2, 5), 10)),
                ),
                NodeSpec(
                    "R2", NodeType.IHP, "W",
                    transportTimeFromParent = RVSpec.Constant(1.0),
                    inventory = listOf(InventorySpec("Widget", PolicySpec.SS(3, 6), 10)),
                ),
            ),
            demandGenerators = listOf(
                DemandGeneratorSpec("R1", "Widget", RVSpec.Exponential(1.5, 10)),
                DemandGeneratorSpec("R2", "Widget", RVSpec.Exponential(2.0, 11)),
            ),
            costFormulations = listOf(
                CostFormulationSpec.Default(params = CostParamsSpec(carryingRate = 0.2)),
            ),
        )

        assertSameSpec(hand, dsl)
        assertTrue(dsl.validate().isEmpty(), "DSL spec should validate: ${dsl.validate()}")
    }

    // -- helpers --------------------------------------------------------

    @Test
    fun `star expands to a root plus leaves`() {
        val spec = supplyChain("Star") {
            transportStrategy = perIHPTimeBased
            val a = item("A", constant(1.0))
            star(
                rootName = "DC",
                leafNames = listOf("S1", "S2", "S3"),
                transportTimeFromRoot = constant(2.0),
                rootConfig = {
                    attachedToExternalSupplier(constant(4.0))
                    inventory(a) { sQ(5, 20, 30) }
                },
                leafConfig = {
                    inventory(a) { sS(2, 6, 10) }
                    demand(a, exponential(1.0, stream = autoStream()))
                },
            )
        }
        // 1 root + 3 leaves.
        assertEquals(4, spec.nodes.size)
        val leaves = spec.nodes.filter { it.parent == "DC" }
        assertEquals(setOf("S1", "S2", "S3"), leaves.map { it.name }.toSet())
        assertTrue(leaves.all { it.transportTimeFromParent == RVSpec.Constant(2.0) })
        // Each leaf got a demand generator with a distinct auto stream.
        assertEquals(3, spec.demandGenerators.size)
        assertEquals(setOf(1, 2, 3), spec.demandGenerators.map { (it.interArrival as RVSpec.Exponential).stream }.toSet())
        assertTrue(spec.validate().isEmpty())
    }

    @Test
    fun `tier generates N children under a parent`() {
        val spec = supplyChain("Tier") {
            val a = item("A", constant(1.0))
            holdingPoint("W") {
                inventory(a) { sQ(5, 20, 30) }
                tier(count = 4, namePrefix = "R", transportTime = constant(1.0)) { i ->
                    inventory(a) { sS(2 + i, 6 + i, 10) }
                    demand(a, exponential(1.5, stream = autoStream()))
                }
            }
        }
        val children = spec.nodes.filter { it.parent == "W" }
        assertEquals(setOf("R1", "R2", "R3", "R4"), children.map { it.name }.toSet())
        // index-dependent config applied.
        val r1 = spec.nodes.first { it.name == "R1" }
        assertEquals(PolicySpec.SS(2, 6), r1.inventory.single().policy)
        val r4 = spec.nodes.first { it.name == "R4" }
        assertEquals(PolicySpec.SS(5, 9), r4.inventory.single().policy)
    }

    @Test
    fun `StreamRange hands out a contiguous block and then refuses`() {
        val range = StreamRange(base = 100, count = 3)
        assertEquals(listOf(100, 101, 102), List(3) { range.next() })
        assertEquals(3, range.used)
        assertThrows<IllegalStateException> { range.next() }
    }

    @Test
    fun `attachedToExternalSupplier on a non-root node is rejected`() {
        assertThrows<IllegalStateException> {
            supplyChain("Bad") {
                item("A", constant(1.0))
                holdingPoint("W") {
                    holdingPoint("R") {
                        attachedToExternalSupplier(constant(1.0)) // R's parent is W
                    }
                }
            }
        }
    }

    // -- tier-from-tables builds and simulates --------------------------

    @Test
    fun `a tierFromTables network builds and simulates`() {
        val rsTable = listOf(
            listOf(PolicySpec.SS(2, 3), PolicySpec.SS(1, 2)),
            listOf(PolicySpec.SS(1, 3), PolicySpec.SS(2, 4)),
            listOf(PolicySpec.SS(2, 4), PolicySpec.SS(1, 2)),
        )
        val demandTable = listOf(
            listOf<RVSpec?>(exponential(2.0, 10), exponential(1.0, 11)),
            listOf<RVSpec?>(exponential(1.0, 12), exponential(2.0, 13)),
            listOf<RVSpec?>(exponential(2.5, 14), exponential(1.5, 15)),
        )

        val spec = supplyChain("Tables") {
            transportStrategy = perIHPTimeBased
            val t1 = item("Type-1", exponential(1.0, 1))
            val t2 = item("Type-2", exponential(0.5, 2))
            holdingPoint("Warehouse") {
                attachedToExternalSupplier(constant(3.0))
                inventory(t1) { sQ(4, 20, 20) }
                inventory(t2) { sQ(5, 20, 20) }
                tierFromTables(
                    namePrefix = "R",
                    items = listOf(t1, t2),
                    policyTable = rsTable,
                    initialOnHand = 10,
                    transportTime = constant(1.0),
                    demandTable = demandTable,
                )
            }
            defaultCost()
        }

        // 1 warehouse + 3 retailers; 3 retailers × 2 items = 6 demand generators.
        assertEquals(4, spec.nodes.size)
        assertEquals(6, spec.demandGenerators.size)
        assertTrue(spec.validate().isEmpty(), "spec should validate: ${spec.validate()}")

        val m = Model("dsl-tables")
        val result = SupplyChainBuilder.build(m, spec)
        assertEquals(4, result.network.getInventoryHoldingPoints().size)
        m.numberOfReplications = 2
        m.lengthOfReplication = 1000.0
        m.lengthOfReplicationWarmUp = 200.0
        m.simulate()
        assertTrue(m.responses.any { it.name.contains("GrandTotal") })
    }

    // -- DSL output survives a serialization round-trip -----------------

    @Test
    fun `a DSL-authored spec round-trips through TOML`() {
        val spec = supplyChain("RT") {
            transportStrategy = perIHPTimeBased
            val a = item("A", exponential(1.0, 1))
            holdingPoint("W") {
                attachedToExternalSupplier(constant(2.0))
                inventory(a) { sQ(4, 20, 20) }
                tier(2, "R", constant(1.0)) {
                    inventory(a) { sS(2, 5, 10) }
                    demand(a, exponential(1.5, stream = autoStream()))
                }
            }
        }
        assertEquals(spec, NetworkSpec.fromToml(spec.toToml()))
    }
}
