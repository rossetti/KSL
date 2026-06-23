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

package ksl.app.optimization

/**
 *  Render an objective-function value for human display.
 *
 *  - `+Double.MAX_VALUE` / `Double.POSITIVE_INFINITY` → `"+∞"`
 *  - `-Double.MAX_VALUE` / `Double.NEGATIVE_INFINITY` → `"−∞"`
 *  - `NaN` → `"—"` (em-dash, "not yet evaluated")
 *  - everything else → four-digit fixed precision (`"%.4f"`)
 *
 *  Solvers commonly seed the "no feasible solution yet" state with
 *  the `±Double.MAX_VALUE` sentinel; printing that as a 309-digit
 *  decimal alarms users without conveying meaning.  Mapping the
 *  sentinel to the infinity symbol matches its intent.
 *
 *  Substrate-level API — usable by any UI shell (Swing live panels,
 *  HTML reports, CLI status output, etc.) so the same value always
 *  renders the same way.
 */
fun formatObjective(v: Double): String = when {
    v == Double.MAX_VALUE || v == Double.POSITIVE_INFINITY -> "+∞"
    v == -Double.MAX_VALUE || v == Double.NEGATIVE_INFINITY -> "−∞"
    v.isNaN() -> "—"
    else -> "%.4f".format(v)
}
