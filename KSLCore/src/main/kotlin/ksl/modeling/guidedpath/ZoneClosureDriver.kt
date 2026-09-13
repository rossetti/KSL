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

import ksl.modeling.variable.Counter
import ksl.modeling.variable.CounterCIfc
import ksl.modeling.variable.RandomVariable
import ksl.simulation.KSLEvent
import ksl.simulation.ModelElement
import ksl.utilities.random.rvariable.RVariableIfc

/**
 * Closes space over and over on a schedule, so a model does not write the same six lines again.
 *
 * **Packaging rather than semantics**, and worth being plain that this adds no capability at all:
 * everything here is `holdZonesFor` and an event, and a model that wants something this does not do
 * should write the event itself rather than bend this into shape. What it saves is the shape that
 * is written identically every time -- sample an interval, sample a duration, make a holder, ask,
 * repeat -- and the two errors that shape invites.
 *
 * The first error is sampling the duration twice: once for the hold and once for a separate "space
 * free again" event, so that the zones reopen at one instant and whatever was waiting on the
 * closure resumes at another. Requiring a [ZoneHoldActionIfc] made that hard to write; taking the
 * duration once here makes it impossible.
 *
 * The second is a holder reused across closures. A holder is the identity of one closure, so a
 * driver that kept one object and asked twice would be told, correctly and unhelpfully, that it
 * already holds space. This mints a new holder per closure, which is the whole reason a holder is
 * an interface with two members and not a model element.
 *
 * ```
 * ZoneClosureDriver(
 *     this, system,
 *     zones = { network.link("Aisle3")!!.zones },
 *     timeBetween = ExponentialRV(240.0),
 *     duration = LognormalRV(20.0, 25.0),
 *     name = "Aisle3Maintenance"
 * )
 * ```
 *
 * @param parent where this sits in the model
 * @param space the guide path whose zones are closed
 * @param zones what to close, read afresh at every closure so the extent may be sampled too
 * @param timeBetween how long between the end of one closure being asked for and the next
 * @param duration how long each closure holds its space once the hold begins
 * @param onOverlap what to do when the space is already promised to another closure; the default
 *   refuses loudly, which is the right default for a driver whose whole job is to run unattended
 * @param firstAt when the first closure is asked for; defaults to one sample of [timeBetween]
 * @param name the driver's name in the model
 */
class ZoneClosureDriver(
    parent: ModelElement,
    val space: GuidedPathSpace,
    private val zones: () -> List<Zone>,
    timeBetween: RVariableIfc,
    duration: RVariableIfc,
    var onOverlap: ZoneOverlap = ZoneOverlap.RAISE,
    private val firstAt: Double = Double.NaN,
    name: String? = null
) : ModelElement(parent, name), ZoneHoldActionIfc {

    private val myTimeBetween = RandomVariable(this, timeBetween, "${this.name}:TimeBetween")
    private val myDuration = RandomVariable(this, duration, "${this.name}:Duration")

    private val myClosuresHeld = Counter(this, name = "${this.name}:ClosuresHeld")

    /** How many closures this driver saw through from start to finish. */
    val closuresHeld: CounterCIfc
        get() = myClosuresHeld

    private var nextHolderId = 1

    /** A holder per closure, because a holder is the identity of one closure and not of the driver. */
    private inner class Closure(id: Int) : ZoneHolderIfc {
        override val name: String = "${this@ZoneClosureDriver.name}_$id"
        override val awaitedZone: Zone? get() = null
    }

    private val myAskAction = EventActionIfc<Nothing> { ask() }

    override fun initialize() {
        nextHolderId = 1
        val first = if (firstAt.isNaN()) myTimeBetween.value else firstAt
        schedule(myAskAction, first)
    }

    private fun ask() {
        val wanted = zones()
        if (wanted.isNotEmpty()) {
            space.holdZonesFor(Closure(nextHolderId++), wanted, myDuration.value, this, onOverlap)
        }
        // Booked from the ask rather than from the release, so that the rate of closures is the
        // rate this driver was given rather than that rate plus however long each drain and hold
        // happened to take -- which depends on traffic and so varies between replications.
        schedule(myAskAction, myTimeBetween.value)
    }

    override fun holdBegan(allocation: ZoneAllocation) = Unit

    override fun holdEnded(allocation: ZoneAllocation) {
        myClosuresHeld.increment()
    }
}
