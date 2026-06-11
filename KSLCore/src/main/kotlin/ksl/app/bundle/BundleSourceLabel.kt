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

package ksl.app.bundle

/**
 *  The source a loaded bundle came from: its JAR file name, or `"classpath"`
 *  for a classpath-discovered bundle.  The human-facing disambiguator shown in
 *  pickers, the Loaded Bundles dialog, and newest-wins dedup disclosure when
 *  several sources carry the same `bundleId`.
 */
fun bundleSourceLabel(bundle: LoadedBundle): String =
    bundle.sourceJar?.fileName?.toString() ?: "classpath"
