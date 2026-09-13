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
import ksl.modeling.entity.HoldQueue
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.GuidedPathSpace
import ksl.modeling.guidedpath.GuidedPathTransportSystem
import ksl.modeling.guidedpath.GuidedTransporter
import ksl.modeling.guidedpath.GuidedTransporterPoolWithQ
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.guidedpath.Zone
import ksl.modeling.guidedpath.ZoneAllocation
import ksl.modeling.guidedpath.ZoneHoldActionIfc
import ksl.modeling.guidedpath.ZoneHolderIfc
import ksl.modeling.guidedpath.rules.ClosestByNetworkDistanceRule
import ksl.modeling.guidedpath.rules.EndOfZoneControl
import ksl.modeling.guidedpath.rules.ReturnToHomeBaseRule
import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.modeling.variable.Response
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.DUniformRV
import ksl.utilities.random.rvariable.ExponentialRV
import ksl.utilities.random.rvariable.LognormalRV
import ksl.utilities.statistic.MultipleComparisonAnalyzer
import ksl.utilities.statistic.Statistic

/**
 *  Guide-path space taken by things that are not vehicles: a spill, and a maintenance window.
 *
 *  The same shop as [SimpleAGVExample], on the same layout, run twice -- once clean and once with
 *  two ordinary disturbances on it. The point is the argument that the whole construct exists for:
 *
 *  **A model with no spills, no closures and no picking interference still has to match observed
 *  throughput.** So that time goes somewhere -- into inflated task times, or a depressed velocity --
 *  and the model then fits the aggregate while being wrong about the mechanism. It will give bad
 *  advice about any change that alters the obstruction rate, which is usually the change a study
 *  was commissioned to evaluate. Both configurations here are offered the same arrival load, and
 *  the run reports for itself which responses separate them and which do not -- read the
 *  `detectable?` column rather than this paragraph, because it depends on the rates below and
 *  those are there to be changed.
 *
 *  ## Two routes, one mechanism
 *
 *  Both ways of taking space are used here, side by side, because both are first-class.
 *
 *  **The spills are a process.** A spill is an entity: it arrives from a generator at a time and
 *  place nobody states in advance, seizes however much of an aisle it happens to cover, takes as
 *  long as it takes to clean, and releases. Any number of them can be in progress at once, which is
 *  the property that decided the design -- an entity is made at run time and cannot be a model
 *  element, so neither can a holder.
 *
 *  **The maintenance window is event-scheduled.** There is one crew, it exists for the whole run,
 *  and what it does is on a schedule rather than in a process. It closes a whole link at a time,
 *  taken together or not at all, and [MaintenanceWindow] is told when each closure begins and ends
 *  -- the end is what schedules the next one, so the model element that drives the closures is the
 *  one that acts on them.
 *
 *  ## Two spills in one aisle
 *
 *  A zone carries one promise at a time, so two closures cannot queue for the same zone. Spills
 *  land where they land, so two of them can want one zone, and this model's answer is that the
 *  second is *part of* the first -- one spill, counted as absorbed rather than cleaned. It is one
 *  answer of several: another model might defer the second until the first ends, or place it
 *  elsewhere. Only the model knows which it means, which is why the verb answers null rather than
 *  deciding for it.
 *
 *  The maintenance window needs no such guard, because nothing else here closes the link it
 *  maintains, so it uses the plain verb.
 *
 *  ## What to read in the output
 *
 *  `NumZonesClosed` is the mean amount of guide path denied to traffic, and `NumBlockedByOccupier`
 *  is the mean number of carts held up by it. Together with `NumBlockedByVehicle` they account for
 *  all of the blocked time, which is what turns one fitted fudge into separately observable
 *  quantities.
 */
object GuidePathDisturbancesExample {

    const val QUIET: String = "NoDisturbances"
    const val DISTURBED: String = "SpillsAndMaintenance"
    const val SYSTEM_NAME: String = "AgvSystem"
    const val REPLICATIONS: Int = 20
    const val HORIZON: Double = 8_000.0
    const val WARM_UP: Double = 1_000.0

    /** The link closed for maintenance: a leg of the one-way loop, six zones taken as one. */
    const val MAINTAINED_LINK: String = "Link2"

