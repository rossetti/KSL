package ksl.modeling.guidedpath

import ksl.examples.book.chapter8.TestAndRepairShopWithGuidedTransporters
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.simulation.Model
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.assertTrue

/**
 *  Exercise 7.13, both parts: the Test and Repair shop served by AGVs on a guided path, compared
 *  against the same models built in the reference implementation.
 *
 *  **(a)** one vehicle on **bidirectional** aisles -- safe only because there is one, which is the
 *  point the exercise makes. Nothing else in the suite validates the direction lock against
 *  anything but itself. **(b)** two vehicles on the same aisles made **one-way**, which is what the
 *  exercise prescribes to keep two vehicles from deadlocking.
 *
 *  The pair is deliberately not read as a fleet-size experiment. Link L10 changes between them as
 *  well as the fleet, so they are two validation cases rather than a controlled comparison.
 *
 *  ## The layout
 *
 *  Ten links of **one zone each**, lengths in metres. The intersections close geometrically on the
 *  declared lengths and bearings, which is the check that the network was read correctly rather
 *  than merely plausibly: starting from I1 and following each link, I9 lands exactly 13 west of I2
 *  and exactly 23 south of I5, as its two other links require.
 *
 *  ```
 *   I4 ── 13 ── I5 ── 17 ── I6
 *   │            │           │
 *   11          L10         11
 *   │            │           │
 *   I3           │          I7
 *   │            │           │
 *   12           │          12
 *   │            │           │
 *   I2 ── 13 ── I9 ── 17 ── I8
 *   │
 *   9 (spur)
 *   │
 *   I1
 *  ```
 *
 *  Stations sit on the nearest intersection, as the exercise directs: diagnostics at I3, test 1 at
 *  I4, test 2 at I6, repair at I7, test 3 at I9. Workers travel at a constant 30 metres per minute,
 *  are one metre long, release at the start of a zone, and stay where they finish.
 *
 *  One declaration difference worth naming: The reference implementation declares the spur from the
 *  dead end outward, I1 to I2, and a KSL spur must *end* at the dead end, so it is declared the
 *  other way round. Same nine metres of aisle; a convention rather than a modelling difference, but
 *  it reads as identical and is not.
 *
 *  ## What agreement means here
 *
 *  These models are stochastic, so the two tools cannot produce the same numbers and it would be
 *  wrong to ask them to. What can be asked is that the estimates are consistent: the gap between
 *  the means is measured against the two half-widths combined in quadrature, so **z <= 1 is
 *  agreement at 95%** for two independent estimates.
 *
 *  The quantities fall in two groups. The service statistics -- utilizations, queue waits, work in
 *  process, throughput, the contract probability -- say whether the two models are the same *shop*,
 *  and are nearly independent of the guide path. Transfer time and transporter utilization say
 *  whether they are the same *aisle*. **In both cases all fourteen service statistics agree**,
 *  worst z = 0.89 in (a) and 0.84 in (b). The aisle is where the interest is.
 *
 *  ## (a): the aisle is a third of a percent out, and one cause is eliminated
 *
 *  Transfer time z = 1.42 and transporter utilization z = 1.31 -- outside the 95% criterion, though
 *  the intervals are so tight (the reference implementation's transfer half-width is 0.015 minutes
 *  on a mean of 7.52) that this is a difference of about a third of a percent. This subsystem's
 *  worker travels marginally further.
 *
 *  The obvious candidate was tested and eliminated. The AGV is one metre long, and on a two-way
 *  aisle a single vehicle turns round constantly, so crediting a reversal at **every** junction the
 *  way a dead end is credited looked right. It overshoots badly: transfer time goes from 0.03
 *  minutes above the reference implementation's to 0.10 below, z from 1.42 to 5.22. Whatever that
 *  implementation does at a junction it is not that, so the narrow dead-end rule stands. The
 *  remaining third of a percent is unexplained and is recorded rather than tuned away.
 *
 *  ## (b): the cross-check settles what L10 is, and finds a number that does not close
 *
 *  The reference implementation declares L10 differently in the two models. In (a) it runs I9 to I5
 *  at **23** metres, which is exactly the distance between them in the layout. In (b) it runs I5 to
 *  I9 at **9** metres heading east -- reversed, a third of the length, and pointing the wrong way
 *  for those two intersections. It cannot be the same piece of aisle the drawing shows, and nine
 *  metres east is what L1 is, which looks like an edit that was not finished.
 *
 *  The comparison decides it. Run with nine, transfer time agrees at **z = 0.11**; run with
 *  twenty-three it is **z = 5.08**. The reference implementation ran with nine, so nine is what its
 *  numbers describe, and the test runs both so the reader sees the difference rather than taking
 *  the choice on trust.
 *
 *  Transporter utilization still does not agree, at z = 2.38, and the arithmetic says where the gap
 *  is rather than leaving it open. A transporter is busy from allocation to release, and an
 *  entity's transfer time is exactly that span, so throughput x transfer time / (fleet x horizon)
 *  must return the reported utilization. It does for this subsystem in both cases, and for the
 *  reference implementation in (a):
 *
 *  ```
 *  (a)  reference  implied 0.37500  reported 0.37526      KSL  implied 0.37884  reported 0.37912
 *  (b)  reference  implied 0.39443  reported 0.40158      KSL  implied 0.39678  reported 0.39707
 *  ```
 *
 *  the reference implementation's (b) figure is the only one that does not close, and it is out by
 *  1.8% -- which is the whole of the disagreement. Something in that model counts a transporter
 *  busy for time its entities do not count as transfer. That is an observation about the source
 *  rather than a claim that it is wrong, and it is the reason this one quantity is held where it
 *  stands instead of being chased on this side.
 */
