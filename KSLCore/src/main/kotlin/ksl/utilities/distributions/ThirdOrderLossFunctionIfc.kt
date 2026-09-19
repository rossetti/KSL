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

/** Represents the 3rd order loss function.
 *
 * Denominated per the support, as [SecondOrderLossFunctionIfc] is:
 *
 *     G3(x) = (1/6)E[max(X-x,0)*max(X-x-1,0)*max(X-x-2,0)]    X discrete
 *     G3(x) = (1/6)E[max(X-x,0)^3]                            X continuous
 *
 * and in both cases the tail accumulation of the order below it — a sum of G2 over the
 * integers above x on a discrete support, an integral of G2 from x on a continuous one.
 *
 * **What it is for.** A second order loss function gives the expected shortage when the
 * stock level is fixed. When the level is itself random — the position an (r, Q) policy
 * occupies is uniform across the reorder band — the shortage has to be averaged over its
 * distribution, and averaging a second order loss function across a range produces a
 * difference of third order ones:
 *
 *     sum over j in (b, c] of G2(j) = G3(b) - G3(c)
 *
 * That identity is what makes the variance of the backorder level computable in closed form
 * rather than by summing across every level in the band.
 *
 * This is deliberately **not** part of [LossFunctionDistributionIfc]. Not every distribution
 * that has the first two orders has a closed form for the third, and a numerical default
 * would be an approximation wearing the same signature as the exact implementations beside
 * it. A caller that needs the third order accepts this type, or tests for it.
 */
interface ThirdOrderLossFunctionIfc {
    /** Computes the 3rd order loss function for a given value of x. Denominated per the
     * support: (1/6)E[max(X-x,0)*max(X-x-1,0)*max(X-x-2,0)] when X is discrete,
     * (1/6)E[max(X-x,0)^3] when X is continuous.
     * @param x The value to be evaluated
     * @return The 3rd order loss function value at x
     */
    fun thirdOrderLossFunction(x: Double): Double
}