    /**
     *  The maintenance crew: a holder, and nothing else.
     *
     *  Two members and no base class, which is the whole of what taking guide-path space requires.
     *  It waits for nothing, because a set of zones is taken together or not at all -- it never
     *  queues on a zone, so it has no zone to name. A cart stopped behind a crew that is already
     *  holding the link is obstructed rather than deadlocked.
     */
    class MaintenanceCrew(id: Int) : ZoneHolderIfc {
        override val name: String = "MaintenanceCrew$id"
        override val awaitedZone: Zone? get() = null
    }

    /**
     *  Closes a whole link every so often, event-scheduled, and acts on the closure ending.
     *
     *  The model element that schedules the closures is the one told about them, which is what
     *  [ZoneHoldActionIfc] is for: the end of one window is what schedules the next, so there is no
     *  second copy of the window length kept anywhere to be added to a grant time nobody was told.
     *
     *  The closure is *asked for* on a schedule and *begins* when the link has drained, which is
     *  not the same instant -- a cart already on the link finishes crossing and leaves first.
     *  Nothing is evicted, and the delay is reported as `TimeToCloseZones` rather than hidden.
     */
    class MaintenanceWindow(
        parent: ModelElement,
        private val space: GuidedPathSpace,
        private val zones: () -> List<Zone>,
        timeBetween: Double,
        windowLength: Double
    ) : ModelElement(parent, "MaintenanceWindow"), ZoneHoldActionIfc {

        private val myTimeBetween = RandomVariable(
            this, ExponentialRV(timeBetween, streamNum = 4), name = "TimeBetweenWindows"
        )
        private val myWindowLength = RandomVariable(
            this, ConstantRV(windowLength), name = "WindowLength"
        )

        private val myWindowsOpened = Counter(this, name = "MaintenanceWindowsHeld")

        /** How many maintenance closures actually took effect. */
        val windowsHeld: CounterCIfc
            get() = myWindowsOpened

        private var nextCrewId = 1

        override fun initialize() {
            nextCrewId = 1
            schedule(myAskAction, myTimeBetween)
        }

        private val myAskAction = EventActionIfc<Nothing> {
            // A crew per occurrence, made here. Nothing about the cast is stated before the run.
            //
            // The plain verb, not tryHoldZonesFor, and that is a statement about this model rather
            // than a shortcut: nothing else closes the maintained link, so no zone of it can be
            // promised to anybody else when this asks. A model whose closures *can* collide has to
            // decide what that means, which is what tryHoldZonesFor is for.
            space.holdZonesFor(
                MaintenanceCrew(nextCrewId++), zones(), myWindowLength.value, this
            )
        }

        override fun holdBegan(allocation: ZoneAllocation) {
            myWindowsOpened.increment()
        }

        override fun holdEnded(allocation: ZoneAllocation) {
            // The end of one window schedules the next, which is the contract this interface is
            // for: whatever the closure was holding up proceeds from here.
            schedule(myAskAction, myTimeBetween)
        }
    }

