package ksl.modeling.guidedpath

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.rules.CyclicalTransporterRule
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.StartOfZoneControl
import ksl.modeling.variable.Response
import ksl.modeling.variable.TWResponse
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  The same shop, in KSL and in the reference implementation, compared number by number.
 *
 *  This is the cross-check the guided-path plan called Gate B, and it is the only evidence in the
 *  repository that this subsystem means the same thing by an automated guided vehicle as the tool
 *  the field already uses. Everything else the suite asserts, it asserts against itself.
 *
 *  ## Why it can be exact
 *
 *  The reference model is deterministic: parts are created every 25 minutes from time zero, the
 *  load and unload delays are 20 minutes apiece, and the carts travel at a constant 10. There is
 *  nothing random in it, so there is no confidence interval to hide inside. Either the two tools
 *  produce the same timeline or they do not, and a discrepancy is a discrepancy rather than
 *  sampling noise. That is a much stronger gate than the plan's original "within one standard
 *  error", and it is available only because the source model happens to have been built without
 *  randomness.
 *
 *  ## What had to be matched
 *
 *  The network was already identical -- the KSL layout was built from this model -- so what needed
 *  matching was the experiment: `StartOfZoneControl` for its release-at-start zone control,
 *  `ParkInPlaceRule` for its leave-a-freed-vehicle-where-it-stands rule, [CyclicalTransporterRule]
 *  for its cyclical unit selection, and constant arrivals and delays in place of the exponential
 *  ones the KSL example ships with. The carts start on the I6 and I7 spurs, which the reference
 *  documentation does not record -- it prints a home station but never a unit's initial position --
 *  and which had to be supplied by the model's author.
 *
 *  ## The reference implementation's timing categories, and how they are reproduced
 *
 *  A reference-model entity accrues **Wait** while queued for a transporter, **Transfer** from the
 *  instant one is allocated until it is freed, and **Other** during a delay the model
 *  declares as neither value-added nor waiting, which is what both 20-minute delays are. So `Total = Wait + Transfer +
 *  Other`, and the fixture satisfies that identity exactly. The process below is written in the
 *  decomposed verbs rather than
 *  `guidedTransport` precisely so that the instant of allocation is visible and the same three
 *  quantities can be formed here.
 *
 *  ## Gate B passes
 *
 *  Every quantity the reference implementation reports is reproduced to floating-point noise -- the
 *  largest difference is 4e-13 on a mean of 131.9, and most are nearer 1e-15. Not "within a
 *  confidence interval": the same numbers.
 *
 *  | quantity | reference | difference |
 *  |---|---|---|
 *  | mean time in system | 131.9 | -4.0e-13 |
 *  | transfer time | 32.2333333333332 | -3.6e-14 |
 *  | wait for a cart | 59.6666666666665 | -6.4e-14 |
 *  | load and unload | 40 | 0 |
 *  | work in process | 4.83916666666666 | -4.4e-15 |
 *  | number waiting for a cart | 2.89124999999999 | 4.4e-16 |
 *  | carts busy | 1.94791666666667 | -3.6e-15 |
 *  | cart utilization | 0.973958333333333 | 2.2e-16 |
 *  | total waiting time in the request queue | 982.8 | ~0 |
 *  | parts in, parts out | 20, 12 | exact |
 *
 *  ### What it took, and the one thing that had to be built
 *
 *  The network was already identical -- the KSL layout was built from this model. Matching the
 *  experiment needed `StartOfZoneControl` for its release-at-start zone control, `ParkInPlaceRule`
 *  for its leave-a-freed-vehicle-where-it-stands rule, a `CyclicalTransporterRule` for its cyclical
 *  unit selection, constant arrivals and delays, and the carts started on the I6 and I7 spurs --
 *  which the reference documentation does not record, since it prints a home station but never a
 *  unit's initial position, and which the model's author supplied.
 *
 *  The one thing that could not be configured was the transporter's size. The reference
 *  implementation sizes this one with the **LENGTH** option at **6 feet**, and this subsystem had
 *  only a whole number of zones. A vehicle with a physical extent that is parked at a dead end has
 *  already covered its own length of the spur, so leaving costs its length less than the spur's
 *  declared distance while entering costs the whole of it. That asymmetry -- and nothing else --
 *  was the entire discrepancy:
 *
 *  - a steady-state cart returns from I5 out of the 36-foot exit spur paying 30 + 72 rather than
 *    36 + 72, so its cycle transfer is 30.6, which is the reference implementation's reported
 *    minimum;
 *  - Cart1 leaves the 6-foot home spur at I6, which its 6-foot body exactly fills, paying nothing,
 *    so its first part is delivered at 79.6, which is the reference implementation's reported
 *    minimum total time.
 *
 *  [GuidedTransporter.physicalLength] now expresses it. It is optional and off by default, so every
 *  model written before behaves exactly as it did.
 *
 *  Two rival explanations were tested and eliminated before that one was believed. The exit spur's
 *  zone structure -- the reference implementation declares one zone of 36 where the KSL layout uses
 *  three of 12 -- changes nothing at all. And simply shortening the spur by 6 over-corrects by
 *  exactly 6, because a cart crosses it twice per cycle and only the outbound crossing is
 *  shortened; that is what showed the mechanism is asymmetric, which the first guess had wrong.
 *
 *  ### Every quantity, including the queue
 *
 *  An earlier version of this test could not compare the mean queue wait directly. The reference
 *  implementation reported 70.2 over 14 observations and this subsystem 81.9 over 12, and the
 *  comparison was made on the total instead, on the reasoning that the two tools counted
 *  observations differently. That reasoning was wrong: The reference implementation counts the way
 *  `seize` counts, and it was the guided-path pool that departed from the library by parking
 *  entities in a hold queue only when the fleet was busy. The pool is now seized like any other
 *  resource pool, so a request served instantly records a wait of zero, the counts agree at 14, and
 *  the mean is compared like everything else.
 */
