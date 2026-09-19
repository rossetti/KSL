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

import ksl.modeling.guidedpath.GuidedPathNetwork
import ksl.modeling.guidedpath.LinkType

/**
 * The guide path of the simple automated guided vehicle example from the chapter on entity movement
 * and material handling: seven intersections, seven links, and a spur to the exit station.
 *
 * Parts arrive at an entry station on `I1` and are carried by one of two carts to an exit station
 * at the end of a spur off `I4`. The four links of the main loop run clockwise and one way only,
 * which is what keeps two carts from ever meeting head on. Each cart parks on its own short spur,
 * off `I2` and `I3`, so that an idle cart is never standing in the traffic it would otherwise
 * block -- the design device the chapter recommends and the reason this layout does not deadlock.
 *
 * Two details of the geometry are worth noticing, because both exercise parts of the model that a
 * tidier example would not.
 *
 * The main loop is discretized at twelve feet, chosen so that two six-foot carts cannot close to
 * less than six feet while moving. The two home spurs, however, are themselves only six feet long:
 * exactly one cart, and half of a twelve-foot zone. They are therefore given a zone size of their
 * own. Zone size belongs to a link rather than to the network precisely so that a layout like this
 * one is expressible without inflating the loop's zone count or misrepresenting the spurs.
 *
 * The loop is one way, so distances are not symmetric. Entry to exit runs the long way around,
 * `I1` to `I2` to `I3` to `I4` and down the spur, 204 feet; the return from the exit is only 108,
 * because the spur is bidirectional and `Link4` carries the cart straight back up to `I1`.
 */
object SimpleAgvNetwork {

    /** The zone size on the main loop, in feet. */
    const val LOOP_ZONE_LENGTH: Double = 12.0

    /** The zone size on the two home-base spurs, which are shorter than one loop zone. */
    const val HOME_SPUR_ZONE_LENGTH: Double = 6.0

    /** The name process code uses for the station where parts arrive. */
    const val ENTRY_STATION: String = "EntryStation"

    /** The name process code uses for the station where parts leave. */
    const val EXIT_STATION: String = "ExitStation"

    /** Where the first cart starts, out of the way of the loop. */
    const val AGV1_HOME: String = "I6"

    /** Where the second cart starts, out of the way of the loop. */
    const val AGV2_HOME: String = "I7"

    /**
     * Builds the guide path. Coordinates place `I4` at the origin with the loop above and to the
     * right of it, matching the layout in the text; they drive layout and animation only.
     */
    fun create(networkName: String = "SimpleAgvNetwork"): GuidedPathNetwork =
        GuidedPathNetwork.builder(networkName)
            .intersection("I1", x = 0.0, y = 72.0)
            .intersection("I2", x = 48.0, y = 72.0)
            .intersection("I3", x = 48.0, y = 0.0)
            .intersection("I4", x = 0.0, y = 0.0)
            .intersection("I5", x = 0.0, y = -36.0)
            .intersection("I6", x = 54.0, y = 72.0)
            .intersection("I7", x = 54.0, y = 0.0)
            // The main loop, clockwise and one way.
            .link("Link1", "I1", "I2", length = 48.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 0.0)
            .link("Link2", "I2", "I3", length = 72.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 270.0)
            .link("Link3", "I3", "I4", length = 48.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 180.0)
            .link("Link4", "I4", "I1", length = 72.0, zoneLength = LOOP_ZONE_LENGTH, beginDirection = 90.0)
            // The spur down to the exit station, long enough to hold a cart clear of the loop.
            .link(
                "Spur", "I4", "I5", length = 36.0, zoneLength = LOOP_ZONE_LENGTH,
                type = LinkType.SPUR, beginDirection = 270.0
            )
            // A home spur per cart, each one cart long.
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
}
