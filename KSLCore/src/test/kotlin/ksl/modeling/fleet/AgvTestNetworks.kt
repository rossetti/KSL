package ksl.modeling.fleet

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType

/**
 *  Layouts shared by more than one test in this package.
 *
 *  A one-way ring is the shape that makes guide-path distance disagree with straight-line distance,
 *  which is what most of these tests are about; keeping the ring in one place stops each test
 *  inventing its own and drifting.
 */
internal object AgvTestNetworks {

    const val NEAR = "NearStation"
    const val FAR = "FarStation"
    const val DROP = "DropPoint"
    const val DEPOT = "Depot"
    const val SECOND_DEPOT = "SecondDepot"

    /**
     *  A drop point on the leg *between* the far station and the near one.
     *
     *  Where a load is set down decides which pickup a freed vehicle is nearest to, and on a one-way
     *  ring that is not a detail. With the drop at `S` -- immediately before `FarStation` -- a cart
     *  is always nearest the far pickup after every delivery, so "first in the queue" and "nearest"
     *  name the same task and any policy comparison between those two ideas is unfalsifiable. This
     *  alias sits after the far station and before the near one, so the two come apart.
     */
    const val ALT_DROP = "AltDrop"

    /** One cart's worth of parking. Four legs of 100, one way round. */
    fun ring(): GuidedPathNetwork = ringBuilder().build()

    /**
     *  The same ring with a second parking spur and an alternative drop point, for tests that need
     *  two vehicles and need "first in the queue" to differ from "nearest".
     *
     *  The `WN` leg is split by `D`, so the ring runs `N -> E -> S -> W -> D -> N`. A load set down
     *  at `D` leaves the vehicle 150 from the near pickup at `E` and 350 from the far one at `W`.
     */
    fun ringWithTwoParks(): GuidedPathNetwork = GuidedPathNetwork.builder("RingTwoParks")
        .intersection("N", x = 0.0, y = 100.0)
        .intersection("E", x = 100.0, y = 0.0)
        .intersection("S", x = 0.0, y = -100.0)
        .intersection("W", x = -100.0, y = 0.0)
        .intersection("D", x = -50.0, y = 50.0)
        .intersection("Park", x = 0.0, y = 140.0)
        .intersection("Park2", x = 140.0, y = 0.0)
        .link("NE", "N", "E", length = 100.0, zoneLength = 10.0, beginDirection = 315.0)
        .link("ES", "E", "S", length = 100.0, zoneLength = 10.0, beginDirection = 225.0)
        .link("SW", "S", "W", length = 100.0, zoneLength = 10.0, beginDirection = 135.0)
        .link("WD", "W", "D", length = 50.0, zoneLength = 10.0, beginDirection = 45.0)
        .link("DN", "D", "N", length = 50.0, zoneLength = 10.0, beginDirection = 45.0)
        .link("ParkSpur", "N", "Park", length = 20.0, zoneLength = 20.0,
            type = LinkType.SPUR, beginDirection = 90.0)
        .link("ParkSpur2", "E", "Park2", length = 20.0, zoneLength = 20.0,
            type = LinkType.SPUR, beginDirection = 0.0)
        .station(NEAR, "E")
        .station(FAR, "W")
        .station(ALT_DROP, "D")
        .station(DEPOT, "Park")
        .station(SECOND_DEPOT, "Park2")
        .build()

    private fun ringBuilder(): GuidedPathNetwork.Builder = GuidedPathNetwork.builder("Ring")
        .intersection("N", x = 0.0, y = 100.0)
        .intersection("E", x = 100.0, y = 0.0)
        .intersection("S", x = 0.0, y = -100.0)
        .intersection("W", x = -100.0, y = 0.0)
        .intersection("Park", x = 0.0, y = 140.0)
        .link("NE", "N", "E", length = 100.0, zoneLength = 10.0, beginDirection = 315.0)
        .link("ES", "E", "S", length = 100.0, zoneLength = 10.0, beginDirection = 225.0)
        .link("SW", "S", "W", length = 100.0, zoneLength = 10.0, beginDirection = 135.0)
        .link("WN", "W", "N", length = 100.0, zoneLength = 10.0, beginDirection = 45.0)
        .link("ParkSpur", "N", "Park", length = 20.0, zoneLength = 20.0,
            type = LinkType.SPUR, beginDirection = 90.0)
        .station(NEAR, "E")
        .station(FAR, "W")
        .station(DROP, "S")
        .station(DEPOT, "Park")
}
