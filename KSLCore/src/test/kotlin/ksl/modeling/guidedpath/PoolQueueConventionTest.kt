package ksl.modeling.guidedpath

import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The transporter pool queues the way every other resource in the library queues.
 *
 *  It did not always. The pool used to park an entity in a `HoldQueue` **only when the fleet was
 *  busy**, so a request served immediately recorded no observation at all and the reported mean
 *  wait was over waiters rather than over requests -- which is not what `seize` means by a queue,
 *  and not what a mean wait means in queueing. It also carried a second queue, a `SeizeQ`, that
 *  existed purely because the underlying seize needed one to point at, and that reported a mean of
 *  exactly zero on every model.
 *
 *  The pool is now an `AbstractResourcePool` with a single `RequestQ`, seized like a resource pool
 *  or a movable resource pool. These tests pin the convention rather than the mechanism, because it
 *  is the convention that was wrong: the count of observations must be the count of requests.
 */
class PoolQueueConventionTest {

    private class Shop(
        parent: ModelElement,
        numCarts: Int = 1,
        private val arrivalTimes: List<Double> = listOf(0.0)
    ) : ProcessModel(parent, "Shop") {

        val network = ksl.examples.general.guidedpath.SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")

        val carts = (1..numCarts).map {
            GuidedTransporter(
                system, TransporterPlacement.At("I${5 + it}"), ConstantRV(10.0), 1,
                name = "Cart$it"
            )
        }

        val pool = GuidedTransporterPoolWithQ(
            this, system, carts, idleDispositionRule = ParkInPlaceRule(), name = "Carts"
        )

        /** The order in which parts were given a cart, and what each waited. */
        val servedOrder = mutableListOf<String>()
        val waits = linkedMapOf<String, Double>()

        inner class Part(name: String) : Entity(name) {
            val make = process("part") {
                val arrived = time
                entity.currentLocation =
                    network.requireLocation(ksl.examples.general.guidedpath.SimpleAgvNetwork.ENTRY_STATION)
                val request = requestGuidedTransporter(
                    pool, ksl.examples.general.guidedpath.SimpleAgvNetwork.ENTRY_STATION
                )
                servedOrder.add(this@Part.name!!)
                waits[this@Part.name!!] = request.timeAllocated - arrived
                transportBy(request, ksl.examples.general.guidedpath.SimpleAgvNetwork.EXIT_STATION)
                releaseGuidedTransporter(request, pool)
            }
        }

        override fun initialize() {
            servedOrder.clear()
            waits.clear()
            arrivalTimes.forEachIndexed { i, t ->
                activate(Part("P${i + 1}").make, timeUntilActivation = t)
            }
        }
    }

    private fun run(shop: (Model) -> Shop, horizon: Double = 2_000.0): Shop {
        val m = Model("PoolQ")
        val s = shop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = horizon
        m.simulate()
        return s
    }

    @Test
    @DisplayName("A request served immediately records a wait of zero, not no observation")
    fun anImmediateRequestIsStillObserved() {
        val shop = run({ Shop(it, numCarts = 1, arrivalTimes = listOf(0.0)) })

        val stat = shop.pool.waitingQ.timeInQ.withinReplicationStatistic
        assertEquals(1, shop.servedOrder.size, "the part was not served")
        assertEquals(0.0, shop.waits.getValue("P1"), 1.0e-12, "the cart was free, so the wait was 0")
        assertEquals(
            1.0, stat.count,
            "one request must produce one observation, as seize does; got ${stat.count}"
        )
        assertEquals(0.0, stat.weightedAverage, 1.0e-12, "the observation should be the zero wait")
    }

    @Test
    @DisplayName("Every request produces exactly one observation, waiters and non-waiters alike")
    fun observationsCountRequests() {
        // One cart and four parts released together: the first is served at once, three wait.
        val shop = run({ Shop(it, numCarts = 1, arrivalTimes = listOf(0.0, 0.0, 0.0, 0.0)) })

        val stat = shop.pool.waitingQ.timeInQ.withinReplicationStatistic
        assertEquals(4, shop.servedOrder.size, "not every part was served: ${shop.servedOrder}")
        assertEquals(
            4.0, stat.count,
            "four requests must produce four observations; got ${stat.count}"
        )
        assertEquals(0.0, shop.waits.getValue(shop.servedOrder.first()), 1.0e-12)
        assertTrue(
            shop.waits.values.drop(1).all { it > 0.0 },
            "the parts that queued should have positive waits: ${shop.waits}"
        )
    }

    @Test
    @DisplayName("Parts that queued are served in order")
    fun theQueueIsHonoured() {
        val shop = run({ Shop(it, numCarts = 1, arrivalTimes = listOf(0.0, 1.0, 2.0, 3.0)) })
        assertEquals(
            listOf("P1", "P2", "P3", "P4"), shop.servedOrder,
            "a FIFO queue served its parts out of order"
        )
    }

    @Test
    @DisplayName("The pool carries one queue, not a real one and a vestigial one")
    fun thePoolHasASingleQueue() {
        val m = Model("PoolQCount")
        val shop = Shop(m, numCarts = 2, arrivalTimes = listOf(0.0))
        // The vestigial SeizeQ that existed only to give the underlying seize something to point
        // at is gone: the pool is now seized through the queue that actually holds the waiting.
        val tree = m.modelElementsAsString
        assertTrue(
            !tree.contains("SeizeQ"),
            "the pool still carries a second, vestigial queue"
        )
        assertTrue(
            shop.pool.waitingQ.name.endsWith(":Q"),
            "the pool's queue should be its request queue: ${shop.pool.waitingQ.name}"
        )
        // Being a resource pool, it now also reports its own NumBusy and FractionBusy, which is
        // what every other pool in the library reports and is why those are not counted here.
        assertTrue(tree.contains("${shop.pool.name}:NumBusy"), "the pool should report its busy units")
    }
}
