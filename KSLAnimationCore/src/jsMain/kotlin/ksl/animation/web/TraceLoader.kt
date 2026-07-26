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

package ksl.animation.web

import ksl.animation.AnimationEvent
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.app.animation.io.AnimationSource
import kotlinx.browser.window

/** How far a load has progressed, for a progress indicator. */
internal data class LoadProgress(val phase: String, val fraction: Double)

/**
 * Fetches a `.atf` trace and its layout and turns them into an [AnimationSource].
 *
 * The interesting part is the parse, not the download. A trace compresses about tenfold, so even a large
 * one is a couple of hundred kilobytes on the wire and decompresses in tens of milliseconds — but
 * decoding twenty thousand JSON-Lines records costs well over a second, which is the dominant cost of
 * opening an animation and far too long to spend with a frozen page.
 *
 * So the parse is chunked: a few thousand lines per turn, yielding to the browser between chunks so the
 * page keeps painting and a progress bar can move. It is the same total work, spread out where the user
 * can see it happening. (A worker thread would take the work off the main thread entirely, which is worth
 * doing if traces get much larger; chunking is a fraction of the complexity and enough for the sizes KSL
 * actually produces.)
 *
 * Both plain `.atf` and gzipped `.atf.gz` are accepted; the browser's own decompression handles the
 * latter, so a trace can be served as a static file with no server-side support.
 */
internal class TraceLoader(private val onProgress: (LoadProgress) -> Unit = {}) {

    /**
     * Loads [traceUrl] and the optional [layoutUrl], calling [onDone] with the assembled source, or
     * [onError] with a message. [assetBase] becomes the source's base for relative image references;
     * when null it defaults to the layout's own directory, matching what the desktop does.
     */
    fun load(
        traceUrl: String,
        layoutUrl: String?,
        assetBase: String?,
        onDone: (AnimationSource) -> Unit,
        onError: (String) -> Unit
    ) {
        onProgress(LoadProgress("fetching", 0.0))
        fetchText(layoutUrl) { layoutText ->
            if (layoutUrl != null && layoutText == null) {
                onError("could not fetch layout: $layoutUrl")
                return@fetchText
            }
            fetchText(traceUrl) { traceText ->
                if (traceText == null) {
                    onError("could not fetch trace: $traceUrl")
                    return@fetchText
                }
                val layout = layoutText?.let {
                    try {
                        AnimationLayout.fromJson(it)
                    } catch (e: Exception) {
                        onError("could not parse layout: ${e.message}")
                        return@fetchText
                    }
                }
                parseChunked(
                    traceText,
                    onDone = { header, events ->
                        onDone(AnimationSource(layout, header, events, assetBase ?: layoutUrl?.parentUrl()))
                    },
                    onError = onError
                )
            }
        }
    }

    /** Assembles a source from payloads already embedded in the page (the self-contained HTML case). */
    fun loadInline(
        traceText: String,
        layoutJson: String?,
        assetBase: String?,
        onDone: (AnimationSource) -> Unit,
        onError: (String) -> Unit
    ) {
        val layout = layoutJson?.let {
            try {
                AnimationLayout.fromJson(it)
            } catch (e: Exception) {
                onError("could not parse inline layout: ${e.message}")
                return
            }
        }
        parseChunked(
            traceText,
            onDone = { header, events -> onDone(AnimationSource(layout, header, events, assetBase)) },
            onError = onError
        )
    }

    /**
     * Decodes the JSON-Lines body in chunks, yielding between them.
     *
     * A `.atf` is a header line followed by one event per line, so it can be split and decoded
     * independently — which is what makes chunking possible at all, and what makes the format inspectable
     * with ordinary text tools.
     */
    private fun parseChunked(
        text: String,
        onDone: (AnimationTraceHeader, List<AnimationEvent>) -> Unit,
        onError: (String) -> Unit
    ) {
        val lines = text.split('\n')
        var index = 0
        // Skip leading blanks to find the header.
        while (index < lines.size && lines[index].isBlank()) index++
        if (index >= lines.size) {
            onError("empty trace: no header line")
            return
        }
        val header = try {
            AnimationTraceHeader.decodeFromLine(lines[index])
        } catch (e: Exception) {
            onError("could not parse trace header: ${e.message}")
            return
        }
        if (header.formatVersion > AnimationEvent.FORMAT_VERSION) {
            onError(
                "trace format version ${header.formatVersion} is newer than this player understands " +
                    "(${AnimationEvent.FORMAT_VERSION})"
            )
            return
        }
        index++

        val events = ArrayList<AnimationEvent>(lines.size)
        var malformed = 0

        fun step() {
            val end = minOf(index + CHUNK_LINES, lines.size)
            while (index < end) {
                val line = lines[index]
                index++
                if (line.isBlank()) continue
                try {
                    events.add(AnimationEvent.decodeFromLine(line))
                } catch (e: Exception) {
                    // One unreadable record should not lose the whole animation; count and carry on.
                    malformed++
                }
            }
            if (index < lines.size) {
                onProgress(LoadProgress("parsing", index.toDouble() / lines.size))
                window.setTimeout({ step() }, 0)
            } else {
                onProgress(LoadProgress("indexing", 1.0))
                if (malformed > 0) {
                    console.warn("KSL animation: skipped $malformed unreadable trace record(s)")
                }
                // Yield once more so the progress bar paints before the indexing pass blocks.
                window.setTimeout({ onDone(header, events) }, 0)
            }
        }
        step()
    }

    /**
     * Fetches [url] as text, transparently decompressing a `.gz` body. Calls back with null on any
     * failure, so a caller reports a message rather than an exception crossing an async boundary.
     */
    private fun fetchText(url: String?, onDone: (String?) -> Unit) {
        if (url == null) {
            onDone(null)
            return
        }
        val gzipped = url.endsWith(".gz", ignoreCase = true)
        try {
            fetchTextImpl(url, gzipped, { text -> onDone(text) }, { onDone(null) })
        } catch (e: Throwable) {
            onDone(null)
        }
    }

    private companion object {
        /**
         * Lines decoded per turn. Large enough that the per-turn overhead is negligible, small enough that
         * the page stays responsive between turns.
         */
        const val CHUNK_LINES = 4000
    }
}

/** The directory part of a URL, used as the default base for a layout's relative image references. */
private fun String.parentUrl(): String = substringBeforeLast('/', "")

/**
 * Fetches a URL as text, piping a gzipped body through the browser's own decompression.
 *
 * Written against the raw APIs because Kotlin's typed wrappers do not cover `DecompressionStream`, and
 * because the streaming form avoids holding both the compressed and decompressed copies of a large trace.
 */
private fun fetchTextImpl(
    url: String,
    gzipped: Boolean,
    onDone: (String) -> Unit,
    onError: (Throwable) -> Unit
) {
    js(
        """
        (function () {
            fetch(url).then(function (response) {
                if (!response.ok) { throw new Error('HTTP ' + response.status); }
                if (gzipped && typeof DecompressionStream !== 'undefined') {
                    var stream = response.body.pipeThrough(new DecompressionStream('gzip'));
                    return new Response(stream).text();
                }
                return response.text();
            }).then(function (text) {
                onDone(text);
            }).catch(function (error) {
                onError(error);
            });
        })()
        """
    )
}
