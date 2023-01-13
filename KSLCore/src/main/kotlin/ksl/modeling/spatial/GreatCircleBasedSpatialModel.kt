/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
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

import ksl.utilities.math.KSLMath
import kotlin.math.*

class GreatCircleBasedSpatialModel () : SpatialModel() {

    var defaultLocationPrecision = KSLMath.defaultNumericalPrecision
        set(precision) {
            require(precision > 0.0) { "The precision must be > 0.0." }
            field = precision
        }

    /**
     * Holds the radius of the earth for calculating great circle distance
     *
     */
    var earthRadius = DEFAULT_EARTH_RADIUS
        set(value) {
            require(value >= 0.0)
            field = value
        }
    /**
     * Can be set to adjust computed great circle distance to account for the
     * circuity of the road/rail network, by default 1.0
     *
     */
    var circuityFactor = 1.0
        set(value) {
            require(value > 0.0){"The circuity factor must be > 0.0"}
            field = value
        }

    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        require(isValid(fromLocation)) { "The location ${fromLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(toLocation)) { "The location ${toLocation.name} is not a valid location for spatial model ${this.name}" }
        val f = fromLocation as GPSCoordinate
        val t = toLocation as GPSCoordinate
        val lat1 = Math.toRadians(f.latitude)
        val lon1 = Math.toRadians(f.longitude)
        val lat2 = Math.toRadians(t.latitude)
        val lon2 = Math.toRadians(t.longitude)

        val lonDiff = lon1 - lon2
        val cosLat2 = cos(lat2)
        val sinLonDiff = sin(lonDiff)
        val cosLat1 = cos(lat1)
        val sinLat2 = sin(lat2)
        val sinLat1 = sin(lat1)
        val cosLonDiff = cos(lonDiff)

        val n1 = cosLat2 * sinLonDiff
        val n2 = cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosLonDiff
        val n = sqrt(n1 * n1 + n2 * n2)
        val d = sinLat1 * sinLat2 + cosLat1 * cosLat2 * cosLonDiff

        val angDiff = atan2(n, d)

        return circuityFactor * earthRadius * angDiff
    }

    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        require(isValid(firstLocation)) { "The location ${firstLocation.name} is not a valid location for spatial model ${this.name}" }
        require(isValid(secondLocation)) { "The location ${secondLocation.name} is not a valid location for spatial model ${this.name}" }
        val f = firstLocation as GPSCoordinate
        val t = secondLocation as GPSCoordinate
        val b1 = KSLMath.equal(f.latitude, t.latitude, defaultLocationPrecision)
        val b2 = KSLMath.equal(f.longitude, t.longitude, defaultLocationPrecision)
        return b1 && b2
    }

    /** Represents a location within this spatial model.
     *
     * @param aName the name of the location, will be assigned based on ID_id if null
     */
    inner class GPSCoordinate(val latitude: Double, val longitude: Double, aName: String? = null) : AbstractLocation(aName) {
        init {
            require(abs(latitude) <= 90.0) { "The latitude must be in range [-90, 90] degrees" }
            require(abs(longitude) <= 180.0) { "The longitude must be in range [-180, 180] degrees" }
        }
        override val model: SpatialModel = this@GreatCircleBasedSpatialModel
        override fun toString(): String {
            return "Location(latitude=$latitude, longitude=$longitude, id=$id, name='$name', spatial model=${model.name})"
        }

    }

    companion object{

        enum class Direction {
            NORTH, SOUTH, EAST, WEST
        }

        /**
         * The circuity factor for road networks, see pg 559 of Ballou
         *
         */
        const val ROADS = 1.17

        /**
         * The circuity factor for rail networks, see pg 559 of Ballou
         *
         */
        const val RAIL = 1.20

        /**
         * The default radius for the earth, @see
         * (http://en.wikipedia.org/wiki/Great_circle_distance)
         *
         */
        const val DEFAULT_EARTH_RADIUS = 6372.795

        /**
         * Returns a latitude in degrees
         *
         * @param direction
         * @param degrees
         * @param minutes
         * @param seconds
         * @return
         */
        fun latitude(
            direction: Direction,
            degrees: Double,
            minutes: Double,
            seconds: Double = 0.0
        ): Double {
            require(direction != Direction.EAST) { "The direction supplied was EAST, not valid for latitude" }
            require(direction != Direction.WEST) { "The direction supplied was WEST, not valid for latitude" }
            require(degrees >= 0.0) { "The degrees must be >= 0." }
            require(degrees <= 90.0) { "The degrees must be <= 90." }
            require(minutes >= 0.0) { "The minutes must be >= 0." }
            require(minutes <= 60.0) { "The minutes must be <= 60.0." }
            require(seconds >= 0.0) { "The seconds must be >=0." }
            require(seconds <= 60.0) { "The seconds must be <= 60" }
            var sign = 1.0
            if (direction == Direction.SOUTH) {
                sign = -1.0
            }
            val lat = degrees + minutes / 60.0 + seconds / 3600.0
            return sign * lat
        }

        /**
         * Returns a longitude in degrees
         *
         * @param direction
         * @param degrees
         * @param minutes
         * @param seconds
         * @return
         */
        fun longitude(
            direction: Direction,
            degrees: Double,
            minutes: Double,
            seconds: Double = 0.0
        ): Double {
            require(direction != Direction.NORTH) { "The direction supplied was NORTH, not valid for longitude" }
            require(direction != Direction.SOUTH) { "The direction supplied was SOUTH, not valid for longitude" }
            require(degrees >= 0.0) { "The degrees must be >= 0." }
            require(degrees <= 180.0) { "The degrees must be <= 180." }
            require(minutes >= 0.0) { "The minutes must be >= 0." }
            require(minutes <= 60.0) { "The minutes must be <= 60.0." }
            require(seconds >= 0.0) { "The seconds must be >=0." }
            require(seconds <= 60.0) { "The seconds must be <= 60" }
            var sign = 1.0
            if (direction == Direction.WEST) {
                sign = -1.0
            }
            val lon = degrees + minutes / 60.0 + seconds / 3600.0
            return sign * lon
        }

        /**
         * Gets the sign associated with the supplied direction
         *
         * @param d the direction
         * @return the sign
         */
        fun directionSign(d: Direction): Double {
            return when (d) {
                Direction.NORTH -> 1.0
                Direction.SOUTH -> -1.0
                Direction.EAST -> 1.0
                Direction.WEST -> -1.0
                else -> 1.0
            }
        }
    }

}