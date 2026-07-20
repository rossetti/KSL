package ksl.app.bundle

import ksl.app.config.BundleManifest
import ksl.app.config.BundleManifestToml
import ksl.app.config.ModelCatalogToml
import ksl.simulation.ModelCatalog
import ksl.simulation.ModelDescriptor
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile

/**
 * The **headless** state and logic of authoring a bundle from a builders JAR — the
 * GUI-free core that both the Bundle Workbench and a future bundling MCP server
 * drive (the capabilities-vs-transport split: this is "the authoring capability",
 * a UI or an agent is just a thin adapter over it).
 *
 * Opening a session discovers the JAR's `ModelBuilderIfc` implementations
 * ([BuilderDiscovery]) and seeds an editable [ModelDraft] for each that built
 * successfully (builders that failed are surfaced via [discoveryErrors]). The caller
 * edits the bundle identity and per-model drafts, then [validate]s and [assemble]s.
 *
 * All authoring *decisions* live here — default `modelId` derivation, the editable
 * draft model, validation orchestration, and driving [BundleAssembler] — so nothing
 * a UI can do is unavailable to a headless caller.
 */
class BundleAuthoringSession private constructor(
    /** The builders JAR being enriched; opened read-only, never modified. */
    val inputJar: Path,
    discovered: List<BuilderDiscovery.DiscoveredBuilder>,
) {

    // ── Bundle identity (editable) ──────────────────────────────────────────────
    var bundleId: String = ""
    var displayName: String = inputJar.fileName.toString().removeSuffix(".jar")
    var description: String = ""
    var version: String? = null
    var kslApiVersion: String? = null
    var author: String? = null
    var homepage: String? = null
    var license: String? = null
    val tags: MutableSet<String> = linkedSetOf()

    /** Builders that failed to instantiate or build (cannot be authored as models). */
    val discoveryErrors: List<BuilderDiscovery.DiscoveredBuilder> = discovered.filterNot { it.isOk }

    /** One editable draft per successfully-built model, in discovered (sorted) order. */
    val models: List<ModelDraft> =
        discovered.filter { it.isOk }.map { ModelDraft(it.builderClass, it.descriptor!!) }

    /** Editable authoring state for one model. */
    class ModelDraft internal constructor(
        val builderClass: String,
        /** The extracted descriptor (read-only; the structural truth of the model). */
        val descriptor: ModelDescriptor,
    ) {
        var modelId: String = defaultModelId(builderClass)
        var displayName: String = modelId
        var description: String = ""
        // Sensible default: every model works in the single and scenario apps. EXPERIMENT is
        // added only when the model exposes at least two numeric factors — the same predicate
        // BundleValidation and ExperimentDocuments enforce. `inputNames` unifies the numeric
        // @KSLControl keys AND the RV parameter keys, so an M/M/1-style model whose only factors
        // are its RV means still qualifies. SIMOPT is left off — it also needs bounded numeric
        // inputs and an objective the author should opt into.
        val supportedApps: MutableSet<KSLAppKind> =
            linkedSetOf(KSLAppKind.SINGLE, KSLAppKind.SCENARIO).apply {
                if (descriptor.inputNames.size >= 2) add(KSLAppKind.EXPERIMENT)
            }
        var catalog: ModelCatalog? = null
    }

    /**
     * Builds the [BundleAssembler.BundleSpec] from the current draft state.  Models
     * whose `modelId` is in [excludeModelIds] are dropped from the bundle — e.g. a
     * shared builder closure that is embedded for runtime but should not surface as a
     * selectable model.
     */
    fun buildSpec(excludeModelIds: Set<String> = emptySet()): BundleAssembler.BundleSpec = BundleAssembler.BundleSpec(
        bundleId = bundleId,
        displayName = displayName,
        description = description,
        version = version,
        kslApiVersion = kslApiVersion,
        author = author,
        homepage = homepage,
        license = license,
        tags = tags.toSet(),
        models = models.filterNot { it.modelId in excludeModelIds }.map { d ->
            BundleAssembler.ModelSpec(
                modelId = d.modelId,
                builderClass = d.builderClass,
                displayName = d.displayName,
                description = d.description,
                supportedApps = d.supportedApps.toSet(),
                descriptor = d.descriptor,
                catalog = d.catalog,
            )
        },
    )

    /** The default output path for [assemble]: `<input-stem>-bundle.jar` beside the input. */
    fun defaultOutputPath(): Path = BundleAssembler.defaultOutputPath(inputJar)

    /**
     * Validates the current draft by assembling it to a temporary bundle JAR, loading
     * it, and running [BundleValidation] against the real artifact — so the report
     * reflects exactly what [assemble] would produce. The temp file is removed before
     * returning.
     */
    fun validate(excludeModelIds: Set<String> = emptySet()): BundleValidation.ValidationReport {
        val tmpDir = Files.createTempDirectory("ksl-bundle-validate")
        val tmpJar = tmpDir.resolve("candidate-bundle.jar")
        try {
            BundleAssembler.assemble(inputJar, tmpJar, buildSpec(excludeModelIds), force = true)
            val loaded = BundleLoader.loadJar(tmpJar)
            try {
                return loaded.firstOrNull()?.let { BundleValidation.validate(it) }
                    ?: BundleValidation.ValidationReport(
                        listOf(
                            BundleValidation.Finding(
                                BundleValidation.Severity.ERROR, "bundle",
                                "no bundle was produced from the draft"
                            )
                        )
                    )
            } finally {
                loaded.forEach { runCatching { it.close() } }
            }
        } finally {
            runCatching { Files.deleteIfExists(tmpJar) }
            runCatching { Files.deleteIfExists(tmpDir) }
        }
    }

    /**
     * Assembles the bundle JAR at [output] from the current draft. The input builders
     * JAR is never modified; [output] must differ from it and is overwritten only
     * with [force].
     */
    fun assemble(output: Path, force: Boolean = false, excludeModelIds: Set<String> = emptySet()) =
        BundleAssembler.assemble(inputJar, output, buildSpec(excludeModelIds), force)

    companion object {
        /**
         * Opens an authoring session over [inputJar], discovering and building its
         * model builders.
         *
         * @throws IllegalArgumentException if [inputJar] is not a regular file
         */
        fun open(
            inputJar: Path,
            parent: ClassLoader = BundleLoader.defaultParent(),
        ): BundleAuthoringSession {
            require(Files.isRegularFile(inputJar)) { "not a regular file: $inputJar" }
            return BundleAuthoringSession(inputJar, BuilderDiscovery.discover(inputJar, parent))
        }

        /**
         * Opens an authoring session over an **already-assembled bundle JAR**, resuming
         * the draft: discovers/builds the in-JAR builders (for descriptors) and then
         * overlays the `bundle.toml` identity + per-model metadata and the in-JAR
         * `catalog.toml`. Models are matched to manifest entries by
         * `builderClass`. If [bundleJar] has no manifest it behaves like [open].
         */
        fun openExisting(
            bundleJar: Path,
            parent: ClassLoader = BundleLoader.defaultParent(),
        ): BundleAuthoringSession {
            require(Files.isRegularFile(bundleJar)) { "not a regular file: $bundleJar" }
            val session = open(bundleJar, parent)
            val manifest = readManifest(bundleJar) ?: return session
            session.bundleId = manifest.bundleId
            session.displayName = manifest.displayName
            session.description = manifest.description
            session.version = manifest.version
            session.kslApiVersion = manifest.kslApiVersion
            session.author = manifest.author
            session.homepage = manifest.homepage
            session.license = manifest.license
            session.tags.clear(); session.tags.addAll(manifest.tags)
            for (entry in manifest.models) {
                val draft = session.models.firstOrNull { it.builderClass == entry.builderClass } ?: continue
                draft.modelId = entry.modelId
                draft.displayName = entry.displayName
                draft.description = entry.description
                draft.supportedApps.clear(); draft.supportedApps.addAll(entry.supportedApps)
                draft.catalog = readEntryText(bundleJar, BundleLayout.catalogPath(entry.modelId))
                    ?.let { runCatching { ModelCatalogToml.decode(it) }.getOrNull() }
            }
            return session
        }

        private fun readManifest(jar: Path): BundleManifest? =
            readEntryText(jar, BundleLayout.BUNDLE_TOML)?.let { runCatching { BundleManifestToml.decode(it) }.getOrNull() }

        private fun readEntryBytes(jar: Path, path: String): ByteArray? = runCatching {
            JarFile(jar.toFile()).use { jf -> jf.getJarEntry(path)?.let { e -> jf.getInputStream(e).use { it.readBytes() } } }
        }.getOrNull()

        private fun readEntryText(jar: Path, path: String): String? =
            readEntryBytes(jar, path)?.toString(Charsets.UTF_8)

        /**
         * Derives a default, filesystem-safe `modelId` from a builder's FQN: the
         * simple class name with a trailing `ModelBuilder`/`Builder` removed
         * (e.g. `ksl.examples.mm1.MM1Builder` → `MM1`).
         */
        fun defaultModelId(builderClass: String): String {
            val simple = builderClass.substringAfterLast('.')
            return simple.removeSuffix("ModelBuilder").removeSuffix("Builder").ifBlank { simple }
        }
    }
}
