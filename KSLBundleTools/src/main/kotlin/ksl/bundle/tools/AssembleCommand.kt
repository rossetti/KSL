package ksl.bundle.tools

import ksl.app.bundle.BundleAuthoringSession
import ksl.app.bundle.BundleValidation
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Implementation of `kslpkg assemble`.
 *
 * Turns a plain **builders JAR** — a JAR whose only required content is one or more
 * named `ksl.simulation.ModelBuilderIfc` implementations (each with a public no-arg
 * constructor, or a Kotlin `object`) — into a self-describing **bundle JAR**. It
 * discovers the builders, builds each model **once** to capture its
 * `ksl.simulation.ModelDescriptor`, and writes a *new* JAR carrying a `bundle.toml`
 * manifest plus, per model, an embedded `descriptor.json` (and `catalog.toml` when a
 * catalog is present). The result loads at runtime as a `ManifestBackedBundle` — no
 * hand-written `KSLModelBundle` class and no `META-INF/services` registration required.
 *
 * This is a thin CLI over the headless `ksl.app.bundle.BundleAuthoringSession` (the
 * same authoring core the Bundle Workbench drives). Identity comes from CLI flags
 * (`--id` is required); per-model metadata uses the session's defaults — `modelId`
 * derived from the builder FQN, `supportedApps` = SINGLE + SCENARIO (plus EXPERIMENT when
 * the model exposes at least two numeric factors — @KSLControl controls or RV parameters).
 * Richer per-model authoring (e.g. tuning `supportedApps` per model, curating a catalog) is
 * the Bundle Workbench's job.
 *
 * The draft is validated before anything is written: if validation reports an ERROR
 * (e.g. a malformed `bundleId`) the bundle is not written and the command exits
 * `UserError`; warnings are printed but do not block.
 */
internal object AssembleCommand {

    fun run(
        args: List<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err
    ): CommandResult {
        val parsed = parseArgs(args, err) ?: return CommandResult.UserError

        if (!Files.isRegularFile(parsed.input)) {
            err.println("assemble: not a regular file: ${parsed.input}")
            return CommandResult.UserError
        }

        val session = try {
            BundleAuthoringSession.open(parsed.input)
        } catch (e: Exception) {
            err.println("assemble: failed to open ${parsed.input}: ${e.message}")
            return CommandResult.InternalError
        }

        if (session.models.isEmpty()) {
            err.println(
                "assemble: no ksl.simulation.ModelBuilderIfc implementations found in ${parsed.input}. " +
                    "A builders JAR must contain at least one public, no-arg ModelBuilderIfc (or Kotlin object)."
            )
            for (d in session.discoveryErrors) err.println("  ${d.builderClass}: ${d.error}")
            return CommandResult.UserError
        }

        for (id in parsed.excludeModelIds - session.models.map { it.modelId }.toSet()) {
            err.println("assemble: --exclude '$id' matches no discovered model; ignoring")
        }

        // Apply identity from the CLI; everything else keeps the session defaults.
        session.bundleId = parsed.bundleId
        parsed.displayName?.let { session.displayName = it }
        parsed.description?.let { session.description = it }
        parsed.version?.let { session.version = it }
        parsed.author?.let { session.author = it }
        parsed.homepage?.let { session.homepage = it }
        parsed.license?.let { session.license = it }
        if (parsed.tags.isNotEmpty()) { session.tags.clear(); session.tags.addAll(parsed.tags) }

        val output = parsed.output ?: session.defaultOutputPath()
        if (Files.exists(output) && !parsed.force) {
            err.println("assemble: output already exists: $output (pass --force to overwrite)")
            return CommandResult.UserError
        }

        // Validate the draft before writing anything; refuse to emit a bundle with errors.
        val report = try {
            session.validate(parsed.excludeModelIds)
        } catch (e: Exception) {
            err.println("assemble: validation failed to run: ${e.message}")
            return CommandResult.InternalError
        }
        if (!report.isClean) {
            err.println("assemble: ${report.errorCount} validation error(s); bundle not written:")
            for (f in report.findings.filter { it.severity == BundleValidation.Severity.ERROR }) {
                err.println("  [${f.severity}] ${f.locus}: ${f.message}${f.suggestion?.let { " — $it" } ?: ""}")
            }
            return CommandResult.UserError
        }

        try {
            session.assemble(output, force = parsed.force, excludeModelIds = parsed.excludeModelIds)
        } catch (e: Exception) {
            err.println("assemble: failed to write $output: ${e.message}")
            return CommandResult.InternalError
        }

        val included = session.models.filterNot { it.modelId in parsed.excludeModelIds }
        out.println("Assembled ${parsed.bundleId} → $output")
        out.println("  Models (${included.size}):")
        for (m in included) {
            out.println("    - ${m.modelId} (${m.displayName}) ← ${m.builderClass}")
        }
        for (m in session.models.filter { it.modelId in parsed.excludeModelIds }) {
            out.println("    - (excluded) ${m.modelId} ← ${m.builderClass}")
        }
        for (d in session.discoveryErrors) {
            out.println("  ! skipped ${d.builderClass}: ${d.error}")
        }
        if (report.warningCount > 0) {
            out.println("  Validation: ${report.warningCount} warning(s):")
            for (f in report.findings) out.println("    [${f.severity}] ${f.locus}: ${f.message}")
        } else {
            out.println("  Validation: clean (0 errors, 0 warnings)")
        }
        return CommandResult.Success
    }

