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

package ksl.app.single.results

import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * The small sidecar that records, beside a run's `<response>_Trace` files,
 * whether each traced response is time-weighted. A trace file does not record
 * that flag, yet reporting needs it to choose a state-variable sample-path plot
 * vs. an observations plot. Writing it at capture time (when the live model is
 * available) lets post-run materialization render a trace from the files alone,
 * with no model.
 *
 * Keys are the trace file *stem* — the response name with `:` replaced by `_`,
 * matching the `<stem>_Trace` file names — so a reader keyed off the file name
 * resolves the flag directly.
 */
object TraceManifest {

    /** The sidecar file name, written into the run's output directory. */
    const val FILE_NAME: String = "trace-manifest.json"

    private val json = Json { encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), Boolean.serializer())

    /** Trace file stem (`:` -> `_`) for [responseName], matching the `_Trace` file. */
    fun stemFor(responseName: String): String = responseName.replace(':', '_')

    /** Writes the `stem -> isTimeWeighted` manifest into [outputDir]; best-effort. */
    fun write(outputDir: Path, byStem: Map<String, Boolean>) {
        runCatching {
            Files.writeString(outputDir.resolve(FILE_NAME), json.encodeToString(serializer, byStem))
        }
    }

    /** Reads the manifest from [outputDir], or an empty map if absent/unreadable. */
    fun read(outputDir: Path): Map<String, Boolean> {
        val file = outputDir.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return emptyMap()
        return runCatching { json.decodeFromString(serializer, Files.readString(file)) }.getOrDefault(emptyMap())
    }
}
