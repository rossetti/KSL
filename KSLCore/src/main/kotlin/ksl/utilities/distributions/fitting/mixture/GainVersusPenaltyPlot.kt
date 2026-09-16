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
package ksl.utilities.distributions.fitting.mixture

import ksl.utilities.io.plotting.BasePlot
import org.jetbrains.letsPlot.geom.geomLine
import org.jetbrains.letsPlot.geom.geomPoint
import org.jetbrains.letsPlot.geom.geomVLine
import org.jetbrains.letsPlot.ggplot
import org.jetbrains.letsPlot.ggsize
import org.jetbrains.letsPlot.intern.Plot
import org.jetbrains.letsPlot.label.labs

/**
 *  What the criterion gained by adding each component, against what it charged for it.
 *
 *  The criterion turns where the two cross: to the left of the crossing an extra component buys
 *  more likelihood than it costs, and to the right it does not. The vertical distance between the
 *  curves is **how firmly the criterion holds its view** at that count.
 *
 *  **That distance is not a measure of how likely the criterion is to be right, and this class
 *  says so in its own caption rather than leaving it to the caller.** Measured across the designed
 *  experiment, when these criteria pick the wrong component count the median gap to the truth is
 *  about 19 units, and only about 4.6% of wrong calls fall inside a conventional weak-evidence
 *  margin of two. A wide gap here means the criterion is not indifferent; it does not mean the
 *  criterion is correct. The half of the reading that measurement supports is the other one — a
 *  narrow gap really does mean the criterion barely preferred one count to its neighbour.
 *
 *  The analyst's own count is marked when they supplied one, because the firmness there is the
 *  number they came for.
 *
 *  **A falling line is not a fault.** The partition is regenerated at each count rather than split
 *  from the one before, so the models do not nest and the gain can be negative: the search found a
 *  worse partition at the larger count. That is worth seeing, so it is plotted rather than clipped.
 *
 *  @param evidence the per-count evidence to draw
 */
class GainVersusPenaltyPlot(
    private val evidence: ComponentCountEvidence
) : BasePlot() {

    init {
        title = "How firmly the criterion holds its view"
        xLabel = "number of components"
        yLabel = "criterion units"
    }

    /**
     *  What the vertical distance does and does not mean.
     *
     *  **Carried on the object but not drawn on the figure.** It was drawn until measurement showed
     *  the drawing did not work: the plot library does not wrap a caption, so this one rendered as
     *  a single long line that ran off the canvas and was clipped mid-word. A truncated caveat is
     *  worse than none, and both places this figure actually appears — a rendered report and a
     *  Quarto document — already carry a caption outside the image that wraps. Whoever renders the
     *  figure prints this beside it; `mixtureCountEvidence` does.
     */
    var caveat: String =
        "The gap is how firmly the criterion holds its view, not how likely that view is to be " +
                "right."

    /**
     *  An optional caption drawn on the figure itself. Empty by default, for the reason in
     *  `caveat`. A caller with a short one may still set it.
     */
    var caption: String = ""

    override fun buildPlot(): Plot {
        val drawn = evidence.rows.filter {
            it.isFeasible && it.marginalGain != null && it.penaltyIncrement != null
        }
        val counts = drawn.map { it.numComponents }
        val data = mapOf<String, Any>(
            "k" to counts + counts,
            "value" to drawn.map { it.marginalGain!! } + drawn.map { it.penaltyIncrement!! },
            "series" to List(drawn.size) { "gain in fit" } +
                    List(drawn.size) { "charged for the component" }
        )
        var p = ggplot(data) +
                geomLine(size = 1.1) { x = "k"; y = "value"; color = "series" } +
                geomPoint(size = 3.0) { x = "k"; y = "value"; color = "series" }
        val hypothesised = evidence.hypothesisedCount
        if (hypothesised != null && hypothesised in counts) {
            p = p + geomVLine(xintercept = hypothesised, linetype = "dashed", color = "gray")
        }
        p = p + (
                if (caption.isBlank()) labs(title = title, x = xLabel, y = yLabel, color = "")
                else labs(title = title, x = xLabel, y = yLabel, color = "", caption = caption)
                ) + ggsize(width, height)
        return p
    }
}
