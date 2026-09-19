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

/** Represents the 1st order loss function.
 *
 * `G1(x) = E[max(X-x,0)]` is one expression for every support, discrete and continuous alike,
 * so unlike [SecondOrderLossFunctionIfc] there is nothing here to choose between. Stated
 * explicitly because the higher orders *do* split, and the two should not be brought into
 * line with each other: G1 is the same function for both families and G2 is not.
 *
 * G1 is defined for every real x, not only whole numbers, and is continuous and decreasing in
 * it. An integer-supported distribution whose closed form is derived for integral arguments
 * must therefore still answer correctly between them — see `DiscreteLossFunctionInterpolation`.
 *
 * @author rossetti
 */
interface FirstOrderLossFunctionIfc {
    /** Computes the first order loss function for the
     * function for given value of x, G1(x) = E[max(X-x,0)]
     * @param x The value to be evaluated
     * @return The loss function value, E[max(X-x,0)]
     */
    fun firstOrderLossFunction(x: Double): Double
}