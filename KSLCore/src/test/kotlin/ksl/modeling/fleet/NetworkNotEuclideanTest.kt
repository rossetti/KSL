package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  "Nearest" means nearest along the guide path, and the layout is built so the two answers differ.
 *
 *  On a one-way loop the two notions of distance come apart badly, and the failure they produce is
 *  silent. A vehicle standing a few feet past a pickup point in space may have to travel almost the
 *  whole circuit to reach it, so a rule using straight-line separation sends the wrong vehicle,
 *  every time, with no symptom other than a fleet that performs worse than it should and nothing in
 *  the output pointing at why.
 *
 *  The test network makes the disagreement unambiguous. Loads are collected at the entry station,
 *  `I1` at `(0, 72)`. `Cart1` parks on the spur at `I6` `(54, 72)` and `Cart2` at `I7` `(54, 0)`:
 *
 *  | | straight line to I1 | along the one-way loop |
 *  |---|---|---|
 *  | `Cart1` at `I6` | **54** | 198 (I6 → I2 → I3 → I4 → I1) |
 *  | `Cart2` at `I7` | 90 | **126** (I7 → I3 → I4 → I1) |
 *
 *  Each measure names a different winner, so a policy cannot satisfy both and the test cannot pass
 *  by accident.
 */
class NetworkNotEuclideanTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")

        val cart1 = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(10.0), name = "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val cart2 = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME), ConstantRV(10.0), name = "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        var carrier: String? = null

        inner class Part : Entity("Part") {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                carrier = transportByFleet(
                    agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION
                ).vehicleName
            }
        }

        override fun initialize() {
            activate(Part().p)
        }
    }

    @Test
    @DisplayName("NearestVehiclePolicy measures along the path, not across the floor")
    fun theNearestVehicleIsTheOneNearestAlongThePath() {
        val m = Model("NotEuclidean")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 500.0
        m.simulate()

        val entry = shop.network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
        val i6 = shop.network.requireLocation(SimpleAgvNetwork.AGV1_HOME)
        val i7 = shop.network.requireLocation(SimpleAgvNetwork.AGV2_HOME)

        // First: confirm the layout still disagrees with itself. If a later edit to the network made
        // the two measures agree, this test would pass while proving nothing, so the premise is
        // asserted rather than assumed.
        val straightTo6 = hypot(i6.x - entry.x, i6.y - entry.y)
        val straightTo7 = hypot(i7.x - entry.x, i7.y - entry.y)
        val pathTo6 = shop.network.distance(i6, entry)
        val pathTo7 = shop.network.distance(i7, entry)

        assertTrue(straightTo6 < straightTo7,
            "the layout no longer has Cart1 nearer in space ($straightTo6 vs $straightTo7)")
        assertTrue(pathTo7 < pathTo6,
            "the layout no longer has Cart2 nearer along the path ($pathTo7 vs $pathTo6)")

        // And the policy picked the one the guide path favours, which is the one straight-line
        // distance rejects.
        assertEquals("Cart2", assertNotNull(shop.carrier),
            "the policy sent the vehicle that is nearest in space rather than along the path: " +
                    "straight-line ${"%.0f".format(straightTo6)} vs ${"%.0f".format(straightTo7)}, " +
                    "path ${"%.0f".format(pathTo6)} vs ${"%.0f".format(pathTo7)}")
    }
}
