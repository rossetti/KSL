package ksl.app.bundle

import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.simulation.ModelBuilderIfc
import ksl.simulation.ModelDescriptor
import ksl.utilities.io.DynamicJarClassLoader
import java.net.URLClassLoader
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Discovers the `ksl.simulation.ModelBuilderIfc` implementations inside a plain
 * **builders JAR** — the model author's deliverable — and extracts each model's
 * `ksl.simulation.ModelDescriptor` by building it once.
 *
 * This is the read side of the builders-JAR → bundle-JAR pipeline (the inverse of
 * what an author writes): the tooling reflectively finds the builder classes
 * (`DynamicJarClassLoader.findSubclasses`), instantiates each via [loadModelBuilder]
 * (honouring the named-class / Kotlin-`object` contract), and calls
 * `builder().build(null, null).modelDescriptor()`. A builder that fails to
 * instantiate or build is reported individually so one bad builder does not sink
 * discovery of the rest.
 *
 * It performs no writes. The caller (the Bundle Workbench's authoring session or
 * `kslpkg assemble`) uses the result to seed a bundle manifest and per-model
 * authoring drafts.
 */
object BuilderDiscovery {

    /**
     * One discovered builder. Exactly one of [descriptor]/[error] is non-null:
     * [descriptor] when the model built successfully, [error] (a human-readable
     * message) when instantiation or building failed.
     *
     * @param builderClass FQN of the `ModelBuilderIfc` implementation
     */
    data class DiscoveredBuilder(
        val builderClass: String,
        val descriptor: ModelDescriptor?,
        val error: String?,
    ) {
        /** True when the model built and its descriptor was extracted. */
        val isOk: Boolean get() = descriptor != null
    }

    /**
     * Discovers and builds every `ModelBuilderIfc` in [jarPath]. Returns one
     * [DiscoveredBuilder] per implementation found, sorted by class name; an empty
     * list when the JAR declares none.
     *
     * @param jarPath the builders JAR to inspect
     * @param parent  parent classloader for delegation (KSL types); defaults to the
     *                loader that holds KSLCore
     */
    fun discover(
        jarPath: Path,
        parent: ClassLoader = BundleLoader.defaultParent(),
    ): List<DiscoveredBuilder> {
        // Pass [parent] (the loader that holds KSLCore) so the scan resolves ModelBuilderIfc and other KSL
        // supertypes regardless of the calling thread's context classloader. Without it findSubclasses falls
        // back to the ambient thread contextClassLoader, which on a server request thread does not see the KSL
        // classes — linking a builder then throws NoClassDefFoundError and (as an Error) crashes the caller.
        val fqns = DynamicJarClassLoader.findSubclasses(jarPath, ModelBuilderIfc::class.java, parent).sorted()
        if (fqns.isEmpty()) {
            logger.info { "No ModelBuilderIfc implementations found in $jarPath" }
            return emptyList()
        }
        URLClassLoader(arrayOf(jarPath.toUri().toURL()), parent).use { cl ->
            return fqns.map { fqn ->
                try {
                    val descriptor = loadModelBuilder(cl, fqn).build(null, null).modelDescriptor()
                    DiscoveredBuilder(fqn, descriptor, null)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to build model from $fqn in $jarPath" }
                    DiscoveredBuilder(fqn, null, e.message ?: e.toString())
                } catch (e: LinkageError) {
                    // A builder compiled against a different KSL, or with an unresolved dependency, throws a
                    // LinkageError (an Error, not an Exception). Record it as a discovery failure instead of
                    // letting it escape and abort the whole request. VirtualMachineError still propagates.
                    logger.warn(e) { "Failed to link/build model from $fqn in $jarPath" }
                    DiscoveredBuilder(fqn, null, e.message ?: e.toString())
                }
            }
        }
    }
}
