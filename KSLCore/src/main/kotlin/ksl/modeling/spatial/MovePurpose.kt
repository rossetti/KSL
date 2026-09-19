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

/**
 * Why a transporter is making a journey.
 *
 * A caller says what a movement is *for*. It does not say what the vehicle is **carrying**, because
 * that is not a caller's opinion: it is a fact about what is aboard, and every movement substrate
 * derives the loaded or empty state from its own record of that. A protocol that could assert a
 * loaded move could assert it wrongly, and nothing downstream -- least of all a utilization figure
 * computed from the state -- would notice.
 *
 * Substrate-independent: a vehicle on a guide path, on a free path, or on a continuous projection
 * is going to do work, going home, or being pushed, and those three exhaust it.
 */
enum class MovePurpose {

    /** Going somewhere to do work: to collect, to deliver, or to run an errand. */
    SERVICE,

    /** Going to a home base or staging area, allocated to nobody. */
    HOME,

    /** Being pushed or towed by something else, rather than driving. */
    TOW
}
