package ksl.examples.general.animationbundle.showcase

import ksl.animation.AnimationLayout
import net.peanuuutz.tomlkt.Toml
import net.peanuuutz.tomlkt.TomlArray
import net.peanuuutz.tomlkt.TomlTable
import net.peanuuutz.tomlkt.asTomlLiteral
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.Path
import kotlin.io.path.exists

/**
 * Generates the published animation gallery — the `rossetti/KSL-Animations` GitHub Pages site.
 *
 * The site serves **one** copy of the player and fetches a trace only when a reader opens an animation, so
 * unlike the downloadable pack it costs nothing to carry a model nobody looks at. Traces are written
 * gzipped: they are JSON Lines and compress to about a tenth, which takes all fifteen from 39 MB to 3.5 MB.
 * GitHub Pages serves `.gz` as an opaque body with no `Content-Encoding`, so the browser hands it to the
 * player untouched and the player's own decompression does the work. That was verified against the live
 * host before this was written, because the whole size argument rests on it.
 *
 * **Flocking is included here** although [AnimationsPackage] leaves it out. That exclusion is a fact about
 * the *download*, where a self-contained page embeds the raw trace as text and its 17 MB would have been a
 * third of the zip. Served gzipped it is 1.8 MB, so the website can be complete where the download could
 * not be.
 *
 * ## What this writes, and what it must not touch
 *
 * The site is half generated and half written by a person. This writes only the generated half, listed in
 * [Result.written]; `index.html`, `gallery.html`, `assets/site.css` and `catalog.toml` belong to whoever
 * writes them and are never overwritten here. The seam between the two is `animations.json`, which the
 * gallery reads to build its cards, so adding a model means writing a blurb rather than editing HTML.
 */
object AnimationSite {

    /** Where the model's source can be read, since "how is this built?" is the first question a page raises. */
    private const val SOURCE_BASE =
        "https://github.com/rossetti/KSL/blob/main/KSLExamples/src/main/kotlin/ksl/examples/general/animationbundle"

    /** One animation, as `animations.json` records it for the gallery. */
    data class Entry(
        val id: String,
        val title: String,
        val blurb: String,
        val watchFor: String,
        val page: String,
        val trace: String,
        val traceBytes: Long
    )

    data class Result(
        val entries: List<Entry>,
        /** Models whose trace this run produced, because none was on disk. */
        val captured: List<String>,
        val written: List<String>,
        val totalBytes: Long
    )

    /** A blurb and a "what to watch for", per model, read from the site's hand-written `catalog.toml`. */
    private data class CatalogEntry(val id: String, val blurb: String, val watchFor: String)

