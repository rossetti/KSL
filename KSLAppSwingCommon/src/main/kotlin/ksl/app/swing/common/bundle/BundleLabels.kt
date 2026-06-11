/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.swing.common.bundle

import ksl.app.bundle.LoadedBundle
import ksl.app.bundle.bundleSourceLabel

/**
 *  One-line, source-disambiguated label for a bundle in a picker, e.g.
 *  `"KSL Book Examples · v1.0.0 · book-examples.jar"`.  Showing the version
 *  and source lets the user tell apart copies of the same bundle loaded from
 *  more than one JAR (or the classpath) — the same disambiguation the
 *  conflict notice and the Loaded Bundles dialog use.
 *
 *  Centralized so every app renders bundle choices identically.
 */
fun bundlePickerLabel(displayName: String, version: String, sourceLabel: String): String {
    val name = displayName.ifBlank { "(unnamed bundle)" }
    return "$name · v$version · $sourceLabel"
}

/** [bundlePickerLabel] for a [LoadedBundle], falling back to the bundle id
 *  when the bundle declares no display name. */
fun bundlePickerLabel(lb: LoadedBundle): String =
    bundlePickerLabel(
        lb.bundle.displayName.ifBlank { lb.bundle.bundleId },
        lb.bundle.version,
        bundleSourceLabel(lb),
    )
