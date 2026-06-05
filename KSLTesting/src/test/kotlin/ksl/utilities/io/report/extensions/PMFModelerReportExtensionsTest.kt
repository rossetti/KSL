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

package ksl.utilities.io.report.extensions

import ksl.utilities.distributions.fitting.PMFModeler
import ksl.utilities.distributions.fitting.PoissonGoodnessOfFit
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.PoissonRV
import ksl.utilities.toDoubles
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Characterization tests for the discrete report extensions. They assert the
 * standard report's structure so a mechanical refactor of these extensions —
 * rendering against data interfaces rather than the live [PMFModeler] — cannot
 * silently drop or rewire a section. Discrete goodness-of-fit is deterministic
 * (no bootstrap), so the structure is stable.
 */
class PMFModelerReportExtensionsTest {

    private fun sampleData(): IntArray {
        val d = PoissonRV(5.0, 1).sample(150)
        return IntArray(d.size) { d[it].toInt() }
    }

    @Test
    fun `EDA report contains all discrete sections`() {
        val text = PMFModeler(sampleData()).toReport().toText()
        listOf(
            "Discrete Data Summary", "Frequency Distribution", "Dispersion Analysis",
            "Index of Dispersion", "Observations", "Autocorrelation"
        ).forEach { assertTrue(text.contains(it), "discrete EDA report missing: '$it'") }
    }

    @Test
    fun `full discrete report contains all sections`() {
        val data = sampleData()
        val modeler = PMFModeler(data)
        val gof = PoissonGoodnessOfFit(data.toDoubles(), mean = data.average())
        val text = gof.toReport(modeler).toText()
        listOf(
            "Discrete Data Summary", "Discrete Data Visualization", "Chi-Squared Bin Table",
            "Goodness of Fit Test", "Dispersion Tests", "Distribution Comparison"
        ).forEach { assertTrue(text.contains(it), "full discrete report missing: '$it'") }
    }
}