@Tag("slow")
class TestAndRepairCrossCheckTest {

    private companion object {
        const val REPLICATIONS = 10
        const val HORIZON_MINUTES = 249_600.0   // 4160 hours, in the base units the reference implementation reports in
        const val VELOCITY = 30.0               // metres per minute
        const val CART_LENGTH = 1.0             // "It is 1 meter in length" -- Exercise 7.13
        const val HOME = "I1"

        // The shop asks for its stations by name, so a network built here has to carry the same
        // five. They are written out rather than read off the shop: the shop keeps them private,
        // as its free-path twin does, and a name that drifted would raise at the first journey
        // rather than quietly produce a different answer.
        const val DIAGNOSTIC = "DiagnosticStation"
        const val TEST1 = "TestStation1"
        const val TEST2 = "TestStation2"
        const val TEST3 = "TestStation3"
        const val REPAIR = "RepairStation"
    }

    /**
     *  the reference implementation's `AGVNetwork`, link for link. One zone per link, so a zone is
     *  a whole aisle segment: with a single vehicle the discretization cannot matter for
     *  contention, and the exercise says as much -- "since we only have 1 transporter our
     *  definition of zones can be very simplistic".
     */
    private fun networkA(): GuidedPathNetwork = GuidedPathNetwork.builder("AGVNetworkA")
        .intersection("I1", x = 0.0, y = 0.0)
        .intersection("I2", x = 9.0, y = 0.0)
        .intersection("I3", x = 9.0, y = 12.0)
        .intersection("I4", x = 9.0, y = 23.0)
        .intersection("I5", x = 22.0, y = 23.0)
        .intersection("I6", x = 39.0, y = 23.0)
        .intersection("I7", x = 39.0, y = 12.0)
        .intersection("I8", x = 39.0, y = 0.0)
        .intersection("I9", x = 22.0, y = 0.0)
        // the reference implementation declares this spur from the dead end outward, I1 to I2; a KSL spur must *end* at
        // the dead end, so it is declared the other way round. Same nine metres of aisle, and the
        // difference is a declaration convention rather than a modelling one -- but it is the sort
        // of thing that reads as identical and is not, so it is written down rather than silently
        // reversed.
        .link("L1", "I2", "I1", length = 9.0, zoneLength = 9.0, type = LinkType.SPUR, beginDirection = 180.0)
        .link("L2", "I2", "I3", length = 12.0, zoneLength = 12.0, type = LinkType.BIDIRECTIONAL, beginDirection = 90.0)
        .link("L3", "I3", "I4", length = 11.0, zoneLength = 11.0, type = LinkType.BIDIRECTIONAL, beginDirection = 90.0)
        .link("L4", "I4", "I5", length = 13.0, zoneLength = 13.0, type = LinkType.BIDIRECTIONAL, beginDirection = 0.0)
        .link("L5", "I5", "I6", length = 17.0, zoneLength = 17.0, type = LinkType.BIDIRECTIONAL, beginDirection = 0.0)
        .link("L6", "I6", "I7", length = 11.0, zoneLength = 11.0, type = LinkType.BIDIRECTIONAL, beginDirection = 270.0)
        .link("L7", "I7", "I8", length = 12.0, zoneLength = 12.0, type = LinkType.BIDIRECTIONAL, beginDirection = 270.0)
        .link("L8", "I8", "I9", length = 17.0, zoneLength = 17.0, type = LinkType.BIDIRECTIONAL, beginDirection = 180.0)
        .link("L9", "I9", "I2", length = 13.0, zoneLength = 13.0, type = LinkType.BIDIRECTIONAL, beginDirection = 180.0)
        .link("L10", "I9", "I5", length = 23.0, zoneLength = 23.0, type = LinkType.BIDIRECTIONAL, beginDirection = 90.0)
        .station(DIAGNOSTIC, "I3")
        .station(TEST1, "I4")
        .station(TEST2, "I6")
        .station(REPAIR, "I7")
        .station(TEST3, "I9")
        .build()

