package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.FeasibleAssignments
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 *  The feasible set, checked against a board whose arithmetic is known by hand.
 *
 *  The set exists so a policy can enumerate and search the actions available to it, rather than
 *  scoring only the candidates it happened to think of. That makes its correctness load-bearing in a
 *  way an ordinary convenience would not be: a policy that trusts `size` or `candidates()` is
 *  trusting them to be the whole action set, and a set that quietly omitted a pairing would produce
 *  a fleet that never considered its best option and no output that said so.
 *
 *  The layout has a deliberate dead end: a one-way spur into `Island` that can be entered from the
 *  ring but not left, so a vehicle parked there can reach nothing. That makes infeasibility a real
 *  property of the network rather than something contrived with a flag.
 */
class FeasibleSetTest {

    companion object {
        const val NORTH_STATION = "NorthStation"
        const val SOUTH_STATION = "SouthStation"
        const val ISLAND = "IslandPark"
        const val WEST_PARK = "WestPark"
        const val EAST_PARK = "EastPark"

        fun create(): GuidedPathNetwork = GuidedPathNetwork.builder("Islands")
            .intersection("N", x = 0.0, y = 100.0)
            .intersection("E", x = 100.0, y = 0.0)
            .intersection("S", x = 0.0, y = -100.0)
            .intersection("W", x = -100.0, y = 0.0)
            .intersection("Island", x = 200.0, y = 0.0)
            .intersection("WPark", x = -140.0, y = 0.0)
            .intersection("EPark", x = 100.0, y = 40.0)
            .link("NE", "N", "E", length = 100.0, zoneLength = 10.0, beginDirection = 315.0)
            .link("ES", "E", "S", length = 200.0, zoneLength = 10.0, beginDirection = 225.0)
            .link("SW", "S", "W", length = 100.0, zoneLength = 10.0, beginDirection = 135.0)
            .link("WN", "W", "N", length = 400.0, zoneLength = 10.0, beginDirection = 45.0)
            // One way in, no way out: a vehicle that parks here can reach nothing.
            .link("ToIsland", "E", "Island", length = 50.0, zoneLength = 50.0, beginDirection = 0.0)
            .link("WParkSpur", "W", "WPark", length = 40.0, zoneLength = 40.0,
                type = LinkType.SPUR, beginDirection = 180.0)
            .link("EParkSpur", "E", "EPark", length = 40.0, zoneLength = 40.0,
                type = LinkType.SPUR, beginDirection = 90.0)
            .station(NORTH_STATION, "N")
            .station(SOUTH_STATION, "S")
            .station(ISLAND, "Island")
            .station(WEST_PARK, "WPark")
            .station(EAST_PARK, "EPark")
            .build()
    }

    /** Captures the feasible set on the first pass that has something in it, then assigns nothing. */
    private class Capturing : AssignmentPolicyIfc {
        var captured: FeasibleAssignments? = null

        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            if (captured == null && context.board.numWaiting >= 2 && context.available.size >= 3) {
                captured = context.feasible
            }
            return emptyList()   // deliberately inert, so the board stays as posed
        }
    }

    private class Shop(parent: ModelElement, val policy: Capturing) : ProcessModel(parent, "Shop") {

        val network = create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, assignmentPolicy = policy, name = "Agv")

        val west = AgvVehicle(agv, TransporterPlacement.At(WEST_PARK), ConstantRV(10.0), name = "West")
            .apply { homeBase = WEST_PARK }
        val east = AgvVehicle(agv, TransporterPlacement.At(EAST_PARK), ConstantRV(10.0), name = "East")
            .apply { homeBase = EAST_PARK }
        val marooned = AgvVehicle(agv, TransporterPlacement.At(ISLAND), ConstantRV(10.0), name = "Marooned")

        inner class Load(label: String, val from: String) : Entity(label) {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(from)
                transportByFleet(agv, SOUTH_STATION, origin = from)
            }
        }

        override fun initialize() {
            activate(Load("ToNorth", NORTH_STATION).p)
            activate(Load("ToSouth", SOUTH_STATION).p)
        }
    }

    @Test
    @DisplayName("The feasible set matches hand arithmetic, and a marooned vehicle is in no pairing")
    fun theSetMatchesTheBoard() {
        val policy = Capturing()
        val m = Model("FeasibleSet")
        val shop = Shop(m, policy)
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        m.simulate()

        val f = assertNotNull(policy.captured, "no pass saw two tasks and three vehicles")

        assertEquals(2, f.outstanding.size, "expected both tasks unassigned: ${f.outstanding.map { it.name }}")
        assertEquals(3, f.available.size, "expected all three vehicles available")

        // Two reachable vehicles times two tasks. The marooned one contributes nothing, so the size
        // is not simply the product of the two list sizes -- which is the whole point of asking.
        assertEquals(4, f.size, "the feasible set is the wrong size: $f")
        assertFalse(f.isEmpty)
        assertEquals(4, f.candidates().count())
        assertEquals(4, f.candidates().map { it.vehicle.name to it.task.name }.distinct().count(),
            "candidates() returned a duplicate pairing")

        // The marooned vehicle is in no pairing at all, in either direction of the query.
        assertTrue(f.candidatesFor(shop.marooned).none(),
            "a vehicle that can reach nothing appeared as a candidate")
        for (task in f.outstanding) {
            assertFalse(f.isFeasible(shop.marooned, task), "marooned should not be feasible for ${task.name}")
            assertEquals(Double.POSITIVE_INFINITY, f.cost(shop.marooned, task),
                "an unreachable pairing should cost infinity, so a comparison never prefers it")
            assertEquals(2, f.candidatesFor(task).count(), "each task should have two takers")
        }

        // The costs are the network's, computed by hand from the layout. West parks 40 down a spur
        // off `W`; the north station is at `N`, 400 along `WN`. East parks 40 off `E`; the north
        // station is reached the long way round, `E`->`S`->`W`->`N`, which is 200+100+400.
        val north = f.outstanding.first { it.pickupLocation == NORTH_STATION }
        assertEquals(440.0, f.cost(shop.west, north), 1e-9, "west-to-north is not the guide path distance")
        assertEquals(740.0, f.cost(shop.east, north), 1e-9, "east-to-north is not the guide path distance")

        // And `best` picks by the score it is given, not by the cost baked into the pairing.
        val cheapest = assertNotNull(f.best { f.cost(it.vehicle, it.task) })
        assertEquals(f.candidates().minOf { f.cost(it.vehicle, it.task) },
            f.cost(cheapest.vehicle, cheapest.task), 1e-9,
            "best() did not return the lowest-scoring candidate")
        val reversed = assertNotNull(f.best { -f.cost(it.vehicle, it.task) })
        assertEquals(f.candidates().maxOf { f.cost(it.vehicle, it.task) },
            f.cost(reversed.vehicle, reversed.task), 1e-9,
            "best() ignored a score that inverts the ordering, so it is not using the score at all")
    }
}
