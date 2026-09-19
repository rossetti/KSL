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
package ksl.modeling.spatial

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  **The contract every movement substrate must keep, written once.**
 *
 *  `VehicleMovementIfc` is what a fleet needs from whatever moves its vehicles, and its KDoc states
 *  six things in prose. This is where those sentences are enforced instead of merely stated. A
 *  substrate joins by subclassing and supplying [scenario]; it then either keeps the contract or
 *  says so out loud.
 *
 *  **Why this exists before there is more than one implementation.** The seam's whole purpose is
 *  that the machinery above it -- tours, dispatching, the manifest -- runs over more than one way of
 *  moving. A contract asserted against a single implementation is a description of that
 *  implementation; the value arrives when the second one runs the same tests unchanged and either
 *  passes or exposes a sentence that was true only of the first.
 *
 *  **The tests are about the shape of a journey, not about a network.** None of them mentions a
 *  zone, an interpolation step, or a delay. Where a substrate must be asked to do something in its
 *  own way -- stopping a vehicle part way -- the subclass supplies it, and the property asserted
 *  afterwards is the same either way.
 */
abstract class VehicleMovementConformance {

    /**
     *  One run of a substrate under test.
     *
     *  @param movement the vehicle, as the fleet sees it
     *  @param start where it begins
     *  @param near somewhere it can reach, closer than [far]
     *  @param far somewhere it can reach, further than [near], and not by way of [near]
     */
    protected class Scenario(
        val movement: VehicleMovementIfc,
        val start: LocationIfc,
        val near: LocationIfc,
        val far: LocationIfc
    )

    /** What a driver did, recorded so the assertions can be made after the run rather than inside it. */
    protected class Trace {
        val positions = mutableListOf<LocationIfc>()
        val remainingToTarget = mutableListOf<Double>()
        val odometer = mutableListOf<Double>()
        var arrivedAt: LocationIfc? = null
        var haltedAt: LocationIfc? = null
        var distanceAtHalt: Double = Double.NaN
        var finalOdometer: Double = Double.NaN
        var startedAlreadyThere: Boolean = false
    }

    /**
     *  Builds and runs a scenario.
     *
     *  @param haltBeforeArrival when true, the substrate must arrange for the vehicle to stop short
     *    of its destination in whatever way it stops vehicles -- a gate at a boundary, a cancelled
     *    travel, an interrupted move. The contract is the same afterwards however it was done
     *  @param redirect when true, the driver must issue a second journey, to `near`, while the first
     *    to `far` is under way
     *  @param goNowhere when true, the driver must ask to travel to where it already is
     */
    protected abstract fun scenario(
        haltBeforeArrival: Boolean = false,
        redirect: Boolean = false,
        goNowhere: Boolean = false
    ): Pair<Scenario, Trace>

    /**
     *  Same place, by the substrate's own reckoning.
     *
     *  **Not by name**, and the difference is a Gate 4 finding rather than a detail. A guide path
     *  has named places, so a location's name identifies it; a continuous projection has
     *  coordinates, and a location built for "where the vehicle is now" is a fresh object with a
     *  generated name that names nothing. Comparing names asserted a property of the first
     *  substrate. `isLocationEqualTo` asks the spatial model whether the two are the same place,
     *  which is what these tests always meant.
     */
    private fun samePlace(a: LocationIfc?, b: LocationIfc?): Boolean =
        a != null && b != null && a.isLocationEqualTo(b)

    @Test
    @DisplayName("A vehicle sent somewhere gets there, and says it is not halted")
    fun itArrives() {
        val (s, t) = scenario()
        assertTrue(
            samePlace(t.arrivedAt, s.far),
            "the journey ended at (${t.arrivedAt?.name}) rather than where it was sent " +
                    "(${s.far.name})"
        )
        assertFalse(s.movement.isHalted, "a vehicle that arrived is not halted")
        assertEquals(
            0.0, s.movement.pathDistanceTo(s.far), 1e-9,
            "having arrived, the distance still to go must be zero"
        )
    }

    @Test
    @DisplayName("A vehicle asked to go where it already is starts no journey")
    fun goingNowhereStartsNothing() {
        val (_, t) = scenario(goNowhere = true)
        assertTrue(
            t.startedAlreadyThere,
            "beginTravelTo must return null rather than a queue nothing will ever wake"
        )
    }

