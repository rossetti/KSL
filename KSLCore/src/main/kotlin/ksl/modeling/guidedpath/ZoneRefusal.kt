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

/**
 * Why a zone would refuse a claim, as [Zone.refusalFor] answers it.
 *
 * Three causes rather than one boolean, because the remedy differs and a modeller choosing between
 * waiting, going elsewhere and giving up needs to know which it is. They are tested in this order,
 * which is the order [Zone.claim] tests them in, so a zone that is both occupied and reserved reads
 * as whichever would stop the claim first.
 */
enum class ZoneRefusal {

    /**
     * Something holds the zone outright -- a vehicle's body covers it, or a holder has been granted
     * it. Asking for it is reasonable: it drains when the holder moves on or gives it back, unless
     * nothing is scheduled to make that happen, which is what
     * [GuidedPathSpace.firstZoneHeldByStationaryVehicle] is for.
     */
    HELD,

    /**
     * Things are present in the zone without holding it -- a population. The zone empties when
     * whatever admitted them takes them away, and nothing in the guide path governs that.
     */
    OCCUPIED,

    /**
     * A closure has reserved the zone and does not admit this claimant. Asking for it as part of a
     * new closure fails now and will keep failing until the reservation is taken up or given up, so
     * this is the one refusal where waiting is usually the wrong answer -- see
     * [GuidedPathSpace.tryRequestZones] and [GuidedPathSpace.firstPromisedZone].
     *
     * Claimant-dependent, which is the whole reason [Zone.refusalFor] takes one: the same reserved
     * zone admits the holder it was promised to and any vehicle escaping an older reservation.
     */
    RESERVED
}