    /**
     *  Exercise 7.13(b)'s network: the same nine aisles made one-way, and L10 replaced.
     *
     *  In (a) L10 runs I9 to I5 and is 23 metres, which is exactly the distance between them in the
     *  layout. In (b) the reference implementation declares it I5 to I9, 9 metres, heading east --
     *  reversed, a third of the length, and pointing the wrong way for those two intersections. It
     *  cannot be the same piece of aisle as the drawing shows, and 9 metres east is what L1 is,
     *  which looks like an edit that was not finished. It nonetheless *ran* with 9, so 9 is what
     *  its numbers describe, and this reproduces the model rather than the figure. [l10Length]
     *  exists so the alternative can be run beside it and the difference seen rather than argued
     *  about.
     */
    private fun networkB(l10Length: Double): GuidedPathNetwork = GuidedPathNetwork.builder("AGVNetworkB")
        .intersection("I1", x = 0.0, y = 0.0)
        .intersection("I2", x = 9.0, y = 0.0)
        .intersection("I3", x = 9.0, y = 12.0)
        .intersection("I4", x = 9.0, y = 23.0)
        .intersection("I5", x = 22.0, y = 23.0)
        .intersection("I6", x = 39.0, y = 23.0)
        .intersection("I7", x = 39.0, y = 12.0)
        .intersection("I8", x = 39.0, y = 0.0)
        .intersection("I9", x = 22.0, y = 0.0)
        .link("L1", "I2", "I1", length = 9.0, zoneLength = 9.0, type = LinkType.SPUR, beginDirection = 180.0)
        .link("L2", "I2", "I3", length = 12.0, zoneLength = 12.0, beginDirection = 90.0)
        .link("L3", "I3", "I4", length = 11.0, zoneLength = 11.0, beginDirection = 90.0)
        .link("L4", "I4", "I5", length = 13.0, zoneLength = 13.0, beginDirection = 0.0)
        .link("L5", "I5", "I6", length = 17.0, zoneLength = 17.0, beginDirection = 0.0)
        .link("L6", "I6", "I7", length = 11.0, zoneLength = 11.0, beginDirection = 270.0)
        .link("L7", "I7", "I8", length = 12.0, zoneLength = 12.0, beginDirection = 270.0)
        .link("L8", "I8", "I9", length = 17.0, zoneLength = 17.0, beginDirection = 180.0)
        .link("L9", "I9", "I2", length = 13.0, zoneLength = 13.0, beginDirection = 180.0)
        .link("L10", "I5", "I9", length = l10Length, zoneLength = l10Length, beginDirection = 270.0)
        .station(DIAGNOSTIC, "I3")
        .station(TEST1, "I4")
        .station(TEST2, "I6")
        .station(REPAIR, "I7")
        .station(TEST3, "I9")
        .build()

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

