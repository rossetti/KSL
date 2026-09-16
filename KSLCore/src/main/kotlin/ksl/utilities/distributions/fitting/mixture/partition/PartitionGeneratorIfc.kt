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
package ksl.utilities.distributions.fitting.mixture.partition

import ksl.utilities.distributions.fitting.mixture.AdmissibilityCertificate
import ksl.utilities.distributions.fitting.mixture.DataPartition

/**
 *  Produces an initial contiguous partition of sorted data into a requested number of groups.
 *
 *  Implementations supply a starting point for the refinement stage; they are not required to
 *  optimize the fitting objective, only to produce a structurally valid partition by whatever
 *  criterion they represent. Different generators exist so that the sensitivity of the fitted
 *  result to its initialization can be measured rather than assumed.
 *
 *  Implementations must return a partition all of whose groups satisfy the supplied
 *  certificate, or null when they cannot. Returning a structurally invalid partition is a
 *  defect: the caller is entitled to assume the result is usable.
 */
interface PartitionGeneratorIfc {

    /**
     *  A short name identifying the generator, suitable for use as a factor level when results
     *  are recorded.
     */
    val name: String

    /**
     *  Generates a partition of the supplied sorted data into the requested number of groups,
     *  or returns null when no partition satisfying the certificate can be produced.
     *
     *  @param sortedData the observations in non-decreasing order
     *  @param numGroups the requested number of groups, must be positive
     *  @param certificate the structural constraints the partition must satisfy; its number of
     *  observations must match the size of the supplied data
     */
    fun generate(
        sortedData: DoubleArray,
        numGroups: Int,
        certificate: AdmissibilityCertificate
    ): DataPartition?

    /**
     *  Verifies that every group of the supplied partition satisfies the certificate. Provided
     *  so that implementations can check their own output before returning it, and so that
     *  tests can state the contract once.
     *
     *  @param partition the partition to check
     *  @param certificate the structural constraints
     */
    fun satisfies(partition: DataPartition, certificate: AdmissibilityCertificate): Boolean {
        if (partition.numObservations != certificate.numObservations) return false
        for (c in partition.cutPositions) {
            if (!certificate.isAdmissibleCut(c)) return false
        }
        for (g in 0 until partition.numGroups) {
            if (!certificate.isValidGroup(partition.startIndex(g), partition.endIndex(g))) {
                return false
            }
        }
        return true
    }
}
