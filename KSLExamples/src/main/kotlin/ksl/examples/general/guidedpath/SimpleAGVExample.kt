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

import ksl.controls.ControlType
import ksl.controls.KSLControl
import ksl.controls.experiments.ScenarioRunner
import ksl.modeling.elements.EventGeneratorRVCIfc
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
import java.io.PrintWriter

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
 *  sent there waits at the spur's mouth, out on the loop, rather than following the first in -- a
 *  cart that entered behind another would face it with neither able to reverse.
 *
 *  **Each cart has a parking spur of its own.** A stopped cart goes on holding the zones it stands
 *  on, so where a fleet idles decides how much of the guide path is unavailable to everybody else.
 *  The second scenario in `main` leaves the carts where they stop, and every obstruction it
 *  reports is on `I4`, the junction at the mouth of the exit spur: one cart stands there with
 *  nothing to do and the other is refused it, the two taking turns about forty times a
 *  replication. None of those waits lasts. Parts keep arriving, each arrival dispatches the parked
 *  cart, and both configurations deliver the same load; the whole cost is time in system. The
 *  reason to care is the shop this one is not. Let the arrivals stop, or thin out, and the same
 *  reading becomes a fleet that has stopped -- which raises nothing either, and which the guide
 *  path reports separately by naming the transporters still waiting when the replication ends.
 *
 *  **The zone sizes differ between links.** The loop is discretized at twelve feet, chosen so that
 *  two six-foot carts cannot close to less than six feet while moving. The home spurs are only six
 *  feet long -- exactly one cart, and half a loop zone -- so they get a zone size of their own.
 *  Zone size belongs to a link rather than to a network precisely so a layout like this is
 *  expressible.
 *
 *  ## How the comparison is run
 *
 *  [sendCartsHome] is the one thing the study varies, and it is a **control**, so the comparison in
 *  `main` is two [ksl.controls.experiments.Scenario]s over one model rather than two models. Both
 *  get the same run parameters and the same arrival stream, so the right reading is the paired
 *  difference, replication by replication.
 *
 *  Throughput here is arrival-limited, so the two configurations deliver the same load and the
 *  damage shows up only in the obstruction count -- which is the point of the example, and is why
 *  that count is in the standard report rather than only in a log.
 *
 *  @param parent the containing model element
 *  @param name the name of the model element
 */
class SimpleAGVExample(
    parent: ModelElement,
    name: String? = null
) : ProcessModel(parent, name) {

    private val entryStation = "EntryStation"
    private val exitStation = "ExitStation"
    private val agv1Home = "I6"
    private val agv2Home = "I7"

    // A zone is the unit of exclusion: one zone holds one vehicle. Twelve feet on the loop keeps
    // two six-foot carts a cart's length apart; a home spur is one cart long and gets its own size.
    private val loopZoneLength = 12.0
    private val homeSpurZoneLength = 6.0

    private val network: GuidedPathNetwork = buildNetwork()

    init {
        // The parts travel on the guide path, so it is their spatial model too.
        spatialModel = network
    }

    /**
     *  The guide path's runtime: zone occupancy, the movement of the carts, and every statistic the
     *  report below is drawn from.
     *
     *  Public and concrete, as a [ksl.modeling.entity.Conveyor] is. A transport system is a
     *  **substrate** rather than a resource -- nothing seizes it, models ask it for space and read
     *  its statistics -- so it has no controlled-access interface and needs none. The things a model
     *  does hold by contract are the transporter and the pool, which do:
     *  [ksl.modeling.guidedpath.GuidedTransporterCIfc] and
     *  [ksl.modeling.guidedpath.GuidedTransporterPoolCIfc].
     */
    val system = GuidedPathTransportSystem(this, network, name = "AgvSystem")

    private val cart1 = GuidedTransporter(
        system, TransporterPlacement.At(agv1Home), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart1"
    ).apply { homeBase = agv1Home }

    private val cart2 = GuidedTransporter(
        system, TransporterPlacement.At(agv2Home), ConstantRV(10.0), 1, EndOfZoneControl(), "Cart2"
    ).apply { homeBase = agv2Home }

    private val carts = GuidedTransporterPoolWithQ(
        this, system, listOf(cart1, cart2),
        ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Carts"
    )

    /**
     *  Whether an idle cart returns to its own spur or stays where it stopped.
     *
     *  The pool reads its disposition rule when a cart is released, so this takes effect from the
     *  next release; the rules carry no state, so nothing needs resetting between replications.
     */
    @set:KSLControl(controlType = ControlType.BOOLEAN)
    var sendCartsHome: Boolean = true
        set(value) {
            carts.idleDispositionRule = if (value) ReturnToHomeBaseRule() else ParkInPlaceRule()
            field = value
        }

    // Named, so that its own TimeBtwEventsRV has a stable key a scenario or a catalog can name.
    private val tba = ExponentialRV(20.0, 1)
    private val myArrivalGenerator = EntityGenerator(::Part, tba, tba, name = "PartArrivals")
    val generator: EventGeneratorRVCIfc
        get() = myArrivalGenerator

    // Handling is deterministic and deliberately small. Half a minute to put a part on the cart
    // and half a minute to take it off is one minute against a loaded move of 204 feet at 10 feet
    // per minute -- about five percent of the journey. That is the proportion this example is
    // built to have: the queueing a reader sees here comes from carts waiting on zones, not from
    // waiting at the stations, so a constant keeps handling out of the way of what is being shown.
    // It is still a real quantity rather than a placeholder, and the catalog nominates it for
    // exactly that reason: sweeping its `value` asks what faster handling would buy the shop.
    private val myLoadingTime = RandomVariable(this, ConstantRV(0.5), name = "LoadingTime")
    val loadingTimeRV: RandomVariableCIfc
        get() = myLoadingTime

    private val myUnLoadingTime = RandomVariable(this, ConstantRV(0.5), name = "UnLoadingTime")
    val unLoadingTimeRV: RandomVariableCIfc
        get() = myUnLoadingTime

    private val myTimeInSystem: Response = Response(this, "TimeInSystem")
    val timeInSystem: ResponseCIfc
        get() = myTimeInSystem

    private val myCompleted: Counter = Counter(this, "PartsDelivered")
    val completed: CounterCIfc
        get() = myCompleted

    /**
     *  Builds the guide path. Coordinates place `I4` at the origin with the loop above and to the
     *  right of it; they drive layout and animation only, never routing, which uses the declared
     *  link lengths.
     *
     *  The loop is one-way, so distances are not symmetric. Entry to exit runs the long way round,
     *  `I1` to `I2` to `I3` to `I4` and down the spur: 204 feet. The return from the exit is only
     *  108, because the spur is two-way and `Link4` carries the cart straight back up to `I1`.
     */
    private fun buildNetwork(): GuidedPathNetwork =
        GuidedPathNetwork.builder("SimpleAgvNetwork")
            .intersection("I1", x = 0.0, y = 72.0)
            .intersection("I2", x = 48.0, y = 72.0)
            .intersection("I3", x = 48.0, y = 0.0)
            .intersection("I4", x = 0.0, y = 0.0)
            .intersection("I5", x = 0.0, y = -36.0)
            .intersection("I6", x = 54.0, y = 72.0)
            .intersection("I7", x = 54.0, y = 0.0)
            .link("Link1", "I1", "I2", length = 48.0, zoneLength = loopZoneLength, beginDirection = 0.0)
            .link("Link2", "I2", "I3", length = 72.0, zoneLength = loopZoneLength, beginDirection = 270.0)
            .link("Link3", "I3", "I4", length = 48.0, zoneLength = loopZoneLength, beginDirection = 180.0)
            .link("Link4", "I4", "I1", length = 72.0, zoneLength = loopZoneLength, beginDirection = 90.0)
            .link(
                "Spur", "I4", "I5", length = 36.0, zoneLength = loopZoneLength,
                type = LinkType.SPUR, beginDirection = 270.0
            )
            .link(
                "Link5", "I2", "I6", length = 6.0, zoneLength = homeSpurZoneLength,
                type = LinkType.SPUR, beginDirection = 0.0
            )
            .link(
                "Link6", "I3", "I7", length = 6.0, zoneLength = homeSpurZoneLength,
                type = LinkType.SPUR, beginDirection = 0.0
            )
            .station(entryStation, "I1")
            .station(exitStation, "I5")
            .build()

    private inner class Part : Entity("Part") {
        val delivery = process(isDefaultProcess = true) {
            val arrived = time
            currentLocation = network.requireLocation(entryStation)
            guidedTransport(
                carts,
                destination = exitStation,
                pickupLocation = entryStation,
                loadingDelay = myLoadingTime,
                unLoadingDelay = myUnLoadingTime
            )
            myTimeInSystem.value = time - arrived
            myCompleted.increment()
        }
    }
}

