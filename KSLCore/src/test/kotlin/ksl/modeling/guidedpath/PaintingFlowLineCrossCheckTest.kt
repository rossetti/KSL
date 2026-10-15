package ksl.modeling.guidedpath

import ksl.examples.book.chapter7.PaintingFlowLineWithAgvs
import ksl.examples.book.chapter7.PaintingFlowLineWithAgvs.PendingRequestTest
import ksl.simulation.Model
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 *  Exercise 7.15: the two-colour painting flow line moved by three AGVs on a one-way guide path,
 *  compared against the same model built in the reference implementation.
 *
 *  This is the largest of the cross-checks. **The shop agrees** -- every utilization, both
 *  throughputs, the station queues, and the flow times of both part types. **Two things about the
 *  aisle do not**, and neither is a length or a bearing that could be corrected: the two models mean
 *  different things by sending an idle vehicle to a staging area.
 *
 *  ## The layout
 *
 *  Sixteen links in ten-foot zones, one-way except the spur to the entry station, three vehicles at
 *  100 feet per minute, one zone long, releasing a zone at the start of leaving it. The stations
 *  sit on the intersections the reference implementation's own station table names -- entry at I6,
 *  workstation at I1, paint at I2, new paint at I4, packing at I3, exit at I5 -- and the staging
 *  area is I12.
 *
 *  ## What agreement means here
 *
 *  Both models are stochastic, so z is the gap between the means over the two half-widths combined
 *  in quadrature, and **z <= 1 is agreement at 95%** for two independent estimates. Ten
 *  replications of 600,000 minutes with a 50,000 minute warm-up, on both sides.
 *
 *  ## What "no requests pending" means, settled by arithmetic
 *
 *  The exercise says a vehicle that finishes a job should go to the staging area "if there are no
 *  requests pending". A part queued with no vehicle assigned is plainly pending. A part that has
 *  been assigned a vehicle which is at that moment driving empty across the network to fetch it is
 *  the ambiguous case, and in this system the two readings are far apart: a part is almost never
 *  left queueing (mean 0.53 minutes) while an empty move averages about 4.6.
 *
 *  the reference implementation's model keeps its own counter, `vNumWaitingRequests`, and reports
 *  its mean as 0.677. Requests arrive at 78,463 per 550,000 minutes, so whatever that counter
 *  counts, it counts each request for 4.75 minutes on average. The queue wait alone is 0.53. The
 *  counter therefore runs until the vehicle arrives, not until it is assigned, and the wider
 *  reading is the one the reference implementation ran.
 *
 *  Both are run below. The wider reading is the primary case; the narrower is run beside it, and it
 *  is worse on the pack queue and commits more of the fleet.
 *
 *  ## Where the two tools genuinely differ
 *
 *  The reference implementation has no way to move an unallocated transporter while this model
 *  leaves a freed vehicle where it stands, so the staging trip is made as an ordinary
 *  **transport**: the entity that has just been set down is duplicated, and the copy keeps the
 *  vehicle and rides it to I12 before freeing it. That is not read off the numbers -- its model
 *  listing has the duplication, the transport to the staging station, and the release that follows
 *  it. A vehicle on its way to the staging area is therefore *busy* and *cannot be diverted*.
 *
 *  In this subsystem a repositioning vehicle is neither. It is unallocated, so it does not count as
 *  busy; it is still in the pool, so it can be allocated; and it is redirected at the next zone
 *  boundary when it is. Those are the two differences, and they show up in two places.
 *
 *  ### Classification: the fleet's utilization
 *
 *  Committed vehicle time -- allocated plus repositioning -- is the one figure the reference
 *  implementation reports as busy, and this subsystem splits it in two:
 *
 *  ```
 *                                allocated   repositioning   committed
 *  reference (busy, one figure)         --              --      0.6930
 *  KSL                              0.5178          0.1729      0.6908
 *  ```
 *
 *  The fleets are committed for the same share of the horizon, and the whole of the 94 that
 *  `agvUtilization` scores is the classification rather than the quantity. That is asserted below,
 *  not merely printed.
 *
 *  ### Diversion: the waits for a vehicle
 *
 *  The request waits are the time from a part asking to a vehicle being allocated to it. They are
 *  small -- 2.03 minutes a part here, 2.13 in the reference -- and they differ in both directions: the
 *  entry station's is shorter here (0.68 against 0.87, z = 8.6), the workstation's and the two paint
 *  stations' are longer (z = 2.4 to 3.9), and the pack station's agrees.
 *
 *  Diversion is measured directly by running this model with a pool that withholds a repositioning
 *  vehicle until it reaches I12, which is what the reference implementation does. That run is not
 *  kept as a test, because it needs a pool written for the purpose; its figures are:
 *
 *  ```
 *                               reference      KSL   KSL, no diversion
 *  legs collected by diversion         --     5.0%                  --
 *  entry request wait               0.870    0.678               0.738
 *  workstation request wait         0.362    0.389               0.445
 *  paint request wait               0.494    0.553               0.592
 *  pack request wait                0.423    0.424               0.460
 *  fetching and blocking, a leg     4.629    4.852               4.664
 *  system time                     194.66   194.42              193.88
 *  ```
 *
 *  So **the entry wait is not wholly diversion.** A diverted vehicle is allocated at once, and 5.9%
 *  of entry collections are made that way; forbidding it closes a third of the entry gap (z = 6.0)
 *  and pushes every other station's wait further above the reference's (z = 3.1 to 7.5). What a
 *  diversion saves in waiting for a vehicle it gives back in fetching: a vehicle turned round on a
 *  one-way network takes longer to arrive. The flow time does not move. What remains of the entry
 *  gap, and the other stations' longer waits, are not explained by these runs; they are at most a
 *  fifth of a minute a leg and net to a tenth of a minute a part.
 *
 *  ### Where the flow time goes
 *
 *  Taking each model's own figures and subtracting what is accounted for -- station queueing, mean
 *  service, the four minutes a leg of loading and unloading, the waits for a vehicle, and the nominal
 *  loaded travel of 8.1 minutes a part for the old route and 8.3 for the new -- leaves the time a part
 *  spends being fetched and being held up, a journey's elapsed time containing its own blocking:
 *
 *  ```
 *                       station queues   waits for a vehicle   fetching and blocking   system time
 *  reference                    73.878                 2.133                  18.517       194.655
 *  KSL                          72.857                 2.033                  19.407       194.424
 *  ```
 *
 *  No column differs by more than about a minute a part, inside the system time's half-width of 2.5,
 *  and the system times agree (z = 0.07).
 *
 *  The paint queue, at z = 1.64, is the one station statistic outside agreement, and it is the most
 *  sensitive of them and the least about the paint station: the workstation ahead of it runs at 82% on
 *  a nearly deterministic 21-to-25-minute service, so what arrives at the paint station is a smoothed
 *  stream whose remaining variability is almost entirely put there by the transport. A queue that
 *  would be 13.8 minutes under Poisson arrivals is 2.5, and it moves with the aisle rather than with
 *  the shop: without diversion it is at z = 1.07.
 *
 *  ## Obstructions
 *
 *  About 9,800 times a replication a vehicle stops behind an idle one other than in the staging
 *  queue. They are counted and printed below; they are not logged.
 *
 *  ## Before R1.8
 *
 *  In R1.7.1 the flow time was 7.61 minutes, 3.9%, longer here, and 77% of that was fetching and
 *  blocking: 6.08 minutes a leg against 4.62. The cause was in the allocation rule. Link 11 is one-way
 *  and I12 holds one vehicle, so a second vehicle sent to the staging area stopped at the end of link
 *  11; a transporter part way along a link was located at that link's far end, so it tied with the
 *  vehicle in I12 and was chosen ahead of it by declaration order, committing the request to a vehicle
 *  that could not move until I12 was cleared. The obstruction count, about 25,000 a replication, was
 *  mostly that queue. R1.8 measures a vehicle from the zone it holds, prefers the one with fewer zones
 *  still to enter at equal distance, and does not count the staging queue as an obstruction; the flow
 *  times have agreed since.
 */
