/*
 * The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2022  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.utilities.io.report.renderer

import ksl.utilities.io.KSL
import java.nio.file.Path

/**
 * Shared configuration passed to every [ksl.utilities.io.report.visitor.ReportVisitor]
 * renderer. Provides output paths, numeric formatting preferences, and rendering limits.
 *
 * Defaults are drawn from the [KSL] global singleton so callers need not specify
 * paths explicitly in the common case.
 *
 * @param outputDir        directory for report files; defaults to [KSL.outDir]
 * @param plotDir          directory for saved plot files; defaults to [KSL.plotDir]
 * @param confidenceLevel  default confidence level for [ksl.utilities.io.report.ast.ReportNode.StatTable]
 *                         nodes that do not specify their own; must be in (0, 1)
 * @param numericPrecision number of decimal places for formatted numeric values in tables;
 *                         must be in [0, 15]
 * @param maxPlotsPerSection maximum number of plots rendered per section before a
 *                           truncation notice is emitted; prevents very large HTML files
 *                           in models with many responses
 */
data class RenderContext(
    val outputDir: Path = KSL.outDir,
    val plotDir: Path = KSL.plotDir,
    val confidenceLevel: Double = 0.95,
    val numericPrecision: Int = 4,
    val maxPlotsPerSection: Int = 20
) {
    init {
        require(confidenceLevel > 0.0 && confidenceLevel < 1.0) {
            "confidenceLevel must be in (0, 1), was $confidenceLevel"
        }
        require(numericPrecision in 0..15) {
            "numericPrecision must be in [0, 15], was $numericPrecision"
        }
        require(maxPlotsPerSection > 0) {
            "maxPlotsPerSection must be > 0, was $maxPlotsPerSection"
        }
    }

    /**
     * Formats a [Double] value to a string using [numericPrecision] decimal places.
     * Returns `"—"` for [Double.isNaN] or [Double.isInfinite] values.
     */
    fun fmt(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "—"
        else -> "%.${numericPrecision}f".format(value)
    }

    /**
     * Formats a [Double] as a percentage string (value × 100) with [numericPrecision]
     * decimal places and a `%` suffix. Returns `"—"` for [Double.isNaN] or
     * [Double.isInfinite] values.
     */
    fun pct(value: Double): String = when {
        value.isNaN() || value.isInfinite() -> "—"
        else -> "%.${numericPrecision}f%%".format(value * 100.0)
    }
}
