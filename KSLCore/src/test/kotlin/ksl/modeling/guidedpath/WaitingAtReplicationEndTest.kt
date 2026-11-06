package ksl.modeling.guidedpath

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The end-of-replication report on waiting transporters warns about a wait that looks like a stall,
 * and says no more than INFO about ordinary queueing.
 *
 * Zones are twelve feet, so a cart at twelve feet a minute takes one minute per zone.
 */
@DisplayName("Transporters waiting when a replication ends")
class WaitingAtReplicationEndTest {

    private class Crew(override val name: String) : ZoneHolderIfc {
        override val awaitedZone: Zone? get() = null
    }

    private class Log : ZoneHoldActionIfc {
        override fun holdBegan(allocation: ZoneAllocation) {}
        override fun holdEnded(allocation: ZoneAllocation) {}
    }

    private abstract class Bay(parent: ModelElement) : ModelElement(parent, "Bay") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Bay")
            .link("L1", "A", "B", length = 120.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys")
        val log = Log()
        fun at(time: Double, action: () -> Unit) = schedule({ _: KSLEvent<Nothing> -> action() }, time)
    }

    private class Reports(val warnings: List<String>, val infos: List<String>) {
        fun warned() = warnings.filter { it.contains("transporter(s) were still waiting") }
        fun queued() = infos.filter { it.contains("transporter(s) were still queueing") }
    }