    /** Every quantity that both tools report, in the order the table prints them. */
    private fun measure(shop: TestAndRepairShopWithGuidedTransporters): List<Triple<String, Double, Double>> {
        fun r(name: String, s: ksl.utilities.statistic.StatisticIfc) = Triple(name, s.average, s.halfWidth)
        return listOf(
            r("entityTotalTime", shop.systemTime.acrossReplicationStatistic),
            r("entityWIP", shop.numInSystem.acrossReplicationStatistic),
            r("probWithinContractLimit", shop.probWithinLimit.acrossReplicationStatistic),
            r("numberIn", shop.numberIn.acrossReplicationStatistic),
            r("numberOut", shop.numberOut.acrossReplicationStatistic),
            r("diagnosticUtilization", shop.diagnostics.scheduledUtil.acrossReplicationStatistic),
            r("test1Utilization", shop.test1.scheduledUtil.acrossReplicationStatistic),
            r("test2Utilization", shop.test2.scheduledUtil.acrossReplicationStatistic),
            r("test3Utilization", shop.test3.scheduledUtil.acrossReplicationStatistic),
            r("diagnosticQueueWait", shop.diagnosticsQ.timeInQ.acrossReplicationStatistic),
            r("test1QueueWait", shop.test1Q.timeInQ.acrossReplicationStatistic),
            r("test2QueueWait", shop.test2Q.timeInQ.acrossReplicationStatistic),
            r("test3QueueWait", shop.test3Q.timeInQ.acrossReplicationStatistic),
            r("repairQueueWait", shop.repairQ.timeInQ.acrossReplicationStatistic),
            r("entityTransferTime", shop.transferTime.acrossReplicationStatistic),
            r("transporterUtilization", shop.transportWorkers.fractionBusyUnits.acrossReplicationStatistic)
        )
    }

    private val shopGroup = setOf(
        "entityTotalTime", "entityWIP", "probWithinContractLimit", "numberIn", "numberOut",
        "diagnosticUtilization", "test1Utilization", "test2Utilization", "test3Utilization",
        "diagnosticQueueWait", "test1QueueWait", "test2QueueWait", "test3QueueWait", "repairQueueWait"
    )

    private fun buildAndRun(
        modelName: String,
        network: GuidedPathNetwork,
        numTransporters: Int,
        homes: List<String>
    ): TestAndRepairShopWithGuidedTransporters {
        val m = Model(modelName)
        val shop = TestAndRepairShopWithGuidedTransporters(
            m, numTransporters = numTransporters, timeBtwArrivals = 20.0, name = "Shop",
            aisleNetwork = network,
            transporterVelocity = ConstantRV(VELOCITY),
            transporterHomes = homes,
            transporterPhysicalLength = CART_LENGTH,
            zoneControlRule = StartOfZoneControl(),
            idleDispositionRule = ParkInPlaceRule()
        )
        m.numberOfReplications = REPLICATIONS
        m.lengthOfReplication = HORIZON_MINUTES
        m.simulate()
        return shop
    }

    /**
     *  Prints the comparison and returns the worst z in each group: the shop first, the aisle
     *  second.
     *
     *  z is the gap between the two means over the two half-widths combined in quadrature, so z <=
     *  1 is agreement at 95% for two independent estimates.
     */
    private fun compare(
        title: String,
        reference: Map<String, Pair<Double, Double>>,
        ksl: List<Triple<String, Double, Double>>
    ): Map<String, Double> {
        println()
        println(title)
        println("  10 replications of 4160 hours; half-widths are each tool's own 95% figures")
        println()
        println("  %-24s %12s %10s %12s %10s %10s".format("quantity", "reference", "+/-", "KSL", "+/-", "z"))
        val zs = linkedMapOf<String, Double>()
        for ((name, value, halfWidth) in ksl) {
            val (a, ah) = reference[name] ?: continue
            val combined = sqrt(ah * ah + halfWidth * halfWidth)
            val z = if (combined > 0.0) abs(value - a) / combined else 0.0
            val group = if (name in shopGroup) "shop " else "aisle"
            println("  %-24s %12.4f %10.4f %12.4f %10.4f %10.2f  %s"
                .format(name, a, ah, value, halfWidth, z, group))
            zs[name] = z
        }
        println()
        return zs
    }

    /**
     *  A transporter is busy from allocation to release, and an entity's transfer time is exactly
     *  that span, so throughput x transfer time / (fleet x horizon) must come back as the reported
     *  utilization. Printed for both tools because it is a check each makes on itself, and because
     *  in one place below the two answers part company.
     */
    private fun utilizationImpliedBy(throughput: Double, transfer: Double, fleet: Int): Double =
        throughput * transfer / (fleet * HORIZON_MINUTES)

