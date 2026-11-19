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

import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.modeling.entity.KSLProcess
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 *  An entity terminated while a guided transporter is fetching it, loading it, carrying it, unloading it, or
 *  being driven by it: the transporter is free of it at once. It is off the manifest, nobody is recorded as
 *  waiting for the journey, the allocation goes back to the pool, and the pool does with the transporter what
 *  it does at an ordinary release, offering it to whoever waits and otherwise applying its idle rule. The
 *  transporter finishes the move it is making with nobody waiting, or is sent on by its pool.
 *
 *  A loop A to B to P and back to A, links 100 long in 25-long zones; one cart at A, speed 10, so each link
 *  takes 10. P asks for the cart at 0 with the pickup at P: the cart is there at 20, and carries P to A by 30.
 */
class TerminatedOnGuidedJourneyTest {

    private class Shop(parent: ModelElement, homeRule: Boolean) : ProcessModel(parent, "Shop") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Loop")
            .link("AB", "A", "B", length = 100.0, zoneLength = 25.0, beginDirection = 0.0)
            .link("BP", "B", "P", length = 100.0, zoneLength = 25.0, beginDirection = 90.0)
            .link("PA", "P", "A", length = 100.0, zoneLength = 25.0, beginDirection = 180.0)
            .build()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(10.0), name = "Cart")
        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart), allocationRule = ClosestByNetworkDistanceRule(), name = "Carts"
        )

        init {
            if (homeRule) {
                cart.homeBase = "A"
                carts.idleDispositionRule = ReturnToHomeBaseRule()
            }
        }

        val log = mutableListOf<String>()
        val setups = mutableListOf<Shop.() -> Unit>()
        val actors = mutableMapOf<String, Entity>()

        /**
         *  Asks for the cart at [from] with the pickup at [pickup], loads for [loading], is carried to [destination]
         *  and unloaded for [unloading], then gives the cart back.
         */
        inner class Part(
            val tag: String, val from: Double, val pickup: String, val destination: String,
            val loading: Double = 0.0, val unloading: Double = 0.0
        ) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                currentLocation = network.requireLocation(pickup)
                val request = requestGuidedTransporter(carts, pickup)
                log.add("$tag got cart@$time")
                transportBy(request, destination, loadingDelay = ConstantRV(loading), unLoadingDelay = ConstantRV(unloading))
                log.add("$tag delivered@$time")
                releaseGuidedTransporter(request, carts)
            }
        }

        /** Drives the cart, without claiming it, from [from] to [destination]. */
        inner class Driver(val tag: String, val from: Double, val destination: String) : Entity(tag) {
            val p: KSLProcess = process(isDefaultProcess = true) {
                delay(from)
                driveTo(cart, network.requireLocation(destination))
                log.add("$tag arrived@$time")
            }
        }

        fun part(tag: String, from: Double, pickup: String = "P", destination: String = "A", loading: Double = 0.0, unloading: Double = 0.0) =
            Part(tag, from, pickup, destination, loading, unloading).also { actors[tag] = it; activate(it.p) }

        fun driver(tag: String, from: Double, destination: String) =
            Driver(tag, from, destination).also { actors[tag] = it; activate(it.p) }

        fun at(t: Double, action: () -> Unit) {
            schedule({ _: KSLEvent<Nothing> -> action() }, t)
        }

        fun terminate(tag: String, t: Double) = at(t) { actors.getValue(tag).terminateProcess() }

        /** Terminates [tag] from an arrival listener, at the cart's first arrival at or after [after]. */
        fun terminateOnArrival(tag: String, after: Double) {
            var done = false
            cart.attachArrivalListener {
                if (!done && time >= after) {
                    done = true
                    actors.getValue(tag).terminateProcess()
                }
            }
        }

        fun probe(t: Double) = at(t) {
            log.add("probe@$t ${cart.transporterState} loads=${cart.numLoadsAboard} busy=${cart.numBusy} at=${cart.positionNow.name}")
        }

        override fun initialize() {
            log.clear()
            actors.clear()
            for (s in setups) s()
        }
    }

    private class Outcome(
        val log: List<String>, val error: Throwable?, val shop: Shop, val alighted: List<String>,
        val states: List<String> = emptyList()
    )

    private fun run(homeRule: Boolean = false, setup: Shop.() -> Unit): Outcome {
        val alighted = mutableListOf<String>()
        val states = mutableListOf<String>()
        val model = Model("GuidedTermination${System.nanoTime()}", autoCSVReports = false)
        model.animationSink = object : AnimationSink {
            override val isActive = true
            override fun emit(event: AnimationEvent) {
                if (event is AnimationEvent.VehicleLoadAlighted) alighted.add("${event.entityId}@${event.simTime}")
                if (event is AnimationEvent.GuidedTransporterStateChanged) states.add("${event.state}@${event.simTime}")
            }
        }
        val shop = Shop(model, homeRule)
        shop.setups.add(setup)
        model.numberOfReplications = 1
        model.lengthOfReplication = 200.0
        val error = try {
            model.simulate()
            null
        } catch (e: Throwable) {
            e
        }
        return Outcome(shop.log.toList(), error, shop, alighted, states)
    }

    private fun assertCompleted(case: String, o: Outcome) {
        assertNull(o.error, "$case: the run stopped: ${o.error}")
    }

    // ---------- fetching ----------

    @Test
    @DisplayName("terminated while the cart comes for it: the cart goes on to the pickup with nobody waiting, and parks there")
    fun whileFetched() {
        val o = run {
            part("V", 0.0)
            terminate("V", 5.0)
            probe(25.0)
        }
        assertCompleted("fetched, nobody waiting", o)
        assertEquals(listOf("probe@25.0 IDLE loads=0 busy=0 at=P"), o.log.filter { it.startsWith("probe") })
    }

    @Test
    @DisplayName("terminated while the cart comes for it, with another request waiting: the cart is sent to it at once")
    fun whileFetchedWithAnotherWaiting() {
        val o = run {
            part("V", 0.0)
            part("Q", 3.0)
            terminate("V", 5.0)
        }
        assertCompleted("fetched, Q waiting", o)
        // the cart is already on its way to P, Q's pickup too
        assertEquals("Q got cart@20.0", o.log.first { it.startsWith("Q got") })
    }

    @Test
    @DisplayName("terminated while the cart comes for it, with a home base: the pool's idle rule sends the cart home, as at a release")
    fun whileFetchedWithAHomeBase() {
        val o = run(homeRule = true) {
            part("V", 0.0)
            terminate("V", 5.0)
            probe(35.0)
        }
        assertCompleted("fetched, home base", o)
        // on link AB at 5, sent home to A: through B at 10 and P at 20, home at 30
        assertEquals(listOf("probe@35.0 IDLE loads=0 busy=0 at=A"), o.log.filter { it.startsWith("probe") })
    }

    @Test
    @DisplayName("terminated after the cart arrived to fetch it, before it resumed: the cart is the next request's at once")
    fun wokenByTheFetch() {
        val o = run {
            part("V", 0.0)
            part("Q", 15.0)
            terminateOnArrival("V", after = 20.0)
        }
        assertCompleted("woken by the fetch", o)
        assertEquals("Q got cart@20.0", o.log.first { it.startsWith("Q got") })
    }

    // ---------- loading ----------

    @Test
    @DisplayName("terminated while it is being loaded: the cart, not yet carrying it, is the next request's at once")
    fun whileLoading() {
        val o = run {
            part("V", 0.0, loading = 5.0)
            part("Q", 15.0)
            terminate("V", 22.0)
        }
        assertCompleted("loading", o)
        assertEquals("Q got cart@22.0", o.log.first { it.startsWith("Q got") })
    }

    @Test
    @DisplayName("terminated while loading or unloading: the cart is not left drawn as loading or unloading")
    fun animationAfterLoadingOrUnloading() {
        val loading = run {
            part("V", 0.0, loading = 5.0)
            terminate("V", 22.0)
        }
        assertCompleted("loading", loading)
        assertEquals("IDLE@22.0", loading.states.last(), "loading: ${loading.states}")
        val unloading = run {
            part("V", 0.0, unloading = 5.0)
            terminate("V", 32.0)
        }
        assertCompleted("unloading", unloading)
        assertEquals("IDLE@32.0", unloading.states.last(), "unloading: ${unloading.states}")
    }

    // ---------- riding ----------

    @Test
    @DisplayName("terminated while riding: off the manifest at once, the cart moving on empty, parked where it was going")
    fun whileRiding() {
        val o = run {
            part("V", 0.0)
            terminate("V", 25.0)
            probe(26.0)
            probe(35.0)
        }
        assertCompleted("riding, nobody waiting", o)
        // at 26 the cart is part way along link PA, whose end, A, is where a transporter on it reads as being
        assertEquals(listOf("probe@26.0 MOVING_EMPTY loads=0 busy=0 at=A", "probe@35.0 IDLE loads=0 busy=0 at=A"),
            o.log.filter { it.startsWith("probe") })
        assertEquals(listOf("${o.shop.actors.getValue("V").id}@25.0"), o.alighted, "the trace takes V off the cart")
    }

    @Test
    @DisplayName("terminated while riding, with another request waiting: the cart is sent on to it, and carries it")
    fun whileRidingWithAnotherWaiting() {
        val o = run {
            part("V", 0.0)
            part("Q", 15.0)
            terminate("V", 25.0)
        }
        assertCompleted("riding, Q waiting", o)
        // on link PA at 25: on to A at 30, round through B at 40 to P at 50; Q is carried to A by 60
        assertEquals(listOf("Q got cart@50.0", "Q delivered@60.0"), o.log.filter { it.startsWith("Q ") })
    }

    @Test
    @DisplayName("terminated after the cart arrived with it, before it resumed: it is off the manifest, and the next request is carried")
    fun wokenAtTheDestination() {
        val o = run {
            part("V", 0.0)
            part("Q", 15.0, pickup = "A", destination = "B")
            terminateOnArrival("V", after = 30.0)
        }
        assertCompleted("woken at the destination", o)
        assertEquals(listOf("Q got cart@30.0", "Q delivered@40.0"), o.log.filter { it.startsWith("Q ") })
    }

    // ---------- aboard without travel, and unloading ----------

    @Test
    @DisplayName("terminated while unloaded where it boarded, a transport needing no travel: it is off the manifest")
    fun aboardWithoutTravel() {
        val o = run {
            part("V", 0.0, destination = "P", unloading = 5.0)
            part("Q", 15.0)
            terminate("V", 22.0)
        }
        assertCompleted("aboard without travel", o)
        assertEquals(listOf("Q got cart@22.0", "Q delivered@32.0"), o.log.filter { it.startsWith("Q ") })
    }

    @Test
    @DisplayName("terminated while being unloaded after the ride: it is off the manifest, and the next request is carried")
    fun whileUnloading() {
        val o = run {
            part("V", 0.0, unloading = 5.0)
            part("Q", 15.0, pickup = "A", destination = "B")
            terminate("V", 32.0)
        }
        assertCompleted("unloading", o)
        assertEquals(listOf("Q got cart@32.0", "Q delivered@42.0"), o.log.filter { it.startsWith("Q ") })
    }

    // ---------- driving ----------

    @Test
    @DisplayName("terminated while driving the cart, without a claim on it: the journey ends with nobody waiting for it")
    fun whileDriving() {
        val o = run {
            driver("D", 0.0, "P")
            terminate("D", 5.0)
            probe(25.0)
        }
        assertCompleted("driving", o)
        assertEquals(listOf("probe@25.0 IDLE loads=0 busy=0 at=P"), o.log.filter { it.startsWith("probe") })
    }
}
