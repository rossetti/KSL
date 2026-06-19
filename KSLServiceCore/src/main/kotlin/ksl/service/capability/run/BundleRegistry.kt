/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2023  Manuel D. Rossetti, rossetti@uark.edu
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ksl.service.capability.run

import ksl.app.bundle.BundleLoader
import ksl.app.bundle.BundleModelProvider
import ksl.app.bundle.KSLAppKind
import ksl.app.bundle.KSLConfigRecipe
import ksl.app.bundle.LoadedBundle
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import ksl.simulation.ModelDescriptor
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

/**
 * A thread-safe view over the bundles a server process makes available, with
 * live mutation for dynamic loading (Phase 8.6). The active set is an immutable
 * list behind a `@Volatile` reference; reads take the current snapshot lock-free,
 * and mutations ([loadOrReplaceFromJar] / [removeFromJar]) swap the reference
 * under a lock.
 *
 * Classloader lifecycle uses **deferred close** (the Phase 6 §14.5 pattern): a
 * bundle displaced by a reload or removal is *not* closed immediately (an
 * in-flight run or a built model may still depend on its classloader); it is
 * retained and closed at [close]. This bounds retention to displaced loaders,
 * reclaimed at shutdown.
 */
class BundleRegistry private constructor(
    initial: List<LoadedBundle>,
) : AutoCloseable {

    @Volatile
    private var bundles: List<LoadedBundle> = initial

    // The resolved view (one active bundle per bundleId), recomputed on every
    // mutation. Reads route through [active]; [bundles] stays the raw set so the
    // watcher tracks every source jar (incl. shadowed ones) and removal works.
    @Volatile
    private var active: List<LoadedBundle> = initial
    @Volatile
    private var shadowedCounts: Map<String, Int> = emptyMap()
    @Volatile
    private var activeConflicts: List<BundleConflict> = emptyList()

    private val displaced = CopyOnWriteArrayList<LoadedBundle>()
    private val mutationLock = Any()

    init {
        synchronized(mutationLock) { recompute() }
    }

    /**
     * Recompute the active set from the raw [bundles] (caller holds [mutationLock]):
     * one active bundle per `bundleId`, newest-wins, true-duplicates collapsed
     * ([BundleResolver]); shadowed copies stay loaded for instant promotion.
     * Logs conflicts (WARN) and true-duplicate collapses (INFO) for the operator.
     */
    private fun recompute() {
        val raw = bundles
        val keys = raw.mapIndexed { i, lb ->
            BundleKey(i, lb.bundle.bundleId, lb.contentHash, lb.builtAt, lb.sourceJar?.fileName?.toString())
        }
        val resolved = BundleResolver.resolve(keys)
        active = resolved.map { raw[it.activeIndex] }
        shadowedCounts = resolved.associate { it.bundleId to it.shadowedIndices.size }
        activeConflicts = resolved.filter { it.shadowedIndices.isNotEmpty() }.map { r ->
            val winner = raw[r.activeIndex]
            val shadowedSources = r.shadowedIndices.map { raw[it].sourceJar?.fileName?.toString() }
            // Distinguish true duplicates (same content) from real version conflicts.
            val realConflicts = r.shadowedIndices.count { raw[it].contentHash != winner.contentHash }
            if (realConflicts > 0) {
                logger.warn {
                    "bundle '${r.bundleId}': using ${winner.sourceJar?.fileName} (built ${winner.builtAt}); " +
                        "shadowed newer-loses copies $shadowedSources"
                }
            } else {
                logger.info { "bundle '${r.bundleId}': collapsed ${r.shadowedIndices.size} identical duplicate jar(s)" }
            }
            BundleConflict(r.bundleId, winner.sourceJar?.fileName?.toString(), shadowedSources)
        }
    }

    /**
     * The active bundle catalog — **one entry per `bundleId`** (true duplicates and
     * older copies are resolved away). When an active bundle superseded others,
     * [BundleInfo.shadowedCount] and [BundleInfo.notice] disclose it (the student
     * catalog hint); [conflicts] gives the operator detail.
     */
    fun listBundles(): List<BundleInfo> = active.map { lb ->
        val shadowed = shadowedCounts[lb.bundle.bundleId] ?: 0
        BundleInfo(
            bundleId = lb.bundle.bundleId,
            displayName = lb.bundle.displayName,
            description = lb.bundle.description,
            version = lb.bundle.version,
            modelIds = lb.bundle.models.map { it.modelId },
            source = lb.sourceJar?.fileName?.toString(),
            shadowedCount = shadowed,
            notice = if (shadowed > 0) {
                "superseded $shadowed older/duplicate cop${if (shadowed == 1) "y" else "ies"} (using the newest build)"
            } else null,
        )
    }

    /** Active winner + shadowed sources per contested `bundleId` (empty when none). */
    fun conflicts(): List<BundleConflict> = activeConflicts

    /** The model ids exposed by one bundle, or an empty list if unknown. */
    fun listModels(bundleId: String): List<String> =
        find(bundleId)?.bundle?.models?.map { it.modelId } ?: emptyList()

    /**
     * The [ModelDescriptor] for one model, or null if the bundle or model is
     * unknown. Resolves via the bundle's in-JAR descriptor, the on-disk cache,
     * or lazy extraction (the [LoadedBundle.descriptorFor] priority order).
     *
     * `descriptorFor` throws when a model id is absent, so this guards against
     * an unknown model id and returns null instead — a defensive contract for
     * callers (e.g. an agent-facing tool) that pass arbitrary ids.
     */
    fun describeModel(bundleId: String, modelId: String): ModelDescriptor? {
        val loaded = find(bundleId) ?: return null
        if (loaded.bundle.models.none { it.modelId == modelId }) return null
        return loaded.descriptorFor(modelId)
    }

    /**
     * A [ModelProviderIfc] over the registry's bundles, suitable for a
     * `KSLAppSession`. Models resolve by their (bundle-flattened) model id, so a
     * `RunConfiguration` referencing `ModelReference.ByProviderId(modelId)` runs
     * through it. The [RunService] builds its session from this.
     */
    fun modelProvider(): BundleModelProvider = BundleModelProvider(active)

    /**
     * The task kinds a model declares it supports (`SINGLE`, `SCENARIO`,
     * `EXPERIMENT`, `SIMOPT`) — the agent's "menu of intents" for this model.
     */
    fun modelKinds(bundleId: String, modelId: String): Set<KSLAppKind> =
        find(bundleId)?.bundle?.models?.firstOrNull { it.modelId == modelId }?.supportedApps ?: emptySet()

    /**
     * The author-curated config recipes shipped with a model (typed by
     * `ConfigRecipeKind`) — the strongest authoring help: real, known-good
     * starting documents. Empty when the author shipped none.
     */
    fun recipes(bundleId: String, modelId: String): List<KSLConfigRecipe> =
        find(bundleId)?.bundle?.recipesFor(modelId) ?: emptyList()

    /**
     * The [ModelDescriptor] for a model by its (bundle-flattened) id alone,
     * scanning every bundle — used when a document references a model by
     * provider id without naming its bundle (e.g. an `ExperimentConfiguration`).
     */
    fun descriptorForModelId(modelId: String): ModelDescriptor? {
        val loaded = active.firstOrNull { lb -> lb.bundle.models.any { it.modelId == modelId } } ?: return null
        return loaded.descriptorFor(modelId)
    }

    /**
     * A version token for the bundle providing [modelId]: its content hash when
     * loaded from a jar (so a *rebuilt* model changes the token), else the
     * bundle's declared version. Used to salt result-cache keys so a reloaded
     * model invalidates results cached under its old code (Phase 8 §9). An
     * unknown model contributes no token.
     */
    fun modelVersion(modelId: String): String? {
        val loaded = active.firstOrNull { lb -> lb.bundle.models.any { it.modelId == modelId } } ?: return null
        return loaded.contentHash ?: loaded.bundle.version
    }

    /** A stable cache salt over the versions of [modelIds] (sorted; unknown ids skipped). */
    fun versionSaltFor(modelIds: Collection<String>): String =
        modelIds.toSortedSet().joinToString("|") { "$it@${modelVersion(it) ?: ""}" }

    private fun find(bundleId: String): LoadedBundle? =
        active.firstOrNull { it.bundle.bundleId == bundleId }

    /** The current active bundle set (resolved; an immutable snapshot). */
    fun currentBundles(): List<LoadedBundle> = active

    /** Source jar → content hash for every loaded bundle (the watcher's diff input). */
    fun knownSources(): Map<Path, String> =
        bundles.mapNotNull { lb -> lb.sourceJar?.let { it to (lb.contentHash ?: "") } }.toMap()

    /**
     * Loads the bundles in [path], replacing any currently loaded from the same
     * jar (their classloaders are displaced for deferred close). Returns the
     * number of bundles the jar provided.
     */
    fun loadOrReplaceFromJar(path: Path): Int {
        val loaded = BundleLoader.loadJar(path)
        synchronized(mutationLock) {
            val (fromThisJar, others) = bundles.partition { it.sourceJar == path }
            displaced.addAll(fromThisJar)
            bundles = others + loaded
            recompute()
        }
        return loaded.size
    }

    /** Drops the bundles loaded from [path] (their classloaders are deferred-closed). */
    fun removeFromJar(path: Path) {
        synchronized(mutationLock) {
            val (gone, kept) = bundles.partition { it.sourceJar == path }
            displaced.addAll(gone)
            bundles = kept
            recompute()
        }
    }

    override fun close() {
        (bundles + displaced).forEach { runCatching { it.close() } }
    }

    companion object {
        /** An empty registry (the watcher loads into it). */
        fun empty(): BundleRegistry = BundleRegistry(emptyList())

        /** Builds a registry from every bundle visible on the current classpath. */
        fun fromClasspath(): BundleRegistry =
            BundleRegistry(BundleLoader.loadFromClasspath())

        /** Builds a registry from every bundle JAR in [dir] (non-recursive). */
        fun fromDirectory(dir: Path): BundleRegistry =
            BundleRegistry(BundleLoader.loadDirectory(dir))
    }
}

/**
 * Lightweight identity record for one (active) bundle, suitable for a picker or
 * tool list. The trailing fields are optional disclosure (default → unchanged
 * JSON for existing clients): [source] is the active jar's file name, and when
 * this bundle superseded older/duplicate copies, [shadowedCount] > 0 and [notice]
 * carries a short human hint for the student-facing catalog.
 */
@Serializable
data class BundleInfo(
    val bundleId: String,
    val displayName: String,
    val description: String,
    val version: String,
    val modelIds: List<String>,
    val source: String? = null,
    val shadowedCount: Int = 0,
    val notice: String? = null,
)
