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
package ksl.modeling.fleet

import ksl.modeling.agv.AgvSystem
import ksl.modeling.agv.AgvVehicle

import ksl.examples.general.guidedpath.SimpleAgvNetwork
import ksl.modeling.entity.ProcessModel
import ksl.modeling.guidedpath.TransporterPlacement
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.ResponseCIfc
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.ConstantRV
import ksl.utilities.random.rvariable.ExponentialRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 *  Vehicles that break down, and the one decision the whole feature turns on.
 *
 *  **A failure interrupts the tour. It does not revoke the assignment.** The vehicle keeps its load,
 *  is repaired where it stands, and resumes the tour from the stop it had reached. The obvious
 *  implementation is the other one -- hand the work back so somebody else can do it -- and it is
 *  wrong in a way that would not show up as an error: a broken-down vehicle would put a load back on
 *  the board while still physically holding it, and two vehicles would then believe they had it. The
 *  first two tests here are that claim, once with a load aboard and once without.
 *
 *  ## One mechanism, four bases
 *
 *  Clock-based and usage-based failures are the same statement with a different odometer: a failure
 *  is due once the chosen quantity has advanced by a draw since the last repair. Nothing schedules
 *  a failure, exactly as nothing schedules a battery's depletion; the thresholds are read at the
 *  vehicle's check points.
 *
 *  Two check points, and they need different machinery for the same reason a vehicle can only be
 *  stopped in two places. **At a zone boundary** the vehicle halts on the zone it has just entered,
 *  and because its agent is suspended in the space layer's movement queue and cannot delay for
 *  itself, the repair is an event that releases the halt. **Between tours** it has finished
 *  delivering and has not yet declared itself available, so it simply delays -- and nothing can be
 *  assigned to a vehicle under repair without anything having to check for that.
 *
 *  ## Why the basis is offered rather than chosen
 *
 *  Calendar time and operating time are not a refinement of one another. A fleet that is idle most
 *  of the time fails a materially different number of times by the two, and the test that asserts
 *  they *differ* is the one that says offering both was worth doing: a basis that changed nothing
 *  would be a parameter that does nothing, which this subsystem has already had to remove once.
 */
class FailureTest {

    private companion object {
        const val VELOCITY = 3.0

        fun mean(r: ResponseCIfc) = r.withinReplicationStatistic.weightedAverage
        fun total(c: CounterCIfc) = c.value
    }

    /** Samples the fleet on a fixed grid, which is how a state with no events of its own is seen. */
    private class Sampler(parent: ModelElement, val every: Double, val take: () -> Unit) :
        ModelElement(parent, "Sampler") {

        private val tick = object : EventActionIfc<Nothing> {
            override fun action(event: KSLEvent<Nothing>) {
                take()
                schedule(this, every)
            }
        }

        override fun initialize() {
            schedule(tick, every)
        }
    }

