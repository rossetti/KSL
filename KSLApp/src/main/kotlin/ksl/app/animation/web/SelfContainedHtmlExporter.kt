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

package ksl.app.animation.web

import ksl.animation.AnimationLayout
import ksl.app.animation.io.AnimationSource
import ksl.app.animation.io.load
import ksl.app.animation.replay.ReplayModel
import ksl.app.animation.replay.autoLayout
import java.nio.file.Files
import java.nio.file.Path

/** What an exported page weighs, so a build can report or gate on it. */
data class ExportSizeReport(
    val playerBytes: Long,
    val traceBytes: Long,
    val layoutBytes: Long,
    val totalBytes: Long
) {
    /** A human-readable one-liner, e.g. "1.4 MB total (player 1.2 MB, trace 190 KB)". */
    fun summary(): String =
        "${human(totalBytes)} total (player ${human(playerBytes)}, trace ${human(traceBytes)})"

    private fun human(bytes: Long): String = when {
        bytes >= 1_048_576 -> "${(bytes * 10 / 1_048_576) / 10.0} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

/** A run to include in a gallery page: a [title], its trace, and an optional layout. */
data class AnimationRunRef(
    val title: String,
    val trace: Path,
    val layout: Path? = null,
    val notes: String? = null
)

/**
 * Writes a KSL animation as a **single HTML file** with the player, the trace and the layout all inside
 * it.
 *
 * The point is that the result has no dependencies at all: it opens from a `file://` path, survives being
 * emailed or dropped into a course management system, and keeps working years later with no server, no
 * install, and no network. For teaching that is a different artifact from a hosted page — it can be handed
 * to a student the way a PDF can.
 *
 * The trace is embedded as text in a script tag rather than as a JavaScript string literal, so no escaping
 * of the JSON is required and the file stays inspectable. That does mean the page is roughly the size of
 * the raw trace; [exportGallery] links to trace files instead, which is the better choice for a set.
 *
 * @param playerBundle the compiled `ksl-animation.js`
 */
class SelfContainedHtmlExporter private constructor(private val player: String) {

    companion object {

        /** Where the built player is packaged, so a running app needs no knowledge of the filesystem. */
        private const val PLAYER_RESOURCE = "/ksl/animation/web/ksl-animation.js"

        private const val PLAYER_FILE = "ksl-animation.js"
        private const val ESCAPED_TITLE_MARK = "@@TITLE@@"

        /**
         * What to tell a user when the player is missing. It is only ever missing in a development build:
         * the released suite always carries it, because the release wires the web build in.
         */
        const val MISSING_PLAYER_MESSAGE: String =
            "This build does not include the animation web player, so an animation cannot be exported to " +
                "HTML. Build it with:\n\n    ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack\n\n" +
                "then rebuild, or pass an explicit bundle path."

        /** Whether this build can export at all — worth asking before offering the action. */
        fun isAvailable(): Boolean =
            SelfContainedHtmlExporter::class.java.getResource(PLAYER_RESOURCE) != null

        /**
         * An exporter using the player packaged with this build, or null when it carries none.
         *
         * Reading it from the classpath rather than a path is what lets the desktop app and the MCP server
         * export without either of them knowing where a build put anything.
         */
        fun bundled(): SelfContainedHtmlExporter? =
            SelfContainedHtmlExporter::class.java.getResourceAsStream(PLAYER_RESOURCE)
                ?.bufferedReader()?.use { SelfContainedHtmlExporter(it.readText()) }

        /** An exporter using an explicit bundle — for a build that has none, and for the CLI. */
        fun using(playerBundle: Path): SelfContainedHtmlExporter {
            require(Files.isRegularFile(playerBundle)) { "player bundle not found: $playerBundle" }
            return SelfContainedHtmlExporter(Files.readString(playerBundle))
        }

        /** The bundled player if there is one, else one read from [fallback]. */
        fun bundledOr(fallback: Path?): SelfContainedHtmlExporter? =
            bundled() ?: fallback?.takeIf { Files.isRegularFile(it) }?.let { using(it) }
    }

    /**
     * Writes [out] as a standalone page replaying [trace] through [layout].
     *
     * A gzipped trace is decompressed while embedding, because a `file://` page cannot fetch its own
     * embedded bytes and decompress them the way a served page can.
     */
    fun export(
        trace: Path,
        layout: Path? = null,
        out: Path,
        title: String = trace.fileName.toString().removeSuffix(".gz").removeSuffix(".atf").trim('.'),
        autoPlay: Boolean = true,
        fitSeconds: Double = 20.0
    ): ExportSizeReport {
        val traceText = readTraceText(trace)
        // Scaffold a layout when none was supplied. Without one a process-view model draws nothing at all:
        // a trace records where things move, not where a queue or its server belongs. The desktop viewer
        // scaffolds in exactly this situation, and an exported page should not be worse than the app.
        val layoutJson = (layout?.let { AnimationLayout.readFromFile(it) } ?: scaffoldFor(trace))?.toJson()

        val html = buildString {
            append(pageHead(title))
            append(
                """
                |  <h1>$ESCAPED_TITLE_MARK</h1>
                |  <div id="player"
                |       data-ksl-trace="inline"
                |       data-ksl-inline="ksl-trace"
                |       ${if (layoutJson != null) """data-ksl-inline-layout="ksl-layout"""" else ""}
                |       data-ksl-autoplay="$autoPlay"
                |       data-ksl-fit="$fitSeconds"
                |       style="height:70vh;min-height:420px"></div>
                |  <p class="foot">
                |    Produced by the <a href="https://github.com/rossetti/KSL">Kotlin Simulation Library</a>.
                |    This page is self-contained: the animation, its layout and the player are all inside this
                |    file. KSL is free software under the GNU General Public License v3.
                |  </p>
                |
                """.trimMargin().replace(ESCAPED_TITLE_MARK, escapeHtml(title))
            )
            append("""  <script type="application/x-ksl-trace" id="ksl-trace">""").append('\n')
            append(escapeForScriptTag(traceText)).append('\n')
            append("  </script>\n")
            if (layoutJson != null) {
                append("""  <script type="application/json" id="ksl-layout">""").append('\n')
                append(escapeForScriptTag(layoutJson)).append('\n')
                append("  </script>\n")
            }
            append("  <script>\n").append(player).append("\n  </script>\n")
            append("</body>\n</html>\n")
        }

        Files.createDirectories(out.toAbsolutePath().parent)
        Files.writeString(out, html)

        return ExportSizeReport(
            playerBytes = player.length.toLong(),
            traceBytes = traceText.length.toLong(),
            layoutBytes = (layoutJson?.length ?: 0).toLong(),
            totalBytes = Files.size(out)
        )
    }

    /**
     * Writes a gallery page linking one player per run in [runs], plus the trace and layout files beside
     * it, into the directory [outDir].
     *
     * Unlike [export] this keeps the traces as separate files and the player as one shared script, so a
     * set of animations costs one copy of the player rather than one per page — the right shape for a
     * published site.
     */
    fun exportGallery(runs: List<AnimationRunRef>, outDir: Path, title: String = "KSL animation gallery"): ExportSizeReport {
        val traceDir = outDir.resolve("traces")
        Files.createDirectories(traceDir)
        Files.writeString(outDir.resolve(PLAYER_FILE), player)

        var traceBytes = 0L
        val sections = StringBuilder()
        for (run in runs) {
            val traceName = run.trace.fileName.toString()
            Files.copy(run.trace, traceDir.resolve(traceName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            traceBytes += Files.size(run.trace)
            val layoutAttr = run.layout?.let { layoutPath ->
                val layoutName = layoutPath.fileName.toString()
                Files.copy(layoutPath, traceDir.resolve(layoutName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                """ data-ksl-layout="traces/$layoutName""""
            } ?: ""
            sections.append(
                """
                |  <h2>${escapeHtml(run.title)}</h2>
                |${run.notes?.let { "  <p class=\"note\">${escapeHtml(it)}</p>" } ?: ""}
                |  <div class="player" data-ksl-trace="traces/$traceName"$layoutAttr
                |       style="height:460px"></div>
                |
                """.trimMargin()
            )
        }

        val html = buildString {
            append(pageHead(title))
            append("  <h1>${escapeHtml(title)}</h1>\n")
            append("  <p class=\"lede\">Each animation below is a real KSL run, replayed in your browser.</p>\n")
            append(sections)
            append("  <p class=\"foot\">Produced by the Kotlin Simulation Library (GPL v3).</p>\n")
            append("""  <script src="$PLAYER_FILE"></script>""").append('\n')
            append("</body>\n</html>\n")
        }
        val index = outDir.resolve("index.html")
        Files.writeString(index, html)

        return ExportSizeReport(
            playerBytes = player.length.toLong(),
            traceBytes = traceBytes,
            layoutBytes = 0,
            totalBytes = Files.size(index) + player.length.toLong() + traceBytes
        )
    }

    /**
     * Builds a layout from the trace itself, as the desktop viewer does when handed a trace with no
     * layout. Returns null only if the trace yields nothing to place.
     */
    private fun scaffoldFor(trace: Path): AnimationLayout? {
        val source = AnimationSource.load(null, trace)
        val replay = ReplayModel.build(source)
        return runCatching { replay.autoLayout(source.events, source.header.description) }.getOrNull()
    }

    /**
     * Reads a `.atf` or `.atf.gz` as text. Decompressed on the way in, because a `file://` page cannot
     * fetch and decompress its own embedded bytes the way a served page can.
     */
    private fun readTraceText(trace: Path): String {
        val name = trace.fileName.toString()
        return if (name.endsWith(".gz", ignoreCase = true)) {
            java.util.zip.GZIPInputStream(Files.newInputStream(trace)).bufferedReader().use { it.readText() }
        } else {
            Files.readString(trace)
        }
    }

    private fun pageHead(title: String): String =
        """
        |<!doctype html>
        |<html lang="en">
        |<head>
        |  <meta charset="utf-8">
        |  <meta name="viewport" content="width=device-width, initial-scale=1">
        |  <title>${escapeHtml(title)}</title>
        |  <style>
        |    body { font: 15px/1.6 system-ui, -apple-system, "Segoe UI", sans-serif;
        |           margin: 0 auto; padding: 20px; max-width: 1100px; color: #222; background: #fff; }
        |    h1 { font-size: 20px; margin: 0 0 12px; }
        |    h2 { font-size: 15px; margin: 26px 0 4px; }
        |    p.lede, p.note { color: #666; margin: 0 0 12px; }
        |    p.note { font-size: 13px; }
        |    p.foot { color: #888; font-size: 12px; margin-top: 24px; }
        |    #player, .player { border: 1px solid #ddd; border-radius: 6px; padding: 8px; }
        |  </style>
        |</head>
        |<body>
        |
        """.trimMargin()

    private fun escapeHtml(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Neutralizes any sequence that would end the enclosing script element early. A trace holds only model
     * data, but a model's element names come from user code, so a name containing `</script>` must not be
     * able to break the page.
     */
    private fun escapeForScriptTag(s: String): String =
        s.replace("</", "<\\/").replace("<!--", "<\\!--")

}
