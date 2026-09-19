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

import ksl.modeling.entity.ProcessModel
import ksl.simulation.KSLEvent
import ksl.simulation.Model
import ksl.simulation.ModelElement
import ksl.utilities.observers.ObserverIfc
import ksl.utilities.random.rvariable.ConstantRV
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 *  **What the seam added to a released package, and what it deliberately did not.**
 *
 *  The conformance suite says the free path keeps the movement contract. These say the three things
 *  a reviewer of an additive change to a released package actually needs to know: that the geometry
 *  decides how finely a journey is discretised and says so honestly when it cannot; that a stepped
 *  journey is indistinguishable from a single-delay one to everything that observes a spatial
 *  element; and that a resource's odometers are about the resource rather than about which verb
 *  happened to move it.
 */
class FreePathSeamTest {

    // ---- the geometry decides ------------------------------------------------------------------

    /**
     *  A spatial model of pairwise distances, which cannot say where between two places is. A
     *  journey there is one step: the resource waits out the leg and arrives, exactly as `move`
     *  has always behaved, and its position is honestly the place it set out from until it gets
     *  there.
     */
    private class Depot(parent: ModelElement, val samples: MutableList<String>) :
        ProcessModel(parent, "Depot") {

        val distances = DistancesModel()

        init {
            distances.addDistance("A", "B", 100.0, symmetric = true)
            spatialModel = distances
        }

        val a = distances.location("A")!!
        val b = distances.location("B")!!

        val cart = MovableResource(this, a, ConstantRV(10.0), name = "Cart", stepSize = 5.0)

        var arrived: Boolean = false
        var odometer: Double = Double.NaN

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                arrived = driveTo(cart, b)
                odometer = cart.distanceTravelled
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun sample(event: KSLEvent<Nothing>) {
            samples.add(cart.positionNow.name)
        }

        override fun initialize() {
            activate(Driver().p)
            var t = 0.5
            while (t < 9.5) {
                schedule(::sample, t)
                t += 0.5
            }
        }
    }

    @Test
    @DisplayName("A geometry that cannot interpolate makes a journey one step, and says where it is honestly")
    fun theGeometryDecidesTheDiscretisation() {
        val samples = mutableListOf<String>()
        val m = Model("DepotModel")
        val depot = Depot(m, samples)
        m.numberOfReplications = 1
        m.lengthOfReplication = 40.0
        m.simulate()

        assertTrue(depot.arrived, "the journey did not end at the destination")
        assertEquals(100.0, depot.odometer, 1e-9, "the leg was 100 units and the odometer disagrees")
        // Every sample before arrival says "A". This is the seam's one unmet promise, and it is
        // unmet because the spatial model has nothing to say rather than because the machinery is
        // wrong: `positionNow` is live wherever the geometry can be asked, and where it cannot the
        // honest answer is the place the vehicle set out from.
        assertTrue(samples.isNotEmpty(), "nothing was sampled")
        assertTrue(
            samples.all { it == "A" },
            "a model of pairwise distances reported a position between two places: $samples"
        )
    }

    // ---- a stepped journey is not observably different -----------------------------------------

    private class Plant(parent: ModelElement, val stepSize: Double) : ProcessModel(parent, "Plant") {

        val plane = Euclidean2DPlane()

        init {
            spatialModel = plane
        }

        val a = plane.Point(0.0, 0.0, "A")
        val b = plane.Point(100.0, 0.0, "B")

        val cart = MovableResource(this, a, ConstantRV(10.0), name = "Cart", stepSize = stepSize)

        var locationUpdates: Int = 0
        var finalLocation: String = ""

