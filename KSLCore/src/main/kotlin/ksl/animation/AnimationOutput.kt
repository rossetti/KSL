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

import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import ksl.utilities.io.KSL
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Opens a UTF-8 writer on [path], transparently gzip-compressing when the path ends in `.gz`
 * (a `.atf.gz` trace; the JSON-Lines format compresses ~10×, bounding large traces — 8F.8).
 */
internal fun openTraceWriter(path: Path): BufferedWriter =
    if (path.fileName.toString().endsWith(".gz", ignoreCase = true))
        GZIPOutputStream(Files.newOutputStream(path)).bufferedWriter()
    else
        Files.newBufferedWriter(path)

/** Opens a UTF-8 reader on [path], transparently gunzipping a `.gz` trace. */
internal fun openTraceReader(path: Path): BufferedReader =
    if (path.fileName.toString().endsWith(".gz", ignoreCase = true))
        GZIPInputStream(Files.newInputStream(path)).bufferedReader()
    else
        Files.newBufferedReader(path)

/**
 * Writes an animation trace in the JSON Lines (`.atf`) format: an
 * [AnimationTraceHeader] on the first line, then one [AnimationEvent] per line.
 * Each line is independently valid JSON, so the file is streamable and
 * inspectable with standard tools (a text editor, `jq`, Python).
 *
 * The writer is renderer- and engine-agnostic: it targets any [Writer], which is
 * why it can be unit-tested against an in-memory `StringWriter` and used in
 * production against a file via [toFile]. It is [Closeable]; wrap it in `use { }`
 * or call [close] to flush and release the underlying writer.
 *
 * Usage is "header first, then events": [writeHeader] must be called exactly once
 * before any [write].
 */
class JsonLinesAnimationOutput(
    private val writer: Writer
) : Closeable {

    private var headerWritten = false

    /** Writes the trace header as the first line. Must be called exactly once, before any [write]. */
    fun writeHeader(header: AnimationTraceHeader) {
        check(!headerWritten) { "the trace header has already been written" }
        writeLine(header.encodeToLine())
        headerWritten = true
    }

    /** Appends one event as a single JSON line. Requires [writeHeader] to have been called. */
    fun write(event: AnimationEvent) {
        check(headerWritten) { "writeHeader(...) must be called before writing events" }
        writeLine(AnimationEvent.encodeToLine(event))
    }

    /** Appends every event in [events], in order. */
    fun writeAll(events: Iterable<AnimationEvent>) {
        for (event in events) write(event)
    }

    private fun writeLine(line: String) {
        writer.write(line)
        writer.write("\n")
    }

    /** Flushes buffered bytes to the underlying destination without closing it. */
    fun flush() {
        writer.flush()
    }

    override fun close() {
        writer.flush()
        writer.close()
    }

    companion object {
        /** Opens a UTF-8 buffered writer on [path] for writing a `.atf` trace (gzip if `*.gz`). */
        fun toFile(path: Path): JsonLinesAnimationOutput =
            JsonLinesAnimationOutput(openTraceWriter(path))
    }
}

/**
 * Reads a JSON Lines (`.atf`) trace produced by [JsonLinesAnimationOutput]:
 * [readHeader] consumes the first line; [events] streams the remaining lines as
 * [AnimationEvent]s lazily, so a large trace is never fully held in memory.
 *
 * The reader is positional: call [readHeader] first, then iterate [events].
 * It is [Closeable]; wrap it in `use { }` or call [close].
 */
class TraceFileReader(
    private val reader: BufferedReader
) : Closeable {

    /**
     *  How many lines [events] skipped because they named an event type this version does not
     *  know. A trace written by a newer version can carry such events; skipping them, rather than
     *  failing on the first, lets an older reader show everything it does understand. A line that
     *  is not a well-formed event of a known type still fails.
     */
    var numSkippedUnknownEvents: Int = 0
        private set

    /** Reads and decodes the header line. Call this once, before [events]. */
    fun readHeader(): AnimationTraceHeader {
        val line = reader.readLine() ?: error("empty trace: expected a header line")
        return AnimationTraceHeader.decodeFromLine(line)
    }

    /**
     * A lazy sequence over the remaining event lines. Blank lines are skipped.
     * Because it reads from the shared [reader], consume it after [readHeader] and
     * before [close].
     */
    fun events(): Sequence<AnimationEvent> = sequence {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) continue
            val event = try {
                AnimationEvent.decodeFromLine(line)
            } catch (e: SerializationException) {
                if (isUnknownEventType(line)) {
                    numSkippedUnknownEvents++
                    continue
                }
                throw e
            }
            yield(event)
        }
    }

    override fun close() {
        reader.close()
    }

    companion object {
        /**
         * Reads an entire `.atf` file at [path] into memory, returning its header
         * paired with the list of events. Convenient for small traces and tests;
         * use a [TraceFileReader] directly to stream large ones.
         */
        fun readAll(path: Path): Pair<AnimationTraceHeader, List<AnimationEvent>> {
            openTraceReader(path).use { bufferedReader ->
                val traceReader = TraceFileReader(bufferedReader)
                val header = traceReader.readHeader()
                val events = traceReader.events().toList()
                if (traceReader.numSkippedUnknownEvents > 0) {
                    KSL.logger.warn {
                        "Skipped ${traceReader.numSkippedUnknownEvents} event(s) of types this version does " +
                                "not know in $path; the trace was written by a newer version (${header.kslVersion ?: "unknown"})."
                    }
                }
                return header to events
            }
        }

        /** The serial names of every event type this version can decode. */
        private val knownEventTypes: Set<String> by lazy {
            AnimationEvent.serializer().descriptor.getElementDescriptor(1).elementNames.toSet()
        }

        /** True when [line] is a JSON object whose event type is not one this version knows. */
        private fun isUnknownEventType(line: String): Boolean {
            val element = try {
                AnimationEvent.format.parseToJsonElement(line)
            } catch (e: SerializationException) {
                return false
            }
            val type = (element as? JsonObject)?.get("event")?.let { (it as? JsonPrimitive)?.contentOrNull }
                ?: return false
            return type !in knownEventTypes
        }

        /**
         * Streams the trace at [path] to [block] — its header plus a lazy event sequence — and
         * closes the reader when [block] returns, so early termination (e.g. a saturation stop)
         * can't leak it. Use this for large traces; [readAll] is the load-everything convenience.
         */
        fun <R> readStreaming(path: Path, block: (AnimationTraceHeader, Sequence<AnimationEvent>) -> R): R =
            openTraceReader(path).use { reader ->
                val traceReader = TraceFileReader(reader)
                block(traceReader.readHeader(), traceReader.events())
            }
    }
}
