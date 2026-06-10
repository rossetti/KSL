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

package ksl.app.editor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ksl.app.bundle.BundleLoader
import ksl.app.bundle.BundleModelProvider
import ksl.app.bundle.LoadedBundle
import ksl.app.settings.UserSettingsStore
import java.nio.file.Files
import java.nio.file.Path

/**
 *  Substrate-level bundle-library bookkeeping shared by every
 *  configuration-shaped app that supports interactive bundle
 *  loading: the list of [LoadedBundle]s currently in scope, the
 *  [BundleModelProvider] adapter that exposes them as a single picker
 *  source, and the load-jar mutator that grows that set while
 *  de-duplicating by `bundleId` and reloading a JAR in place when the
 *  same path is re-loaded with changed content.
 *
 *  Pre-decomposition, Scenario / Experiment / Simopt each
 *  reimplemented this with byte-identical state + a byte-identical
 *  `loadBundleJar` body + a byte-identical nested
 *  `sealed class LoadBundleResult`.  This type captures that shared
 *  shape so each app controller composes one instance instead of
 *  carrying ~30 LOC of duplicate plumbing.
 *
 *  ## What this owns
 *
 *  - The [loadedBundles] StateFlow — the set in scope, populated by
 *    [discoverFromClasspath] and [loadJar]; a reload replaces a JAR's
 *    prior entries in place.
 *  - The [bundleProvider] StateFlow — a [BundleModelProvider]
 *    wrapping the current set, or `null` when no bundles are loaded.
 *  - [discoverFromClasspath] — one-shot probe of the JVM classpath
 *    via [BundleLoader.loadFromClasspath]; hosts call once in init.
 *  - [loadJar] — JAR-load with de-duplication, in-place reload of a
 *    rebuilt JAR, and deferred close of any bundles it displaces.
 *  - [findBundle] — `bundleId`-keyed lookup helper.
 *  - [close] — closes every loaded and retired bundle's classloader
 *    and resets to empty; safe to call when empty and safe to call twice.
 *
 *  ## What this deliberately does NOT own
 *
 *  - **Host-side fan-out on bundle changes.**  Experiment and Simopt
 *    re-resolve [`currentModelDescriptor`] whenever the loaded set
 *    grows (a previously-unresolvable `ModelReference.ByBundleAndModelId`
 *    may now resolve).  The substrate exposes this via the
 *    [onBundlesChanged] constructor callback rather than a built-in
 *    coroutine collector — hosts pass `::refreshModelDescriptor`
 *    (or any other side-effect lambda) at construction time.
 *    Scenario passes the default no-op.
 *  - **The host's existing `loadBundleJar` public method.**  Hosts
 *    typically forward through to [loadJar] to keep their existing
 *    public surface stable, but the substrate doesn't dictate the
 *    method name.
 *  - **Host lifecycle.**  The substrate's [close] handles bundle
 *    cleanup only; hosts integrate it into their broader
 *    `AutoCloseable.close()` (run-handle cancellation, session
 *    cleanup, scope cancellation).
 *
 *  ## Semantics of the four mutators
 *
 *  | Method | Effect | Typical caller |
 *  |---|---|---|
 *  | [discoverFromClasspath] | append classpath bundles (if any) | host `init {}` |
 *  | [loadJar]               | append / reload a JAR's bundles (dedup by `bundleId`; reload by `sourceJar`) | host's `loadBundleJar` |
 *  | [findBundle]            | read-only lookup | host descriptor resolution |
 *  | [close]                 | close every loaded + retired bundle, reset to empty | host `close()` |
 *
 *  Substrate-level API — usable by any UI shell.  Owns no
 *  coroutine scope and no background work; the only async-shaped
 *  contract is the [onBundlesChanged] callback which is invoked
 *  synchronously inside the mutator that grew the set.
 *
 *  Not thread-safe: it assumes single-threaded (typically EDT-confined)
 *  mutation, matching how the four Swing apps drive it.  A future
 *  multi-threaded host would need to serialize calls externally.
 *
 *  @param onBundlesChanged invoked after every mutation that changes
 *  the loaded set ([discoverFromClasspath]'s successful add, and
 *  [loadJar]'s `Loaded` and `Reloaded` outcomes).  NOT invoked when
 *  [loadJar] returns `AlreadyLoaded`, `NoBundles`, or `Failed`.
 *  Defaults to a no-op.
 */