    /**
     *  The chapter's shop with one or two carts, either of which may be given a failure model.
     */
    private class Shop(
        parent: ModelElement,
        val failure: FailureModel?,
        meanInterarrival: Double,
        deterministicArrivals: Boolean = false,
        val secondCart: Boolean = false
    ) : ProcessModel(parent, "Shop") {
        val network = SimpleAgvNetwork.create()

        init {
            spatialModel = network
        }

        val agv = AgvSystem(this, network, name = "Agv")
        val cart = AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV1_HOME), ConstantRV(VELOCITY),
            name = "Cart", failureModel = failure
        ).apply { homeBase = SimpleAgvNetwork.AGV1_HOME }

        val cart2: AgvVehicle? = if (!secondCart) null else AgvVehicle(
            agv, TransporterPlacement.At(SimpleAgvNetwork.AGV2_HOME), ConstantRV(VELOCITY), name = "Cart2"
        ).apply { homeBase = SimpleAgvNetwork.AGV2_HOME }

        var completions = 0

        private inner class Part : Entity() {
            val move = process(isDefaultProcess = true) {
                currentLocation = network.requireLocation(SimpleAgvNetwork.ENTRY_STATION)
                transportByFleet(agv, SimpleAgvNetwork.EXIT_STATION, origin = SimpleAgvNetwork.ENTRY_STATION)
                completions++
            }
        }

        private val tba =
            if (deterministicArrivals) ConstantRV(meanInterarrival)
            else ExponentialRV(meanInterarrival, streamNum = 1)
        private val generator = EntityGenerator(::Part, tba, tba)

        override fun initialize() {
            completions = 0
        }
    }

    private fun run(
        name: String,
        horizon: Double,
        replications: Int = 1,
        build: (Model) -> Shop
    ): Shop {
        val m = Model(name)
        val s = build(m)
        m.numberOfReplications = replications
        m.lengthOfReplication = horizon
        m.simulate()
        return s
    }

    // ---- 1 and 2: what a failure does to the work in hand ---------------------------------------

    /**
     *  Runs a shop that fails often, and watches which tasks were in hand when it did.
     *
     *  A failure has no event a listener could be hung on -- that is the design -- so the fleet is
     *  sampled on a grid instead. A sample that catches the cart failed with a task IN_PROGRESS is
     *  a failure with the load aboard; one with the task ASSIGNED is a failure on the way to
     *  collect it. Both must end COMPLETED.
     */
    private class Caught {
        val withLoad = mutableMapOf<String, Dispatcher.Task>()
        val whileEmpty = mutableMapOf<String, Dispatcher.Task>()

        /**
         *  The task the vehicle was holding at the last sample before the horizon.
         *
         *  Recorded here because it cannot be read afterwards: `afterReplication` drops the
         *  per-replication agent, and with it the vehicle's assignment. Exactly one task may be
         *  left unfinished by a run that was cut off, and this is which one.
         */
        var lastInHand: Dispatcher.Task? = null
    }

    private fun failOften(): Pair<Shop, Caught> {
        val caught = Caught()
        val s = run("FailOften", horizon = 3000.0) { m ->
            val shop = Shop(
                m,
                FailureModel.distanceBased(ConstantRV(50.0), ConstantRV(4.0)),
                meanInterarrival = 90.0
            )
            Sampler(shop, 0.25) {
                val cart = shop.cart
                caught.lastInHand = cart.currentAssignment?.task ?: caught.lastInHand
                if (!cart.isFailed) return@Sampler
                val task = cart.currentAssignment?.task ?: return@Sampler
                when (task.state) {
                    TaskState.IN_PROGRESS -> caught.withLoad[task.name] = task
                    TaskState.ASSIGNED -> caught.whileEmpty[task.name] = task
                    else -> Unit
                }
            }
            shop
        }
        return s to caught
    }

    @Test
    @DisplayName("a vehicle that fails carrying a load keeps it, is repaired, and delivers it")
    fun failureWithALoadAboardIsAnInterruptionAndNotARevocation() {
        val (s, caught) = failOften()

        assertTrue(
            total(s.cart.numFailures!!) > 5.0,
            "the vehicle failed only ${total(s.cart.numFailures!!)} times, which is too few for " +
                    "this test to be exercising anything"
        )
        assertTrue(
            caught.withLoad.isNotEmpty(),
            "no sample caught the vehicle broken down with a load aboard, so the case this test " +
                    "exists for never arose"
        )
        // The claim. Every load that was aboard a vehicle when it broke down was still aboard when
        // the vehicle was repaired, and went on to be delivered -- with the single exception the
        // horizon always allows, which is the one the vehicle was still holding when the clock
        // stopped. Nothing else may be left open, and nothing at all may be cancelled.
        val atHorizon = caught.lastInHand
        val open = caught.withLoad.values.filter { it.state != TaskState.COMPLETED }
        assertTrue(
            open.all { it === atHorizon },
            "loads aboard a failed vehicle did not reach their destination: " +
                    open.joinToString { "${it.name}=${it.state}" }
        )
        // And nothing was handed back, which is the implementation this test is written against.
        assertEquals(
            0.0, total(s.agv.dispatcher.numAssignmentsRevoked), 0.0,
            "a failure revoked an assignment. A vehicle that breaks down keeps its load: handing " +
                    "the task back while still physically holding the load would let two vehicles " +
                    "believe they had it"
        )
        assertTrue(s.completions > 20, "only ${s.completions} loads were delivered")
    }

    @Test
    @DisplayName("a vehicle that fails on its way to collect still collects")
    fun failureWhileEmptyResumesTheSameApproach() {
        val (_, caught) = failOften()

        assertTrue(
            caught.whileEmpty.isNotEmpty(),
            "no sample caught the vehicle broken down on its way to a pickup"
        )
        val atHorizon = caught.lastInHand
        val open = caught.whileEmpty.values.filter { it.state != TaskState.COMPLETED }
        assertTrue(
            open.all { it === atHorizon },
            "loads whose vehicle broke down on the way to them were not collected: " +
                    open.joinToString { "${it.name}=${it.state}" }
        )
        // A revoked approach would show here as a CANCELLED task, and a lost one as a task stuck in
        // ASSIGNED with the vehicle long since somewhere else.
        assertTrue(
            caught.whileEmpty.values.none { it.state == TaskState.CANCELLED },
            "a load was cancelled after its vehicle broke down on the way to it"
        )
    }

    // ---- 3: the basis is a real choice ----------------------------------------------------------

    @Test
    @DisplayName("calendar and operating bases give different failure counts on an idle fleet")
    fun theBasisChangesTheAnswer() {
        fun failures(basis: FailureBasis): Double {
            val s = run("Basis$basis", horizon = 6000.0) { m ->
                Shop(
                    m,
                    FailureModel.clockBased(ConstantRV(120.0), ConstantRV(3.0), basis),
                    meanInterarrival = 600.0
                )
            }
            return total(s.cart.numFailures!!)
        }

        val calendar = failures(FailureBasis.CALENDAR_TIME)
        val operating = failures(FailureBasis.OPERATING_TIME)

        assertTrue(operating > 0.0, "the operating-time fleet never failed at all")
        // A cart that works a small fraction of a long run ages far faster by the wall clock than
        // by the hours it worked. If these came out equal the basis would be a parameter that does
        // nothing, and offering it would be worse than not having it.
        assertTrue(
            calendar > operating,
            "an idle fleet failed $calendar times on calendar time and $operating on operating " +
                    "time. The two bases are supposed to disagree on exactly this fleet"
        )
    }

    // ---- 4: usage is a count, not a time --------------------------------------------------------

    @Test
    @DisplayName("a usage-based vehicle fails after exactly N tasks, every time")
    fun usageBasedFailureIsACount() {
        val n = 3.0
        val s = run("Usage", horizon = 3000.0) { m ->
            Shop(
                m, FailureModel.usageBased(ConstantRV(n), ConstantRV(2.0)),
                meanInterarrival = 90.0, deterministicArrivals = true
            )
        }

        val completed = total(s.cart.numTasksCompleted)
        assertTrue(completed >= 9.0, "only $completed tasks were completed")
        // An identity, not an estimate. The threshold is a count and the check between tours runs
        // before the vehicle declares itself available again, so the failures fall at exactly the
        // 3rd, 6th, 9th task and nowhere else.
        assertEquals(
            floor(completed / n), total(s.cart.numFailures!!), 0.0,
            "a vehicle failing every $n tasks completed $completed of them and failed " +
                    "${total(s.cart.numFailures!!)} times"
        )
        // Out of service for exactly the repair, because the default policy adds nothing around it.
        // A policy that made somebody walk to the vehicle would move this and not the repair time,
        // which is the distinction the row is named for.
        assertEquals(
            2.0, mean(s.cart.timeOutOfService!!), 1.0e-12,
            "the vehicle was out of service for ${mean(s.cart.timeOutOfService!!)} against a repair " +
                    "time of the constant 2.0 it was given, under a policy that does nothing else"
        )
    }

    // ---- 5: a broken vehicle is an obstruction ---------------------------------------------------

    @Test
    @DisplayName("a vehicle under a long repair blocks the fleet behind it")
    fun aFailedVehicleObstructs() {
        val s = run("Obstruction", horizon = 3000.0) { m ->
            Shop(
                m, FailureModel.distanceBased(ConstantRV(60.0), ConstantRV(150.0)),
                meanInterarrival = 40.0, secondCart = true
            )
        }

        assertTrue(total(s.cart.numFailures!!) > 0.0, "the first cart never failed")
        // The second cart has no failure model at all, so anything that stopped it came from
        // outside itself. On a one-way loop, that is the first cart standing in the way.
        assertTrue(
            total(s.cart2!!.numTimesBlocked) > 0.0,
            "a healthy cart sharing a one-way loop with one that spent 150 time units per repair " +
                    "standing on it was never once blocked"
        )
    }

    // ---- 6: the horizon ---------------------------------------------------------------------------

    @Test
    @DisplayName("a vehicle under repair at the horizon is reported, so its load is not invisible")
    fun theHorizonReportsARepairInProgress() {
        val s = run("Horizon", horizon = 400.0) { m ->
            Shop(
                m, FailureModel.distanceBased(ConstantRV(40.0), ConstantRV(5000.0)),
                meanInterarrival = 30.0
            )
        }

        assertEquals(
            1.0, mean(s.agv.numVehiclesFailedAtHorizon), 0.0,
            "a vehicle whose repair takes longer than the whole replication was not reported as " +
                    "being under repair when it ended"
        )
        // Which is what the reader needs in order to interpret the other two rows correctly: the
        // assignment left open and the entity left suspended belong to the breakdown, not to a run
        // that was merely too short.
        assertTrue(
            mean(s.agv.numAssignmentsStillOpen) > 0.0 || mean(s.agv.numEntitiesNeverResumed) > 0.0,
            "the failed vehicle was reported but nothing it was holding was"
        )
    }

    // ---- 7: the replication boundary --------------------------------------------------------------

    @Test
    @DisplayName("failure state does not survive a replication boundary")
    fun threeReplicationsAreIdentical() {
        val s = run("Reset", horizon = 2000.0, replications = 3) { m ->
            Shop(
                m, FailureModel.usageBased(ConstantRV(2.0), ConstantRV(5.0)),
                meanInterarrival = 90.0, deterministicArrivals = true
            )
        }

        val across = s.cart.numFailures!!.acrossReplicationStatistic
        assertEquals(3.0, across.count, 0.0, "three replications did not produce three observations")
        assertTrue(across.average > 0.0, "no replication produced a failure")
        // Every input is constant, so the three replications are the same experiment. Any spread
        // here is state carried over a boundary -- a repair still pending, a threshold not redrawn,
        // an odometer not cleared -- which is exactly the class of defect a single replication
        // cannot show.
        assertEquals(
            0.0, across.standardDeviation, 0.0,
            "three identical replications of a deterministic model failed a different number of " +
                    "times: ${across.average} on average with a spread of ${across.standardDeviation}"
        )
        assertEquals(
            0.0, s.cart.fracTimeFailed!!.acrossReplicationStatistic.standardDeviation, 1.0e-12,
            "the fraction of time broken down differed between identical replications"
        )
    }

    // ---- configurations refused --------------------------------------------------------------------

    @Test
    @DisplayName("a clock-based model cannot be given a basis that is not a clock")
    fun aClockBasedModelMustMeasureTime() {
        val thrown = assertFailsWith<IllegalArgumentException> {
            FailureModel.clockBased(ConstantRV(10.0), ConstantRV(1.0), FailureBasis.DISTANCE_TRAVELLED)
        }
        assertTrue("must be OPERATING_TIME" in (thrown.message ?: ""), thrown.message ?: "")
    }
}
