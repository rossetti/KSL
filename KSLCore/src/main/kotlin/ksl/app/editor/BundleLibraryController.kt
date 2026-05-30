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
import java.nio.file.Path

/**
 *  Substrate-level bundle-library bookkeeping shared by every
 *  configuration-shaped app that supports interactive bundle
 *  loading: the append-only list of [LoadedBundle]s currently in
 *  scope, the [BundleModelProvider] adapter that exposes them as a
 *  single picker source, and the load-jar mutator that grows that
 *  set while de-duplicating by `bundleId`.
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
 *  - The [loadedBundles] StateFlow — append-only list, populated by
 *    [discoverFromClasspath] and [loadJar].
 *  - The [bundleProvider] StateFlow — a [BundleModelProvider]
 *    wrapping the current set, or `null` when no bundles are loaded.
 *  - [discoverFromClasspath] — one-shot probe of the JVM classpath
 *    via [BundleLoader.loadFromClasspath]; hosts call once in init.
 *  - [loadJar] — JAR-load with de-duplication and prompt cleanup of
 *    duplicate classloaders.
 *  - [findBundle] — `bundleId`-keyed lookup helper.
 *  - [close] — closes every loaded bundle's classloader; safe to
 *    call when empty and safe to call twice.
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
 *  | [loadJar]               | append a JAR's bundles (dedup by `bundleId`) | host's `loadBundleJar` |
 *  | [findBundle]            | read-only lookup | host descriptor resolution |
 *  | [close]                 | close every loaded bundle | host `close()` |
 *
 *  Substrate-level API — usable by any UI shell.  Owns no
 *  coroutine scope and no background work; the only async-shaped
 *  contract is the [onBundlesChanged] callback which is invoked
 *  synchronously inside the mutator that grew the set.
 *
 *  @param onBundlesChanged invoked after every successful mutation
 *  ([discoverFromClasspath]'s successful add, [loadJar]'s
 *  `Loaded` outcome).  NOT invoked when [loadJar] returns `Failed`
 *  or `NoBundles`.  Defaults to a no-op.
 */
class BundleLibraryController(
    private val onBundlesChanged: () -> Unit = {}
) : AutoCloseable {

    private val myLoadedBundles = MutableStateFlow<List<LoadedBundle>>(emptyList())
    /**
     *  All bundles currently in scope — classpath-discovered +
     *  every JAR successfully loaded via [loadJar].  Append-only:
     *  bundles are never removed except via [close], because
     *  [LoadedBundle]s from the same JAR share a classloader and
     *  partial unload would invalidate sibling bundles.
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
        if (classpathBundles.isNotEmpty()) addBundles(classpathBundles)
    }

    /**
     *  Load every `KSLModelBundle` from the JAR at [jarPath] and
     *  append the discovered bundles to [loadedBundles].  Bundles
     *  whose `bundleId` already exists in the controller are
     *  silently dropped — the first registration wins, matching
     *  [BundleModelProvider]'s duplicate-handling.  Duplicate
     *  [LoadedBundle] instances are [close]d immediately so their
     *  redundant classloaders are released.
     *
     *  - [LoadBundleResult.Loaded]  — at least one new bundleId was added.
     *    [onBundlesChanged] fires after the append.
     *  - [LoadBundleResult.NoBundles] — the JAR loaded but carried no
     *    new bundleIds (either zero `KSLModelBundle` entries or every
     *    entry was already loaded).  [onBundlesChanged] does NOT fire.
     *  - [LoadBundleResult.Failed] — the loader threw; [Failed.reason]
     *    is suitable for surfacing to the user.  [onBundlesChanged]
     *    does NOT fire.
     */
    fun loadJar(jarPath: Path): LoadBundleResult {
        val newBundles = try {
            BundleLoader.loadJar(jarPath)
        } catch (t: Throwable) {
            return LoadBundleResult.Failed(
                t.message ?: t::class.simpleName ?: "load failed"
            )
        }
        if (newBundles.isEmpty()) return LoadBundleResult.NoBundles
        val existingIds = myLoadedBundles.value.map { it.bundle.bundleId }.toSet()
        val (toAdd, duplicates) =
            newBundles.partition { it.bundle.bundleId !in existingIds }
        // Close duplicates immediately — they own a redundant classloader.
        duplicates.forEach { runCatching { it.close() } }
        if (toAdd.isEmpty()) return LoadBundleResult.NoBundles
        addBundles(toAdd)
        return LoadBundleResult.Loaded(toAdd.map { it.bundle.bundleId })
    }

    /**
     *  Look up a loaded bundle by `bundleId`.  Returns `null` when
     *  no loaded bundle carries that id.
     */
    fun findBundle(bundleId: String): LoadedBundle? =
        myLoadedBundles.value.firstOrNull { it.bundle.bundleId == bundleId }

    /**
     *  Close every loaded bundle's classloader.  Wraps each close
     *  in [runCatching] so a single failure does not skip the
     *  remainder.  Safe when empty; safe to call twice (idempotent
     *  — after the second call the list is still drained because
     *  the first call did not clear the list, but each
     *  [LoadedBundle.close] is itself idempotent per its
     *  documentation).
     */
    override fun close() {
        myLoadedBundles.value.forEach { runCatching { it.close() } }
    }

    private fun addBundles(newBundles: List<LoadedBundle>) {
        val merged = myLoadedBundles.value + newBundles
        myLoadedBundles.value = merged
        myBundleProvider.value =
            if (merged.isEmpty()) null else BundleModelProvider(merged)
        onBundlesChanged()
    }

    /**
     *  Outcome of [loadJar].
     *
     *  - [Loaded] — one or more new bundles were added.  Carries the
     *    list of newly-added `bundleId`s for caller logging / UX.
     *  - [NoBundles] — the JAR loaded but contributed no new
     *    bundleIds (either zero SPI entries or every entry was a
     *    duplicate of an already-loaded bundle).
     *  - [Failed] — the load attempt threw.  [Failed.reason] is
     *    suitable for surfacing to the user.
     */
    sealed class LoadBundleResult {
        data class Loaded(val newBundleIds: List<String>) : LoadBundleResult()
        object NoBundles : LoadBundleResult()
        data class Failed(val reason: String) : LoadBundleResult()
    }
}