    private data class ParsedArgs(
        val input: Path,
        val bundleId: String,
        val displayName: String?,
        val description: String?,
        val version: String?,
        val author: String?,
        val homepage: String?,
        val license: String?,
        val tags: List<String>,
        val output: Path?,
        val force: Boolean,
        val excludeModelIds: Set<String>,
    )

    private fun parseArgs(args: List<String>, err: PrintStream): ParsedArgs? {
        if (args.isEmpty()) {
            err.println("assemble: expected <builders.jar> --id <bundleId> [options]")
            return null
        }
        var inputArg: String? = null
        var bundleId: String? = null
        var displayName: String? = null
        var description: String? = null
        var version: String? = null
        var author: String? = null
        var homepage: String? = null
        var license: String? = null
        val tags = mutableListOf<String>()
        var outputArg: String? = null
        var force = false
        val excludeIds = mutableListOf<String>()

        var i = 0
        while (i < args.size) {
            val a = args[i]
            // Helper: the value that must follow a value-taking flag.
            fun valueFor(): String? {
                if (i + 1 >= args.size) { err.println("assemble: $a requires a value"); return null }
                return args[i + 1]
            }
            when (a) {
                "--id" -> { bundleId = valueFor() ?: return null; i += 2 }
                "--name" -> { displayName = valueFor() ?: return null; i += 2 }
                "--description" -> { description = valueFor() ?: return null; i += 2 }
                "--version" -> { version = valueFor() ?: return null; i += 2 }
                "--author" -> { author = valueFor() ?: return null; i += 2 }
                "--homepage" -> { homepage = valueFor() ?: return null; i += 2 }
                "--license" -> { license = valueFor() ?: return null; i += 2 }
                "--tag" -> { tags += (valueFor() ?: return null); i += 2 }
                "-o", "--output" -> { outputArg = valueFor() ?: return null; i += 2 }
                "--force" -> { force = true; i++ }
                "--exclude" -> {
                    excludeIds += (valueFor() ?: return null).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    i += 2
                }
                else -> {
                    if (a.startsWith("-")) { err.println("assemble: unknown flag $a"); return null }
                    if (inputArg != null) {
                        err.println("assemble: expected exactly one input JAR, got '$inputArg' and '$a'")
                        return null
                    }
                    inputArg = a; i++
                }
            }
        }
        if (inputArg == null) { err.println("assemble: missing <builders.jar>"); return null }
        if (bundleId.isNullOrBlank()) { err.println("assemble: --id <bundleId> is required"); return null }
        return ParsedArgs(
            input = Paths.get(inputArg).toAbsolutePath(),
            bundleId = bundleId,
            displayName = displayName,
            description = description,
            version = version,
            author = author,
            homepage = homepage,
            license = license,
            tags = tags,
            output = outputArg?.let { Paths.get(it).toAbsolutePath() },
            force = force,
            excludeModelIds = excludeIds.toSet(),
        )
    }
}
