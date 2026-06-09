package ksl.modeling.station

import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3 (slice B) tests for [GateStation]: instances are held while the gate is
 * closed and released when it opens.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationGateTest {

    /** Opens [gate] once at [openTime]. */
    private class GateOpener(
        parent: ModelElement,
        private val gate: GateStation,
        private val openTime: Double
    ) : ModelElement(parent) {
        override fun initialize() {
            schedule(this::openAction, openTime)
        }

        @Suppress("UNUSED_PARAMETER")
        private fun openAction(event: KSLEvent<Nothing>) {
            gate.open()
        }
    }

    @Test
    fun closedGateHoldsThenReleasesOnOpen() {
        val m = Model("Gate", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val gate = net.gateStation("Gate", nextReceiver = exit, initiallyOpen = false)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = gate, maxArrivals = 3)
        GateOpener(m, gate, openTime = 5.0)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // arrivals at t=1,2,3 are held; gate opens at t=5 releasing all three
        // system times: 5-1, 5-2, 5-3 = 4,3,2 -> average 3.0
        assertEquals(3.0, gate.numReleased.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(3.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun openGatePassesThrough() {
        val m = Model("GateOpen", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val gate = net.gateStation("Gate", nextReceiver = exit, initiallyOpen = true)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = gate, maxArrivals = 3)
        m.numberOfReplications = 1
        m.lengthOfReplication = 20.0
        m.simulate()

        // open gate: instances pass straight through, zero held time -> system time 0
        assertEquals(3.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(0.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
