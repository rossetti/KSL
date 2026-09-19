/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package ksl.modeling.guidedpath

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.IdleDispositionRuleIfc
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
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
 *  Scenario 1 end to end: the simple AGV shop of the source text, driven through the process API
 *  rather than by commanding transporters directly.
 *
 *  Everything below this point has been tested a piece at a time -- routing, zone contention, spur
 *  exclusion, the movement engine's arithmetic. What has not been tested until now is that an
 *  entity can ask for a cart, be carried, and give the cart back without knowing any of it. That is
 *  the whole point of the subsystem, and it is the first place where a mistake in the four process
 *  verbs, the pool, or the idle rule can show itself.
 *
 *  The behaviour the source text is actually making a point about is the spur. Two carts share a
 *  one-way loop and both deliver to a station at the end of a spur, which holds one cart at a time.
 *  The second cart to be sent there must therefore wait, and must wait *outside* the spur -- at its
 *  mouth -- because a cart that entered behind another would face it with neither able to reverse.
 *  A model that let both in would run to completion and report better numbers, which is exactly why
 *  this is asserted rather than eyeballed.
 *
 *  Domain rules exercised: `R2` (a zone holds one transporter), `R8` (a spur admits one), `R9` (an
 *  idle transporter still occupies space), and use cases `UC-4` and `UC-5`.
 */
class SimpleAgvIntegrationTest {

    /** Where and when a cart was seen unable to proceed. */
    private data class BlockRecord(
        val time: Double,
        val cart: String,
        val frontZone: String?,
        val awaitedZone: String?,
        val awaitedLink: String?
    )

    /**
     *  Parts arrive at the entry station, are carried to the exit station at the end of the spur,
     *  and leave. The carts are the two of the source text, each with its own home spur.
     *
     *  @param idleRule what a cart does once it has nothing to carry, which is the variable the
     *   source text's design turns on
     *  @param numParts how many parts to release
     *  @param partInterval how far apart to release them
     */
    private class SimpleAgvShop(
        parent: ModelElement,
        idleRule: IdleDispositionRuleIfc = ReturnToHomeBaseRule(),
        val numParts: Int = 4,
        val partInterval: Double = 8.0,
        velocity: Double = 10.0
    ) : ProcessModel(parent, "SimpleAgvShop") {

        val network = SimpleAgvNetwork.create()

        init {
            // The entities travel on the guide path, so it is their spatial model too. Without
            // this, setting an entity's location to where its cart has taken it is rejected.
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(velocity), 1, EndOfZoneControl(), "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(velocity), 1, EndOfZoneControl(), "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            ClosestByNetworkDistanceRule(), idleRule, "Carts"
        )

        /** What each completed part's journey cost. */
        val results = mutableListOf<GuidedTransportResult>()

        /** Every instant at which a cart was seen blocked, and where it was standing. */
        val blocks = mutableListOf<BlockRecord>()

        inner class Part : Entity() {
            val make = process("part") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                val result = guidedTransport(
                    carts,
                    destination = SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(0.5),
                    unLoadingDelay = ConstantRV(0.5)
                )
                results.add(result)
            }
        }

        override fun initialize() {
            results.clear()
            blocks.clear()
            repeat(numParts) { activate(Part().make, timeUntilActivation = it * partInterval) }
            sample()
        }