    @Test
    @DisplayName("Distance still to go never increases while travelling toward it")
    fun progressIsMonotone() {
        val (_, t) = scenario()
        assertTrue(t.remainingToTarget.size >= 3, "the trace is too short to say anything")
        for (i in 1 until t.remainingToTarget.size) {
            assertTrue(
                t.remainingToTarget[i] <= t.remainingToTarget[i - 1] + 1e-9,
                "distance to the target grew from ${t.remainingToTarget[i - 1]} to " +
                        "${t.remainingToTarget[i]}: a vehicle travelling toward somewhere must not " +
                        "get further from it, and a substrate reporting a stale position would"
            )
        }
        assertTrue(
            t.remainingToTarget.first() > t.remainingToTarget.last(),
            "nothing moved: the trace shows the same distance throughout"
        )
    }

    @Test
    @DisplayName("A journey is observable while it is happening, not only once it has ended")
    fun progressIsVisibleMidJourney() {
        // The test that a monotonicity check alone cannot make. A substrate that reported the
        // vehicle's *starting* position until the instant it arrived would give a non-increasing
        // trace -- a step from the full distance to zero -- and pass every assertion above. It
        // would also make every distance-based dispatching decision read the fleet's position as
        // of whenever each vehicle last stopped, which is the defect this seam exists to prevent.
        //
        // A substrate whose journeys have no intermediate decision points fails here, and should:
        // that is the gap, stated as a failing test rather than as a paragraph.
        val (_, t) = scenario()
        val first = t.remainingToTarget.first()
        val last = t.remainingToTarget.last()
        val partWay = t.remainingToTarget.count { it < first - 1e-9 && it > last + 1e-9 }
        assertTrue(
            partWay >= 1,
            "the vehicle was never observed part way: ${t.remainingToTarget.size} samples showed " +
                    "only $first and $last. A position reported only on arrival satisfies " +
                    "monotonicity and is still stale everywhere it matters"
        )
    }

    @Test
    @DisplayName("The odometer never decreases")
    fun theOdometerIsMonotone() {
        val (_, t) = scenario()
        for (i in 1 until t.odometer.size) {
            assertTrue(
                t.odometer[i] >= t.odometer[i - 1] - 1e-9,
                "distance travelled went backwards, from ${t.odometer[i - 1]} to ${t.odometer[i]}"
            )
        }
        assertTrue(t.finalOdometer > 0.0, "a vehicle that travelled recorded no distance")
    }

    @Test
    @DisplayName("A vehicle stopped part way says so, and says where it stopped")
    fun aHaltIsVisibleAndLocated() {
        val (s, t) = scenario(haltBeforeArrival = true)
        assertEquals(
            true, t.haltedAt != null,
            "the wait ended without the vehicle reporting a halt; a caller cannot tell an arrival " +
                    "from a stop, which is the whole difference between them"
        )
        assertFalse(
            samePlace(t.haltedAt, s.far),
            "a vehicle stopped short of its destination is not at its destination"
        )
        assertTrue(
            t.distanceAtHalt > 0.0,
            "the vehicle stopped without having travelled: the fixture did not let it start"
        )
    }

    @Test
    @DisplayName("A halted vehicle resumes from where it stopped, not from where it set off")
    fun itResumesFromWhereItStopped() {
        val (s, t) = scenario(haltBeforeArrival = true)
        assertTrue(
            samePlace(t.arrivedAt, s.far),
            "the vehicle was released and still did not reach its destination; it is at " +
                    "(${t.arrivedAt?.name})"
        )
        assertFalse(s.movement.isHalted, "it arrived, so it is no longer halted")
        assertTrue(
            t.finalOdometer > t.distanceAtHalt,
            "the odometer did not grow after the halt: nothing moved when it was released"
        )
    }

    @Test
    @DisplayName("A redirection keeps the odometer continuous: ground covered stays covered")
    fun theOdometerSurvivesARedirect() {
        val (s, t) = scenario(redirect = true)
        assertTrue(
            samePlace(t.arrivedAt, s.near),
            "the vehicle did not end up where it was redirected to; it is at (${t.arrivedAt?.name})"
        )
        for (i in 1 until t.odometer.size) {
            assertTrue(
                t.odometer[i] >= t.odometer[i - 1] - 1e-9,
                "the odometer went backwards across a redirection: a vehicle that turns round has " +
                        "still covered the ground it covered"
            )
        }
        assertTrue(t.finalOdometer > 0.0, "the redirected journey recorded no distance at all")
    }
}
