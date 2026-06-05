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

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.report.toText
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Characterization tests for the continuous report extensions. They assert the
 * standard report's structure (section titles + key tables) so that a mechanical
 * refactor of these extensions — e.g. rendering against data interfaces rather
 * than the live [PDFModeler] — cannot silently drop or rewire a section.
 *
 * Byte-for-byte snapshots are intentionally avoided: the shift-minimum CI and
 * the per-fit bootstrap CIs are stochastic, so exact output is not reproducible.
 */
class PDFModelerReportExtensionsTest {

    private fun sampleData(): DoubleArray = ExponentialRV(10.0, 1).sample(120)

    @Test
    fun `EDA report contains all data sections`() {
        val text = PDFModeler(sampleData()).toReport().toText()
        listOf(
            "Data Statistical Summary", "Sample Statistics",
            "Box Plot Summary", "Five-Number Summary", "Outlier Summary",
            "Histogram", "Shift Parameter Analysis", "Estimated Left Shift",
            "Data Visualization", "Box Plot", "Observations", "Autocorrelation"
        ).forEach { assertTrue(text.contains(it), "EDA report missing section/label: '$it'") }
    }

    @Test
    fun `full fitting report contains all sections`() {
        val modeler = PDFModeler(sampleData())
        val results = modeler.estimateAndEvaluateScores()
        val text = results.toReport(modeler, allGOF = true).toText()
        listOf(
            "Data Statistical Summary", "Data Visualization", "MODA Scoring",
            "Goodness of Fit", "Bootstrap Parameter Estimates", "Distribution Fit Plots",
            "Goodness of Fit Tests", "Chi-Squared Bin Table", "Kolmogorov-Smirnov",
            "Anderson-Darling"
        ).forEach { assertTrue(text.contains(it), "fitting report missing section/label: '$it'") }
    }
}
