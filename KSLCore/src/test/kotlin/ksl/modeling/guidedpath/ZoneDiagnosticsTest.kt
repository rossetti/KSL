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

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  What the guide path says when the *model* is wrong, rather than when the code is.
 *
 *  These are the cases a modeller reaches by writing something reasonable-looking that cannot work,
 *  and every one of them was found by running such a model rather than by reading the code. The
 *  thing being asserted is not that the run behaves a certain way -- it behaves correctly in every
 *  test here -- but that the run **says** what happened. A subsystem whose failure mode is a
 *  plausible number is worse than one that raises.
 *
 *  The worst of them, and the reason this file exists: a closure that can never be granted used to
 *  be completely silent. `holdBegan` never fired, no warning was logged, no statistic moved, and
 *  the replication looked exactly like one in which nothing was ever meant to close. A study of
 *  "what does maintenance cost me?" answered with the no-maintenance number and said nothing.
 *
 *  ## The arithmetic
 *
 *  Zones are twelve feet and carts travel twelve feet a minute, so one zone costs one minute.
 *  Junctions are dimensionless. The link below is four zones, so a cart sent from A to B arrives
 *  at 4.0 and then stands on junction B with no route and nothing carried -- which is exactly the
 *  thing that will never move again on its own.
 */
class ZoneDiagnosticsTest {

    private class Crew(id: Int) : ZoneHolderIfc {
        override val name: String = "Crew$id"
        override val awaitedZone: Zone? get() = null
    }

    private class Log : ZoneHoldActionIfc {
        val began = mutableListOf<String>()
        override fun holdBegan(allocation: ZoneAllocation) { began.add(allocation.holder.name) }
        override fun holdEnded(allocation: ZoneAllocation) {}
    }

