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
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.toText
import ksl.utilities.moda.AdditiveMODAModel
import ksl.utilities.moda.modaReportData
import ksl.utilities.random.rvariable.ExponentialRV
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Characterization tests for the MODA report extension. They assert the standard
 * report's structure so a refactor of the renderer — rendering against the
 * [ksl.utilities.moda.MODAReportData] holder rather than the live
 * [AdditiveMODAModel] — cannot silently drop or rewire a section. The live-vs-
 * holder equality test proves the projection produced by [modaReportData] is
 * faithful to rendering the live model directly.
 */
class MODAReportExtensionsTest {

    private fun fittedModel(): AdditiveMODAModel {
        val data = ExponentialRV(10.0, streamNum = 2).sample(500)
        val m = PDFModeler(data)
        val est = m.estimateParameters(PDFModeler.allEstimators)
        return m.evaluateScoringResults(m.scoringResults(est))
    }

    @Test
    fun `MODA report contains all sections`() {
        val text = fittedModel().toReport().toText()
        listOf(
            "Metric Definitions", "Scores and Values", "Raw Scores by Alternative and Metric",
            "Overall Value", "Rankings", "1st Rank Count", "Avg Rank"
        ).forEach { assertTrue(text.contains(it), "MODA report missing: '$it'") }
    }

    @Test
    fun `live model and projected holder render identically`() {
        val model = fittedModel()
        val live   = report("X") { moda(model) }.toText()
        val holder = report("X") { moda(model.modaReportData()) }.toText()
        assertEquals(live, holder)
    }
}
