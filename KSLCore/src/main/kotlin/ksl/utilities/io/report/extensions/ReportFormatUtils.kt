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

package ksl.utilities.io.report.extensions

/**
 * Shared numeric formatting helpers for report extension functions.
 *
 * All functions are `internal` — visible across the extensions package within KSLCore
 * but not exposed as public API.
 *
 * **Em-dash sentinel:** every function returns `"\u2014"` (U+2014 EM DASH) when the
 * input is NaN or infinite, providing a consistent "no value" marker across all
 * rendered report formats (HTML, Markdown, Text, LaTeX).
 */

/** Formats [value] to 4 decimal places; returns `"\u2014"` for NaN or infinite values. */
internal fun fmtDouble(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "\u2014"
    else -> "%.4f".format(value)
}

/**
 * Formats [value] as a percentage with 2 decimal places (e.g. `"12.34%"`);
 * returns `"\u2014"` for NaN or infinite values.
 *
 * The input is expected to be a proportion in [0, 1]; it is multiplied by 100
 * before formatting.
 */
internal fun fmtPct(value: Double): String = when {
    value.isNaN() || value.isInfinite() -> "\u2014"
    else -> "%.2f%%".format(value * 100.0)
}

/**
 * Formats a histogram bin-edge value to 4 significant figures.
 *
 * Special cases:
 * - `Double.NEGATIVE_INFINITY` or `-Double.MAX_VALUE` → `"\u2212\u221E"` (−∞)
 * - `Double.POSITIVE_INFINITY` or `Double.MAX_VALUE`  → `"+\u221E"` (+∞)
 * - NaN → `"\u2014"`
 *
 * Uses `"%.4g"` (significant figures) rather than `"%.4f"` (decimal places) so
 * that very large or very small bin boundaries are rendered compactly.
 */
internal fun fmtLimit(value: Double): String = when {
    value == Double.NEGATIVE_INFINITY || value == -Double.MAX_VALUE -> "\u2212\u221E"
    value == Double.POSITIVE_INFINITY || value == Double.MAX_VALUE  -> "+\u221E"
    value.isNaN() -> "\u2014"
    else -> "%.4g".format(value)
}

/**
 * Formats a control bound value to 4 decimal places, rendering infinite bounds
 * as the appropriate infinity symbol.
 *
 * Special cases:
 * - `Double.NEGATIVE_INFINITY` → `"\u2212\u221E"` (−∞)
 * - `Double.POSITIVE_INFINITY` → `"+\u221E"` (+∞)
 * - NaN → `"\u2014"`
 *
 * Unlike [fmtLimit], this function uses `"%.4f"` and does not treat
 * `-Double.MAX_VALUE` / `Double.MAX_VALUE` as infinity, since those are
 * valid finite control bounds.
 */
internal fun fmtBound(value: Double): String = when {
    value == Double.NEGATIVE_INFINITY -> "\u2212\u221E"
    value == Double.POSITIVE_INFINITY -> "+\u221E"
    value.isNaN()                     -> "\u2014"
    else                              -> "%.4f".format(value)
}