    /**
     *  Parts carried from the entry station to the exit station, with spills on the loop.
     *
     *  @param parent the containing model element
     *  @param disturbed whether spills occur and maintenance windows are taken
     *  @param timeBtwArrivals the mean time between part arrivals, in minutes
     */
    class DisturbedShop(
        parent: ModelElement,
        disturbed: Boolean,
        timeBtwArrivals: Double = 20.0
    ) : ProcessModel(parent, "DisturbedShop") {

        val network: GuidedPathNetwork = SimpleAGVExample.createNetwork("DisturbedNet")

        init {
            spatialModel = network
        }

        val system = GuidedPathTransportSystem(this, network, name = SYSTEM_NAME)

        val cart1 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAGVExample.AGV1_HOME), ConstantRV(10.0), 1,
            EndOfZoneControl(), "Cart1"
        ).apply { homeBase = SimpleAGVExample.AGV1_HOME }

        val cart2 = GuidedTransporter(
            system, TransporterPlacement.At(SimpleAGVExample.AGV2_HOME), ConstantRV(10.0), 1,
            EndOfZoneControl(), "Cart2"
        ).apply { homeBase = SimpleAGVExample.AGV2_HOME }

        val carts = GuidedTransporterPoolWithQ(
            this, system, listOf(cart1, cart2),
            ClosestByNetworkDistanceRule(), ReturnToHomeBaseRule(), "Carts"
        )

        private val myTimeInSystem = Response(this, "TimeInSystem")
        val timeInSystem: ResponseCIfc
            get() = myTimeInSystem

        private val myCompleted = Counter(this, "PartsDelivered")
        val completed: CounterCIfc
            get() = myCompleted

        @Suppress("unused")
        private val generator = EntityGenerator(
            ::Part, ExponentialRV(timeBtwArrivals, streamNum = 1),
            ExponentialRV(timeBtwArrivals, streamNum = 1)
        )

        inner class Part : Entity() {
            @Suppress("unused")
            val delivery = process(isDefaultProcess = true) {
                val arrived = time
                currentLocation = network.requireLocation(SimpleAGVExample.ENTRY_STATION)
                guidedTransport(
                    carts,
                    destination = SimpleAGVExample.EXIT_STATION,
                    pickupLocation = SimpleAGVExample.ENTRY_STATION,
                    loadingDelay = ConstantRV(0.5),
                    unLoadingDelay = ConstantRV(0.5)
                )
                myTimeInSystem.value = time - arrived
                myCompleted.increment()
            }
        }

        // ---- the process route: spills, which arrive and are cleaned ---------------------------

        /** Where a spill waits while the zones it landed on finish draining. */
        val spillQ = HoldQueue(this, "SpillQ")

        // Three and four: the maintained link is left to the maintenance window, so the only
        // closures that can collide here are two spills in the same aisle.
        private val mySpillLink = RandomVariable(this, DUniformRV(3, 4, streamNum = 5), "SpillLink")
        private val mySpillExtent = RandomVariable(this, DUniformRV(1, 2, streamNum = 6), "SpillExtent")
        private val myCleanupTime = RandomVariable(
            this, LognormalRV(15.0, 20.0, streamNum = 7), "CleanupTime"
        )

        private val mySpillsCleaned = Counter(this, "SpillsCleaned")

        /** How many spills were cleaned up. */
        val spillsCleaned: CounterCIfc
            get() = mySpillsCleaned

        private val mySpillsAbsorbed = Counter(this, "SpillsAbsorbed")

        /** How many spills landed where a closure was already in place. */
        val spillsAbsorbed: CounterCIfc
            get() = mySpillsAbsorbed

        /**
         *  A spill is an entity, and that is the whole reason a holder cannot be a model element.
         *
         *  Where it lands, how much of the aisle it covers and how long it takes to clean are all
         *  drawn here, at run time, and any number of spills may be in progress at once.
         */
        inner class Spill : Entity() {
            @Suppress("unused")
            val cleanup = process(isDefaultProcess = true) {
                val link = network.link("Link${mySpillLink.value.toInt()}")!!
                val extent = link.zones.take(mySpillExtent.value.toInt())
                // A zone carries one promise at a time, so a spill landing on an aisle another
                // spill is still having closed cannot queue behind it. This model's answer is that
                // the second is part of the first -- one spill, cleaned once. Deferring it or
                // placing it elsewhere would be equally reasonable; the choice belongs here.
                //
                // A zone a *cart* is on is not a collision and needs no guard: that is the ordinary
                // case, and the call below waits for the cart to finish crossing and leave.
                if (trySeizeZones(system, extent, spillQ) == null) {
                    mySpillsAbsorbed.increment()
                    return@process
                }
                delay(myCleanupTime)
                releaseZones(system)
                mySpillsCleaned.increment()
            }
        }

        @Suppress("unused")
        private val spills = if (disturbed) {
            EntityGenerator(
                ::Spill, ExponentialRV(90.0, streamNum = 2), ExponentialRV(90.0, streamNum = 2)
            )
        } else {
            null
        }

        // ---- the event route: a maintenance window on a whole link ------------------------------

        @Suppress("unused")
        private val maintenance = if (disturbed) {
            MaintenanceWindow(
                this, system, { network.link(MAINTAINED_LINK)!!.zones },
                timeBetween = 300.0, windowLength = 30.0
            )
        } else {
            null
        }
    }

    /**
     *  One scenario per configuration, with the same replications, horizon, warm-up and arrival
     *  stream, because the only thing being compared is whether the guide path is disturbed.
     */
    fun buildRunner(): ScenarioRunner {
        val runner = ScenarioRunner("GuidePathDisturbances")
        // Disturbed first, because MultipleComparisonAnalyzer keys each paired difference
        // "first - second" in the order the scenarios were added, and the reading that makes sense
        // here is what the disturbances cost rather than what their absence saves.
        for ((label, disturbed) in listOf(DISTURBED to true, QUIET to false)) {
            val m = Model("Disturbances_$label")
            DisturbedShop(m, disturbed = disturbed)
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

fun main() {
    val runner = GuidePathDisturbancesExample.buildRunner()
    runner.simulate()
    runner.print()

    val quiet = GuidePathDisturbancesExample.QUIET
    val disturbed = GuidePathDisturbancesExample.DISTURBED
    val sys = GuidePathDisturbancesExample.SYSTEM_NAME

    println()
    println("What the disturbances cost: $disturbed minus $quiet, paired by replication")
    println("(${GuidePathDisturbancesExample.REPLICATIONS} replications, 95% intervals)")
    println()
    println("  %-44s %12s %12s %12s".format("response", "difference", "half-width", "detectable?"))
    val differences = mutableMapOf<String, Double>()
    for (response in listOf(
        "PartsDelivered",
        "TimeInSystem",
        "$sys:NumTransportersBlocked",
        "$sys:NumBlockedByVehicle",
        "$sys:NumBlockedByOccupier",
        "$sys:NumBlockedByPopulation",
        "$sys:NumZonesClosed"
    )) {
        val observations = runner.observationsAsMap(response)
        check(observations.size == 2) {
            "expected per-replication observations of $response for both scenarios, got " +
                "${observations.keys}. A missing response would print an empty row rather than say so."
        }
        val mca = MultipleComparisonAnalyzer(observations, response)
        val d = checkNotNull(mca.pairedDifferenceStatistic(disturbed, quiet)) {
            "no paired difference for '$disturbed - $quiet' of $response"
        }
        val detectable = if (kotlin.math.abs(d.average) > d.halfWidth) "yes" else "no"
        differences[response] = d.average
        println("  %-44s %12.4f %12.4f %12s".format(response, d.average, d.halfWidth, detectable))
    }

    // The decomposition is a claim, so it is checked here rather than left to the reader. The three
    // causes must account for all of the blocked time: a residue would mean a cart was held up by
    // something nobody is naming, which is precisely the condition this construct exists to end.
    // The disturbance counters have no counterpart in the quiet configuration -- the maintenance
    // window does not exist there at all -- so they are reported as levels rather than as paired
    // differences. A paired difference against a scenario that has no such element would be a
    // comparison with nothing.
    println()
    println("What the disturbances did, in $disturbed (mean per replication)")
    println()
    for (counter in listOf(
        "SpillsCleaned", "SpillsAbsorbed", "MaintenanceWindowsHeld"
    )) {
        val observations = runner.observationsAsMap(counter)
        val values = checkNotNull(observations[disturbed]) {
            "expected per-replication observations of $counter for $disturbed, got " +
                "${observations.keys}"
        }
        println("  %-44s %12.2f".format(counter, Statistic(values).average))
    }
    println()
    println("  SpillsAbsorbed counts the spills that landed on an aisle another spill was still")
    println("  having closed. A zone carries one promise at a time, so the request has to be able")
    println("  to come back empty-handed, and what to do about that belongs to the model rather")
    println("  than to the guide path. This model absorbs them; deferring them until the aisle")
    println("  reopens, or placing them elsewhere, would be equally reasonable readings.")

    val total = differences.getValue("$sys:NumTransportersBlocked")
    val parts = differences.getValue("$sys:NumBlockedByVehicle") +
            differences.getValue("$sys:NumBlockedByOccupier") +
            differences.getValue("$sys:NumBlockedByPopulation")
    println()
    println("  %-44s %12.4f".format("blocked time, by cause, summed", parts))
    println("  %-44s %12.4f".format("unaccounted for", total - parts))
    check(kotlin.math.abs(total - parts) < 1.0e-9) {
        "the blocked-time decomposition left ${total - parts} unaccounted for, which means a cart " +
            "was held up by something none of the three causes names"
    }

    println()
    println("  Read NumBlockedByOccupier and NumZonesClosed together. The quiet configuration has")
    println("  no mechanism for either, so both are exactly zero there and every minute of cart")
    println("  obstruction in it is a cart waiting on another cart. In the disturbed configuration")
    println("  that same total splits into separately observable quantities, and none of them had")
    println("  to be fitted.")
    println()
    println("  That is the argument for the construct, and it does not depend on whether the")
    println("  headline count separates the two runs. A shop that really has spills and closures,")
    println("  modelled without them, must still reproduce the throughput it was calibrated on --")
    println("  so the missing obstruction time ends up inside task times or vehicle speed, where it")
    println("  is invisible and where it will not respond to the change a study is evaluating.")
}