fun main() {
    val model = Model("SimpleAGV")
    SimpleAGVExample(model, name = "AgvShop")
    model.numberOfReplications = 10
    model.lengthOfReplication = 8000.0
    model.lengthOfReplicationWarmUp = 1000.0
    // One model serves both scenarios, so the streams must be sent back to the start for the
    // second one. Without this the scenarios see different arrivals, the arrival variability stops
    // cancelling, and the paired half-widths below widen by more than an order of magnitude.
    model.resetStartStreamOption = true

    // One model, one control. Both scenarios draw arrivals from the same stream, so the arrival
    // variability cancels in the paired difference below.
    val sentHome = "CartsSentHome"
    val leftInPlace = "CartsLeftInPlace"
    val runner = ScenarioRunner("SimpleAgvHomeBases")
    runner.addScenario(model, name = sentHome, inputs = mapOf("AgvShop.sendCartsHome" to 1.0))
    runner.addScenario(model, name = leftInPlace, inputs = mapOf("AgvShop.sendCartsHome" to 0.0))
    runner.simulate()
    // An autoflush writer: print() builds an unflushed one internally, whose output can be lost.
    runner.write(PrintWriter(System.out, true))

    println()
    println("Where an idle cart waits: $sentHome minus $leftInPlace, paired by replication")
    println("(${model.numberOfReplications} replications, 95% intervals)")
    println()
    println("  %-40s %12s %12s %12s".format("response", "difference", "half-width", "detectable?"))
    for (response in listOf(
        "PartsDelivered",
        "TimeInSystem",
        "AgvSystem:NumObstructionsDetected",
        "AgvSystem:NumBlockedByIdleVehicle",
        "AgvSystem:NumTransportersBlocked"
    )) {
        val observations = runner.observationsAsMap(response)
        check(observations.size == 2) {
            "expected per-replication observations of $response for both scenarios, got " +
                "${observations.keys}. A missing response would print an empty row rather than say so."
        }
        val mca = MultipleComparisonAnalyzer(observations, response)
        val d = checkNotNull(mca.pairedDifferenceStatistic(sentHome, leftInPlace)) {
            "no paired difference for '$sentHome - $leftInPlace' of $response"
        }
        val detectable = if (kotlin.math.abs(d.average) > d.halfWidth) "yes" else "no"
        println("  %-40s %12.4f %12.4f %12s".format(response, d.average, d.halfWidth, detectable))
    }
}