class BundleLibraryController(
    private val onBundlesChanged: () -> Unit = {}
) : AutoCloseable {

    private val myLoadedBundles = MutableStateFlow<List<LoadedBundle>>(emptyList())
    /**
     *  All bundles currently in scope — classpath-discovered +
     *  every JAR successfully loaded via [loadJar].  Bundles are
     *  removed only by a reload that replaces a whole JAR's prior
     *  entries (keyed on [`sourceJar`][LoadedBundle.sourceJar], the
     *  atomic classloader unit) or by [close].  A single bundle is
     *  never removed in isolation, since [LoadedBundle]s from the same
     *  JAR share a classloader.
     */
    val loadedBundles: StateFlow<List<LoadedBundle>> = myLoadedBundles.asStateFlow()

    private val myBundleProvider = MutableStateFlow<BundleModelProvider?>(null)
    /**
     *  Adapter exposing every loaded bundle as a single
     *  [BundleModelProvider].  `null` whenever [loadedBundles] is
     *  empty; non-null and rebuilt on every successful add.
     */
    val bundleProvider: StateFlow<BundleModelProvider?> = myBundleProvider.asStateFlow()

    /**
     *  Bundles displaced by a reload (a JAR re-loaded from a path whose
     *  prior content differed).  Their classloaders are NOT closed at
     *  reload time — an in-flight run or an already-built model may still
     *  depend on them — but are retained here and closed together in
     *  [close].  This bounds classloader retention to one displaced loader
     *  per content-changed reload, reclaimed at shutdown.
     */
    private val retired = mutableListOf<LoadedBundle>()

    /**
     *  Discover bundles already on the JVM classpath via
     *  [BundleLoader.loadFromClasspath] and append them to
     *  [loadedBundles].  Typically called once from the host's
     *  `init {}` block so a packaged app immediately shows
     *  available models in the picker.
     *
     *  No-op when the classpath carries no `KSLModelBundle` SPI
     *  entries.  When at least one is found, [onBundlesChanged]
     *  fires after the append.
     */
    fun discoverFromClasspath() {
        val classpathBundles = BundleLoader.loadFromClasspath()
        if (classpathBundles.isNotEmpty()) commit(myLoadedBundles.value + classpathBundles)
    }

    /**
     *  Discover bundles the user has installed into `~/.ksl/bundles/` via
     *  [BundleLoader.loadDirectory] and append them to [loadedBundles].
     *  The directory is created if it does not yet exist, giving users a
     *  well-known place to drop bundle JARs (e.g. the KSL Book Examples
     *  bundle).  Typically called once from the host's `init {}` block in
     *  place of [discoverFromClasspath], so a released app ships with no
     *  baked-in bundles yet still loads whatever the user installed.
     *
     *  No-op when the directory holds no loadable `KSLModelBundle` JARs.
     *  When at least one is found, [onBundlesChanged] fires after the append.
     */
    fun discoverFromUserBundlesDir() {
        val dir = UserSettingsStore.defaultSettingsDir().resolve("bundles")
        runCatching { Files.createDirectories(dir) }
        val dirBundles = BundleLoader.loadDirectory(dir)
        if (dirBundles.isNotEmpty()) commit(myLoadedBundles.value + dirBundles)
    }

    /**
     *  Load every `KSLModelBundle` from the JAR at [jarPath].  The
     *  outcome depends on whether that path — and that path's content —
     *  is already loaded:
     *
     *  - **New path** — the JAR's bundles are appended.  Bundles whose
     *    `bundleId` already exists (from a *different* source) are
     *    dropped, first-registration-wins, matching
     *    [BundleModelProvider]'s duplicate handling.  Returns
     *    [LoadBundleResult.Loaded], or [LoadBundleResult.AlreadyLoaded]
     *    if every `bundleId` was already present elsewhere.
     *  - **Same path, identical content** (same SHA-256) — no-op;
     *    returns [LoadBundleResult.AlreadyLoaded].
     *  - **Same path, changed content** (a rebuilt JAR) — the prior
     *    bundles from that path are *replaced* by the freshly-loaded
     *    ones so the picker serves the new code, and returns
     *    [LoadBundleResult.Reloaded].  The displaced bundles' classloaders
     *    are NOT closed immediately (a run in flight, or an
     *    already-built model, may still depend on them); they move to an
     *    internal retired list and are closed in [close].  See the
     *    [retired] field.
     *  - **No bundles in the JAR** — returns [LoadBundleResult.NoBundles].
     *  - **Load failure** — returns [LoadBundleResult.Failed].
     *
     *  [onBundlesChanged] fires only on `Loaded` and `Reloaded` (the
     *  cases that change the loaded set), not on `AlreadyLoaded`,
     *  `NoBundles`, or `Failed`.
     *
     *  Reload is keyed on [`sourceJar`][LoadedBundle.sourceJar] (the
     *  JAR is the atomic classloader unit), so classpath-loaded bundles
     *  (`sourceJar == null`) are never displaced by it.
     */
    fun loadJar(jarPath: Path): LoadBundleResult {
        val fresh = try {
            BundleLoader.loadJar(jarPath)
        } catch (t: Throwable) {
            return LoadBundleResult.Failed(
                t.message ?: t::class.simpleName ?: "load failed"
            )
        }
        if (fresh.isEmpty()) return LoadBundleResult.NoBundles

        val priorSamePath = myLoadedBundles.value.filter { it.sourceJar == jarPath }
        if (priorSamePath.isNotEmpty()) {
            // Same path already loaded — reload only if the content changed.
            if (priorSamePath.first().contentHash == fresh.first().contentHash) {
                closeGroup(fresh)   // identical rebuild — discard the redundant loader
                return LoadBundleResult.AlreadyLoaded(priorSamePath.map { it.bundle.bundleId })
            }
            val survivors = myLoadedBundles.value - priorSamePath.toSet()
            retired += priorSamePath               // deferred close (see [retired])
            commit(survivors + dedupAgainst(survivors, fresh))
            return LoadBundleResult.Reloaded(fresh.map { it.bundle.bundleId })
        }

        // New path.
        val toAdd = dedupAgainst(myLoadedBundles.value, fresh)
        if (toAdd.isEmpty()) {
            // Every bundleId is already loaded from another source — the whole
            // fresh JAR is unused, so closing its shared loader is safe.
            closeGroup(fresh)
            return LoadBundleResult.AlreadyLoaded(fresh.map { it.bundle.bundleId })
        }
        commit(myLoadedBundles.value + toAdd)
        return LoadBundleResult.Loaded(toAdd.map { it.bundle.bundleId })
    }

    /**
     *  Look up a loaded bundle by `bundleId`.  Returns `null` when
     *  no loaded bundle carries that id.
     */
    fun findBundle(bundleId: String): LoadedBundle? =
        myLoadedBundles.value.firstOrNull { it.bundle.bundleId == bundleId }

    /**
     *  Close every loaded and retired bundle's classloader and reset
     *  the controller to empty.  Wraps each close in [runCatching] so a
     *  single failure does not skip the remainder.  Safe when empty;
     *  safe to call twice (the second call sees empty lists and is a
     *  no-op).  After this call [loadedBundles] is empty and
     *  [bundleProvider] is `null`, so no consumer can read a stale,
     *  closed bundle.
     */
    override fun close() {
        (myLoadedBundles.value + retired).forEach { runCatching { it.close() } }
        retired.clear()
        myLoadedBundles.value = emptyList()
        myBundleProvider.value = null
    }

    /**
     *  Bundles from [fresh] whose `bundleId` is not already present in
     *  [keep].  Filtering (rather than closing) is deliberate: bundles
     *  from one JAR share a classloader, so a not-added sibling's loader
     *  must stay open for the bundles that *are* added.  The caller
     *  closes the whole [fresh] group only when none of it is retained.
     */
    private fun dedupAgainst(keep: List<LoadedBundle>, fresh: List<LoadedBundle>): List<LoadedBundle> {
        val ids = keep.map { it.bundle.bundleId }.toSet()
        return fresh.filter { it.bundle.bundleId !in ids }
    }

    private fun closeGroup(group: List<LoadedBundle>) {
        group.forEach { runCatching { it.close() } }
    }

    private fun commit(live: List<LoadedBundle>) {
        myLoadedBundles.value = live
        myBundleProvider.value = if (live.isEmpty()) null else BundleModelProvider(live)
        onBundlesChanged()
    }

    /**
     *  Outcome of [loadJar].
     *
     *  - [Loaded] — one or more new bundles were appended.  Carries the
     *    list of newly-added `bundleId`s for caller logging / UX.
     *  - [Reloaded] — a JAR at an already-loaded path was rebuilt and
     *    its bundles were replaced with the new content.  Carries the
     *    reloaded `bundleId`s.
     *  - [AlreadyLoaded] — nothing changed: either the same path with
     *    identical content, or a new path whose every `bundleId` was
     *    already loaded from another source.  Carries those `bundleId`s.
     *  - [NoBundles] — the JAR declares no `KSLModelBundle` service.
     *  - [Failed] — the load attempt threw.  [Failed.reason] is
     *    suitable for surfacing to the user.
     */
    sealed class LoadBundleResult {
        data class Loaded(val newBundleIds: List<String>) : LoadBundleResult()
        data class Reloaded(val bundleIds: List<String>) : LoadBundleResult()
        data class AlreadyLoaded(val bundleIds: List<String>) : LoadBundleResult()
        object NoBundles : LoadBundleResult()
        data class Failed(val reason: String) : LoadBundleResult()
    }
}
