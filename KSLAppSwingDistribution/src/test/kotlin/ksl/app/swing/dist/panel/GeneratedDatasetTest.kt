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

import ksl.app.dist.catalog.FittingCatalog
import ksl.app.dist.data.DatasetImporter
import ksl.utilities.random.rvariable.RVType
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the GUI "generate data" path: the reference built from the form
 * selections is valid and reproducible — the substrate importer materializes it
 * into a dataset of the requested size, and a positive stream number reproduces.
 */
class GeneratedDatasetTest {

    @Test
    fun `buildRef yields a reproducible, importable generated dataset`() {
        val family = FittingCatalog.families.first { it.rvType == RVType.Poisson }
        val ref = GeneratedDataset.buildRef(
            family, params = mapOf("mean" to 5.0), sampleSize = 200, streamNumber = 1, name = "pois"
        )
        val importer = DatasetImporter.default
        val datasets = importer.import(ref)

        assertEquals(1, datasets.size)
        val ds = datasets.single()
        assertEquals("pois", ds.name)
        assertEquals(200, ds.data.size)
        assertTrue(
            ds.data.all { it.isFinite() && it >= 0.0 && it == floor(it) },
            "a Poisson sample should be non-negative integers"
        )

        // A positive stream number reproduces: re-importing the same ref is identical.
        val again = importer.import(ref).single()
        assertTrue(ds.data.contentEquals(again.data), "stream > 0 must reproduce the same sample")
    }
}
