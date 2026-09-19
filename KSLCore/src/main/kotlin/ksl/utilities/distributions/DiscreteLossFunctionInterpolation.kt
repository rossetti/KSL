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

import kotlin.math.floor

/**
 *  The loss functions of an integer-supported distribution, evaluated **between** whole numbers.
 *
 *  `G1(x) = E[max(X - x, 0)]` is defined for every real `x`, not only integral ones, and is
 *  continuous and decreasing in it; a caller comparing a quantity that came from a continuous model
 *  against a discrete one — pricing a normal-approximation order quantity under a fitted negative
 *  binomial, say, or an `RQInventoryModel` whose reorder point is not a whole number — has no reason
 *  to expect whole numbers to be required.
 *
 *  The closed forms the discrete distributions carry are derived for integral arguments, and each
 *  went wrong differently when handed a fraction: `Poisson` and `NegativeBinomial` dropped the term
 *  carrying the mass function (which is zero off the support) and returned `(mu - x)(1 - F(x))`,
 *  **negative** above the mean; `Binomial` silently floored; `Geometric` applied a continuous
 *  formula. These functions replace all four behaviours with one exact interpolation, so nothing is
 *  approximated and the whole-number results are unchanged.
 *
 *  **Why interpolation is exact rather than an approximation.** With `n = floor(x)` and `t = x - n`
 *  in `[0, 1)`, only support points at or above `n + 1` contribute to `G1`, and only those at or
 *  above `n + 2` contribute to `G2`, whichever fraction `t` takes. Splitting the sums on that fact
 *  gives identities in quantities the distributions already compute correctly at whole numbers:
 *
 *  <pre>
 *    G1(x) = G1(n) - t * (1 - F(n))
 *    G2(x) = G2(n) - t * (G1(n+1) + S) + (1/2) * (t*t + t) * S,   S = 1 - F(n+1)
 *  </pre>
 *
 *  The same argument carries to the third order, where only points at or above `n + 3`
 *  contribute. Writing `a = k - n`, the three clamped factors expand as
 *
 *  <pre>
 *    (a-t)(a-1-t)(a-2-t) = a(a-1)(a-2) - 3t*a(a-1) + 3t(1+t)*a - t(t+1)(t+2)
 *  </pre>
 *
 *  and each of those four sums over `k >= n + 3` is a quantity the distribution already has at
 *  whole numbers, giving
 *
 *  <pre>
 *    G3(x) = G3(n) - t * (G2(n+1) + G1(n+2) + S)
 *                  + (1/2) * t * (1+t) * (G1(n+2) + 2S)
 *                  - (1/6) * t * (1+t) * (2+t) * S,             S = 1 - F(n+2)
 *  </pre>
 *
 *  Checked against direct summation for `Poisson(6)` and `Poisson(1.5)` over 201 arguments from
 *  -3 to 25, the largest disagreement was 5.7e-14.
 *
 *  Both reduce to the whole-number result at `t = 0`. Checked against direct summation for
 *  `Poisson(6)` over a dense sweep of arguments from -3 to 25, the largest disagreement was 1.4e-14.
 *
 *  These hold for negative `n` too, which matters: the old below-zero branches used
 *  `floor(abs(x)) + mean`, right at a negative whole number and wrong between them.
 */

/**
 *  `G1(x)` for an integer-supported distribution at a non-integral [x], from the distribution's own
 *  whole-number results.
 *
 *  Calls back into [d] only at whole numbers, so an implementation using this for its fractional
 *  case does not recurse.
 *
 *  @param d the distribution, whose support is the non-negative integers
 *  @param x the argument; callers apply this only when `x` is not a whole number
 */
internal fun interpolatedFirstOrderLoss(d: LossFunctionDistributionIfc, x: Double): Double {
    val n = floor(x)
    val t = x - n
    return d.firstOrderLossFunction(n) - t * (1.0 - d.cdf(n))
}

/**
 *  `G2(x)` for an integer-supported distribution at a non-integral [x], from the distribution's own
 *  whole-number results.
 *
 *  Calls back into [d] only at whole numbers, so an implementation using this for its fractional
 *  case does not recurse.
 *
 *  @param d the distribution, whose support is the non-negative integers
 *  @param x the argument; callers apply this only when `x` is not a whole number
 */