    fun build(
        bundleJar: Path,
        tracesDir: Path,
        layoutsRoot: Path,
        catalogFile: Path,
        player: Path,
        outDir: Path,
        captureMissing: Boolean = true
    ): Result {
        require(Files.isRegularFile(player)) {
            "no player at $player. Build it first:\n  ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack"
        }
        require(Files.isDirectory(outDir)) {
            "no site at $outDir — this writes into an existing checkout of the KSL-Animations repository, " +
                "it does not create one"
        }
        val catalog = readCatalog(catalogFile)
        val (bundleId, modelIds) = AnimationsPackage.readManifest(bundleJar)

        val siteTraces = outDir.resolve("traces").also { Files.createDirectories(it) }
        val pages = outDir.resolve("a").also { Files.createDirectories(it) }
        Files.createDirectories(outDir.resolve("assets"))

        // Resolve everything before writing anything. A model with no layout or no blurb is a person's job,
        // and finding out halfway through leaves a site that is half old and half new — the state in which
        // it is hardest to tell what is wrong.
        val missingLayouts = modelIds.filter { !layoutsRoot.resolve(bundleId).resolve("$it.lay.toml").exists() }
        val missingBlurbs = modelIds.filter { it !in catalog }
        require(missingLayouts.isEmpty()) {
            "no polished layout for: ${missingLayouts.joinToString(", ")}\n" +
                "Every model the bundle ships needs one; produce it with the polish workflow."
        }
        require(missingBlurbs.isEmpty()) {
            "no entry in ${catalogFile.fileName} for: ${missingBlurbs.joinToString(", ")}\n" +
                "Each animation needs a blurb before it can be published."
        }

        val captured = ArrayList<String>()
        val written = ArrayList<String>()
        val entries = ArrayList<Entry>()
        var bytes = 0L

        for (modelId in modelIds) {
            var trace = tracesDir.resolve("$modelId.atf")
            if (!trace.exists() && captureMissing) {
                println("  capturing $modelId …")
                trace = ShowcaseCapture.capture(modelId, tracesDir).traceFile
                captured.add(modelId)
            }
            require(trace.exists()) { "no trace for $modelId and capture is off" }

            val gz = siteTraces.resolve("$modelId.atf.gz")
            gzip(trace, gz)
            bytes += Files.size(gz)
            written.add("traces/${gz.fileName}")

            // JSON, not a copy of the .lay.toml. The browser carries its own layout reader and it parses
            // JSON only; handed TOML it fails with "Expected start of the object '{'" and draws nothing.
            val layout = AnimationLayout.read(layoutsRoot.resolve(bundleId).resolve("$modelId.lay.toml"))
            val layoutFile = siteTraces.resolve("$modelId.lay.json")
            Files.writeString(layoutFile, layout.toJson())
            bytes += Files.size(layoutFile)
            written.add("traces/${layoutFile.fileName}")

            val cat = catalog.getValue(modelId)
            entries.add(
                Entry(
                    id = modelId,
                    title = layout.title?.takeIf { it.isNotBlank() } ?: modelId,
                    blurb = cat.blurb,
                    watchFor = cat.watchFor,
                    page = "a/$modelId.html",
                    trace = "traces/$modelId.atf.gz",
                    traceBytes = Files.size(gz)
                )
            )
        }

        for ((i, e) in entries.withIndex()) {
            val page = pages.resolve("${e.id}.html")
            Files.writeString(page, pageHtml(e, entries.getOrNull(i - 1), entries.getOrNull(i + 1)))
            bytes += Files.size(page)
            written.add("a/${page.fileName}")
        }

        val indexFile = outDir.resolve("animations.json")
        Files.writeString(indexFile, indexJson(entries))
        written.add("animations.json")

        val playerOut = outDir.resolve("assets/ksl-animation.js")
        Files.copy(player, playerOut, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        bytes += Files.size(playerOut)
        written.add("assets/ksl-animation.js")

        return Result(entries, captured, written, bytes)
    }

    private fun gzip(source: Path, target: Path) {
        Files.newInputStream(source).use { input ->
            GZIPOutputStream(Files.newOutputStream(target), 8192).use { output -> input.copyTo(output) }
        }
    }

    /**
     * Reads `catalog.toml` through tomlkt's document API rather than a generated serializer, because
     * KSLExamples does not apply the serialization compiler plugin and is not worth changing for four
     * fields. The file is small and its shape is fixed.
     */
    private fun readCatalog(file: Path): Map<String, CatalogEntry> {
        require(Files.isRegularFile(file)) { "no catalog at $file — the site's blurbs live there" }
        val table: TomlTable = Toml.parseToTomlTable(Files.readString(file))
        val rows = table["animation"] as? TomlArray ?: error("$file declares no [[animation]] entries")
        return rows.map { row ->
            val entry = row as? TomlTable ?: error("a malformed [[animation]] entry in $file")
            fun field(name: String, required: Boolean): String {
                val v = entry[name] ?: if (required) error("an [[animation]] in $file has no $name") else return ""
                return v.asTomlLiteral().toString().removeSurrounding("\"")
            }
            CatalogEntry(field("id", true), field("blurb", true), field("watchFor", false))
        }.associateBy { it.id }
    }

    /**
     * `animations.json`, written by hand for the same reason the catalog is read by hand. This is the seam
     * the gallery reads, so its shape is part of the site's contract and worth being able to see in one
     * place.
     */
    private fun indexJson(entries: List<Entry>): String = buildString {
        appendLine("{")
        appendLine("  \"animations\": [")
        entries.forEachIndexed { i, e ->
            fun field(name: String, value: String) = appendLine("      \"$name\": \"${jsonEscape(value)}\",")
            appendLine("    {")
            field("id", e.id)
            field("title", e.title)
            field("blurb", e.blurb)
            field("watchFor", e.watchFor)
            field("page", e.page)
            field("trace", e.trace)
            appendLine("      \"traceBytes\": ${e.traceBytes}")
            appendLine(if (i == entries.lastIndex) "    }" else "    },")
        }
        appendLine("  ]")
        appendLine("}")
    }

    private fun jsonEscape(s: String): String = buildString {
        for (c in s) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
        }
    }

