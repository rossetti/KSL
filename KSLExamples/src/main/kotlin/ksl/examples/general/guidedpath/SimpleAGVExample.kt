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

package ksl.examples.general.guidedpath

import ksl.controls.experiments.ScenarioRunner
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.LinkType
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ParkInPlaceRule
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.RandomVariableCIfc
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.statistic.MultipleComparisonAnalyzer

/**
 *  The simple automated-guided-vehicle example from the chapter on entity movement and material
 *  handling: two carts on a one-way loop carrying parts from an entry station to an exit station at
 *  the end of a spur.
 *
 *  The layout is built here rather than imported, because the layout *is* the lesson. Four things
 *  about it are worth reading before the code:
 *
 *  **The main loop runs one way.** That is what makes head-on deadlock impossible rather than
 *  merely unlikely: two carts on this loop can queue behind one another but can never face each
 *  other. A two-way aisle would be shorter to draw and would introduce the one failure mode this
 *  layout is designed not to have.
 *
 *  **The exit station is at the end of a spur.** A spur admits one cart at a time. The second cart
 *  sent there waits at the spur's mouth, out on the loop, rather than following the first in — a
 *  cart that entered behind another would face it with neither able to reverse.
 *
 *  **Each cart has a parking spur of its own.** A stopped cart goes on holding the zones it stands
 *  on, so where a fleet idles decides how much of the guide path is unavailable to everybody else.
 *  This is the single most likely way for a working-looking model to be quietly wrong, and the
 *  second scenario below demonstrates it: with the carts left where they stop, the first delivery
 *  parks on the exit station, the only way off the exit spur, and every later delivery stops at the
 *  mouth for the rest of the run. Nothing raises. The run simply stops moving.
 *
 *  **The zone sizes differ between links.** The loop is discretized at twelve feet, chosen so that
 *  two six-foot carts cannot close to less than six feet while moving. The home spurs are only six
 *  feet long — exactly one cart, and half a loop zone — so they get a zone size of their own. Zone
 *  size belongs to a link rather than to a network precisely so a layout like this is expressible.
 *
 *  ## How the comparison is run
 *
 *  The two configurations are two [ksl.controls.experiments.Scenario]s inside a [ScenarioRunner],
 *  which gives both the same run parameters, prints a half-width summary report for each, and hands
 *  back per-replication observations for a paired comparison. Throughput here is arrival-limited,
 *  so the two configurations deliver the same load and the damage shows up only in the obstruction
 *  count — which is the point of the example, and is why that count is in the standard report
 *  rather than only in a log.
 *
 *  Parts arrive at the entry station and are carried to the exit station by whichever cart is
 *  free. The vocabulary the model is written in — the station names, the two zone lengths and the
 *  layout itself — is in the companion object, so a caller can name a place, or reuse the layout,
 *  without repeating a string.
 *
 *  @param parent the containing model element
 *  @param sendCartsHome whether an idle cart returns to its own spur or stays where it stopped
 *  @param timeBtwArrivals the mean time between part arrivals, in minutes
 */
