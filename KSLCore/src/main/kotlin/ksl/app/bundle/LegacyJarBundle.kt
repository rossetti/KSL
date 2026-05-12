package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simulation.ModelBuilderIfc
import ksl.utilities.io.DynamicJarClassLoader
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Synthesized `KSLModelBundle` for JARs that predate the bundle SPI — i.e. JARs
 * that do not declare a `META-INF/services/ksl.app.bundle.KSLModelBundle`
 * services file. Such JARs are discovered reflectively via
 * `ksl.utilities.io.DynamicJarClassLoader`, the same mechanism used by the
 * legacy `ksl.utilities.io.JARModelBuilder`.
 *
 * Discovered `ksl.simulation.ModelBuilderIfc` classes are wrapped one-to-one
 * as `KSLBundledModel` entries. Identity fields are derived from the JAR
 * filename and the discovered class names; capability declarations are
 * generous (all four app kinds) because there is no metadata to constrain
 * them. Legacy bundles ship no `KSLConfigRecipe` entries.
 *
 * Instances own a `DynamicJarClassLoader` and are `AutoCloseable`; the
 * enclosing `LoadedBundle` closes this object as part of its own `close`.
 */
internal class LegacyJarBundle private constructor(
    override val bundleId: String,
    override val displayName: String,
    override val models: List<KSLBundledModel>,
    private val loader: DynamicJarClassLoader
) : KSLModelBundle, AutoCloseable {

    override val description: String =
        "Legacy JAR (no bundle manifest); models discovered by reflection."

    override val version: String = "unversioned"

    override val kslApiVersion: String = "unknown"

    override fun close() {
        try {
            loader.close()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to close DynamicJarClassLoader for legacy bundle $bundleId" }
        }
    }

    companion object {

        /**
         * Attempts to build a `LegacyJarBundle` from `jarPath`. Returns `null`
         * if no `ModelBuilderIfc` implementations are discovered; in that case
         * the caller should treat the JAR as having no bundles to offer.
         *
         * The returned bundle owns a fresh `DynamicJarClassLoader`. Its
         * `close` must be called (typically transitively via `LoadedBundle.close`).
         */
        fun tryCreate(jarPath: Path, @Suppress("UNUSED_PARAMETER") parent: ClassLoader): LegacyJarBundle? {
            val loader = DynamicJarClassLoader(jarPath)
            val discovered: Map<String, Class<*>> = try {
                loader.findSubClasses(ModelBuilderIfc::class.java)
            } catch (e: Exception) {
                logger.warn(e) { "Reflective scan failed for $jarPath" }
                loader.close()
                return null
            }
            if (discovered.isEmpty()) {
                loader.close()
                return null
            }
            val bundleIdValue = jarPath.fileName.toString().removeSuffix(".jar").removeSuffix(".JAR")
            val displayNameValue = jarPath.fileName.toString()
            val models: List<KSLBundledModel> = discovered.map { (fqn, cls) ->
                LegacyBundledModel(modelId = fqn, simpleName = cls.simpleName ?: fqn, loader = loader, cls = cls)
            }
            return LegacyJarBundle(
                bundleId = bundleIdValue,
                displayName = displayNameValue,
                models = models,
                loader = loader
            )
        }
    }

    /**
     * Adapter exposing one reflectively-discovered `ModelBuilderIfc` class as
     * a `KSLBundledModel`. Each call to `builder` resolves the builder afresh
     * from the JAR's classloader, preferring a Kotlin singleton `object`
     * declaration and falling back to a public no-argument constructor — the
     * same two-step resolution used by `JARModelBuilder.initializeBuilder`.
     */
    private class LegacyBundledModel(
        override val modelId: String,
        simpleName: String,
        private val loader: DynamicJarClassLoader,
        private val cls: Class<*>
    ) : KSLBundledModel {

        override val displayName: String = simpleName

        override val description: String = "Discovered by reflection: $modelId"

        override val supportedApps: Set<KSLAppKind> = setOf(
            KSLAppKind.SINGLE,
            KSLAppKind.SCENARIO,
            KSLAppKind.EXPERIMENT,
            KSLAppKind.SIMOPT
        )

        override fun builder(): ModelBuilderIfc {
            val instance = loader.singletonObjectReference(cls)
                ?: loader.noArgumentInstance(cls)
                ?: throw RuntimeException(
                    "Cannot instantiate $modelId: no Kotlin object singleton and no public no-arg constructor"
                )
            return instance as ModelBuilderIfc
        }
    }
}
