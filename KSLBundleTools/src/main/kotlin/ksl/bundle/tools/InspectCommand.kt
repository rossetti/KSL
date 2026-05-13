package ksl.bundle.tools

import ksl.app.bundle.BundleLayout
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.KSLConfigRecipe
import ksl.app.bundle.KSLModelBundle
import ksl.app.bundle.LoadedBundle
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile

/**
 * Implementation of `kslpkg inspect`.
 *
 * Prints a human-readable summary of every `KSLModelBundle` declared in a
 * JAR. Discovery is delegated to `BundleLoader.loadJar`, which uses
 * `java.util.ServiceLoader` against
 * `META-INF/services/ksl.app.bundle.KSLModelBundle`.
 *
 * A JAR with no bundles is not an error: the command prints a clear
 * "no bundles found" message and returns `Success`. This lets scripts
 * pipe `inspect` output through `grep`/`jq` without confusing a clean
 * empty result with a tool failure.
 */
internal object InspectCommand {

    /**
     * @param args the arguments after `inspect` in the original `argv`
     * @param out  where to write human-readable output (defaulting to
     *             `System.out`; overridable for tests)
     * @param err  where to write error messages (defaulting to `System.err`)
     */
    fun run(
        args: List<String>,
        out: PrintStream = System.out,
        err: PrintStream = System.err
    ): CommandResult {
        if (args.size != 1) {
            err.println("inspect: expected exactly one argument <jar>; got ${args.size}")
            return CommandResult.UserError
        }
        val jarPath: Path = try {
            Paths.get(args[0]).toAbsolutePath()
        } catch (e: Exception) {
            err.println("inspect: invalid path '${args[0]}': ${e.message}")
            return CommandResult.UserError
        }
        if (!Files.isRegularFile(jarPath)) {
            err.println("inspect: not a regular file: $jarPath")
            return CommandResult.UserError
        }

        val inJarDescriptorPaths: Set<String> = try {
            collectInJarDescriptorPaths(jarPath)
        } catch (e: Exception) {
            err.println("inspect: failed to read JAR entries from $jarPath: ${e.message}")
            return CommandResult.InternalError
        }

        val loaded: List<LoadedBundle> = try {
            BundleLoader.loadJar(jarPath)
        } catch (e: Exception) {
            err.println("inspect: failed to load bundles from $jarPath: ${e.message}")
            return CommandResult.InternalError
        }

        try {
            if (loaded.isEmpty()) {
                out.println("JAR: $jarPath")
                out.println("No bundles found.")
                out.println(
                    "  (No META-INF/services/ksl.app.bundle.KSLModelBundle entry.)"
                )
                out.println(
                    "  If this JAR holds bare ksl.simulation.ModelBuilderIfc " +
                            "implementations, load it via ksl.utilities.io.JARModelBuilder instead."
                )
                return CommandResult.Success
            }

            out.println("JAR: $jarPath")
            out.println("Discovery: ServiceLoader (META-INF/services/ksl.app.bundle.KSLModelBundle)")
            out.println("Bundles: ${loaded.size}")
            out.println()
            for (lb in loaded) {
                printBundle(lb.bundle, jarPath, inJarDescriptorPaths, out = out)
            }
            return CommandResult.Success
        } finally {
            for (lb in loaded) {
                try { lb.close() } catch (_: Exception) { /* best-effort */ }
            }
        }
    }

    /**
     * Returns the set of entry names inside `jarPath` that match
     * `META-INF/ksl/models/<anything>/descriptor.json`. Used to mark each
     * model with whether its descriptor is already embedded.
     */
    private fun collectInJarDescriptorPaths(jarPath: Path): Set<String> {
        val prefix = "${BundleLayout.MODELS_ROOT}/"
        val suffix = "/descriptor.json"
        JarFile(jarPath.toFile()).use { jar ->
            return jar.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith(prefix) && it.endsWith(suffix) }
                .toSet()
        }
    }

    private fun printBundle(
        bundle: KSLModelBundle,
        jarPath: Path,
        inJarDescriptorPaths: Set<String>,
        out: PrintStream
    ) {
        out.println("Bundle: ${bundle.bundleId}")
        out.println("  Display name : ${bundle.displayName}")
        out.println("  Description  : ${bundle.description}")
        out.println("  Version      : ${bundle.version}")
        out.println("  KSL API      : ${bundle.kslApiVersion}")
        out.println("  Source JAR   : $jarPath")
        out.println("  Models       : ${bundle.models.size}")
        for (model in bundle.models) {
            out.println("    - ${model.modelId} (${model.displayName})")
            out.println("        Description  : ${model.description}")
            out.println("        Apps         : ${model.supportedApps.joinToString(", ")}")
            val hasInJar = BundleLayout.descriptorPath(model.modelId) in inJarDescriptorPaths
            out.println("        Has in-JAR descriptor : ${if (hasInJar) "yes" else "no"}")
            val recipes: List<KSLConfigRecipe> = bundle.recipesFor(model.modelId)
            if (recipes.isEmpty()) {
                out.println("        Recipes      : (none)")
            } else {
                out.println("        Recipes      :")
                for (r in recipes) {
                    out.println("          - ${r.name} [${r.kind}]")
                }
            }
        }
        out.println("  Optional metadata:")
        out.println("    Author    : ${bundle.author ?: "(unset)"}")
        out.println("    Homepage  : ${bundle.homepage ?: "(unset)"}")
        out.println("    License   : ${bundle.license ?: "(unset)"}")
        out.println("    Tags      : ${if (bundle.tags.isEmpty()) "(none)" else bundle.tags.joinToString(", ")}")
        out.println()
    }
}
