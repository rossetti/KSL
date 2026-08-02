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

package ksl.app.moda

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.peanuuutz.tomlkt.Toml
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Reading and writing studies, in either of the two formats they are kept in.
 *
 *  TOML is for studies people write and edit, because its sections and plain assignments survive
 *  being read and changed by hand. JSON is for studies that travel between programs. The same
 *  document is described once and both formats are produced from that description, so neither can
 *  drift from the other.
 */
object ModaDocumentFormats {

    /**
     *  Null fields are left out rather than written as empty entries, so a hand-edited study is not
     *  full of settings nobody chose. A missing entry decodes to whatever the field defaults to,
     *  which is the same thing the writer left out.
     */
    private val toml = Toml {
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /**
     *  Defaults are written out here, unlike in TOML, because a document travelling between
     *  programs should say what it means rather than rely on the reader's defaults matching the
     *  writer's.
     */
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /** The study as TOML, for someone to read or edit. */
    fun toToml(document: ModaDocument): String = toml.encodeToString(document)

    /** Reads a study from TOML. */
    fun fromToml(text: String): ModaDocument = toml.decodeFromString<ModaDocument>(text)

    /** The study as JSON, for another program to read. */
    fun toJson(document: ModaDocument): String = json.encodeToString(document)

    /** Reads a study from JSON. */
    fun fromJson(text: String): ModaDocument = json.decodeFromString<ModaDocument>(text)

    /**
     *  Reads a study from a file, choosing the format from the file's extension.
     *
     *  @throws IllegalArgumentException if the extension is not one of the formats studies are kept
     *  in, since guessing would risk reporting a parse failure for a file that was never a study
     */
    fun read(path: Path): ModaDocument {
        val text = Files.readString(path)
        return when (val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "toml" -> fromToml(text)
            "json" -> fromJson(text)
            else -> throw IllegalArgumentException(
                "A study is kept in a .toml or .json file. '$path' ends in " +
                        if (extension.isEmpty()) "no extension." else "'.$extension'."
            )
        }
    }

    /**
     *  Writes a study to a file, choosing the format from the file's extension.
     */
    fun write(document: ModaDocument, path: Path) {
        val text = when (val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()) {
            "toml" -> toToml(document)
            "json" -> toJson(document)
            else -> throw IllegalArgumentException(
                "A study is kept in a .toml or .json file. '$path' ends in " +
                        if (extension.isEmpty()) "no extension." else "'.$extension'."
            )
        }
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, text)
    }
}
