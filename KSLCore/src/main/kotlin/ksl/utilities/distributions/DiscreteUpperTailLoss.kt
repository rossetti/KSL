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
package ksl.utilities.distributions

/**
 *  `G1`, `G2` or `G3` at a whole number [x] above the mean of a distribution on the non-negative integers,
 *  summed over the upper tail from the definition:
 *
 *  <pre>
 *    Gk(x) = sum over j &gt; x of C(j - x, k) p(j)
 *  </pre>
 *
 *  Above the mean the closed forms subtract terms of nearly equal size (for `G1`, the mean times the mass at
 *  x less x minus the mean times the tail probability), so they lose digits as x grows, and far into the tail
 *  they return values that are negative or that grow with x. Every term of the sum is positive, so it keeps
 *  full accuracy. The mass is stepped from one point to the next by [ratio], which costs a multiplication a
 *  term; the terms fall off geometrically once the mass does, and the sum stops when they no longer change it.
 *
 *  A distribution so wide that its terms fall off slowly would need more terms than is reasonable; the sum then
 *  gives up, after [TAIL_SUM_TERM_LIMIT] terms, and returns null. The closed forms lose little there, since x is
 *  then not far into the tail as the distribution's spread measures it, and the caller uses them instead.
 *
 *  @param x a whole number above the mean, below `Int.MAX_VALUE`
 *  @param order 1, 2 or 3
 *  @param massAfterX the mass at x + 1
 *  @param ratio the mass at j + 1 divided by the mass at j
 *  @return the loss, or null when the sum does not settle within [TAIL_SUM_TERM_LIMIT] terms
 */
internal fun upperTailLoss(x: Double, order: Int, massAfterX: Double, ratio: (Double) -> Double): Double? {
    require(order in 1..3) { "The order of a loss function must be 1, 2 or 3; it was $order." }
    // stepped as a Double: a sum that runs past the largest Int must not wrap to negative points
    var j = x + 1.0
    var terms = 0
    var mass = massAfterX
    var n = 1.0
    var total = 0.0
    while (mass > 0.0) {
        val ways = when (order) {
            1 -> n
            2 -> n * (n - 1.0) / 2.0
            else -> n * (n - 1.0) * (n - 2.0) / 6.0
        }
        val term = ways * mass
        total += term
        // a term too small to change the total, once the terms have begun (the first ones of a higher order are 0)
        if (n > order && term <= total * 1e-17) break
        if (++terms >= TAIL_SUM_TERM_LIMIT) return null
        mass *= ratio(j)
        j += 1.0
        n += 1.0
    }
    return total
}

/** The most terms `upperTailLoss` sums before it gives up on a distribution whose tail falls off too slowly. */
internal const val TAIL_SUM_TERM_LIMIT: Int = 1_000_000

/**
 *  Refuses a whole-number argument of a loss function that its distribution's mass function, defined on `Int`,
 *  cannot reach: from `Int.MAX_VALUE - 2` on, the points a loss function or its interpolation needs are not
 *  representable.
 */
internal fun requireLossArgumentRepresentable(x: Double) {
    require(x < Int.MAX_VALUE - 2.0) {
        "A loss function of a distribution on the integers is computed only below ${Int.MAX_VALUE - 2}; it was asked at $x."
    }
}