    private fun reportConsistency(
        reference: Map<String, Pair<Double, Double>>,
        ksl: List<Triple<String, Double, Double>>,
        fleet: Int
    ) {
        fun kslOf(n: String) = ksl.first { it.first == n }.second
        val aImplied = utilizationImpliedBy(
            reference.getValue("numberOut").first, reference.getValue("entityTransferTime").first, fleet)
        val kImplied = utilizationImpliedBy(kslOf("numberOut"), kslOf("entityTransferTime"), fleet)
        println("  utilization implied by each tool's own throughput and transfer time:")
        println("    reference  implied %.5f  reported %.5f".format(aImplied, reference.getValue("transporterUtilization").first))
        println("    KSL    implied %.5f  reported %.5f".format(kImplied, kslOf("transporterUtilization")))
        println()
    }

    @Test
    @DisplayName("7.13(a) one AGV, two-way aisles: the shop agrees; the aisle is a third of a percent out")
    fun oneAgvOnBidirectionalAisles() {
        val reference = referenceFixture("/reference/P7-13a.csv")
        val shop = buildAndRun("P7-13a", networkA(), numTransporters = 1, homes = listOf(HOME))
        val measured = measure(shop)
        val z = compare(
            "Exercise 7.13(a): one AGV, bidirectional aisles, KSL against the reference implementation", reference, measured
        )
        reportConsistency(reference, measured, fleet = 1)

        val worstShop = shopGroup.mapNotNull { z[it] }.max()
        assertTrue(worstShop <= 1.0, "the two models are not the same shop: worst z = %.2f".format(worstShop))
        // NOT agreement: see this class's KDoc. Held where it stands so it cannot drift further.
        for (name in listOf("entityTransferTime", "transporterUtilization")) {
            assertTrue(
                z.getValue(name) <= 2.0,
                "$name has drifted beyond where it stood: z = %.2f".format(z.getValue(name))
            )
        }
    }

    @Test
    @DisplayName("7.13(b) two AGVs, one-way aisles: L10 is 9 metres, and the reference implementation's own utilization does not close")
    fun twoAgvsOnUnidirectionalAisles() {
        val reference = referenceFixture("/reference/P7-13b.csv")
        val declared = buildAndRun(
            "P7-13b", networkB(l10Length = 9.0), numTransporters = 2, homes = listOf(HOME, "I8")
        )
        val measured = measure(declared)
        val z = compare(
            "Exercise 7.13(b): two AGVs, one-way aisles, L10 as the reference implementation declares it (I5->I9, 9 m)",
            reference, measured
        )
        reportConsistency(reference, measured, fleet = 2)

        // The alternative reading of L10 -- the 23 metres the layout shows -- run beside it so the
        // choice is settled by measurement rather than by argument about which was intended.
        val asDrawn = buildAndRun(
            "P7-13b-drawn", networkB(l10Length = 23.0), numTransporters = 2, homes = listOf(HOME, "I8")
        )
        val zDrawn = compare(
            "  ...and with L10 at the 23 metres the layout shows, for comparison", reference, measure(asDrawn)
        )

        val worstShop = shopGroup.mapNotNull { z[it] }.max()
        assertTrue(worstShop <= 1.0, "the two models are not the same shop: worst z = %.2f".format(worstShop))

        // Nine metres is the reference implementation's aisle. Transfer time is the quantity that distinguishes them, and
        // it agrees on nine and is five half-widths out on twenty-three.
        assertTrue(
            z.getValue("entityTransferTime") <= 1.0,
            "transfer time should agree on the declared L10: z = %.2f".format(z.getValue("entityTransferTime"))
        )
        assertTrue(
            zDrawn.getValue("entityTransferTime") > 3.0,
            "the 23 metre reading should be clearly worse, but scored z = %.2f"
                .format(zDrawn.getValue("entityTransferTime"))
        )

        // Utilization does not agree, and the arithmetic above says where the gap is: The reference implementation's own
        // reported figure does not follow from its own throughput and transfer time here, while both
        // this subsystem's and the reference implementation's (a) figures do. Held where it stands rather than explained.
        assertTrue(
            z.getValue("transporterUtilization") <= 3.0,
            "transporter utilization has drifted beyond where it stood: z = %.2f"
                .format(z.getValue("transporterUtilization"))
        )
    }
}