class SimpleAGVExample(
    parent: ModelElement,
    sendCartsHome: Boolean = true,
    timeBtwArrivals: Double = 20.0
) : ProcessModel(parent, "AgvShop") {

    companion object {

        const val LOOP_ZONE_LENGTH: Double = 12.0
        const val HOME_SPUR_ZONE_LENGTH: Double = 6.0
        const val ENTRY_STATION: String = "EntryStation"
        const val EXIT_STATION: String = "ExitStation"
        const val AGV1_HOME: String = "I6"
        const val AGV2_HOME: String = "I7"
        const val SYSTEM_NAME: String = "AgvSystem"

        /**
         *  Builds the guide path. Coordinates place `I4` at the origin with the loop above and to the
         *  right of it; they drive layout and animation only, never routing, which uses the declared
         *  link lengths.
         *
         *  The loop is one-way, so distances are not symmetric. Entry to exit runs the long way round,
         *  `I1` to `I2` to `I3` to `I4` and down the spur: 204 feet. The return from the exit is only
         *  108, because the spur is two-way and `Link4` carries the cart straight back up to `I1`.
         */
        fun createNetwork(networkName: String = "SimpleAgvNetwork"): GuidedPathNetwork =
            GuidedPathNetwork.builder(networkName)
                .intersection("I1", x = 0.0, y = 72.0)
                .intersection("I2", x = 48.0, y = 72.0)
                .intersection("I3", x = 48.0, y = 0.0)
                .intersection("I4", x = 0.0, y = 0.0)
                .intersection("I5", x = 0.0, y = -36.0)
                .intersection("I6", x = 54.0, y = 72.0)
                .intersection("I7", x = 54.0, y = 0.0)
                .link("Link1", "I1", "I2", length = 48.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 0.0)
                .link("Link2", "I2", "I3", length = 72.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 270.0)
                .link("Link3", "I3", "I4", length = 48.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 180.0)
                .link("Link4", "I4", "I1", length = 72.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 90.0)
                .link(
                    "Spur", "I4", "I5", length = 36.0, zoneLength = LOOP_ZONE_LENGTH,
                    type = LinkType.SPUR, beginDirection = 270.0
                )
                .link(
                    "Link5", "I2", "I6", length = 6.0, zoneLength = HOME_SPUR_ZONE_LENGTH,
                    type = LinkType.SPUR, beginDirection = 0.0
                )
                .link(
                    "Link6", "I3", "I7", length = 6.0, zoneLength = HOME_SPUR_ZONE_LENGTH,
                    type = LinkType.SPUR, beginDirection = 0.0
                )
                .station(ENTRY_STATION, "I1")
                .station(EXIT_STATION, "I5")
                .build()

        const val REPLICATIONS: Int = 10
        const val HORIZON: Double = 8_000.0
        const val WARM_UP: Double = 1_000.0

        const val SENT_HOME: String = "CartsSentHome"
        const val LEFT_IN_PLACE: String = "CartsLeftInPlace"

        /**
         *  One scenario per configuration. Both get the same replications, horizon, warm-up and arrival
         *  stream, because the only thing being compared is where an idle cart waits and any difference
         *  in the run settings would swamp it.
         */
        fun buildRunner(): ScenarioRunner {
            val runner = ScenarioRunner("SimpleAgvHomeBases")
            for ((label, sendHome) in listOf(SENT_HOME to true, LEFT_IN_PLACE to false)) {
                val m = Model("SimpleAGV_$label")
                SimpleAGVExample(m, sendCartsHome = sendHome)
                runner.addScenario(
                    model = m,
                    name = label,
                    inputs = emptyMap(),
                    numberReplications = REPLICATIONS,
                    lengthOfReplication = HORIZON,
                    lengthOfReplicationWarmUp = WARM_UP
                )
            }
            return runner
        }
    }

    val network: GuidedPathNetwork = createNetwork()

    init {
        // The parts travel on the guide path, so it is their spatial model too.
        spatialModel = network
    }

    val system = GuidedPathTransportSystem(this, network, name = SYSTEM_NAME)

    val cart1 = GuidedTransporter(
        system, TransporterPlacement.At(AGV1_HOME), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart1"
    ).apply { homeBase = AGV1_HOME }

    val cart2 = GuidedTransporter(
        system, TransporterPlacement.At(AGV2_HOME), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart2"
    ).apply { homeBase = AGV2_HOME }

    val carts = GuidedTransporterPoolWithQ(
        this, system, listOf(cart1, cart2),
        ClosestByNetworkDistanceRule(),
        if (sendCartsHome) ReturnToHomeBaseRule() else ParkInPlaceRule(),
        "Carts"
    )

    private val myTimeInSystem = Response(this, "TimeInSystem")
    val timeInSystem: ResponseCIfc
        get() = myTimeInSystem

    private val myCompleted = Counter(this, "PartsDelivered")
    val completed: CounterCIfc
        get() = myCompleted

    private val myLoadingTime = RandomVariable(this, ConstantRV(0.5), name = "LoadingTime")
    val loadingTimeRV: RandomVariableCIfc
        get() = myLoadingTime

    private val myUnLoadingTime = RandomVariable(this, ConstantRV(0.5), name = "UnLoadingTime")
    val unLoadingTimeRV: RandomVariableCIfc
        get() = myUnLoadingTime

    @Suppress("unused")
    private val generator = EntityGenerator(
        ::Part, ExponentialRV(timeBtwArrivals, streamNum = 1),
        ExponentialRV(timeBtwArrivals, streamNum = 1)
    )

    inner class Part : Entity() {
        @Suppress("unused")
        val delivery = process(isDefaultProcess = true) {
            val arrived = time
            currentLocation = network.requireLocation(ENTRY_STATION)
            guidedTransport(
                carts,
                destination = EXIT_STATION,
                pickupLocation = ENTRY_STATION,
                loadingDelay = myLoadingTime,
                unLoadingDelay = myUnLoadingTime
            )
            myTimeInSystem.value = time - arrived
            myCompleted.increment()
        }
    }
}

fun main() {
    val runner = SimpleAGVExample.buildRunner()
    runner.simulate()
    runner.print()

    val home = SimpleAGVExample.SENT_HOME
    val parked = SimpleAGVExample.LEFT_IN_PLACE
    println()
    println("Where an idle cart waits: $home minus $parked, paired by replication")
    println("(${SimpleAGVExample.REPLICATIONS} replications, 95% intervals)")
    println()
    println("  %-40s %12s %12s %12s".format("response", "difference", "half-width", "detectable?"))
    for (response in listOf(
        "PartsDelivered",
        "TimeInSystem",
        "${SimpleAGVExample.SYSTEM_NAME}:NumObstructionsDetected",
        "${SimpleAGVExample.SYSTEM_NAME}:NumTransportersBlocked"
    )) {
        val observations = runner.observationsAsMap(response)
        check(observations.size == 2) {
            "expected per-replication observations of $response for both scenarios, got " +
                "${observations.keys}. A missing response would print an empty row rather than say so."
        }
        val mca = MultipleComparisonAnalyzer(observations, response)
        val d = checkNotNull(mca.pairedDifferenceStatistic(home, parked)) {
            "no paired difference for '$home - $parked' of $response"
        }
        val detectable = if (kotlin.math.abs(d.average) > d.halfWidth) "yes" else "no"
        println("  %-40s %12.4f %12.4f %12s".format(response, d.average, d.halfWidth, detectable))
    }
}