@Tag("slow")
class PaintingFlowLineCrossCheckTest {

    private companion object {
        const val REPLICATIONS = 10
        const val HORIZON_MINUTES = 600_000.0
        const val WARM_UP_MINUTES = 50_000.0

        /** What each reported figure is averaged over. */
        const val OBSERVED_MINUTES = HORIZON_MINUTES - WARM_UP_MINUTES

        const val FLEET = 3
        const val LEGS_PER_PART = 4

        /**
         *  The quantities that say whether the two models are the same *shop*. They are nearly
         *  independent of the guide path, and all of them agree.
         */
        val SHOP = setOf(
            "numberIn", "numberOut",
            "workerUtilization", "painterUtilization", "newPainterUtilization", "packerUtilization",
            "workStationQueueWait", "newPaintQueueWait", "packQueueWait",
            "workStationQueueLength", "newPaintQueueLength", "packQueueLength"
        )
    }

    private fun referenceFixture(resource: String): Map<String, Pair<Double, Double>> {
        val text = checkNotNull(javaClass.getResourceAsStream(resource)) {
            "the reference fixture $resource is missing from the test resources"
        }.bufferedReader().readText()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it != "statistic,value,halfWidth" }
            .map { it.split(",") }
            .associate { it[0] to (it[1].toDouble() to it[2].toDouble()) }
    }

    private fun buildAndRun(policy: PendingRequestTest): PaintingFlowLineWithAgvs {
        val m = Model("P7-15-$policy")
        val shop = PaintingFlowLineWithAgvs(m, name = "Shop", pendingRequestTest = policy)
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON_MINUTES
        m.lengthOfReplicationWarmUp = WARM_UP_MINUTES
        m.simulate()
        return shop
    }

    /** Every quantity both tools report, in the order the table prints them. */
    private fun measure(shop: PaintingFlowLineWithAgvs): List<Triple<String, Double, Double>> {
        fun r(name: String, s: ksl.utilities.statistic.StatisticIfc) = Triple(name, s.average, s.halfWidth)
        return listOf(
            r("systemTime", shop.systemTime.acrossReplicationStatistic),
            r("oldPartSystemTime", shop.oldPartSystemTime.acrossReplicationStatistic),
            r("newPartSystemTime", shop.newPartSystemTime.acrossReplicationStatistic),
            r("numberIn", shop.numberIn.acrossReplicationStatistic),
            r("numberOut", shop.numberOut.acrossReplicationStatistic),
            r("workerUtilization", shop.worker.scheduledUtil.acrossReplicationStatistic),
            r("painterUtilization", shop.painter.scheduledUtil.acrossReplicationStatistic),
            r("newPainterUtilization", shop.newPainter.scheduledUtil.acrossReplicationStatistic),
            r("packerUtilization", shop.packer.scheduledUtil.acrossReplicationStatistic),
            r("workStationQueueWait", shop.workStationQ.timeInQ.acrossReplicationStatistic),
            r("paintQueueWait", shop.paintQ.timeInQ.acrossReplicationStatistic),
            r("newPaintQueueWait", shop.newPaintQ.timeInQ.acrossReplicationStatistic),
            r("packQueueWait", shop.packQ.timeInQ.acrossReplicationStatistic),
            r("workStationQueueLength", shop.workStationQ.numInQ.acrossReplicationStatistic),
            r("paintQueueLength", shop.paintQ.numInQ.acrossReplicationStatistic),
            r("newPaintQueueLength", shop.newPaintQ.numInQ.acrossReplicationStatistic),
            r("packQueueLength", shop.packQ.numInQ.acrossReplicationStatistic),
            r("enterRequestWait", shop.enterRequestWait.acrossReplicationStatistic),
            r("workStationRequestWait", shop.workStationRequestWait.acrossReplicationStatistic),
            r("paintRequestWait", shop.paintRequestWait.acrossReplicationStatistic),
            r("newPaintRequestWait", shop.newPaintRequestWait.acrossReplicationStatistic),
            r("packRequestWait", shop.packRequestWait.acrossReplicationStatistic),
            r("agvNumberBusy", shop.agvs.numBusyUnits.acrossReplicationStatistic),
            r("agvUtilization", shop.agvs.fractionBusyUnits.acrossReplicationStatistic)
        )
    }

    private fun compare(
        title: String,
        reference: Map<String, Pair<Double, Double>>,
        ksl: List<Triple<String, Double, Double>>
    ): Map<String, Double> {
        println()
        println(title)
        println("  $REPLICATIONS replications of ${HORIZON_MINUTES.toInt()} minutes after a " +
                "${WARM_UP_MINUTES.toInt()} minute warm-up; half-widths are each tool's own 95% figures")
        println()
        println("  %-24s %12s %10s %12s %10s %10s".format("quantity", "reference", "+/-", "KSL", "+/-", "z"))
        val zs = linkedMapOf<String, Double>()
        for ((name, value, halfWidth) in ksl) {
            val (a, ah) = reference[name] ?: continue
            val combined = sqrt(ah * ah + halfWidth * halfWidth)
            val z = if (combined > 0.0) abs(value - a) / combined else 0.0
            val group = if (name in SHOP) "shop " else "aisle"
            println("  %-24s %12.4f %10.4f %12.4f %10.4f %10.2f  %s"
                .format(name, a, ah, value, halfWidth, z, group))
            zs[name] = z
        }
        println()
        return zs
    }

    /**
     *  Splits the fleet's committed time into the part spent on jobs and the part spent going to
     *  the staging area, and prints both against the single figure the reference implementation
     *  reports.
     *
     *  Neither piece needs an instrument that does not already exist. Travelling and blocked time
     *  are exhaustive of everything except loading, unloading, and standing still, and the time a
     *  vehicle spends travelling *on a job* -- empty out and loaded back, blocking included,
     *  because a journey's elapsed time contains its own blocking -- is what each part reports as
     *  its transfer time less the four minutes per leg of loading and unloading. The remainder is
     *  repositioning, which the reference implementation counts as busy and this subsystem does
     *  not.
     */
    private fun reportCommitment(shop: PaintingFlowLineWithAgvs, referenceBusy: Double) {
        val vehicles = shop.transportSystem.transporters
        val travelAndBlock = vehicles.sumOf {
            it.fracTimeMoving.acrossReplicationStatistic.average +
                    it.fracTimeBlocked.acrossReplicationStatistic.average
        } / vehicles.size
        val loadUnloadPerPart =
            LEGS_PER_PART * 2.0 * PaintingFlowLineWithAgvs.LOAD_UNLOAD_MINUTES
        val onJobs = (shop.transferTime.acrossReplicationStatistic.average - loadUnloadPerPart) *
                shop.numberOut.acrossReplicationStatistic.average / (FLEET * OBSERVED_MINUTES)
        val repositioning = travelAndBlock - onJobs
        val allocated = shop.agvs.fractionBusyUnits.acrossReplicationStatistic.average
        println("  what the fleet is committed to, as a fraction of the horizon:")
        println("    allocated %.4f + repositioning %.4f = committed %.4f   (the reference implementation reports busy %.4f)"
            .format(allocated, repositioning, allocated + repositioning, referenceBusy))
        println("    empty move per leg %.4f minutes; obstructions detected per replication %.0f"
            .format(
                shop.transportSystem.approachTime.acrossReplicationStatistic.average,
                shop.transportSystem.numObstructionsDetected.acrossReplicationStatistic.average
            ))
        println()
    }

    @Test
    @DisplayName("7.15 three AGVs: the shop agrees exactly; the fleet's committed time agrees, its split does not")
    fun threeAgvsOnAOneWayNetwork() {
        val reference = referenceFixture("/reference/P7-15.csv")
        val referenceBusy = reference.getValue("agvUtilization").first

        val shop = buildAndRun(PendingRequestTest.UNTIL_COLLECTED)
        val measured = measure(shop)
        val z = compare(
            "Exercise 7.15: three AGVs, one-way guide path, staging area at I12, KSL against the reference implementation\n" +
                    "  a request counts as pending until a vehicle reaches the part, which is what " +
                    "the reference implementation's own counter measures",
            reference, measured
        )
        reportCommitment(shop, referenceBusy)

        // The narrower reading of "pending", run beside it so the choice is settled by measurement.
        val queuedOnly = buildAndRun(PendingRequestTest.QUEUED_ONLY)
        val zQueuedOnly = compare(
            "  ...and counting only parts actually queued, for comparison", reference, measure(queuedOnly)
        )
        reportCommitment(queuedOnly, referenceBusy)

        val worstShop = SHOP.mapNotNull { z[it] }.max()
        assertTrue(worstShop <= 1.0, "the two models are not the same shop: worst z = %.2f".format(worstShop))

        // The wider reading is the reference implementation's. The narrower one sends a vehicle to the
        // staging area twice as often, and the extra traffic reaches the pack queue; both readings agree
        // on the workstation queue.
        for (name in listOf("packQueueWait")) {
            assertTrue(
                z.getValue(name) < zQueuedOnly.getValue(name),
                "counting a request pending until collection should fit the reference better on $name, " +
                        "but scored z = %.2f against %.2f".format(z.getValue(name), zQueuedOnly.getValue(name))
            )
        }

        // Agreement since R1.8 (see "Before R1.8" in this class's KDoc).
        for (name in listOf("systemTime", "oldPartSystemTime", "newPartSystemTime", "packRequestWait")) {
            assertTrue(z.getValue(name) <= 1.0, "$name should agree with the reference: z = %.2f".format(z.getValue(name)))
        }

        // NOT agreement: see this class's KDoc. Held where they stand so they cannot drift further
        // without the reason being re-examined. Re-pinned for R1.8 (z then: paint queue 1.64 and 1.59,
        // request waits 8.57 at the entry, 2.39, 3.88 and 3.22, AGV busy and utilization 94.2).
        val pinned = mapOf(
            "paintQueueWait" to 3.0, "paintQueueLength" to 3.0,
            "enterRequestWait" to 13.0, "workStationRequestWait" to 4.0,
            "paintRequestWait" to 6.0, "newPaintRequestWait" to 5.0,
            "agvNumberBusy" to 140.0, "agvUtilization" to 140.0
        )
        for ((name, limit) in pinned) {
            assertTrue(
                z.getValue(name) <= limit,
                "$name has drifted beyond where it stood: z = %.2f, pinned at %.1f"
                    .format(z.getValue(name), limit)
            )
        }

        // The claim the KDoc rests on, asserted rather than only described: the two fleets are
        // committed for nearly the same share of the horizon, and differ in how that time is
        // classified. The reference implementation counts the trip to the staging area as busy; this subsystem does not.
        val vehicles = shop.transportSystem.transporters
        val travelAndBlock = vehicles.sumOf {
            it.fracTimeMoving.acrossReplicationStatistic.average +
                    it.fracTimeBlocked.acrossReplicationStatistic.average
        } / vehicles.size
        val onJobs = (shop.transferTime.acrossReplicationStatistic.average -
                LEGS_PER_PART * 2.0 * PaintingFlowLineWithAgvs.LOAD_UNLOAD_MINUTES) *
                shop.numberOut.acrossReplicationStatistic.average / (FLEET * OBSERVED_MINUTES)
        val committed = shop.agvs.fractionBusyUnits.acrossReplicationStatistic.average +
                (travelAndBlock - onJobs)
        assertTrue(
            abs(committed - referenceBusy) / referenceBusy <= 0.05,
            "committed vehicle time should account for the reference implementation's busy figure to within 5%%, but was " +
                    "%.4f against %.4f".format(committed, referenceBusy)
        )
    }
}
