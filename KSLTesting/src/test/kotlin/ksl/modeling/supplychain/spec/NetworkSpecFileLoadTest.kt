package ksl.modeling.supplychain.spec

import ksl.simulation.Model
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Loads the authored `.toml` / `.json` spec files shipped under
 * `KSLExamples` resources, then builds and simulates them — the
 * end-to-end "a topology authored as a file builds and runs"
 * acceptance for DSL plan Phase D3.  Also asserts the TOML and JSON
 * forms of the same network parse to the identical [NetworkSpec].
 */
class NetworkSpecFileLoadTest {

    private fun resource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
        assertNotNull(stream, "missing resource $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private val base = "/ksl/examples/general/supplychain"

    @Test
    fun `the SS network TOML and JSON files parse to the same spec`() {
        val fromToml = NetworkSpec.fromToml(resource("$base/me-ss-network.toml"))
        val fromJson = NetworkSpec.fromJson(resource("$base/me-ss-network.json"))
        assertEquals(fromToml, fromJson)
        // Sanity on the parsed topology.
        assertEquals("ME-Inventory-Network", fromToml.name)
        assertEquals(TransportStrategySpec.PerIHPTimeBased, fromToml.transportStrategy)
        assertEquals(4, fromToml.items.size)
        assertEquals(6, fromToml.nodes.size) // warehouse + 5 retailers
        assertEquals(20, fromToml.demandGenerators.size)
        assertTrue(fromToml.validate().isEmpty(), "authored spec must be valid")
    }

    @Test
    fun `the SS network loaded from TOML builds and simulates`() {
        val spec = NetworkSpec.fromToml(resource("$base/me-ss-network.toml"))
        val m = Model("ss-from-toml")
        val result = SupplyChainBuilder.build(m, spec)
        assertEquals(6, result.network.getInventoryHoldingPoints().size)
        m.numberOfReplications = 2
        m.lengthOfReplication = 1000.0
        m.lengthOfReplicationWarmUp = 200.0
        m.simulate()
        assertTrue(m.responses.any { it.name.contains("GrandTotal") })
    }

    @Test
    fun `the cross-dock network loaded from JSON builds and simulates`() {
        val spec = NetworkSpec.fromJson(resource("$base/cross-dock-network.json"))
        val m = Model("cd-from-json")
        val result = SupplyChainBuilder.build(m, spec)
        assertEquals(1, result.network.getInventoryCrossDocks().size)
        assertEquals(2, result.network.getInventoryHoldingPoints().size)
        m.numberOfReplications = 2
        m.lengthOfReplication = 1000.0
        m.lengthOfReplicationWarmUp = 200.0
        m.simulate()
    }

    @Test
    fun `a comparative multi-formulation cost study runs from a TOML file`() {
        val spec = NetworkSpec.fromToml(resource("$base/comparative-cost-network.toml"))
        assertEquals(3, spec.costFormulations.size)
        assertTrue(spec.validate().isEmpty(), "authored spec must be valid: ${spec.validate()}")

        val m = Model("comparative-from-toml")
        SupplyChainBuilder.build(m, spec)
        m.numberOfReplications = 2
        m.lengthOfReplication = 1500.0
        m.lengthOfReplicationWarmUp = 300.0
        m.simulate()

        // Each named formulation produced its own grand-total response,
        // and higher carrying rates cost more on the same sample path.
        val std = m.responses.first { it.name == "standard:GrandTotal" }.acrossReplicationStatistic.average
        val high = m.responses.first { it.name == "highCarrying:GrandTotal" }.acrossReplicationStatistic.average
        val whHeavy = m.responses.first { it.name == "warehouseHeavy:GrandTotal" }.acrossReplicationStatistic.average
        assertTrue(std > 0.0)
        assertTrue(high > std, "highCarrying ($high) should exceed standard ($std)")
        assertTrue(whHeavy > std, "warehouseHeavy ($whHeavy) should exceed standard ($std)")
    }
}
