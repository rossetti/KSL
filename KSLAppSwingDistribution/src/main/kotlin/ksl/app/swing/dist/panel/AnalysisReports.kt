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

import ksl.utilities.distributions.fitting.PDFModeler
import ksl.utilities.io.plotting.ACFPlot
import ksl.utilities.io.plotting.ObservationsPlot
import ksl.utilities.io.report.dsl.ReportBuilder
import ksl.utilities.io.report.dsl.report
import ksl.utilities.io.report.extensions.histogram
import ksl.utilities.io.report.extensions.statistic
import ksl.utilities.io.report.writeHtml
import ksl.utilities.statistic.Histogram
import ksl.utilities.statistic.HistogramIfc
import ksl.utilities.statistic.Statistic
import java.nio.file.Path

/** Histogram binning choice for the Analysis tab. */
sealed interface HistogramBinning {
    data object Auto : HistogramBinning
    data class Manual(val lowerLimit: Double, val binWidth: Double, val numBins: Int) : HistogramBinning
}

/**
 * Generates per-dataset exploratory-analysis artifacts as HTML, built with the
 * KSL report framework (`report { … }` + the `statistic`/`histogram`/`plot`
 * extensions) and the lower-level plot/shift utilities. Each function writes an
 * HTML file into the dataset's folder and returns its path; the caller opens it
 * in the system browser, matching the sibling apps' rendering approach.
 */
object AnalysisReports {

    fun statistics(name: String, data: DoubleArray, dir: Path): Path {
        val doc = report("Statistics — $name", outputDir = dir, plotDir = dir) {
            statistic(Statistic(name, data))
        }
        return doc.writeHtml(path = dir.resolve("statistics.html")).toPath()
    }

    fun histogram(name: String, data: DoubleArray, dir: Path, binning: HistogramBinning): Path {
        val h = buildHistogram(name, data, binning)
        val doc = report("Histogram — $name", outputDir = dir, plotDir = dir) {
            histogram(h, caption = name, showPlot = true)
        }
        return doc.writeHtml(path = dir.resolve("histogram.html")).toPath()
    }

    fun observations(name: String, data: DoubleArray, dir: Path): Path {
        val obs = ObservationsPlot(data, dataName = name).apply { title = "Observations — $name" }
        val doc = report("Observations — $name", outputDir = dir, plotDir = dir) { plot(obs, caption = name) }
        return doc.writeHtml(path = dir.resolve("observations.html")).toPath()
    }

    fun acf(name: String, data: DoubleArray, dir: Path): Path {
        val acf = ACFPlot(data, dataName = name).apply { title = "ACF — $name" }
        val doc = report("ACF — $name", outputDir = dir, plotDir = dir) { plot(acf, caption = name) }
        return doc.writeHtml(path = dir.resolve("acf.html")).toPath()
    }

    fun shift(name: String, data: DoubleArray, dir: Path, ciSamples: Int, ciLevel: Double): Path {
        val doc = report("Shift analysis — $name", outputDir = dir, plotDir = dir) {
            shiftContent(data, ciSamples, ciLevel)
        }
        return doc.writeHtml(path = dir.resolve("shift.html")).toPath()
    }

    /** One combined report covering every analysis for a dataset. */
    fun fullReport(
        name: String,
        data: DoubleArray,
        dir: Path,
        binning: HistogramBinning,
        ciSamples: Int,
        ciLevel: Double
    ): Path {
        val h = buildHistogram(name, data, binning)
        val obs = ObservationsPlot(data, dataName = name).apply { title = "Observations — $name" }
        val acf = ACFPlot(data, dataName = name).apply { title = "ACF — $name" }
        val doc = report("Analysis — $name", outputDir = dir, plotDir = dir) {
            statistic(Statistic(name, data))
            histogram(h, caption = name, showPlot = true)
            section("Observations") { plot(obs) }
            section("Autocorrelation") { plot(acf) }
            section("Shift analysis") { shiftContent(data, ciSamples, ciLevel) }
        }
        return doc.writeHtml(path = dir.resolve("analysis.html")).toPath()
    }

    /** Recommended manual binning derived from the data, used to seed the manual fields. */
    fun recommendedBinning(data: DoubleArray): HistogramBinning.Manual {
        val s = Statistic(data)
        return HistogramBinning.Manual(
            lowerLimit = s.min,
            binWidth = Histogram.recommendBinWidth(s.count, s.standardDeviation),
            numBins = Histogram.recommendNumBins(s.count, s.min, s.max, s.standardDeviation)
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun buildHistogram(name: String, data: DoubleArray, binning: HistogramBinning): HistogramIfc =
        when (binning) {
            HistogramBinning.Auto -> Histogram.create(data, name = name)
            is HistogramBinning.Manual -> Histogram.create(binning.lowerLimit, binning.numBins, binning.binWidth)
        }

    /** Adds the shift summary (estimated shift + CI on the minimum) to the current report builder. */
    private fun ReportBuilder.shiftContent(data: DoubleArray, ciSamples: Int, ciLevel: Double) {
        val shift = PDFModeler.leftShiftData(data).shift
        val ci = PDFModeler.confidenceIntervalForMinimum(data, numBootstrapSamples = ciSamples, level = ciLevel)
        val min = data.minOrNull() ?: 0.0
        val max = data.maxOrNull() ?: 0.0
        val pct = String.format("%.0f%%", ciLevel * 100.0)
        dataTable(
            headers = listOf("Quantity", "Value"),
            rows = listOf(
                listOf("Estimated left shift", fmt(shift)),
                listOf("Minimum", fmt(min)),
                listOf("Maximum", fmt(max)),
                listOf("$pct CI on the minimum (lower)", fmt(ci.lowerLimit)),
                listOf("$pct CI on the minimum (upper)", fmt(ci.upperLimit)),
                listOf("CI half-width", fmt(ci.halfWidth)),
                listOf("Bootstrap samples", ciSamples.toString())
            ),
            caption = "Shift and $pct confidence interval on the minimum"
        )
        val interpretation = if (shift > 0.0) {
            "The data appear left-shifted by ${fmt(shift)}. With automatic shift estimation enabled, the " +
                "fitting engine subtracts this amount before fitting."
        } else {
            "No left shift is recommended for these data."
        }
        paragraph(interpretation)
    }

    private fun fmt(v: Double): String = if (v.isFinite()) String.format("%.6g", v) else v.toString()
}
