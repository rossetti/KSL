package ksl.examples.general.animationbundle

import ksl.examples.general.animationbundle.showcase.AnimationSite
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Guards the generated gallery against publishing something broken.
 *
 * The failure this exists for is silent: a page that references a trace or a layout which was never written
 * looks perfect in the diff and shows a blank rectangle to a reader. The site is fifteen pages nobody reads
 * before pushing, so nothing else would catch it — a sibling test, [ShippedLayoutTest], exists because
 * exactly that shipped once before.
 *
 * The traces here are stubs. The generator gzips a trace and never parses one, so a one-line file exercises
 * every path it has while keeping the test to a second or so; capturing fifteen real runs would test
 * `ShowcaseCapture`, which is not what is under test. The **layouts are the real ones**, because converting
 * them is a step that has already gone wrong once.
 */
class AnimationSiteTest {

    private val bundleJar: Path = Path.of("build/libs/animation-examples.jar")
    private val layoutRoot: Path = Path.of("../docs/animations/layouts")

    private fun modelIds(): List<String> {
        assertTrue(Files.isRegularFile(bundleJar), "no $bundleJar — run :KSLExamples:animationExamplesBundleJar")
        val text = ZipFile(bundleJar.toFile()).use { zip ->
            zip.getInputStream(zip.getEntry("META-INF/ksl/bundle.toml")).bufferedReader().readText()
        }
        return Regex("""^\s*modelId\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
            .findAll(text).map { it.groupValues[1] }.toList()
    }

    /**
     * A site checkout carrying the hand-written half a real one has.
     *
     * Every file here is one the *generated* pages link to but do not create — the stylesheet, the brand
     * marks, the gallery, the license. Listing them is the point rather than a convenience: it is the
     * contract between the two halves, and the first run of this test found a link to `gallery.html` that
     * nothing had written, which would have been a dead "All animations" on all fifteen pages.
     */
    private fun siteDir(root: Path): Path = Files.createDirectories(root.resolve("site")).also {
        Files.writeString(it.resolve("index.html"), "<!-- written by a person -->")
        Files.writeString(it.resolve("gallery.html"), "<!-- written by a person -->")
        Files.writeString(it.resolve("LICENSE.txt"), "GPL-3.0")
        Files.createDirectories(it.resolve("assets/branding"))
        Files.writeString(it.resolve("assets/site.css"), "/* written by a person */")
        Files.writeString(it.resolve("assets/branding/ksl-logo.svg"), "<svg/>")
        Files.writeString(it.resolve("assets/branding/ksl-mark.svg"), "<svg/>")
    }

    private fun stubTraces(root: Path, ids: List<String>): Path =
        Files.createDirectories(root.resolve("traces")).also { dir ->
            // Never parsed by the generator — only gzipped — so the content only has to be bytes.
            ids.forEach { Files.writeString(dir.resolve("$it.atf"), """{"event":"stub"}""" + "\n") }
        }

    private fun catalog(root: Path, ids: List<String>): Path =
        root.resolve("catalog.toml").also { file ->
            Files.writeString(file, ids.joinToString("\n\n") {
                """
                [[animation]]
                id = "$it"
                blurb = "A blurb for $it."
                watchFor = "Something worth watching in $it."
                """.trimIndent()
            })
        }

    private fun player(root: Path): Path =
        root.resolve("ksl-animation.js").also { Files.writeString(it, "/* stand-in player */") }

    private fun generate(root: Path, ids: List<String> = modelIds()): Pair<Path, AnimationSite.Result> {
        val out = siteDir(root)
        val result = AnimationSite.build(
            bundleJar = bundleJar,
            tracesDir = stubTraces(root, ids),
            layoutsRoot = layoutRoot,
            catalogFile = catalog(root, ids),
            player = player(root),
            outDir = out,
            captureMissing = false
        )
        return out to result
    }

    @Test
    @DisplayName("every generated page references a trace and a layout that exist")
    fun everyPageResolves(@TempDir root: Path) {
        val (out, result) = generate(root)
        val problems = StringBuilder()

        for (entry in result.entries) {
            val page = out.resolve(entry.page)
            if (!Files.isRegularFile(page)) {
                problems.appendLine("${entry.id}: no page at ${entry.page}")
                continue
            }
            val html = Files.readString(page)
            // The href a browser would actually request, resolved the way it resolves it.
            for (ref in Regex("""(?:src|href|data-ksl-trace|data-ksl-layout)="(\.\./[^"]+|[^":]+\.(?:json|js|css))"""")
                .findAll(html).map { it.groupValues[1] }) {
                val target = page.parent.resolve(ref).normalize()
                if (!Files.exists(target)) problems.appendLine("${entry.id}: references $ref, which is not there")
            }
        }
        assertTrue(problems.isEmpty(), "Pages referencing files that were never written:\n$problems")
    }

    @Test
    @DisplayName("layouts are written as JSON, because the browser's reader parses nothing else")
    fun layoutsAreJson(@TempDir root: Path) {
        val (out, result) = generate(root)
        for (entry in result.entries) {
            val layout = out.resolve("traces/${entry.id}.lay.json")
            assertTrue(Files.isRegularFile(layout), "${entry.id}: no layout written")
            val first = Files.readString(layout).trimStart().first()
            // A copied .lay.toml starts with 'title = ...' and fails in the browser with
            // "Expected start of the object '{'" -- a blank canvas and no other clue.
            assertEquals('{', first, "${entry.id}: layout is not JSON; it starts '$first'")
        }
    }

    @Test
    @DisplayName("the catalog lists every shipped model exactly once")
    fun catalogMatchesTheBundle(@TempDir root: Path) {
        val ids = modelIds()
        val (out, _) = generate(root, ids)
        val json = Files.readString(out.resolve("animations.json"))
        val listed = Regex(""""id":\s*"([^"]+)"""").findAll(json).map { it.groupValues[1] }.toList()
        assertEquals(ids, listed, "animations.json must list the bundle's models, in order and once each")
    }

    @Test
    @DisplayName("the previous/next chain runs the set once with no dangling ends")
    fun pagerChainIsClosed(@TempDir root: Path) {
        val (out, result) = generate(root)
        val first = Files.readString(out.resolve(result.entries.first().page))
        val last = Files.readString(out.resolve(result.entries.last().page))
        assertTrue("""class="prev"""" !in first, "the first animation must not link to a previous one")
        assertTrue("""class="next"""" in first, "but it must link forward")
        assertTrue("""class="next"""" !in last, "the last animation must not link to a next one")
        assertTrue("""class="prev"""" in last, "but it must link back")
    }

    @Test
    @DisplayName("the hand-written half of the site is left alone")
    fun handWrittenFilesSurvive(@TempDir root: Path) {
        val (out, _) = generate(root)
        assertEquals("<!-- written by a person -->", Files.readString(out.resolve("index.html")))
        assertEquals("/* written by a person */", Files.readString(out.resolve("assets/site.css")))
    }

    @Test
    @DisplayName("a model with no blurb stops the build instead of publishing in silence")
    fun aMissingBlurbRefuses(@TempDir root: Path) {
        val ids = modelIds()
        val out = siteDir(root)
        val thrown = assertFailsWith<IllegalArgumentException> {
            AnimationSite.build(
                bundleJar = bundleJar,
                tracesDir = stubTraces(root, ids),
                layoutsRoot = layoutRoot,
                catalogFile = catalog(root, ids.drop(1)), // one model left out of the catalog
                player = player(root),
                outDir = out,
                captureMissing = false
            )
        }
        assertTrue(ids.first() in (thrown.message ?: ""), "the message must name the model: ${thrown.message}")
        assertTrue(
            Files.notExists(out.resolve("animations.json")),
            "and nothing may be written: a site half old and half new is the hardest state to diagnose"
        )
    }

    @Test
    @DisplayName("a missing player stops the build, since every page would load nothing")
    fun aMissingPlayerRefuses(@TempDir root: Path) {
        val ids = modelIds()
        val thrown = assertFailsWith<IllegalArgumentException> {
            AnimationSite.build(
                bundleJar = bundleJar,
                tracesDir = stubTraces(root, ids),
                layoutsRoot = layoutRoot,
                catalogFile = catalog(root, ids),
                player = root.resolve("no-such-player.js"),
                outDir = siteDir(root),
                captureMissing = false
            )
        }
        assertTrue(
            "jsBrowserProductionWebpack" in (thrown.message ?: ""),
            "and it must say how to build one: ${thrown.message}"
        )
    }
}
