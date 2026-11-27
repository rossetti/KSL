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
 *  The number of terms depends on how fast the mass falls off, not on x: a distribution whose mass falls off
 *  slowly (a negative binomial with a small success probability) needs many. Past [TAIL_SUM_TERM_LIMIT] terms the
 *  sum gives up, and refuses with [tooWide]: no closed form stands in for it accurately there.
 *
 *  @param x a whole number above the mean, below `Int.MAX_VALUE`
 *  @param order 1, 2 or 3
 *  @param massAfterX the mass at x + 1
 *  @param ratio the mass at j + 1 divided by the mass at j
 *  @param tooWide the message of the refusal when the sum does not settle within [TAIL_SUM_TERM_LIMIT] terms
 *  @return the loss
 */
internal fun upperTailLoss(
    x: Double,
    order: Int,
    massAfterX: Double,
    tooWide: () -> String,
    ratio: (Double) -> Double
): Double {
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
        require(++terms < TAIL_SUM_TERM_LIMIT, tooWide)
        mass *= ratio(j)
        j += 1.0
        n += 1.0
    }
    return total
}

/** The most terms `upperTailLoss` sums before it gives up on a distribution whose tail falls off too slowly. */
internal const val TAIL_SUM_TERM_LIMIT: Int = 200_000_000

/** The largest whole number a loss function of a distribution on the integers is computed at, from its `Int` mass function. */
internal const val LARGEST_LOSS_ARGUMENT: Double = Int.MAX_VALUE - 5.0

/**
 *  Refuses a whole-number argument of a loss function that its distribution's mass function, defined on `Int`,
 *  cannot reach, or that the loops below zero, counting in `Int`, cannot: beyond [LARGEST_LOSS_ARGUMENT] either
 *  way. The margin below `Int.MAX_VALUE` keeps the points a loss function at a whole number needs (up to x + 3)
 *  representable; an interpolation checks each whole number it uses in turn.
 */
internal fun requireLossArgumentRepresentable(x: Double) {
    require(x >= -LARGEST_LOSS_ARGUMENT && x <= LARGEST_LOSS_ARGUMENT) {
        "A loss function of a distribution on the integers is computed only between ${-LARGEST_LOSS_ARGUMENT.toLong()} " +
                "and ${LARGEST_LOSS_ARGUMENT.toLong()}; it was asked at $x."
    }
}

/**
 *  A loss function at a non-finite argument: 0 at infinity, where nothing lies above, and infinite at minus
 *  infinity; null for a finite argument, which the loss function computes. NaN is refused.
 */
internal fun lossAtNonFinite(x: Double): Double? {
    require(!x.isNaN()) { "The argument of a loss function must be a number; it was NaN." }
    return when (x) {
        Double.POSITIVE_INFINITY -> 0.0
        Double.NEGATIVE_INFINITY -> Double.POSITIVE_INFINITY
        else -> null
    }
}
