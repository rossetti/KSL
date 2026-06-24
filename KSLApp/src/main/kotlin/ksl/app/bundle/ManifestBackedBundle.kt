package ksl.app.bundle

import ksl.app.config.BundleManifest
import ksl.app.config.ModelManifestEntry
import ksl.app.config.RecipeEntry
import ksl.simulation.ModelBuilderIfc
import java.io.InputStream

/**
 * The single reusable, data-driven `KSLModelBundle` implementation. It interprets a
 * [BundleManifest] at runtime instead of being a hand-written, per-bundle class.
 *
 * `BundleLoader` constructs one of these when it finds a `BundleLayout.BUNDLE_TOML`
 * manifest in a JAR (or on the classpath); the [classLoader] is the loader that can
 * resolve the bundle's builder classes and recipe resources (typically the JAR's
 * `URLClassLoader`). Because every member is derived from the manifest, enriching a
 * builders JAR is a pure *data* operation — no `KSLModelBundle` class is generated
 * or compiled.
 *
 * @param classLoader resolves builder classes ([ManifestBackedModel.builder]) and
 *                    recipe resources ([recipesFor]); must be able to see the
 *                    bundle JAR's contents
 * @param manifest    the parsed bundle manifest
 */
class ManifestBackedBundle(
    private val classLoader: ClassLoader,
    private val manifest: BundleManifest,
) : KSLModelBundle {

    override val bundleId: String = manifest.bundleId
    override val displayName: String = manifest.displayName
    override val description: String = manifest.description
    override val version: String = manifest.version
    override val kslApiVersion: String = manifest.kslApiVersion
    override val author: String? = manifest.author
    override val homepage: String? = manifest.homepage
    override val license: String? = manifest.license
    override val tags: Set<String> = manifest.tags

    override val models: List<KSLBundledModel> =
        manifest.models.map { ManifestBackedModel(classLoader, it) }

    private val recipesByModel: Map<String, List<KSLConfigRecipe>> =
        manifest.models.associate { entry ->
            entry.modelId to entry.recipes.map { ResourceRecipe(classLoader, it) }
        }

    override fun recipesFor(modelId: String): List<KSLConfigRecipe> =
        recipesByModel[modelId] ?: emptyList()
}

/**
 * One model exposed by a [ManifestBackedBundle], backed by a [ModelManifestEntry].
 *
 * [builder] reflectively obtains the model's `ModelBuilderIfc` from the entry's
 * `builderClass`: a Kotlin `object` is returned via its `INSTANCE` field, otherwise
 * a public zero-argument constructor is invoked. The instance is created on each
 * call (cheap, per the `KSLBundledModel.builder` contract).
 */
private class ManifestBackedModel(
    private val classLoader: ClassLoader,
    private val entry: ModelManifestEntry,
) : KSLBundledModel {

    override val modelId: String = entry.modelId
    override val displayName: String = entry.displayName
    override val description: String = entry.description
    override val supportedApps: Set<KSLAppKind> = entry.supportedApps

    override fun builder(): ModelBuilderIfc = loadModelBuilder(classLoader, entry.builderClass)
}

/**
 * Reflectively obtains a [ModelBuilderIfc] named by [builderClass] from
 * [classLoader]: a Kotlin `object` is returned via its public static `INSTANCE`
 * field; otherwise a public zero-argument constructor is invoked. Shared by
 * [ManifestBackedModel] and [BuilderDiscovery] so both honour the same contract.
 *
 * @throws IllegalStateException if the class is absent or lacks a usable constructor
 * @throws IllegalArgumentException if the class does not implement [ModelBuilderIfc]
 */
internal fun loadModelBuilder(classLoader: ClassLoader, builderClass: String): ModelBuilderIfc {
    val cls = try {
        classLoader.loadClass(builderClass)
    } catch (e: ClassNotFoundException) {
        throw IllegalStateException(
            "builder class '$builderClass' is not present on the classloader", e
        )
    }
    require(ModelBuilderIfc::class.java.isAssignableFrom(cls)) {
        "builder class '$builderClass' does not implement ${ModelBuilderIfc::class.java.name}"
    }
    val instance: Any =
        runCatching { cls.getField("INSTANCE") }.getOrNull()
            ?.takeIf { ModelBuilderIfc::class.java.isAssignableFrom(it.type) }
            ?.get(null)
            ?: run {
                val ctor = try {
                    cls.getDeclaredConstructor()
                } catch (e: NoSuchMethodException) {
                    throw IllegalStateException(
                        "builder class '$builderClass' has no public zero-argument constructor", e
                    )
                }
                ctor.isAccessible = true
                ctor.newInstance()
            }
    return instance as ModelBuilderIfc
}

/**
 * A [KSLConfigRecipe] whose bytes live at a fixed in-JAR resource [RecipeEntry.path],
 * read through the bundle's [classLoader] on each [openStream].
 */
private class ResourceRecipe(
    private val classLoader: ClassLoader,
    private val entry: RecipeEntry,
) : KSLConfigRecipe {

    override val name: String = entry.name
    override val kind: ConfigRecipeKind = entry.kind

    override fun openStream(): InputStream =
        classLoader.getResourceAsStream(entry.path)
            ?: throw IllegalStateException(
                "Recipe '${entry.name}' declares resource '${entry.path}', which is not " +
                    "present in the bundle."
            )
}
