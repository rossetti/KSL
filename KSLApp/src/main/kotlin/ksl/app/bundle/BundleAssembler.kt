package ksl.app.bundle

import kotlinx.serialization.json.Json
import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import ksl.app.config.ModelCatalogToml
import ksl.app.config.ModelManifestEntry
import ksl.app.config.RecipeEntry
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * Turns a plain **builders JAR** into an enriched **bundle JAR** by writing data-only
 * artifacts into a *new* JAR: the `bundle.toml` manifest plus, per model, a
 * `descriptor.json`, an optional `catalog.toml`, and any recipe files. The result is
 * loadable through [BundleLoader] as a [ManifestBackedBundle].
 *
 * Invariants:
 *  - The input JAR is opened read-only and **never modified**; [assemble] refuses to
 *    write to the input path. The author's original build output is preserved.
 *  - Output is written to a temp file beside the destination and atomically moved
 *    into place; an existing output is overwritten only with `force`.
 *  - The JAR manifest gets a `Build-Time` attribute so [LoadedBundle.builtAt] is a
 *    real timestamp (deterministic newest-wins resolution across re-assembles).
 *
 * The assembler owns the in-JAR **layout**: it assigns recipe paths via
 * [BundleLayout] and writes the manifest's [RecipeEntry] paths to match the bytes it
 * emits, so a caller cannot desynchronise the manifest from the files. Authoring
 * *decisions* (ids, supportedApps, which catalog/recipes) live in
 * [BundleAuthoringSession]; this class only serialises and writes.
 */
object BundleAssembler {

    /** Canonical descriptor JSON config — matches `kslpkg enrich` and the loader. */
    private val descriptorJson = Json {
        prettyPrint = true
        encodeDefaults = true
        allowSpecialFloatingPointValues = true
    }

    /** The authored content of one model to be written into the bundle JAR. */
    data class ModelSpec(
        val modelId: String,
        val builderClass: String,
        val displayName: String,
        val description: String = "",
        val supportedApps: Set<KSLAppKind> = emptySet(),
        val descriptor: ModelDescriptor,
        val catalog: ModelCatalog? = null,
        val recipes: List<RecipeContent> = emptyList(),
    )

    /** One authored recipe: its label/kind and raw bytes (TOML or JSON). */
    data class RecipeContent(
        val name: String,
        val kind: ConfigRecipeKind,
        val bytes: ByteArray,
    )

    /** The full authored content of a bundle (identity + models). */
    data class BundleSpec(
        val bundleId: String,
        val displayName: String,
        val description: String,
        val version: String,
        val kslApiVersion: String,
        val author: String? = null,
        val homepage: String? = null,
        val license: String? = null,
        val tags: Set<String> = emptySet(),
        val models: List<ModelSpec>,
    )

    /** Returns `<input-stem>-bundle.jar` beside [input] (the default output name). */
    fun defaultOutputPath(input: Path): Path {
        val name = input.fileName.toString()
        val stem = if (name.endsWith(".jar", ignoreCase = true)) name.dropLast(4) else name
        val parent = input.parent ?: Paths.get(".")
        return parent.resolve("$stem-bundle.jar").toAbsolutePath()
    }

    /**
     * Assembles a bundle JAR at [output] from the builders JAR [input] and the
     * authored [spec].
     *
     * @throws IllegalArgumentException if [input] is not a regular file, or [output]
     *         resolves to the same file as [input]
     * @throws java.nio.file.FileAlreadyExistsException if [output] exists and [force] is false
     */
    fun assemble(input: Path, output: Path, spec: BundleSpec, force: Boolean = false) {
        require(Files.isRegularFile(input)) { "input is not a regular file: $input" }
        require(
            input.toAbsolutePath().normalize() != output.toAbsolutePath().normalize()
        ) { "output must differ from the input JAR (the input is never modified): $output" }
        if (Files.exists(output) && !force) {
            throw java.nio.file.FileAlreadyExistsException(
                output.toString(), null, "output already exists (pass force=true to overwrite)"
            )
        }

        val entries = LinkedHashMap<String, ByteArray>()
        val modelEntries = spec.models.map { model ->
            entries[BundleLayout.descriptorPath(model.modelId)] = descriptorBytes(model.descriptor)
            model.catalog?.let { catalog ->
                entries[BundleLayout.catalogPath(model.modelId)] =
                    ModelCatalogToml.encode(catalog).toByteArray(Charsets.UTF_8)
            }
            val recipeEntries = model.recipes.map { recipe ->
                val path = "${recipeDir(recipe.kind, model.modelId)}/${recipe.name}.toml"
                entries[path] = recipe.bytes
                RecipeEntry(recipe.name, recipe.kind, path)
            }
            ModelManifestEntry(
                modelId = model.modelId,
                builderClass = model.builderClass,
                displayName = model.displayName,
                description = model.description,
                supportedApps = model.supportedApps,
                recipes = recipeEntries,
            )
        }
        val manifest = BundleManifest(
            bundleId = spec.bundleId,
            displayName = spec.displayName,
            description = spec.description,
            version = spec.version,
            kslApiVersion = spec.kslApiVersion,
            author = spec.author,
            homepage = spec.homepage,
            license = spec.license,
            tags = spec.tags,
            models = modelEntries,
        )
        entries[BundleLayout.BUNDLE_TOML] = BundleManifestToml.encode(manifest).toByteArray(Charsets.UTF_8)

        val parent = output.toAbsolutePath().parent ?: Paths.get(".")
        val tmp = Files.createTempFile(parent, output.fileName.toString(), ".tmp")
        try {
            JarRewriter.rewrite(input, tmp, entries, mapOf("Build-Time" to Instant.now().toString()))
            Files.move(tmp, output, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            try { Files.deleteIfExists(tmp) } catch (_: Exception) { /* swallow */ }
            throw e
        }
    }

    /** Byte-stable canonical JSON for a descriptor (same config as `kslpkg enrich`). */
    private fun descriptorBytes(descriptor: ModelDescriptor): ByteArray =
        descriptorJson.encodeToString(ModelDescriptor.serializer(), descriptor).toByteArray(Charsets.UTF_8)

    /** The in-JAR directory for a recipe of the given [kind] under [modelId]. */
    private fun recipeDir(kind: ConfigRecipeKind, modelId: String): String = when (kind) {
        ConfigRecipeKind.RUN -> BundleLayout.runRecipesDir(modelId)
        ConfigRecipeKind.SCENARIO_BATCH -> BundleLayout.scenarioRecipesDir(modelId)
        ConfigRecipeKind.OPTIMIZATION -> BundleLayout.optimizationRecipesDir(modelId)
        ConfigRecipeKind.EXPERIMENT -> BundleLayout.experimentRecipesDir(modelId)
    }
}
