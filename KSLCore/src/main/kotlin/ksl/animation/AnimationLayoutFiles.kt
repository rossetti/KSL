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

package ksl.animation

import net.peanuuutz.tomlkt.Toml
import java.nio.file.Files
import java.nio.file.Path

/**
 * File and TOML codecs for [AnimationLayout], kept apart from the layout data itself.
 *
 * `AnimationLayout` and its element types describe an animation's presentation and must stay free of
 * platform I/O, because the same declarations are compiled for a non-JVM target to drive a web
 * renderer. Reading and writing files is inherently platform-bound, so it lives here instead.
 *
 * These are same-package extensions, so every existing call site is unchanged: `layout.writeToFile(p)`
 * and `AnimationLayout.read(p)` continue to compile and mean exactly what they did before.
 *
 * TOML is here rather than alongside `toJson` for the same reason: the TOML codec is only available for
 * the JVM, while the JSON codec is multiplatform. A layout is therefore readable and writable as TOML
 * anywhere KSL itself runs, and readable as JSON anywhere at all.
 */

/** Writes this layout to [path] (typically a `.lay.json` file), UTF-8, pretty-printed. */
fun AnimationLayout.writeToFile(path: Path) {
    Files.newBufferedWriter(path).use { it.write(toJson()) }
}

/** Serializes this layout to TOML (an alternate, human-friendly layout format, 8E.2). */
fun AnimationLayout.toToml(): String = AnimationLayout.tomlFormat.encodeToString(AnimationLayout.serializer(), this)

/** Writes this layout to [path] (typically a `.lay.toml` file) as TOML. */
fun AnimationLayout.writeTomlToFile(path: Path) {
    Files.newBufferedWriter(path).use { it.write(toToml()) }
}

/**
 * TOML codec for the layout (handles the sealed spatial-space hierarchy via its serial names). Tolerates
 * keys this version no longer models (e.g. the retired `dashboards`) so older files still load.
 */
val AnimationLayout.Companion.tomlFormat: Toml
    get() = TOML

private val TOML: Toml = Toml { ignoreUnknownKeys = true }

/** Parses a TOML layout string into an [AnimationLayout] (8E.2). */
fun AnimationLayout.Companion.fromToml(text: String): AnimationLayout =
    tomlFormat.decodeFromString(AnimationLayout.serializer(), text)

/** Reads an [AnimationLayout] from [path]. */
fun AnimationLayout.Companion.readFromFile(path: Path): AnimationLayout =
    AnimationLayout.fromJson(Files.readString(path))

/** Reads an [AnimationLayout] from a `.lay.toml` [path]. */
fun AnimationLayout.Companion.readTomlFromFile(path: Path): AnimationLayout =
    AnimationLayout.fromToml(Files.readString(path))

/** Reads a layout from [path], picking the codec by extension: `.toml` -> TOML, else JSON. */
fun AnimationLayout.Companion.read(path: Path): AnimationLayout =
    if (path.fileName.toString().endsWith(".toml", ignoreCase = true)) AnimationLayout.readTomlFromFile(path)
    else AnimationLayout.readFromFile(path)
