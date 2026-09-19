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

/** Represents the 2nd order loss function.
 *
 * Each order of a loss function is the tail accumulation of the order below it, taken in the
 * natural measure for the support:
 *
 *     G2(x) = sum over integers j > x of G1(j)      X discrete
 *     G2(x) = integral from x to infinity of G1(u)   X continuous
 *
 * Carrying that out gives two different closed forms, and **each family uses its own**:
 *
 *     G2(x) = (1/2)E[max(X-x,0)*max(X-x-1,0)]        X discrete
 *     G2(x) = (1/2)E[max(X-x,0)^2]                   X continuous
 *
 * They are not interchangeable. The discrete form carries the extra factor because
 * `(X-x)(X-x-1) = (X-x)^2 - (X-x)` when X and x are whole numbers, and using the continuous
 * form on a Poisson overstates G2 by half the mean — an error of a few percent in an
 * inventory cost, and of the wrong sign in an optimization.
 *
 * An implementor uses the form belonging to its own support, and says which in its own KDoc.
 * `LossFunctionInvariantsTest` checks each against the accumulation relation for its family,
 * which is what keeps this from drifting.
 *
 * Note that no such split arises at first order: `G1(x) = E[max(X-x,0)]` is the same
 * expression either way. See [FirstOrderLossFunctionIfc].
 *
 * @author rossetti
 */
interface SecondOrderLossFunctionIfc {
    /** Computes the 2nd order loss function for the distribution function for a given value
     * of x: (1/2)E[max(X-x,0)*max(X-x-1,0)] when X is discrete, (1/2)E[max(X-x,0)^2] when X
     * is continuous.
     * @param x The value to be evaluated
     * @return The 2nd order loss function value at x
     */
    fun secondOrderLossFunction(x: Double): Double
}