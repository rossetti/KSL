package ksl.modeling.station.config

import ksl.modeling.station.MarkingHookIfc
import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData
import kotlin.test.assertTrue

/**
 * Phase-4 DTO/TOML coverage for source marking hooks — the named-hook registry
 * that lets the DTO carry per-instance random marking (sampling a qObjectType,
 * attaching a value object, etc.) by hook name.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QueueingNetworkMarkingConfigTest {

    private fun const(x: Double) = RVData(RVType.Constant, mapOf("value" to doubleArrayOf(x)))

    private fun stampTypeSpec() = QueueingNetworkSpec(
        name = "marking",
        sources = listOf(
            SourceSpec("Arrivals", const(1.0), maxArrivals = 4, marking = "stampType",
                routing = RoutingSpec.Direct("Dispatch"))
        ),
        stations = listOf(
            StationSpec("Dispatch", null,
                routing = RoutingSpec.ByType(
                    branches = listOf(TypeBranch(1, "A"), TypeBranch(2, "B")),
                    default = "A"
                )),
            StationSpec("A", null, routing = RoutingSpec.Direct("Exit")),
            StationSpec("B", null, routing = RoutingSpec.Direct("Exit"))
        ),
        sinks = listOf(SinkSpec("Exit"))
    )

    @Test
    fun tomlRoundTripStable() {
        val t1 = QueueingNetworkToml.encode(stampTypeSpec())
        val t2 = QueueingNetworkToml.encode(QueueingNetworkToml.decode(t1))
        assertEquals(t1, t2, "marking-hook TOML re-encode should be stable")
        assertTrue(t1.contains("stampType"))
        assertTrue(t1.contains("marking"))
    }

    @Test
    fun markingHookStampsTypesDeterministically() {
        val m = Model("DtoMarking", autoCSVReports = false)
        // alternate types 1 and 2
        var i = 0
        val net = StationNetworkBuilder(
            stampTypeSpec(),
            markings = mapOf("stampType" to MarkingHookIfc { q, _ ->
                q.qObjectType = if (i++ % 2 == 0) 1 else 2
            })
        ).build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // 4 arrivals split 2/2 between A and B (alternating type 1, 2)
        val a = net.node("A") as ksl.modeling.station.SingleQStation
        val b = net.node("B") as ksl.modeling.station.SingleQStation
        assertEquals(2.0, a.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(2.0, b.numProcessed.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun unknownMarkingHookFailsLoudly() {
        val m = Model("MissingMarkingHook", autoCSVReports = false)
        val thrown = assertThrows(IllegalStateException::class.java) {
            StationNetworkBuilder(stampTypeSpec()).build(m)
        }
        assertTrue(thrown.message!!.contains("stampType"),
            "error should name the missing marking hook: ${thrown.message}")
    }
}
