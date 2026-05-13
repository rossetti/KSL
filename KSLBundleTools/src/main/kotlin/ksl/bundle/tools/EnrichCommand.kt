package ksl.bundle.tools

import kotlinx.serialization.json.Json
import ksl.app.bundle.BundleLayout
import ksl.app.bundle.KSLModelBundle
import ksl.simulation.ModelDescriptor
import java.io.PrintStream
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.ServiceLoader
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest

/**
 * Implementation of `kslpkg enrich`.
 *
 * Reads a bundle JAR, builds each declared model once to extract its
 * `ksl.simulation.ModelDescriptor`, and writes a copy of the JAR with the
 * descriptors embedded at the paths defined by `BundleLayout`. The result
 * is a self-describing bundle that loaders can read without instantiating
 * any model — the runtime three-tier resolution short-circuits to the
 * in-JAR descriptor entry on the first call.
 *
 * Strategy notes:
 * - Discovery is via `ServiceLoader` only. A JAR without a
 *   `META-INF/services/ksl.app.bundle.KSLModelBundle` registration is not a
 *   bundle and is not enrichable; this command refuses such inputs rather
 *   than synthesizing descriptors that would not correspond to any real
 *   `KSLBundledModel` at runtime.
 * - All descriptors are extracted before any output write begins, and the
 *   input classloader is closed before the output JarFile is opened, so
 *   there is no simultaneous read/write against the same JAR file handle.
 * - JAR entry timestamps are set to epoch so the layout is stable across
 *   runs. The descriptor JSON contents themselves are also byte-stable:
 *   `Model.modelDescriptor()` returns only model-intrinsic state, and the
 *   runtime-identification fields (experiment name / id, run name) are not
 *   part of the descriptor's `experimentRunDefaults` block. Two enrich
 *   invocations against the same source JAR therefore produce
 *   byte-identical `descriptor.json` entries inside the output.
 * - New descriptor entries are appended in sorted order by path; the
 *   copied entries preserve their input order, with two exceptions:
 *   `META-INF/MANIFEST.MF` is re-emitted from the parsed `Manifest` (the
 *   JarOutputStream writes its own manifest when constructed with one),
 *   and any pre-existing entry at a descriptor path is dropped so
 *   re-enriching does not produce duplicate entries.
 */
internal object EnrichCommand {

    private val myJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    fun run(
        args: List<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err
    ): CommandResult {
        val parsed = parseArgs(args, err) ?: return CommandResult.UserError
        val (input, output, force) = parsed

        if (!Files.isRegularFile(input)) {
            err.println("enrich: not a regular file: $input")
            return CommandResult.UserError
        }
        if (Files.exists(output) && !force) {
            err.println("enrich: output already exists: $output (pass --force to overwrite)")
            return CommandResult.UserError
        }

        val descriptors: Map<String, ByteArray> = try {
            extractDescriptors(input)
        } catch (e: Exception) {
            err.println("enrich: failed to extract descriptors from $input: ${e.message}")
            return CommandResult.InternalError
        }
        if (descriptors.isEmpty()) {
            err.println(
                "enrich: no KSLModelBundle providers declared in $input. " +
                        "Ensure the JAR contains a META-INF/services/ksl.app.bundle.KSLModelBundle entry."
            )
            return CommandResult.UserError
        }

        val tmp: Path = try {
            Files.createTempFile(output.parent ?: Paths.get("."), output.fileName.toString(), ".tmp")
        } catch (e: Exception) {
            err.println("enrich: cannot create temp file beside $output: ${e.message}")
            return CommandResult.InternalError
        }
        try {
            writeEnrichedJar(input, tmp, descriptors)
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) { /* swallow */ }
            err.println("enrich: failed to write $output: ${e.message}")
            return CommandResult.InternalError
        }

