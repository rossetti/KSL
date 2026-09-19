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
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 *  §11.10 invariant 2: the detector observes and changes nothing, so a run with detection enabled
 *  and the same run with it disabled are the same experiment.
 *
 *  This is not a nicety. Detection is optional -- a study may switch it off for throughput -- and
 *  if switching it off changed any number, then the two settings would be two different models and
 *  every result would carry a footnote about which one produced it. Worse, the failure would be
 *  invisible: nothing about a run announces that a detector nudged a claim order or consumed a
 *  random draw.
 *
 *  So the assertion is equality of *every* response and *every* counter in the whole model, to the
 *  bit, across several replications. A single divergent statistic anywhere fails it, including in
 *  parts of the model that have nothing to do with the guide path.
 *
 *  The model used is one that genuinely blocks: transporters contending for the exit spur, so the
 *  blocking transition -- the one place the detector runs -- is exercised many times per
 *  replication. A model that never blocked would pass this test while proving nothing.
 */
class DetectorPurityTest {

    private class ContendedShop(parent: ModelElement) : ProcessModel(parent, "ContendedShop") {

        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart1"
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME),
            ConstantRV(10.0), 1, EndOfZoneControl(), "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Carts"
        )

        // Random arrivals, so the run draws from a stream. A detector that consumed a draw would
        // shift every subsequent arrival and the divergence would be unmissable.
        private val timeBetweenArrivals = ExponentialRV(20.0, streamNum = 1)

        var completed = 0

        inner class Part : Entity() {
            val make = process("part") {
                entity.currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                guidedTransport(
                    carts,
                    destination = SimpleAgvNetwork.EXIT_STATION,
                    pickupLocation = SimpleAgvNetwork.ENTRY_STATION,
                    loadingDelay = ConstantRV(0.5),
                    unLoadingDelay = ConstantRV(0.5)
                )
                completed++
            }
        }

        override fun initialize() {
            completed = 0
            scheduleNext()
        }

        private fun scheduleNext() {
            schedule({ _: ksl.simulation.KSLEvent<Nothing> ->
                activate(Part().make)
                scheduleNext()
            }, timeBetweenArrivals.value)
        }
    }

    /** Every response and counter in the model, by name, as the numbers a report would show. */
    private fun fingerprint(m: Model): Map<String, List<Double>> {
        val out = sortedMapOf<String, List<Double>>()
        for (r in m.responses) {
            val s = r.acrossReplicationStatistic
            out[r.name] = listOf(s.count, s.average, s.variance, s.min, s.max)
        }
        for (c in m.counters) {
            val s = c.acrossReplicationStatistic
            out[c.name] = listOf(s.count, s.average, s.variance, s.min, s.max)
        }
        return out
    }

    private fun run(detection: Boolean): Pair<Model, ContendedShop> {
        val m = Model("PurityRun")
        val shop = ContendedShop(m)
        shop.system.deadlockDetectionEnabled = detection
        m.numberOfReplications = 5
        m.lengthOfReplication = 600.0
        m.simulate()
        return m to shop
    }

    @Test
    @DisplayName("Enabling detection changes no number anywhere in the model")
    fun detectionChangesNothing() {
        val (withDetection, busyShop) = run(detection = true)
        val (withoutDetection, quietShop) = run(detection = false)

        assertTrue(
            busyShop.system.numTransportersBlocked.withinReplicationStatistic.max > 0.0,
            "the model must actually block, or this test proves nothing about the one place the " +
                    "detector runs"
        )
        assertTrue(
            busyShop.completed > 0,
            "parts must actually be carried, or the run compared is an empty one"
        )
        assertEquals(
            busyShop.completed, quietShop.completed,
            "the same parts must get through either way"
        )

        val a = fingerprint(withDetection)
        val b = fingerprint(withoutDetection)
        assertTrue(
            a.size > 20,
            "the comparison must cover the whole model's output, but only ${a.size} statistics " +
                    "were found, which suggests the fingerprint is not reading what it should"
        )
        assertEquals(a.keys, b.keys, "the two runs must report the same set of statistics")
        for (name in a.keys) {
            val expected = a.getValue(name)
            val actual = b.getValue(name)
            for (i in expected.indices) {
                val e = expected[i]
                val g = actual[i]
                // Bit equality, not a tolerance: nothing about running an observer should perturb
                // a figure even in the last place.
                assertTrue(
                    e == g || (e.isNaN() && g.isNaN()),
                    "statistic ($name), element $i differs between a run with detection and one " +
                            "without: $e vs $g. The detector has mutated something it observes."
                )
            }
        }
    }

    @Test
    @DisplayName("The obstruction counter exists whether or not detection is on")
    fun theCounterIsAlwaysReported() {
        // A response that appeared only under one setting would make the shape of the report depend
        // on the flag, which is its own kind of divergence: a script reading the output would break
        // when the flag changed.
        val (withDetection, _) = run(detection = true)
        val (withoutDetection, _) = run(detection = false)
        val name = withDetection.counters.map { it.name }.first { it.endsWith("NumObstructionsDetected") }
        assertTrue(withoutDetection.counters.any { it.name == name })
    }
}
