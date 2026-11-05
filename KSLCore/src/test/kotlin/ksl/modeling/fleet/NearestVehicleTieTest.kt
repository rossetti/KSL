package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.KSLProcessBuilder
import ksl.modeling.entity.ProcessModel
import ksl.modeling.fleet.policies.AssignmentPolicyIfc
import ksl.modeling.fleet.policies.Bid
import ksl.modeling.fleet.policies.BidPolicyIfc
import ksl.modeling.fleet.policies.CompletionTimeBid
import ksl.modeling.fleet.policies.ContractNetAssignmentPolicy
import ksl.modeling.fleet.policies.DispatchContext
import ksl.modeling.fleet.policies.FurthestVehiclePolicy
import ksl.modeling.fleet.policies.MoveToStagingDisposition
import ksl.modeling.fleet.policies.NearestVehiclePolicy
import ksl.modeling.fleet.policies.NetworkDistanceBid
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.MoveToStagingAreaRule
import ksl.modeling.spatial.Euclidean2DPlane
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.BernoulliRV
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two carts often measure the same distance to a pickup: one parked at an intersection, and one in
 * the last zone of the link into it. The one behind can't start until the one in front moves.
 */
@DisplayName("Vehicles at the same distance from a pickup")
class NearestVehicleTieTest {

    companion object {
        /** A one-way loop A, S, P, D, with 10 ft zones. S is the staging point. */
        fun loop(): GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .intersection("A").intersection("S").intersection("P").intersection("D")
            .link("Approach", "A", "S", length = 30.0, zoneLength = 10.0)
            .link("Out", "S", "P", length = 20.0, zoneLength = 10.0)
            .link("Deliver", "P", "D", length = 20.0, zoneLength = 10.0)
            .link("Back", "D", "A", length = 60.0, zoneLength = 10.0)
            .build()

        /**
         * The same with a second drop-off, E, on a short way back. A cart freed at D can arrive at
         * the staging point behind one freed later at E, so the vehicle free longest isn't always
         * the one in front.
         */
        fun twoDropOffs(): GuidedPathNetwork = GuidedPathNetwork.builder("TwoDropOffs")
            .intersection("A").intersection("S").intersection("P").intersection("D").intersection("E")
            .link("Approach", "A", "S", length = 30.0, zoneLength = 10.0)
            .link("Out", "S", "P", length = 20.0, zoneLength = 10.0)
            .link("Deliver", "P", "D", length = 20.0, zoneLength = 10.0)
            .link("Back", "D", "A", length = 100.0, zoneLength = 10.0)
            .link("DeliverE", "P", "E", length = 20.0, zoneLength = 10.0)
            .link("BackE", "E", "A", length = 10.0, zoneLength = 10.0)
            .build()
    }

    /**
     * One part at P wants to go to D. "Behind" is declared first, sorts first by name, and stands
     * behind "Parked", so a rule that leaves the tie to either order sends the wrong vehicle.
     */
    private class OneRequest(
        parent: ModelElement,
        policy: AssignmentPolicyIfc,
        bid: BidPolicyIfc? = null
    ) : ProcessModel(parent, "OneRequest") {
        val net = loop()
        val agv = AgvSystem(this, net, assignmentPolicy = policy, name = "AgvSys")
        val queued = AgvVehicle(agv, TransporterPlacement.OnZone("Approach.Zone3"), ConstantRV(10.0), name = "Behind")
        val parked = AgvVehicle(agv, TransporterPlacement.At("S"), ConstantRV(10.0), name = "Parked")
        var carrier: String? = null
        var deliveredAt = Double.NaN

        init {
            spatialModel = net
            if (bid != null) {
                queued.bidPolicy = bid
                parked.bidPolicy = bid
            }
        }

        inner class Part : Entity() {
            val p: KSLProcess = process {
                delay(1.0)
                currentLocation = net.requireLocation("P")
                val r = transportByFleet(agv, destination = "D", origin = "P")
                carrier = r.vehicleName
                deliveredAt = time
            }
        }

        override fun initialize() {
            carrier = null
            activate(Part().p)
        }
    }

