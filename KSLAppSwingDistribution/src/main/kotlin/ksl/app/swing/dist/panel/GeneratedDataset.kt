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

package ksl.app.swing.dist.panel

import ksl.app.dist.catalog.DistributionFamilyDescriptor
import ksl.app.dist.config.DataSourceReference
import ksl.utilities.random.rvariable.RVType
import ksl.utilities.random.rvariable.parameters.RVData

/**
 * Pure builder for the "generate data from a distribution" path. Keeps the
 * form-values-to-reference mapping out of the Swing widget so it is unit-testable.
 */
object GeneratedDataset {

    /**
     * Builds a reproducible [DataSourceReference.Generated] for [family] with the
     * supplied scalar [params] (parameter name to value). The substrate importer
     * does the actual reproducible sampling; a positive [streamNumber] reproduces.
     */
    fun buildRef(
        family: DistributionFamilyDescriptor,
        params: Map<String, Double>,
        sampleSize: Int,
        streamNumber: Int,
        name: String
    ): DataSourceReference.Generated {
        val rvType = family.rvType as? RVType
            ?: error("family '${family.id}' has no samplable RVType")
        val rv = RVData(rvType, params.mapValues { doubleArrayOf(it.value) })
        return DataSourceReference.Generated(
            rv = rv, sampleSize = sampleSize, streamNumber = streamNumber, name = name
        )
    }
}