    /**
     * One animation's page.
     *
     * Deliberately plain HTML against the site's own stylesheet rather than anything templated: there are
     * fifteen of these, they differ only in their content, and the moment a page needs to be *designed* it
     * belongs in the hand-written half of the site instead.
     */
    private fun pageHtml(e: Entry, previous: Entry?, next: Entry?): String {
        val nav = buildString {
            previous?.let { append("""<a class="prev" href="${it.id}.html">← ${escape(it.title)}</a>""") }
            next?.let { append("""<a class="next" href="${it.id}.html">${escape(it.title)} →</a>""") }
        }
        return """
            |<!doctype html>
            |<!-- GENERATED by AnimationSite. Edits here are discarded by the next build. -->
            |<html lang="en">
            |<head>
            |    <meta charset="utf-8">
            |    <meta name="viewport" content="width=device-width, initial-scale=1">
            |    <title>${escape(e.title)} — KSL Animations</title>
            |    <meta name="description" content="${escape(e.blurb)}">
            |    <link rel="icon" href="../assets/branding/ksl-mark.svg">
            |    <link rel="stylesheet" href="../assets/site.css">
            |</head>
            |<body>
            |
            |<header class="site">
            |    <div class="wrap">
            |        <a href="../"><img src="../assets/branding/ksl-logo.svg" alt="KSL — Kotlin Simulation Library"></a>
            |        <nav class="site">
            |            <a href="../gallery.html">All animations</a>
            |            <a href="https://github.com/rossetti/KSL">KSL on GitHub</a>
            |        </nav>
            |    </div>
            |</header>
            |
            |<main class="wrap">
            |    <h1>${escape(e.title)}</h1>
            |    <hr class="rule">
            |    <p class="lede">${escape(e.blurb)}</p>
            |
            |    <div class="player"
            |         data-ksl-trace="../${e.trace}"
            |         data-ksl-layout="../traces/${e.id}.lay.json"></div>
            |
            |${if (e.watchFor.isBlank()) "" else "    <h2>What to watch for</h2>\n    <p>${escape(e.watchFor)}</p>\n"}
            |    <h2>Underneath</h2>
            |    <p>
            |        This is a real run of a real model, not a recording. The page fetched
            |        <a href="../${e.trace}">the trace</a> (${e.traceBytes / 1024} KB gzipped) and drew it through the
            |        same renderer the desktop animation application uses. Download it and open it there to lay it
            |        out yourself.
            |    </p>
            |    <p>
            |        <a href="$SOURCE_BASE/${e.id}.kt">Read the model's source</a> — the simulation is not modified to
            |        be animated; capture is a flag on a run.
            |    </p>
            |
            |    <nav class="pager">$nav</nav>
            |</main>
            |
            |<footer class="site">
            |    <div class="wrap">
            |        <p>
            |            Produced by the <a href="https://github.com/rossetti/KSL">Kotlin Simulation Library</a>.
            |            Free software under the <a href="../LICENSE.txt">GNU General Public License v3</a>.
            |        </p>
            |    </div>
            |</footer>
            |
            |<script src="../assets/ksl-animation.js"></script>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}

fun main() {
    val result = AnimationSite.build(
        bundleJar = Path(System.getProperty("bundleJar") ?: error("-DbundleJar required")),
        tracesDir = Path(System.getProperty("traces") ?: "build/showcase"),
        layoutsRoot = Path(System.getProperty("layouts") ?: "docs/animations/layouts"),
        catalogFile = Path(System.getProperty("catalog") ?: error("-Dcatalog required")),
        player = Path(System.getProperty("player") ?: error("-Dplayer required")),
        outDir = Path(System.getProperty("out") ?: error("-Dout required")),
    )
    result.entries.forEach { println("  ${it.id}  ${it.traceBytes / 1024} KB") }
    if (result.captured.isNotEmpty()) println("  captured ${result.captured.size} missing trace(s)")
    println("wrote ${result.written.size} file(s), ${result.totalBytes / 1048576} MB")
    println("Review the diff in the site checkout, then commit and push.")
}