    private fun report(length: Double, build: (Model) -> Unit): Reports {
        val logger = LoggerFactory.getLogger(GuidedPathSpace::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        val level = logger.level
        logger.level = Level.INFO
        try {
            val m = Model("Waiting${System.nanoTime()}", autoCSVReports = false)
            build(m)
            m.numberOfReplications = 1
            m.lengthOfReplication = length
            m.simulate()
        } finally {
            logger.level = level
            logger.detachAppender(appender)
            appender.stop()
        }
        return Reports(
            appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage },
            appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage }
        )
    }

    /** A fast cart follows a slow one that is moving: ten minutes per zone against one. */
    private class Following(parent: ModelElement) : Bay(parent) {
        val slow = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone3"), ConstantRV(1.2), 1, name = "Slow")
        val fast = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone1"), ConstantRV(12.0), 1, name = "Fast")

        override fun initialize() {
            at(0.0) {
                slow.sendTo("B")
                fast.sendTo("B")
            }
        }
    }

    @Test
    @DisplayName("a wait behind a moving vehicle is INFO, even when it is a large part of the run")
    fun waitBehindAMovingVehicleIsInfo() {
        val r = report(15.0) { Following(it) }
        assertEquals(emptyList(), r.warned())
        val message = r.queued().single()
        assertTrue(message.contains("(Fast)"), message)
        assertTrue(message.contains("behind a moving vehicle"), message)
    }

    /** A cart stops behind one that nothing will ever move. */
    private class BehindParked(parent: ModelElement) : Bay(parent) {
        val parked = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone8"), ConstantRV(12.0), 1, name = "Parked")
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart")

        override fun initialize() {
            at(90.0) { cart.sendTo("B") }
        }
    }

    @Test
    @DisplayName("a wait behind a parked vehicle warns, even when it has just begun")
    fun waitBehindAParkedVehicleWarns() {
        val r = report(100.0) { BehindParked(it) }
        assertEquals(emptyList(), r.queued())
        val message = r.warned().single()
        assertTrue(message.contains("(Cart)"), message)
        assertTrue(message.contains("held by (Parked)"), message)
        assertTrue(message.contains("behind a parked vehicle"), message)
    }

    /** A second cart waits behind a blocked one, so the wait ends two vehicles away. */
    private class Chain(parent: ModelElement) : Bay(parent) {
        val parked = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone8"), ConstantRV(12.0), 1, name = "Parked")
        val first = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone6"), ConstantRV(12.0), 1, name = "First")
        val second = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone4"), ConstantRV(12.0), 1, name = "Second")

        override fun initialize() {
            at(90.0) {
                first.sendTo("B")
                second.sendTo("B")
            }
        }
    }

    @Test
    @DisplayName("the wait is followed to where it ends")
    fun waitIsFollowedToItsEnd() {
        val message = report(100.0) { Chain(it) }.warned().single()
        assertTrue(message.startsWith("GuidedPathTransportSystem (Sys): 2 transporter(s)"), message)
        assertEquals(2, Regex("behind a parked vehicle").findAll(message).count(), message)
    }

    /** A crew holds a zone, so what is in the way isn't a transporter. */
    private class BehindACrew(parent: ModelElement, private val sendAt: Double) : Bay(parent) {
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart")

        override fun initialize() {
            at(0.0) { system.requestZones(Crew("Crew"), listOf(network.zone("L1.Zone3")!!), log) }
            at(sendAt) { cart.sendTo("B") }
        }
    }

    @Test
    @DisplayName("any other wait warns once it reaches a tenth of the run so far")
    fun otherWaitsAreJudgedByLength() {
        // reaches the crew's zone 2 minutes after setting off
        val long = report(100.0) { BehindACrew(it, 80.0) }
        assertEquals(emptyList(), long.queued())
        assertTrue(long.warned().single().contains("a long wait, after 18.0"), long.warned().single())

        val short = report(100.0) { BehindACrew(it, 92.0) }
        assertEquals(emptyList(), short.warned())
        assertTrue(short.queued().single().contains("a short wait, after 6.0"), short.queued().single())
    }

    /** One crew hands the zone to the next every ten minutes, so the cart retries and loses each time. */
    private class Relay(parent: ModelElement) : Bay(parent) {
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart")

        override fun initialize() {
            val zone = network.zone("L1.Zone3")!!
            var crew = Crew("Crew0")
            at(0.0) { system.requestZones(crew, listOf(zone), log) }
            at(10.0) { cart.sendTo("B") }
            for (i in 2..9) {
                at(i * 10.0) {
                    system.releaseZones(crew)
                    crew = Crew("Crew$i")
                    system.requestZones(crew, listOf(zone), log)
                }
            }
        }
    }

    @Test
    @DisplayName("a retry that fails doesn't restart the wait")
    fun aFailedRetryKeepsTheWaitGoing() {
        // blocked from 12, and the zone last changed hands at 90
        val r = report(100.0) { Relay(it) }
        assertEquals(emptyList(), r.queued())
        assertTrue(r.warned().single().contains("a long wait, after 88.0"), r.warned().single())
    }

    /** The cart is sent again and again while a crew holds the zone in front of it. */
    private class SentAgain(parent: ModelElement) : Bay(parent) {
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart")

        override fun initialize() {
            at(0.0) { system.requestZones(Crew("Crew"), listOf(network.zone("L1.Zone3")!!), log) }
            at(10.0) { cart.sendTo("B") }
            for (i in 2..9) at(i * 10.0) { cart.sendTo("B") }
        }
    }

    @Test
    @DisplayName("being sent again while waiting doesn't restart the wait")
    fun aRedirectKeepsTheWaitGoing() {
        val r = report(100.0) { SentAgain(it) }
        assertEquals(emptyList(), r.queued())
        assertTrue(r.warned().single().contains("a long wait, after 88.0"), r.warned().single())
    }

    /** The cart gets past one crew, then stops at another. */
    private class TwoCrews(parent: ModelElement) : Bay(parent) {
        val cart = GuidedTransporter(system, TransporterPlacement.At("A"), ConstantRV(12.0), 1, name = "Cart")
        val first = Crew("First")

        override fun initialize() {
            at(0.0) {
                system.requestZones(first, listOf(network.zone("L1.Zone3")!!), log)
                system.requestZones(Crew("Second"), listOf(network.zone("L1.Zone6")!!), log)
            }
            at(10.0) { cart.sendTo("B") }
            at(50.0) { system.releaseZones(first) }
        }
    }

    @Test
    @DisplayName("entering a zone ends a wait, so the next one starts from zero")
    fun enteringAZoneEndsTheWait() {
        // Released at 50, then into zones 3, 4 and 5, and stopped at the crew in zone 6.
        val r = report(100.0) { TwoCrews(it) }
        assertTrue(r.warned().single().contains("a long wait, after 47.0"), r.warned().single())
    }

    /** Two carts and a closure in a cycle, with the detector off so that the run reaches its end. */
    private class Cycle(parent: ModelElement) : ModelElement(parent, "Aisle") {
        val network: GuidedPathNetwork = GuidedPathNetwork.builder("Aisle")
            .link("L1", "A", "B", length = 36.0, zoneLength = 12.0)
            .build()
        val system = GuidedPathTransportSystem(this, network, name = "Sys").also { it.deadlockDetectionEnabled = false }
        val cart1 = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone2"), ConstantRV(12.0), 1, name = "Cart1")
        val cart2 = GuidedTransporter(system, TransporterPlacement.OnZone("L1.Zone1"), ConstantRV(12.0), 1, name = "Cart2")
        val crew = Crew("Crew")
        val log = Log()

        override fun initialize() {
            schedule({ _: KSLEvent<Nothing> ->
                system.requestZones(crew, listOf(network.zone("L1.Zone1")!!, network.zone("L1.Zone3")!!), log)
            }, 0.5)
            schedule({ _: KSLEvent<Nothing> ->
                cart1.sendTo("B")
                cart2.sendTo("B")
            }, 1.0)
        }
    }

    @Test
    @DisplayName("a circular wait warns, and says so")
    fun circularWaitWarns() {
        val message = report(100.0) { Cycle(it) }.warned().single()
        assertEquals(2, Regex("a circular wait").findAll(message).count(), message)
    }
}
