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

package ksl.app.optimization.results

import ksl.simopt.solvers.SolverStateSnapshot
import ksl.utilities.io.plotting.BasePlot
import ksl.utilities.io.plotting.PlotIfc
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Convergence plot for a simulation-optimization run.
 *
 *  Extends [BasePlot] so it slots directly into the framework's
 *  [ksl.utilities.io.report.dsl.ReportBuilder.plot] DSL — the HTML
 *  renderer embeds an **interactive** lets-plot fragment (zoom +
 *  hover) via [BasePlot.toEmbeddedHTML]; the Markdown renderer
 *  exports a PNG and references it; etc.
 *
 *  Plot shape:
 *  - X axis: `iteration`
 *  - Y axis: best estimated objective at that iteration
 *  - One geom_line + geom_point series across the run's history
 *
 *  Sentinel "no feasible solution yet" values (`±Double.MAX_VALUE`,
 *  `±Infinity`, `NaN`) are filtered out before plotting so the Y
 *  axis isn't crushed by 1.8e308 seed values.
 *
 *  Substrate-level API — usable by any UI shell.
 */
class ConvergencePlot(
    history: List<SolverStateSnapshot>
) : BasePlot() {

    init {
        title = "Convergence"
        xLabel = "Iteration"
        yLabel = "Best estimated objective"
        width = 720
        height = 360
    }

    /** Filtered, finite-only view of the snapshots. */
    private val plottable: List<SolverStateSnapshot> = history.filter {
        it.estimatedObjFncValue.isFinitePlottable()
    }

    /** `true` when at least one plottable snapshot exists; callers
     *  should check this before adding the plot to a report or
     *  writing it to disk. */
    val hasData: Boolean get() = plottable.isNotEmpty()

    override fun buildPlot(): Plot {
        val data = mapOf(
            "iteration" to plottable.map { it.iterationNumber },
            "est_obj" to plottable.map { it.estimatedObjFncValue }
        )
        return ggplot(data) +
            geomLine { x = "iteration"; y = "est_obj" } +
            geomPoint { x = "iteration"; y = "est_obj" } +
            labs(title = title, x = xLabel, y = yLabel) +
            ggsize(width, height)
    }
}

/**
 *  Convenience helpers around [ConvergencePlot] for writing a PNG
 *  sibling artifact in the run directory.  The interactive embed
 *  in the HTML report is handled by the report DSL — this writer
 *  only produces the static PNG for users who want an image they
 *  can drop into slides or share without a browser.
 *
 *  Substrate-level API — usable by any UI shell.
 */
object ConvergencePlotBuilder {

    /** Build the plot wrapper for [history].  Returns `null` when
     *  no snapshot has a plottable (finite, non-sentinel) value. */
    fun buildPlot(history: List<SolverStateSnapshot>): ConvergencePlot? {
        val plot = ConvergencePlot(history)
        return if (plot.hasData) plot else null
    }

    /** Write the static PNG sibling artifact for [history] to [path].
     *  Returns `true` on success, `false` when there's no plottable
     *  data or when the write throws. */
    fun write(history: List<SolverStateSnapshot>, path: Path): Boolean = try {
        val plot = buildPlot(history) ?: return false
        Files.createDirectories(path.parent)
        val fileName = path.fileName.toString().substringBeforeLast('.')
        plot.saveToFile(
            fileName = fileName,
            directory = path.parent,
            plotTitle = plot.title,
            extType = PlotIfc.ExtType.PNG
        )
        Files.exists(path)
    } catch (_: Throwable) {
        false
    }
}

/** Sentinel-aware "plottable" check — excludes the `±MAX_VALUE`
 *  seeds solvers use to mark "no feasible solution yet" so the
 *  Y axis isn't dominated by 1.8e308. */
private fun Double.isFinitePlottable(): Boolean =
    this.isFinite() && this != Double.MAX_VALUE && this != -Double.MAX_VALUE
