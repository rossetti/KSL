package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The two subsystems report the same quantities under the same names.
 *
 *  This is what makes the equivalence benchmark possible. Comparing a passive model to an active one
 *  row by row only works if the rows can be matched, and matching them by hand -- by a reader
 *  deciding that this subsystem's utilization figure means the same as that one's -- is how a
 *  comparison quietly stops comparing. The names have to line up.
 *
 *  They do not line up *exactly*, and the discrepancy is worth pinning rather than papering over. A
 *  vehicle's physical statistics are reported by the `GuidedTransporter` it composes, which is a
 *  model element in its own right and must have a distinct name -- so an active `Cart1` reports
 *  `Cart1:Body:FracTimeMoving` where the passive `Cart1` reports `Cart1:FracTimeMoving`. The mapping
 *  is one documented suffix, applied mechanically, and this test is where that claim is checked
 *  rather than assumed.
 *
 *  The alternative -- naming the body the same as the vehicle -- is not available: model element
 *  names are unique across a model.
 */
class StatisticParityTest {

    private class PassiveShop(parent: ModelElement) : ProcessModel(parent, "Passive") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }
        val pool = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Pool"
        )

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                guidedTransport(
                    pool, SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION
                )
            }
        }

        override fun initialize() {
            repeat(3) { i -> activate(Part().p, timeUntilActivation = i * 80.0) }
        }
    }

    private class ActiveShop(parent: ModelElement) : ProcessModel(parent, "Active") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            repeat(3) { i -> activate(Part().p, timeUntilActivation = i * 80.0) }
        }
    }

    private fun statisticNames(build: (Model) -> Unit): Set<String> {
        val m = Model("Parity")
        build(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 400.0
        m.simulate()
        return m.simulationReporter.acrossReplicationStatisticsList().map { it.name }.toSet()
    }

    @Test
    @DisplayName("A vehicle's physical statistics match the passive transporter's, under one documented suffix")
    fun theSharedStatisticsCarryMatchingNames() {
        val passive = statisticNames { PassiveShop(it) }
        val active = statisticNames { ActiveShop(it) }

        // The quantities a comparison needs: what a vehicle spent its time doing.
        val shared = listOf(
            "FracTimeMoving", "FracTimeTransporting", "FracTimeMovingEmpty",
            "FracTimeBlocked", "NumTimesBlocked"
        )
        for (stat in shared) {
            val fromPassive = "Cart1:$stat"
            val fromActive = "Cart1:Body:$stat"
            assertTrue(fromPassive in passive,
                "the passive subsystem no longer reports $fromPassive; the benchmark's row mapping " +
                        "is out of date. Present: ${passive.filter { it.startsWith("Cart1") }}")
            assertTrue(fromActive in active,
                "the active subsystem no longer reports $fromActive. Present: " +
                        "${active.filter { it.startsWith("Cart1") }}")
        }

        // The mapping is exactly one suffix, applied mechanically -- not a per-statistic lookup a
        // reader has to maintain. Every physical statistic the passive cart reports has an active
        // counterpart found by inserting ":Body", and this is what a benchmark can rely on.
        val passivePhysical = passive.filter { it.startsWith("Cart1:") }
        assertTrue(passivePhysical.isNotEmpty())
        val unmapped = passivePhysical.filterNot { it.replace("Cart1:", "Cart1:Body:") in active }
        assertEquals(emptyList(), unmapped,
            "these passive statistics have no active counterpart under the documented mapping, so a " +
                    "row-by-row comparison would silently drop them")

        // And the active subsystem reports things the passive one has no concept of -- which is the
        // point of it, and is why the mapping is one-way rather than a bijection.
        //
        // These are compared by full name, and that is a weaker check than it looks: the two systems
        // are different model elements, so any "Agv:..." row is absent from a model whose system is
        // called "Sys" whatever either of them measures. It says these rows exist on one side and are
        // not literally present on the other; it cannot say that no *name* means two things.
        //
        // It could not, and did not. `Agv:TransportTime` sat opposite the passive `Sys:TransportTime`
        // -- one name, two intervals, request-to-set-down against aboard-to-set-down -- while this
        // loop appeared to rule it out. Comparing leaf names instead does not fix it either, because
        // leaves like `NumInQ` are generic to every queue in the library and would conflate all of
        // them. The check that actually holds is structural and lives in `StatisticNamingTest`: the
        // two subsystems' *own* rows, in one model containing both, must have disjoint names.
        for (extra in listOf(
            "Agv:Dispatcher:TaskQ:NumInQ",
            "Agv:Dispatcher:WaitForAssignment",
            "Agv:TimeAboard",
            "Cart1:FracTimeOnTask"
        )) {
            assertTrue(extra in active, "the active subsystem should report $extra")
            assertTrue(extra !in passive, "$extra should have no passive counterpart")
        }
    }
}