        init {
            cart.attachObserver(object : ObserverIfc<SpatialElementIfc> {
                override fun onChange(newValue: SpatialElementIfc) {
                    locationUpdates++
                }
            })
        }

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                driveTo(cart, b)
                finalLocation = cart.currentLocation.name
            }
        }

        override fun initialize() {
            locationUpdates = 0
            activate(Driver().p)
        }
    }

    private fun runPlant(stepSize: Double): Plant {
        val m = Model("PlantModel$stepSize")
        val plant = Plant(m, stepSize)
        m.numberOfReplications = 1
        m.lengthOfReplication = 60.0
        m.simulate()
        return plant
    }

    @Test
    @DisplayName("However finely a journey is stepped, it writes currentLocation exactly once")
    fun steppingIsInvisibleToWhateverWatchesASpatialElement() {
        val coarse = runPlant(stepSize = 100.0)   // one step: the whole leg
        val fine = runPlant(stepSize = 1.0)       // a hundred steps
        assertEquals("B", coarse.finalLocation)
        assertEquals("B", fine.finalLocation)
        assertEquals(
            coarse.locationUpdates, fine.locationUpdates,
            "a hundred-step journey notified observers ${fine.locationUpdates} times and a " +
                    "one-step journey ${coarse.locationUpdates}. Stepping writes to a live " +
                    "position; currentLocation is written once, when the journey ends, so nothing " +
                    "watching a spatial element can tell the two apart"
        )
        assertEquals(
            1, fine.locationUpdates,
            "one journey should produce one location update, as a move always has"
        )
    }

    // ---- the odometers are about the resource --------------------------------------------------

    private class Round(parent: ModelElement, val useSeam: Boolean) : ProcessModel(parent, "Round") {

        val plane = Euclidean2DPlane()

        init {
            spatialModel = plane
        }

        val a = plane.Point(0.0, 0.0, "A")
        val b = plane.Point(60.0, 0.0, "B")
        val c = plane.Point(60.0, 80.0, "C")

        val cart = MovableResource(this, a, ConstantRV(10.0), name = "Cart", stepSize = 5.0)

        var distance: Double = Double.NaN
        var operating: Double = Double.NaN

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                if (useSeam) {
                    driveTo(cart, b)
                    driveTo(cart, c)
                } else {
                    move(cart, b)
                    move(cart, c)
                }
                distance = cart.distanceTravelled
                operating = cart.operatingTime
            }
        }

        override fun initialize() {
            activate(Driver().p)
        }
    }

    private fun runRound(useSeam: Boolean): Round {
        val m = Model("RoundModel$useSeam")
        val round = Round(m, useSeam)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()
        return round
    }

    @Test
    @DisplayName("The odometers count the same round whether it was driven or moved")
    fun theOdometersAreAboutTheResource() {
        val driven = runRound(useSeam = true)
        val moved = runRound(useSeam = false)
        // A -> B is 60, B -> C is 80. At 10 per unit time that is 14 units of moving.
        assertEquals(140.0, driven.distance, 1e-9, "the seam's odometer is wrong")
        assertEquals(
            140.0, moved.distance, 1e-9,
            "a resource moved by `move` recorded no distance: an odometer that depends on which " +
                    "verb was used is worse than none"
        )
        assertEquals(14.0, driven.operating, 1e-9)
        assertEquals(14.0, moved.operating, 1e-9)
    }

    // ---- what a driver learns from a journey that was stopped ----------------------------------

    private class Breakdown(parent: ModelElement) : ProcessModel(parent, "Breakdown") {

        val plane = Euclidean2DPlane()

        init {
            spatialModel = plane
        }

        val a = plane.Point(0.0, 0.0, "A")
        val b = plane.Point(100.0, 0.0, "B")

        val cart = MovableResource(this, a, ConstantRV(10.0), name = "Cart", stepSize = 5.0)

        var firstAttemptArrived: Boolean = true
        var secondAttemptArrived: Boolean = false
        var whereItStopped: Double = Double.NaN

        inner class Driver : Entity("Driver") {
            val p = process(isDefaultProcess = true) {
                schedule({ _: KSLEvent<Nothing> -> cart.halt() }, 4.0)
                firstAttemptArrived = driveTo(cart, b)
                whereItStopped = cart.positionNow.x
                cart.resumeHalted()
                secondAttemptArrived = driveTo(cart, b)
            }
        }

        override fun initialize() {
            activate(Driver().p)
        }
    }

    @Test
    @DisplayName("A journey stopped part way reports that it was, and resumes from where it stopped")
    fun aStoppedJourneyIsToldApartFromAnArrival() {
        val m = Model("BreakdownModel")
        val shop = Breakdown(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 100.0
        m.simulate()

        assertFalse(
            shop.firstAttemptArrived,
            "`driveTo` returned true for a journey that was stopped short; a caller cannot tell " +
                    "an arrival from a breakdown, which is the whole difference between them"
        )
        assertEquals(
            40.0, shop.whereItStopped, 1e-9,
            "four units at ten a unit is forty along the way, and that is where it should be"
        )
        assertTrue(shop.secondAttemptArrived, "it never got there after being released")
    }
}

/**
 *  **A shift, for all three kinds of vehicle.**
 *
 *  The one capability the agent layer had that the other two did not. It is not a new mechanism: a
 *  resource with no capacity cannot be seized and requests for it queue, which is what being off
 *  shift means. What is new is that the same named pattern now reaches a free-path resource and a
 *  guided transporter, from one object, so the three cannot drift apart.
 */
class ShiftControlTest {

    private class Yard(parent: ModelElement) : ProcessModel(parent, "ShiftYard") {

        val plane = Euclidean2DPlane()

        init {
            spatialModel = plane
        }

        val a = plane.Point(0.0, 0.0, "A")
        val b = plane.Point(50.0, 0.0, "B")

        val cart = MovableResourceWithQ(this, a, ConstantRV(10.0), name = "Cart")

        val servedBefore = mutableListOf<Double>()
        val servedAfter = mutableListOf<Double>()

        inner class Job(aName: String, val afterShiftEnds: Boolean) : Entity(aName) {
            val p = process(isDefaultProcess = true) {
                val alloc = seize(cart)
                move(cart, b)
                if (afterShiftEnds) servedAfter.add(time) else servedBefore.add(time)
                release(alloc)
            }
        }

        @Suppress("UNUSED_PARAMETER")
        private fun endShift(event: KSLEvent<Nothing>) = cart.goOffShift()

        @Suppress("UNUSED_PARAMETER")
        private fun startShift(event: KSLEvent<Nothing>) = cart.goOnShift()

        override fun initialize() {
            activate(Job("Before", afterShiftEnds = false).p)
            schedule(::endShift, 1.0)
            // Asks for the vehicle while it is off shift. It waits rather than being refused, which
            // is what a queue in front of a closed depot is.
            schedule({ _: KSLEvent<Nothing> -> activate(Job("During", afterShiftEnds = true).p) }, 2.0)
            schedule(::startShift, 100.0)
        }
    }

    @Test
    @DisplayName("A free-path vehicle taken off shift is not seized again until it is back on")
    fun aVehicleOffShiftIsNotAvailable() {
        val m = Model("ShiftModel")
        val yard = Yard(m)
        m.numberOfReplications = 1
        m.lengthOfReplication = 200.0
        m.simulate()

        assertEquals(1, yard.servedBefore.size, "the job that started before the shift ended did not finish")
        assertEquals(1, yard.servedAfter.size, "the job asked during the shift break was never served")
        assertTrue(
            yard.servedAfter.single() >= 100.0,
            "a job that asked while the vehicle was off shift was served at " +
                    "${yard.servedAfter.single()}, before the shift resumed at 100"
        )
        assertFalse(yard.cart.isOffShift, "the vehicle is back on shift and says otherwise")
    }
}
