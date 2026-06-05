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

import ksl.app.dist.config.DataSourceReference
import ksl.app.dist.config.FitConfiguration
import ksl.app.dist.runner.FittingRunner
import ksl.utilities.random.rvariable.ExponentialRV
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Proves the GUI reporting path: given a fit result and the dataset's raw data,
 * [FitReports.single] renders the canonical engine report (via the DTO adapters)
 * rather than the plot-free table report or the server-rendered fallback.
 */
class FitReportsTest {

    @Test
    fun `single renders the canonical report from the DTO plus raw data`() {
        val rv = ExponentialRV(2.5, streamNum = 31)
        val data = DoubleArray(300) { rv.value }
        val result = FittingRunner.fit(
            FitConfiguration(DataSourceReference.Inline(mapOf("expo" to data)))
        )
        val dir = Files.createTempDirectory("fitreports-test")
        val path = FitReports.single(result, rawData = data, dir = dir)
        val html = Files.readString(path)
        // Section titles only the canonical extensions emit (the DTO table
        // renderer never produces these), proving single() took the canonical path.
        listOf("Box Plot Summary", "Data Visualization", "Rankings", "Goodness of Fit Tests")
            .forEach { assertTrue(html.contains(it), "canonical report missing: '$it'") }
    }

    @Test
    fun `all-fitted-distributions report details more distributions than recommended`() {
        val rv = ExponentialRV(2.5, streamNum = 31)
        val data = DoubleArray(300) { rv.value }
        val result = FittingRunner.fit(
            FitConfiguration(DataSourceReference.Inline(mapOf("expo" to data)))
        )
        val dir = Files.createTempDirectory("fitreports-test-all")
        // The two writes target the same dir/file; each string is read before the next overwrites it.
        val recommended = Files.readString(FitReports.single(result, data, dir, allGoodnessOfFit = false))
        val allFits = Files.readString(FitReports.single(result, data, dir, allGoodnessOfFit = true))
        val recCount = recommended.split("Goodness of Fit Tests").size - 1
        val allCount = allFits.split("Goodness of Fit Tests").size - 1
        assertTrue(allCount > 1, "all-fits report should detail multiple distributions, got $allCount")
        assertTrue(allCount > recCount, "all-fits ($allCount) should detail more than recommended ($recCount)")
    }
}
