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
package ksl.modeling.entity

/**
 * Takes a resource off shift and puts it back on, by driving its capacity to zero and back.
 *
 * **A shift is not a new mechanism.** A resource with no capacity cannot be seized and its waiting
 * requests queue until it has some again, which is exactly what being off shift means; the capacity
 * change rule then decides what happens to work already under way, exactly as it does for every
 * other capacity change. What this adds is a *name* for that pattern, and one piece of state the
 * pattern needs and capacity alone cannot supply.
 *
 * **Why the flag is kept rather than inferred from `capacity == 0`.** A resource legitimately
 * constructed with, or driven to, zero capacity is not off shift, and would otherwise be refused by
 * [goOffShift] and silently given capacity by [goOnShift]. The two are different facts and are kept
 * apart.
 *
 * Held by whatever wants a shift rather than inherited from a base class, so that adding shifts to
 * a vehicle does not add a member to every resource in every model that already exists.
 *
 * @param resource the resource whose capacity is being driven
 * @param onShiftCapacity what to restore. The resource's initial capacity by default, which is what
 *   a shift returning to normal means
 */
class ShiftControl @JvmOverloads constructor(
    private val resource: Resource,
    val onShiftCapacity: Int = resource.initialCapacity
) {

    init {
        require(onShiftCapacity > 0) {
            "The on-shift capacity of (${resource.name}) must be > 0, but was $onShiftCapacity. A " +
                    "resource that has no capacity when on shift is never available at all."
        }
    }

    private var myOffShift: Boolean = false

    /** True while the resource has been taken off shift. */
    val isOffShift: Boolean
        get() = myOffShift

    /**
     * Takes the resource off shift indefinitely.
     *
     * New seize requests wait in its queue until [goOnShift]. Work already allocated finishes
     * according to the resource's own `capacityChangeRule`, which is `IGNORE` by default -- so a
     * vehicle part way through a job finishes it and then goes home, rather than stopping where it
     * stands. A model that wants the other behaviour changes the rule; this does not decide it.
     */
    fun goOffShift() {
        if (myOffShift) return
        myOffShift = true
        resource.changeCapacity(
            resource.CapacityChangeNotice(capacity = 0, duration = Double.POSITIVE_INFINITY)
        )
    }

    /** Brings the resource back on shift, restoring [onShiftCapacity]. */
    fun goOnShift() {
        if (!myOffShift) return
        myOffShift = false
        resource.changeCapacity(
            resource.CapacityChangeNotice(
                capacity = onShiftCapacity, duration = Double.POSITIVE_INFINITY
            )
        )
    }

    /** Every replication starts on shift. Called from the owner's `initialize`. */
    fun initialize() {
        myOffShift = false
    }

    override fun toString(): String =
        "ShiftControl(${resource.name}, ${if (myOffShift) "off shift" else "on shift"})"
}
