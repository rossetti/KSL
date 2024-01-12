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
package ksl.utilities.random.mcmc

/**
 * For use with MetropolisHastingsMV. Represents the proposal function
 * for the multivariate case.
 *
 */
interface ProposalFunctionMVIfc {

    /**
     *
     * the expected size of the array
     */
    val dimension: Int

    /** The ratio of g(y,x)/g(x,y).
     *
     *  proposal ratio = g(x|y)/g(y|x) = g(y,x)/g(x,y).
     *
     * The ratio of the proposal function
     * evaluated at x = current and y = proposed, where g() is some
     * proposal function of x and y. The implementor should ensure
     * that the returned ratio is a valid double
     *
     * @param currentX the x to evaluate
     * @param proposedY the y to evaluate
     * @return the ratio of the proposal function
     */
    fun proposalRatio(currentX: DoubleArray, proposedY: DoubleArray): Double

    /**
     *
     * @param currentX the current state value of the chain (i.e. x)
     * @return the generated possible state (i.e. proposed y) which may or may not be accepted
     */
    fun generateProposedGivenCurrent(currentX: DoubleArray): DoubleArray
}

abstract class ProposalFunctionMV(val density: FunctionMVIfc) : ProposalFunctionMVIfc {
    override val dimension: Int
        get() = density.dimension

}