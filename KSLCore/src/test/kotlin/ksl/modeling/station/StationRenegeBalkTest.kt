package ksl.modeling.station

import ksl.simulation.Model
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import ksl.utilities.random.rvariable.ConstantRV

/**
 * Phase-3 (slice C) tests for reneging (impatient customers leaving the queue) on
 * [SingleQStation], and for balking expressed with the existing routing primitives.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StationRenegeBalkTest {

    @Test
    fun impatientCustomersRenege() {
        val m = Model("Renege", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val reneged = net.sink("Reneged")
        val server = net.singleQStation("Server", ConstantRV(10.0), nextReceiver = exit)
        server.renegeTime = ConstantRV(3.0)         // patience = 3
        server.renegeReceiver = reneged
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = server, maxArrivals = 4)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // c1 (t=1) served until t=11; c2,c3,c4 wait but renege after 3 (at t=5,6,7)
        // completions: c1 at exit, c2/c3/c4 at the reneged sink -> 4 total (NS balances)
        // system times: c1=10, c2=3, c3=3, c4=3 -> average 19/4
        assertEquals(3.0, server.numReneged.acrossReplicationStatistic.average, 1e-9)
        assertEquals(4.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
        assertEquals(19.0 / 4.0, net.systemTime.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun servedBeforePatienceDoesNotRenege() {
        val m = Model("NoRenege", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val reneged = net.sink("Reneged")
        val server = net.singleQStation("Server", ConstantRV(1.0), nextReceiver = exit)
        server.renegeTime = ConstantRV(100.0)       // very patient
        server.renegeReceiver = reneged
        net.source("Arrivals", ConstantRV(2.0), firstReceiver = server, maxArrivals = 5)
        m.numberOfReplications = 1
        m.lengthOfReplication = 50.0
        m.simulate()

        // service (1) < interarrival (2): nobody waits long enough to renege
        assertEquals(0.0, server.numReneged.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }

    @Test
    fun balkingViaConditionalRouter() {
        // Balking is expressible with existing primitives: route an arrival away
        // when the target station's queue is too long.
        val m = Model("Balk", autoCSVReports = false)
        val net = StationNetwork(m, "Net")
        val exit = net.sink("Exit")
        val balked = net.sink("Balked")
        val server = net.singleQStation("Server", ConstantRV(10.0), nextReceiver = exit)
        // balk if 2 or more already waiting in the server's queue
        val router = ConditionalRouter(
            listOf(RoutingCase({ _ -> server.waitingQ.size >= 2 }, balked)),
            default = server
        )
        net.register("Dispatch", router)
        net.source("Arrivals", ConstantRV(1.0), firstReceiver = router, maxArrivals = 5)
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        m.simulate()

        // c1 enters service; c2,c3 join the queue (size reaches 2); c4,c5 see size>=2 and balk.
        // So 3 are served (reach Exit) and 2 balk (reach Balked); all 5 leave the system.
        assertEquals(3.0, server.numProcessed.acrossReplicationStatistic.average, 1e-9)
        assertEquals(5.0, net.numCompleted.acrossReplicationStatistic.average, 1e-9)
    }
}