        out.println("Wrote $output")
        out.println("  Embedded ${descriptors.size} descriptor entr${if (descriptors.size == 1) "y" else "ies"}:")
        for (path in descriptors.keys.sorted()) {
            out.println("    $path")
        }
        return CommandResult.Success
    }

    private data class ParsedArgs(val input: Path, val output: Path, val force: Boolean)

    private fun parseArgs(args: List<String>, err: PrintStream): ParsedArgs? {
        if (args.isEmpty()) {
            err.println("enrich: expected <input.jar> [-o <output.jar>] [--force]")
            return null
        }
        var inputArg: String? = null
        var outputArg: String? = null
        var force = false
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-o", "--output" -> {
                    if (i + 1 >= args.size) {
                        err.println("enrich: $a requires a value")
                        return null
                    }
                    if (outputArg != null) {
                        err.println("enrich: $a specified more than once")
                        return null
                    }
                    outputArg = args[i + 1]
                    i += 2
                }
                "--force" -> {
                    force = true
                    i++
                }
                else -> {
                    if (a.startsWith("-")) {
                        err.println("enrich: unknown flag $a")
                        return null
                    }
                    if (inputArg != null) {
                        err.println("enrich: expected exactly one input JAR, got '$inputArg' and '$a'")
                        return null
                    }
                    inputArg = a
                    i++
                }
            }
        }
        if (inputArg == null) {
            err.println("enrich: missing <input.jar>")
            return null
        }
        val input = Paths.get(inputArg).toAbsolutePath()
        val output = when {
            outputArg != null -> Paths.get(outputArg).toAbsolutePath()
            else -> defaultOutputPath(input)
        }
        return ParsedArgs(input, output, force)
    }

    /**
     * Returns `<input-stem>-enriched.jar` in the same directory as the input.
     * The `.jar` extension is recognised case-insensitively; if the input
     * does not end in `.jar`, `-enriched.jar` is simply appended.
     */
    internal fun defaultOutputPath(input: Path): Path {
        val name = input.fileName.toString()
        val stem = when {
            name.endsWith(".jar", ignoreCase = true) -> name.substring(0, name.length - 4)
            else -> name
        }
        val parent = input.parent ?: Paths.get(".")
        return parent.resolve("$stem-enriched.jar").toAbsolutePath()
    }

    /**
     * Loads the bundle JAR, builds every declared model once, extracts its
     * `ModelDescriptor`, and returns a path-to-bytes map keyed by the
     * in-JAR location each entry will occupy.
     */
    private fun extractDescriptors(jarPath: Path): Map<String, ByteArray> {
        val parent = EnrichCommand::class.java.classLoader
        val urlLoader = URLClassLoader(arrayOf(jarPath.toUri().toURL()), parent)
        try {
            val bundles: List<KSLModelBundle> =
                ServiceLoader.load(KSLModelBundle::class.java, urlLoader).toList()
            val result = mutableMapOf<String, ByteArray>()
            for (bundle in bundles) {
                for (model in bundle.models) {
                    val descriptor: ModelDescriptor = try {
                        model.builder().build(null, null).modelDescriptor()
                    } catch (e: Exception) {
                        throw RuntimeException(
                            "Failed to build ${bundle.bundleId}/${model.modelId}: ${e.message}", e
                        )
                    }
                    val json = myJson.encodeToString(ModelDescriptor.serializer(), descriptor)
                    result[BundleLayout.descriptorPath(model.modelId)] = json.toByteArray(Charsets.UTF_8)
                }
            }
            return result
        } finally {
            try { urlLoader.close() } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * Streams `input` to `output`, skipping the manifest and any entries
     * whose paths are about to be overwritten, then appends the supplied
     * descriptor entries in sorted-path order.
     */
    private fun writeEnrichedJar(
        input: Path,
        output: Path,
        descriptors: Map<String, ByteArray>
    ) {
        JarFile(input.toFile()).use { jar ->
            val manifest: Manifest = jar.manifest ?: Manifest()
            Files.newOutputStream(output).use { os ->
                JarOutputStream(os, manifest).use { jarOut ->
                    val replacePaths = descriptors.keys
                    val entries = jar.entries()
                    while (entries.hasMoreElements()) {
                        val src = entries.nextElement()
                        val name = src.name
                        if (name.equals("META-INF/MANIFEST.MF", ignoreCase = true)) continue
                        if (name in replacePaths) continue
                        val copy = JarEntry(name).apply { time = 0L }
                        jarOut.putNextEntry(copy)
                        if (!src.isDirectory) {
                            jar.getInputStream(src).use { input -> input.copyTo(jarOut) }
                        }
                        jarOut.closeEntry()
                    }
                    for (path in descriptors.keys.sorted()) {
                        val entry = JarEntry(path).apply { time = 0L }
                        jarOut.putNextEntry(entry)
                        jarOut.write(descriptors.getValue(path))
                        jarOut.closeEntry()
                    }
                }
            }
        }
    }
}