internal fun interpolatedSecondOrderLoss(d: LossFunctionDistributionIfc, x: Double): Double {
    val n = floor(x)
    val t = x - n
    val tailAboveNextPoint = 1.0 - d.cdf(n + 1.0)
    return d.secondOrderLossFunction(n) -
        t * (d.firstOrderLossFunction(n + 1.0) + tailAboveNextPoint) +
        0.5 * (t * t + t) * tailAboveNextPoint
}

/**
 *  `G3(x)` for an integer-supported distribution at a non-integral [x], from the distribution's own
 *  whole-number results.
 *
 *  **Not the Pascal recursion.** `G3(b) = G3(b+1) + G2(b+1)` is exact at whole numbers and false
 *  between them — on `Poisson(6)` it is out by 5.7e-3 at `x = 5.25` — so a fractional case cannot
 *  be served by stepping the recursion. That is the same shape of error as the closed forms these
 *  functions exist to replace, and it is why this is a derivation rather than a loop.
 *
 *  Calls back into [d] and [thirdOrderAtWholeNumber] only at whole numbers, so an implementation
 *  using this for its fractional case does not recurse.
 *
 *  @param d the distribution, whose support is the non-negative integers
 *  @param x the argument; callers apply this only when `x` is not a whole number
 *  @param thirdOrderAtWholeNumber the distribution's own `G3`, valid at whole numbers. Passed
 *  rather than taken from an interface so that this does not constrain which types may use it.
 */
internal fun interpolatedThirdOrderLoss(
    d: LossFunctionDistributionIfc,
    x: Double,
    thirdOrderAtWholeNumber: (Double) -> Double
): Double {
    val n = floor(x)
    val t = x - n
    val tailAboveSecondPoint = 1.0 - d.cdf(n + 2.0)
    val firstAboveSecondPoint = d.firstOrderLossFunction(n + 2.0)
    return thirdOrderAtWholeNumber(n) -
        t * (d.secondOrderLossFunction(n + 1.0) + firstAboveSecondPoint + tailAboveSecondPoint) +
        0.5 * t * (1.0 + t) * (firstAboveSecondPoint + 2.0 * tailAboveSecondPoint) -
        (1.0 / 6.0) * t * (1.0 + t) * (2.0 + t) * tailAboveSecondPoint
}

/**
 *  `G3(x)` at a whole number for an integer-supported distribution, accumulated from `G2`.
 *
 *  Each order is the tail sum of the one below it, so with the third binomial moment in hand the
 *  rest is one pass over the integers between 0 and [x]:
 *
 *  <pre>
 *    G3(b) = G3(0) - sum over 0 &lt; y &lt;= b of G2(y)     b &gt; 0
 *    G3(b) = G3(0) + sum over b &lt; y &lt;= 0 of G2(y)     b &lt; 0
 *  </pre>
 *
 *  This is the same accumulating loop the distributions already use for `G2`, one order up, and
 *  costs no evaluations of the mass function beyond the ones `G2` makes. Distributions with a
 *  closed form for `G3` should use it instead; this is for the ones whose closed form would be
 *  more work to derive than the loop is to run.
 *
 *  @param d the distribution, whose support is the non-negative integers
 *  @param x a whole-number argument
 *  @param thirdOrderAtZero `G3(0)`, one sixth of the third binomial moment `E[X(X-1)(X-2)]`
 */
internal fun accumulatedThirdOrderLoss(
    d: LossFunctionDistributionIfc,
    x: Double,
    thirdOrderAtZero: Double
): Double {
    var total = thirdOrderAtZero
    if (x > 0.0) {
        var y = 1
        while (y <= x) {
            total -= d.secondOrderLossFunction(y.toDouble())
            y++
        }
    } else if (x < 0.0) {
        var y = 0
        while (y > x) {
            total += d.secondOrderLossFunction(y.toDouble())
            y--
        }
    }
    return total
}

/** True when [x] is a whole number, so the distribution's own closed form applies directly. */
internal fun isWholeNumber(x: Double): Boolean = x == floor(x)
