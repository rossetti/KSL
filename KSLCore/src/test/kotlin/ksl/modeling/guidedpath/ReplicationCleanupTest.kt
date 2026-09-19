package ksl.modeling.guidedpath

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  What happens to entities suspended mid-transport when a replication ends.
 *
 *  A transport spans many events, so an entity is suspended for the whole of it -- riding in the
 *  system's movement hold queue, or waiting in the pool's queue for a cart. A replication horizon
 *  will therefore routinely fall while entities are suspended, and if any of that state survived
 *  into the next replication the subsystem would be quietly wrong in a way no single-replication
 *  test could detect.
 *
 *  **Nothing in this subsystem does the cleaning.** It is done by `ProcessModel.afterReplication`,
 *  which terminates every suspended entity, and by `Queue.afterReplication`, which clears. A
 *  terminated process releases its allocations, is removed from its queue with no statistics, and
 *  has any delay event cancelled. That the subsystem relies on inherited machinery rather than
 *  doing anything itself is precisely why it needs a test: there is no code here to read that would
 *  tell a maintainer the property holds.
 *
 *  The model is arranged so the horizon always falls with one entity riding and several queued.
 */
class ReplicationCleanupTest {

    private class Shop(parent: ModelElement) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()
        init { spatialModel = network }
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart1").apply { homeBase = SimpleAgvNetwork.AGV1_HOME }
        val carts = GuidedTransporterPoolWithQ(this, system, listOf(cart),
            ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Carts")

        val observations = mutableListOf<String>()

        inner class Part : Entity() {
            val p = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                guidedTransport(carts, SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION)
            }
        }

        override fun initialize() {
            // Snapshot the state the previous replication left behind, BEFORE creating any work.
            observations.add("rep=${model.currentReplicationNumber} INIT " +
                "awaitingPickupQ=${system.awaitingPickupHoldQ.size} " +
                "ridingQ=${system.ridingHoldQ.size} " +
                "poolWaitingQ=${carts.waitingQ.size} " +
                "cartNumBusy=${cart.numBusy} cartState=${cart.transporterState}")
            // Six parts, one every 5 minutes: the horizon lands mid-transport with others queued.
            repeat(6) { i -> activate(Part().p, timeUntilActivation = i * 5.0) }
        }

        override fun replicationEnded() {
            observations.add("rep=${model.currentReplicationNumber} ENDED " +
                "awaitingPickupQ=${system.awaitingPickupHoldQ.size} " +
                "ridingQ=${system.ridingHoldQ.size} " +
                "poolWaitingQ=${carts.waitingQ.size} " +
                "cartNumBusy=${cart.numBusy} cartState=${cart.transporterState}")
        }
    }

    @Test
    @DisplayName("Every replication starts clean, however the previous one ended")
    fun everyReplicationStartsClean() {
        val m = Model("CleanupProbe")
        val shop = Shop(m)
        m.numberOfReplications = 4
        m.lengthOfReplication = 40.0   // ends while a part is riding and others wait
        m.simulate()

        val inits = shop.observations.filter { it.contains(" INIT ") }
        val ends = shop.observations.filter { it.contains(" ENDED ") }
        assertEquals(4, inits.size)
        assertEquals(4, ends.size)

        // The hazard must actually be present, or the test proves nothing: every replication has to
        // end with an entity riding, entities queued, and the cart committed.
        for (e in ends) {
            // The split says which wait, so this is now checked rather than inferred from the
            // cart's state: exactly one part aboard, and nobody left standing to be collected.
            assertTrue(e.contains("ridingQ=1"), "a part must still be riding at the horizon: $e")
            assertTrue(e.contains("awaitingPickupQ=0"), "nobody should be awaiting collection: $e")
            assertTrue(e.contains("poolWaitingQ=5"), "parts must still be waiting for a cart: $e")
            assertTrue(e.contains("cartNumBusy=1"), "the cart must still be allocated: $e")
            assertTrue(e.contains("cartState=MOVING_LOADED"), "and still carrying: $e")
        }

        // And every replication must nevertheless begin from nothing.
        for (i in inits) {
            assertTrue(i.contains("awaitingPickupQ=0"), "the pickup queue must be empty at initialize: $i")
            assertTrue(i.contains("ridingQ=0"), "the riding queue must be empty at initialize: $i")
            assertTrue(i.contains("poolWaitingQ=0"), "the pool queue must be empty at initialize: $i")
            assertTrue(i.contains("cartNumBusy=0"), "the cart's allocation must not survive: $i")
            assertTrue(i.contains("cartState=IDLE"), "nor its state: $i")
        }
    }

    @Test
    @DisplayName("Diagnostics in replicationEnded see the live state, before anything is torn down")
    fun diagnosticsRunBeforeTeardown() {
        // ModelElement runs replicationEnded() for every element before afterReplication() for any
        // element. That ordering is what lets this subsystem's stall diagnostic report what was
        // stuck: were it reversed, the queues would already be cleared and every diagnostic would
        // report an empty, healthy system.
        val m = Model("OrderingProbe")
        val shop = Shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()
        val ended = shop.observations.single { it.contains(" ENDED ") }
        assertTrue(
            ended.contains("ridingQ=1"),
            "replicationEnded must observe the entity still suspended, not an already-cleared " +
                    "queue: $ended"
        )
    }
}