    private fun oneRequest(policy: AssignmentPolicyIfc, bid: BidPolicyIfc? = null): OneRequest {
        val m = Model("Tie${System.nanoTime()}", autoCSVReports = false)
        val shop = OneRequest(m, policy, bid)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("nearest sends the vehicle in front, which delivers at the same time as the passive pool")
    fun nearestSendsTheVehicleInFront() {
        val shop = oneRequest(NearestVehiclePolicy())
        assertEquals("Parked", shop.carrier)
        assertEquals(5.0, shop.deliveredAt, 1e-9)
        // The premise: by distance alone, the task goes to the vehicle that can't start.
        assertNull(oneRequest(DistanceOnly()).carrier)
    }

    @Test
    @DisplayName("Contract-Net with either shipped bid awards to the vehicle in front")
    fun contractNetAwardsToTheVehicleInFront() {
        assertEquals("Parked", oneRequest(ContractNetAssignmentPolicy(0.0), NetworkDistanceBid()).carrier)
        assertEquals("Parked", oneRequest(ContractNetAssignmentPolicy(0.0), CompletionTimeBid()).carrier)
        // The premise: by value and name alone, the award goes to the vehicle that can't start.
        val byName: (List<Bid>) -> Bid? = { bids -> bids.minWithOrNull(compareBy({ it.value }, { it.vehicle.name })) }
        assertNull(oneRequest(ContractNetAssignmentPolicy(0.0, byName), NetworkDistanceBid()).carrier)
    }

    @Test
    @DisplayName("furthest prefers the vehicle behind, as the passive furthest rule does")
    fun furthestPrefersTheVehicleBehind() {
        // Not delivered: the vehicle behind can't start until the parked one moves, and nothing moves it.
        assertNull(oneRequest(FurthestVehiclePolicy()).carrier)
    }

    @Test
    @DisplayName("the count and the read-only view report where a vehicle stands")
    fun countAndViewReportPosition() {
        val shop = oneRequest(NearestVehiclePolicy())
        // Parked has gone to D and stands there; the other has not moved.
        assertEquals("Approach.Zone3", shop.queued.frontZone?.name)
        assertEquals(listOf("Approach.Zone3"), shop.queued.heldZones.map { it.name })
        assertTrue(shop.queued.travellingForward)
        // S, the two zones of Out, and P
        assertEquals(4, shop.queued.zonesToEnter("P"))
        assertEquals("D", shop.parked.frontZone?.name)
        assertNull(shop.parked.zonesToEnter("NotAPlace"))
    }

    private class FreePathTie(parent: ModelElement) : ProcessModel(parent, "FreePathTie") {
        val plane = Euclidean2DPlane()
        val places = listOf(plane.Point(0.0, 0.0, "Depot"), plane.Point(100.0, 0.0, "Press"))

        init {
            spatialModel = plane
        }

        val fleet = FreePathFleet(this, plane, places, name = "Fleet")
        val second = FreePathVehicle(fleet, "Depot", ConstantRV(10.0), name = "Zed", stepSize = 5.0)
        val first = FreePathVehicle(fleet, "Depot", ConstantRV(10.0), name = "Abel", stepSize = 5.0)
        var carrier: String? = null

        inner class Load : Entity() {
            val p: KSLProcess = process {
                delay(1.0)
                currentLocation = places[0]
                carrier = transportByFleet(fleet, destination = "Press", origin = "Depot").vehicleName
            }
        }

        override fun initialize() {
            activate(Load().p)
        }
    }

    @Test
    @DisplayName("a free path has no count, and a tie keeps today's rule: the first available vehicle")
    fun freePathKeepsItsRule() {
        val m = Model("FreeTie${System.nanoTime()}", autoCSVReports = false)
        val shop = FreePathTie(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()
        assertNull(shop.first.zonesToEnter("Press"))
        assertEquals("Zed", shop.carrier)
    }

    /** The rule before the tie-break: distance alone, keeping the first at a tie. */
    private class DistanceOnly : AssignmentPolicyIfc {
        override suspend fun KSLProcessBuilder.assign(context: DispatchContext): List<AssignmentProposal> {
            val free = context.available.toMutableList()
            val proposals = mutableListOf<AssignmentProposal>()
            for (task in context.board.unassigned) {
                if (free.isEmpty()) break
                val chosen = free.minByOrNull { context.distanceTo(it, task.pickupLocation) } ?: break
                free.remove(chosen)
                proposals.add(AssignmentProposal(chosen, task))
            }
            return proposals
        }
    }

    private class ActiveShop(parent: ModelElement, policy: AssignmentPolicyIfc) : ProcessModel(parent, "ActiveShop") {
        val net = twoDropOffs()
        val agv = AgvSystem(this, net, assignmentPolicy = policy, name = "Agv")
        val carts = listOf("Approach.Zone1", "Approach.Zone2", "Approach.Zone3").mapIndexed { i, z ->
            AgvVehicle(agv, TransporterPlacement.OnZone(z), ConstantRV(10.0), name = "Cart$i").apply {
                dispositionPolicy = MoveToStagingDisposition("S")
            }
        }
        private val gap = RandomVariable(this, ExponentialRV(8.0, 11))
        private val pick = RandomVariable(this, BernoulliRV(0.5, 12))
        val flowTime = Response(this, "FlowTime")

        init {
            spatialModel = net
        }

        inner class Part : Entity() {
            val p: KSLProcess = process {
                currentLocation = net.requireLocation("P")
                transportByFleet(agv, destination = if (pick.value > 0.5) "D" else "E", origin = "P")
                flowTime.value = time - createTime
            }
        }

        private fun arrive(event: KSLEvent<Nothing>) {
            activate(Part().p)
            schedule(::arrive, gap)
        }

        override fun initialize() {
            schedule(::arrive, gap)
        }
    }

    private class PassiveShop(parent: ModelElement) : ProcessModel(parent, "PassiveShop") {
        val net = twoDropOffs()
        private val sys = GuidedPathTransportSystem(this, net, name = "Sys")
        private val carts = listOf("Approach.Zone1", "Approach.Zone2", "Approach.Zone3").mapIndexed { i, z ->
            GuidedTransporter(sys, TransporterPlacement.OnZone(z), ConstantRV(10.0), name = "Cart$i")
        }
        private val pool = GuidedTransporterPoolWithQ(
            this, sys, carts, idleDispositionRule = MoveToStagingAreaRule("S"), name = "Pool"
        )
        private val gap = RandomVariable(this, ExponentialRV(8.0, 11))
        private val pick = RandomVariable(this, BernoulliRV(0.5, 12))
        val flowTime = Response(this, "FlowTime")

        init {
            spatialModel = net
        }

        inner class Part : Entity() {
            val p: KSLProcess = process {
                currentLocation = net.requireLocation("P")
                guidedTransport(pool, if (pick.value > 0.5) "D" else "E", "P")
                flowTime.value = time - createTime
            }
        }

        private fun arrive(event: KSLEvent<Nothing>) {
            activate(Part().p)
            schedule(::arrive, gap)
        }

        override fun initialize() {
            schedule(::arrive, gap)
        }
    }

    private fun runShop(build: (Model) -> Response): Pair<Double, Double> {
        val m = Model("Shop${System.nanoTime()}", autoCSVReports = false)
        val flow = build(m)
        m.numberOfReplications = 10
        m.lengthOfReplication = 20000.0
        m.lengthOfReplicationWarmUp = 2000.0
        m.simulate()
        val s = flow.acrossReplicationStatistic
        return s.average to s.halfWidth
    }

    @Test
    @DisplayName("three vehicles with shared staging: active is closer to passive than distance alone")
    fun threeVehiclesDock() {
        val passive = runShop { PassiveShop(it).flowTime }
        val active = runShop { ActiveShop(it, NearestVehiclePolicy()).flowTime }
        val distanceOnly = runShop { ActiveShop(it, DistanceOnly()).flowTime }
        assertTrue(
            abs(active.first - passive.first) < abs(distanceOnly.first - passive.first),
            "passive $passive, active $active, distance alone $distanceOnly"
        )
    }
}