    private class Bay(parent: ModelElement) : ModelElement(parent, "Bay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Bay")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val log = Log()
        var setUp: (Bay) -> Unit = {}
        override fun initialize() {
            log.began.clear()
            setUp(this)
        }
        fun at(time: Double, action: () -> Unit) =
            schedule({ _: KSLEvent<Nothing> -> action() }, time)
    }

    /**
     *  A second fixture, because a transporter is a [ModelElement] and so must exist before the run
     *  -- the very constraint that made a holder an interface. This one stands a cart on junction B
     *  from the first instant and never moves it, which is what keeps a closure over B pending for
     *  the whole replication.
     */
    private class ReservedBay(parent: ModelElement) : ModelElement(parent, "ReservedBay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("ReservedBay")
            .link("L1", "A", "B", length = 48.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val parked = GuidedTransporter(
            system, TransporterPlacement.At("B"), ConstantRV(12.0), 1, name = "Parked"
        )
        val cart = GuidedTransporter(
            system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart"
        )
        val log = Log()
        override fun initialize() {
            log.began.clear()
            // Crew4 asks for the far zone and the junction the parked cart is standing on. The
            // junction never drains, so the reservation over Zone4 stays in force all run.
            schedule({ _: KSLEvent<Nothing> ->
                system.requestZones(
                    Crew(4), listOf(network.zone("L1.Zone4")!!, network.zone("B")!!), log
                )
            }, 0.5)
            schedule({ _: KSLEvent<Nothing> -> cart.sendTo("B") }, 1.0)
        }
    }

    private fun model(length: Double = 60.0, setUp: (Bay) -> Unit): Pair<Model, Bay> {
        val m = Model("Diag")
        m.numberOfReplications = 1
        m.lengthOfReplication = length
        val bay = Bay(m)
        bay.setUp = setUp
        return m to bay
    }

    /** WARN messages logged while the block runs, as the interval-statistics tests capture them. */
    private fun captureWarnings(block: () -> Unit): List<String> {
        val logger = org.slf4j.LoggerFactory.getLogger(GuidedPathSpace::class.java)
                as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
            .filter { it.level == ch.qos.logback.classic.Level.WARN }
            .map { it.formattedMessage }
    }

    // ---- the closure that can never be granted -------------------------------------------------

    @Test
    fun `a closure over space a parked vehicle holds is reported, counted, and named`() {
        // The cart arrives at B at 4.0 and stays: no route, nothing carried, nothing scheduled to
        // move it. The crew then asks for B. That request can never be granted by anything the
        // closure itself can do, and before this was built the replication said nothing at all.
        val (m, bay) = model { b ->
            b.at(0.0) { b.cart.sendTo("B") }
            b.at(6.0) { b.system.requestZones(Crew(1), listOf(b.network.zone("B")!!), b.log) }
        }
        val warnings = captureWarnings { m.simulate() }

        // The hold never began -- that is the fault, and it is real.
        assertTrue(bay.log.began.isEmpty(), "the hold must not have begun: ${bay.log.began}")

        // A diagnosis the model can read, not only one it can print.
        assertEquals(1, bay.system.waitingRequests.size, "one request should still be waiting")
        val stuck = bay.system.waitingRequests.single()
        assertEquals("Crew1", stuck.holder.name)
        assertEquals(6.0, stuck.requestedAt)
        assertEquals(1.0, bay.system.numRequestsUnfilled.value, 0.0)

        // And the wording, because the load-bearing part is *why*: "has not drained" alone would be
        // true and useless. A modeller has to be told the space was never going to drain.
        val message = warnings.single { it.contains("request(s) for space") }
        assertTrue(message.contains("(Crew1) asked at 6.0 for [B]"), message)
        assertTrue(message.contains("never will"), message)
        assertTrue(message.contains("(Cart) is parked in it"), message)
    }

    @Test
    fun `a closure still legitimately draining at the horizon reads differently`() {
        // The same report, and it must not cry fault. The cart is still crossing the link when the
        // replication ends, so the space really is draining and the request was merely cut short.
        // Distinguishing this from the case above is the whole value of the message.
        val (m, bay) = model(length = 2.5) { b ->
            b.at(0.0) { b.cart.sendTo("B") }
            b.at(0.5) { b.system.requestZones(Crew(2), b.network.link("L1")!!.zones, b.log) }
        }
        val warnings = captureWarnings { m.simulate() }
        assertEquals(1.0, bay.system.numRequestsUnfilled.value, 0.0)
        val message = warnings.single { it.contains("request(s) for space") }
        assertTrue(message.contains("it is held by (Cart)"), message)
        assertTrue(!message.contains("never will"), "a draining zone must not be called hopeless: $message")
    }

    @Test
    fun `a request granted before the horizon is not reported at all`() {
        // The negative case, which is what stops the report from being noise. Nothing is waiting at
        // the end, so nothing is said and nothing is counted.
        val (m, bay) = model { b ->
            b.at(1.0) { b.system.requestZones(Crew(3), listOf(b.network.zone("L1.Zone3")!!), b.log) }
            b.at(20.0) { b.system.releaseZones(b.system.waitingRequests.firstOrNull()?.holder ?: Crew(3)) }
        }
        val warnings = captureWarnings { m.simulate() }
        assertEquals(listOf("Crew3"), bay.log.began)
        assertEquals(0, bay.system.waitingRequests.size)
        assertEquals(0.0, bay.system.numRequestsUnfilled.value, 0.0)
        assertTrue(warnings.none { it.contains("request(s) for space") }, warnings.toString())
    }

    // ---- the stall warning and reservations ----------------------------------------------------

    @Test
    fun `the stall warning names a reservation when the awaited zone is free`() {
        // A cart waiting on a zone that is *free* used to be reported as waiting for that zone,
        // full stop, with no reason given -- the deadlock report was taught about reservations and
        // this warning was not, so the two diagnostics disagreed about what a reservation is. The
        // cart here is stopped by nothing standing in its way: Zone4 is empty and says no anyway.
        val m = Model("Diag-reserved")
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        val bay = ReservedBay(m)
        val warnings = captureWarnings { m.simulate() }

        assertTrue(bay.log.began.isEmpty(), "the closure must never have begun: ${bay.log.began}")
        val awaited = bay.cart.awaitedZone
        assertEquals("L1.Zone4", awaited?.name, "the cart should be stopped at the reserved zone")
        assertEquals(null, awaited?.holder, "and that zone should be free, which is the point")

        val stall = warnings.single { it.contains("transporter(s) were still waiting") }
        assertTrue(
            stall.contains("free but reserved for (Crew4)"),
            "the warning must say why an empty zone refused the cart: $stall"
        )
        // Both halves of the same stall, from the two sides. Reporting only one of them is how a
        // modeller fixes the cart, re-runs, and still has a crew that never gets its space.
        val unfilled = warnings.single { it.contains("request(s) for space") }
        assertTrue(unfilled.contains("(Crew4) asked at 0.5"), unfilled)
        assertTrue(unfilled.contains("(Parked) is parked in it"), unfilled)
    }

    // ---- the mistakes that already reported themselves well ------------------------------------

    @Test
    fun `asking twice from one holder raises, naming the holder and the space`() {
        val (m, bay) = model { b ->
            b.at(1.0) {
                b.system.requestZones(Crew(5), listOf(b.network.zone("L1.Zone1")!!), b.log)
            }
        }
        // Two requests from the *same* holder object, which is the mistake the rule exists for.
        val crew = Crew(6)
        val (m2, bay2) = model { b ->
            b.at(1.0) {
                b.system.requestZones(crew, listOf(b.network.zone("L1.Zone1")!!), b.log)
                b.system.requestZones(crew, listOf(b.network.zone("L1.Zone3")!!), b.log)
            }
        }
        val thrown = assertFailsWith<IllegalStateException> { m2.simulate() }
        assertTrue(thrown.message!!.contains("Crew6"), thrown.message!!)
        assertTrue(thrown.message!!.contains("One request at a time"), thrown.message!!)
        check(bay.system.name.isNotEmpty() && bay2.system.name.isNotEmpty())
        m.simulate()
    }
}
