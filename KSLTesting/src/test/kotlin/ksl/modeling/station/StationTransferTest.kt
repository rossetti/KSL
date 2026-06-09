package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3i (slice A) test for network-to-network transfer via [TransferStation]
 * and [IngressStation]: an instance flows through network A, transfers (with a
 * transport delay) to network B, and is disposed there with end-to-end timing.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationTransferTest {

    @Test
    fun instanceTransfersAcrossNetworksWithEndToEndTime() {
        val m = Model("Transfer", autoCSVReports = false)

        // network B: ingress -> stationB -> sink
        val netB = StationNetwork(m, "NetB")
        val exitB = netB.sink("Exit")
        val stationB = netB.singleQStation("StationB", ConstantRV(4.0), nextReceiver = exitB)
        val ingressB = netB.ingress("In", firstReceiver = stationB)

        // network A: source -> stationA -> transfer (delay 3) -> ingressB
        val netA = StationNetwork(m, "NetA")
        val toB = netA.transferStation("ToB", target = ingressB, transferDelay = ConstantRV(3.0))
        val stationA = netA.singleQStation("StationA", ConstantRV(2.0), nextReceiver = toB)
        netA.source("Arrivals", ConstantRV(100.0), firstReceiver = stationA, maxArrivals = 1, timeUntilFirstRV = ConstantRV(1.0))

        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // c1 created t=1; A service [1,3]; transfer delay -> arrive B at t=6; B service [6,10]
        // disposed in B at t=10. End-to-end system time = 10 - 1 = 9, recorded in network B.
        // A counts no completions (only a transfer); B counts one.
        assertEquals(0.0, netA.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(1.0, netB.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(9.0, netB.systemTime.acrossReplicationStatistic.average, 1e-9)
    }
}