class SimpleAgvCrossCheckTest {

    /** The reference implementation's experiment, in KSL. Every constant here is read off the reference model's report. */
    private class ReferenceShop(parent: ModelElement) : ProcessModel(parent, "ReferenceShop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(VELOCITY), 1, StartOfZoneControl(), "Cart1", physicalLength = CART_LENGTH
        )

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(VELOCITY), 1, StartOfZoneControl(), "Cart2", physicalLength = CART_LENGTH
        )

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            CyclicalTransporterRule(), ParkInPlaceRule(), "Carts"
        )

        val totalTime = Response(this, "TotalTime")
        val transferTime = Response(this, "TransferTime")
        val waitTime = Response(this, "WaitTime")
        val otherTime = Response(this, "OtherTime")
        val wip = TWResponse(this, "WIP")

        var numberIn: Int = 0
            private set
        var numberOut: Int = 0
            private set

        /** arrival, allocation, cart, freed -- for reading the two timelines against each other. */
        val trace = mutableListOf<String>()

        inner class Part : Entity() {
            val make = process("part") {
                val arrived = time
                numberIn++
                wip.increment()
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                // Returns once a cart has been allocated *and* has arrived, which is what the
                // reference implementation's transport request does.
                val request = requestGuidedTransporter(carts, SimpleAgvNetwork.ENTRY_STATION)
                val allocatedAt = request.timeAllocated
                delay(LOAD_DELAY)
                transportBy(request, SimpleAgvNetwork.EXIT_STATION)
                delay(UNLOAD_DELAY)
                val freedAt = time
                releaseGuidedTransporter(request, carts)
                waitTime.value = allocatedAt - arrived
                transferTime.value = (freedAt - allocatedAt) - (LOAD_DELAY + UNLOAD_DELAY)
                otherTime.value = LOAD_DELAY + UNLOAD_DELAY
                totalTime.value = time - arrived
                trace.add(
                    "    arrived %7.2f  allocated %7.2f  by %-6s freed %7.2f".format(
                        arrived, allocatedAt, request.transporter.name, freedAt))
                numberOut++
                wip.decrement()
            }
        }

        override fun initialize() {
            numberIn = 0
            numberOut = 0
            // Reference model: Create, Type Constant, Value 25, First Creation 0.0, Max Arrivals Infinite.
            // Over a 480-minute horizon that is exactly twenty parts, at 0, 25, ..., 475.
            var t = 0.0
            while (t < HORIZON) {
                activate(Part().make, timeUntilActivation = t)
                t += TIME_BETWEEN_ARRIVALS
            }
        }

        companion object {
            const val VELOCITY = 10.0

            /** The reference implementation sizes this transporter by length, at 6 feet. */
            const val CART_LENGTH = 6.0
            const val LOAD_DELAY = 20.0
            const val UNLOAD_DELAY = 20.0
            const val TIME_BETWEEN_ARRIVALS = 25.0
            const val HORIZON = 480.0
        }
    }

    private fun referenceFixture(): Map<String, Double> {
        val text = checkNotNull(javaClass.getResourceAsStream("/reference/SimpleAGVExample.csv")) {
            "the reference fixture is missing from the test resources"
        }.bufferedReader().readText()
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it != "statistic,value" }
            .map { it.split(",") }
            .associate { it[0] to it[1].toDouble() }
    }

    @Test
    @DisplayName("KSL reproduces the reference implementation's deterministic run exactly, on every quantity it reports")
    fun kslComparedWithReference() {
        val reference = referenceFixture()

        val m = Model("ReferenceCrossCheck")
        val shop = ReferenceShop(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = ReferenceShop.HORIZON
        m.simulate()

        val ksl = linkedMapOf(
            "entityTotalTime" to shop.totalTime.withinReplicationStatistic.weightedAverage,
            "entityTransferTime" to shop.transferTime.withinReplicationStatistic.weightedAverage,
            "entityWaitTime" to shop.waitTime.withinReplicationStatistic.weightedAverage,
            "entityOtherTime" to shop.otherTime.withinReplicationStatistic.weightedAverage,
            "entityWIP" to shop.wip.withinReplicationStatistic.weightedAverage,
            "requestQueueNumberWaiting" to shop.carts.waitingQ.numInQ.withinReplicationStatistic.weightedAverage,
            "requestQueueWaitingTime" to shop.carts.waitingQ.timeInQ.withinReplicationStatistic.weightedAverage,
            "transporterNumberBusy" to
                    (shop.cart1.numBusyUnits.withinReplicationStatistic.weightedAverage +
                            shop.cart2.numBusyUnits.withinReplicationStatistic.weightedAverage),
            "numberIn" to shop.numberIn.toDouble(),
            "numberOut" to shop.numberOut.toDouble(),
            "entityObservations" to shop.totalTime.withinReplicationStatistic.count,
            "queueObservations" to shop.carts.waitingQ.timeInQ.withinReplicationStatistic.count
        )
        ksl["transporterUtilization"] = ksl.getValue("transporterNumberBusy") / 2.0
        // Kept as a second reading of the same fact: mean times count. It agrees because the counts
        // agree, which is the thing that had to be fixed rather than reconciled.
        ksl["requestQueueTotalWaitingTime"] =
            ksl.getValue("requestQueueWaitingTime") * ksl.getValue("queueObservations")

        // Printed whether it passes or fails. A gate whose evidence is only visible when it breaks
        // is a gate nobody can check.
        println()
        println("  per-part timeline (completed parts only):")
        shop.trace.forEach { println(it) }
        println("  queue observations: ksl=%.0f".format(
            shop.carts.waitingQ.timeInQ.withinReplicationStatistic.count))
        println()
        println("Gate B: the simple AGV example, KSL against the reference implementation (deterministic, 1 rep of 480)")
        println()
        println("  %-28s %20s %20s %14s".format("quantity", "reference", "KSL", "difference"))
        for ((name, referenceValue) in reference) {
            val kslValue = ksl[name]
            if (kslValue == null) {
                println("  %-28s %20.10f %20s".format(name, referenceValue, "not measured"))
                continue
            }
            println(
                "  %-28s %20.10f %20.10f %14.2e".format(name, referenceValue, kslValue, kslValue - referenceValue)
            )
        }
        println()

        // ---- acceptance -----------------------------------------------------------------------
        //
        // Exact, because the model is deterministic. The tolerance below is floating-point noise
        // over quantities of order a hundred, not a margin for disagreement: the largest observed
        // difference is 4e-13 and the rest are nearer 1e-15.
        val tolerance = 1.0e-9

        for (name in listOf("numberIn", "numberOut", "entityObservations", "queueObservations")) {
            assertEquals(reference.getValue(name), ksl.getValue(name), "structural mismatch on $name")
        }

        val compared = listOf(
            "entityTotalTime", "entityTransferTime", "entityWaitTime", "entityOtherTime",
            "entityWIP", "requestQueueNumberWaiting", "requestQueueWaitingTime",
            "transporterNumberBusy", "transporterUtilization",
            "requestQueueTotalWaitingTime"
        )
        for (name in compared) {
            val a = reference.getValue(name)
            val k = ksl.getValue(name)
            assertTrue(
                abs(k - a) / maxOf(1.0, abs(a)) < tolerance,
                "$name: reference $a against KSL $k"
            )
        }
    }
}