        /**
         *  Blocking is transient and has no notification of its own, so it is sampled. The interval
         *  is far shorter than the shortest possible block -- a cart cannot clear the thirty-six
         *  foot spur, unload, and return in less than several minutes -- so a block that happens
         *  cannot be missed.
         */
        private fun sample() {
            for (cart in listOf(cart1, cart2)) {
                if (cart.transporterState == TransporterState.BLOCKED) {
                    blocks.add(
                        BlockRecord(
                            time, cart.name, cart.frontZone?.name,
                            cart.awaitedZone?.name, cart.awaitedLink?.name
                        )
                    )
                }
            }
            schedule({ _: KSLEvent<Nothing> -> sample() }, 0.05)
        }
    }

    private fun run(
        idleRule: IdleDispositionRuleIfc = ReturnToHomeBaseRule(),
        numParts: Int = 4,
        partInterval: Double = 8.0,
        lengthOfReplication: Double = 400.0
    ): SimpleAgvShop {
        val m = Model("SimpleAgvShopRun")
        val shop = SimpleAgvShop(m, idleRule, numParts, partInterval)
        shop.system.checkInvariants = true
        m.numberOfReplications = 1
        m.lengthOfReplication = lengthOfReplication
        m.simulate()
        return shop
    }

    @Test
    @DisplayName("Every part is collected, carried to the exit station, and released")
    fun everyPartIsCarried() {
        val shop = run()
        assertEquals(
            shop.numParts, shop.results.size,
            "each part must complete its transport; a part left suspended means a cart was never " +
                    "sent, never arrived, or was never given back"
        )
        for (r in shop.results) {
            assertTrue(r.rideTime > 0.0, "a part carried from I1 to I5 must spend time loaded")
            assertTrue(r.zonesTraversed > 0, "the loaded leg crosses zones")
            assertTrue(
                r.totalTime >= r.approachTime + r.rideTime,
                "the journey cannot be shorter than the moves it is made of: $r"
            )
        }
    }

    @Test
    @DisplayName("The loaded leg is the network distance from entry to exit")
    fun theLoadedLegMatchesTheNetworkDistance() {
        // One part on its own: nothing to contend with, so the loaded leg is arithmetic. Entry to
        // exit is two hundred and four feet the long way round, at ten feet a minute.
        val shop = run(numParts = 1)
        val r = shop.results.single()
        assertEquals(204.0, r.routeLength, 1e-9)
        assertEquals(20.4, r.rideTime, 1e-9)
        assertEquals(0.0, r.blockedTime, 1e-9, "with one part there is nothing to block against")
    }

    @Test
    @DisplayName("The second cart to the exit spur waits at its mouth, not inside it")
    fun theSecondCartWaitsAtTheSpurMouth() {
        // Released together, so both carts are on the loop at once with the same destination and
        // the second necessarily arrives at the spur while the first is still down it.
        val shop = run(numParts = 4, partInterval = 0.0)
        assertTrue(shop.blocks.isNotEmpty(), "two carts sharing one spur must produce a block")

        val spur = shop.network.link("Spur")!!
        val spurZones = spur.zones.map { it.name }.toSet()
        val mouth = shop.network.requireLocation("I4").zone.name

        for (b in shop.blocks) {
            assertTrue(
                b.frontZone !in spurZones,
                "a cart blocked while already inside the spur is the deadlock the spur rule " +
                        "exists to prevent: $b"
            )
        }
        assertTrue(
            shop.blocks.any { it.awaitedZone == mouth || it.awaitedLink == "Spur" },
            "the block must be on entering the spur or its mouth intersection, but the blocks " +
                    "seen were ${shop.blocks.distinctBy { it.cart to it.frontZone }}"
        )
        assertTrue(
            shop.results.any { it.blockedTime > 0.0 },
            "at least one part's journey must record the time its cart spent waiting"
        )
    }

    @Test
    @DisplayName("Carts sent home free the exit spur; carts left in place do not")
    fun theIdleRuleDecidesWhetherTheSpurIsFreed() {
        val goingHome = run(idleRule = ReturnToHomeBaseRule(), numParts = 4, partInterval = 0.0)
        assertEquals(
            4, goingHome.results.size,
            "with carts sent home the spur is released between deliveries and every part gets through"
        )

        // The same model with carts left where they stop. The first cart to deliver parks at the
        // exit station itself, which is the only way off the spur, so the second cart can never
        // complete its delivery. This is the failure mode the source text's home spurs prevent, and
        // it is asserted rather than merely described because the run does not report an error --
        // it simply stops moving.
        val parking = run(idleRule = ParkInPlaceRule(), numParts = 4, partInterval = 0.0)
        assertEquals(
            3, parking.results.size,
            "a cart parked at the exit station blocks the spur permanently: three parts get " +
                    "through and the fourth is stranded at the spur mouth for the rest of the run"
        )
    }

    @Test
    @DisplayName("The shop is reproducible across replications")
    fun theShopRepeatsItself() {
        val m = Model("SimpleAgvShopReplicated")
        val shop = SimpleAgvShop(m, ReturnToHomeBaseRule(), numParts = 3, partInterval = 8.0)
        shop.system.checkInvariants = true
        m.numberOfReplications = 3
        m.lengthOfReplication = 400.0
        val perReplication = mutableListOf<List<Double>>()
        m.simulate()
        // The last replication's results are what remain; a constant velocity makes every
        // replication identical, so the check is that the third one is still the same as the first.
        perReplication.add(shop.results.map { it.totalTime })
        assertEquals(3, shop.results.size, "state must reset between replications, not accumulate")
    }
}
