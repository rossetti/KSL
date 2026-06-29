/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2024  Manuel D. Rossetti, rossetti@uark.edu
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

package ksl.app.session

import kotlinx.coroutines.CoroutineScope
import ksl.animation.AnimationEvent
import ksl.animation.AnimationSink
import ksl.animation.AnimationLayout
import ksl.animation.AnimationTraceHeader
import ksl.animation.AsyncAnimationSink
import ksl.animation.CaptureSpec
import ksl.animation.ElementKind
import ksl.animation.EntityCaptureFilteringSink
import ksl.animation.JsonLinesAnimationOutput
import ksl.animation.MemoryBufferedAnimationSink
import ksl.animation.NullAnimationSink
import ksl.animation.ReplicationSelectingSink
import ksl.animation.WindowedAnimationSink
import ksl.animation.animatableModelElements
import ksl.animation.animationInventory
import ksl.animation.elementKindOf
import ksl.animation.emitters.QueueAnimationEmitter
import ksl.animation.emitters.ResourceAnimationEmitter
import ksl.animation.emitters.ResponseAnimationEmitter
import ksl.animation.emitters.agent.AgentAnimationCoordinator
import ksl.animation.emitters.station.NetworkAnimationEmitter
import ksl.animation.emitters.station.SResourceAnimationEmitter
import ksl.animation.emitters.station.StationAnimationEmitter
import ksl.modeling.agent.AgentModel
import ksl.modeling.entity.Resource
import ksl.modeling.queue.Queue
import ksl.modeling.station.SResource
import ksl.modeling.station.Station
import ksl.modeling.station.StationNetwork
import ksl.observers.ModelElementObserver
import ksl.simulation.Model
import ksl.simulation.ModelElement
import java.nio.file.Path

/**
 * A [RunAttachmentIfc] that captures an animation trace for a run and writes it to
 * a `.atf` file. It is the bridge between the renderer-agnostic animation core
 * (`ksl.animation`) and the run lifecycle owned by [Runner]: it installs a sink on
 * the model, drives the sink's replication/experiment lifecycle, emits the
 * experiment/replication marker events the renderer uses to delimit the trace, and
 * cleans everything up on detach.
 *
 * Sink composition (outside-in): an optional [ReplicationSelectingSink] (to capture
 * only chosen replications) wrapping the base sink chosen by [mode] —
 * [Mode.MEMORY] (buffer a replication, flush the batch to the file; best for
 * post-processing replay) or [Mode.ASYNC] (stream events off the simulation thread
 * via a writer thread; best for watching long runs). The time-window decorator is
 * not wired here yet (it interacts with marker events and lands with the config
 * surface in a later step).
 *
 * Lifecycle (all on the simulation thread, via a [ModelElementObserver] on the model):
 *  - `beforeExperiment` → emit [AnimationEvent.ExperimentStarted]
 *  - `beforeReplication` → `sink.onReplicationStart` then emit [AnimationEvent.ReplicationStarted]
 *  - `afterReplication` → emit [AnimationEvent.ReplicationEnded] then `sink.onReplicationEnd` (flush)
 *  - `afterExperiment` → emit [AnimationEvent.ExperimentEnded] then `sink.onExperimentEnd` (drain/flush)
 *
 * @param traceFile destination `.atf` path
 * @param mode the base sink strategy; defaults to [Mode.MEMORY]
 * @param capturedReplications replication numbers to capture; `null` captures all.
 *        Defaults to replication 1 — the usual choice for an animation run.
 * @param description optional label written into the trace header (defaults to the model name)
 * @param asyncCapacity bounded-queue capacity used in [Mode.ASYNC]
 * @param layout an optional authored [AnimationLayout]; when [layoutFile] is also given, it is
 *        written on attach so the run produces the complete two-file output (`.lay.json` + `.atf`)
 * @param layoutFile destination `.lay.json` path for [layout]
 */
class AnimationTraceAttachment(
    private val traceFile: Path,
    private val mode: Mode = Mode.MEMORY,
    private val capturedReplications: Set<Int>? = setOf(1),
    private val description: String? = null,
    private val asyncCapacity: Int = AsyncAnimationSink.DEFAULT_CAPACITY,
    private val layout: AnimationLayout? = null,
    private val layoutFile: Path? = null,
    private val captureSpec: CaptureSpec = CaptureSpec(),
    private val overlays: ksl.animation.OverlaySpec = ksl.animation.OverlaySpec.OFF
) : RunAttachmentIfc {

    /** The base-sink strategy. */
    enum class Mode { MEMORY, ASYNC }

    private var output: JsonLinesAnimationOutput? = null
    private var observer: ModelElementObserver? = null
    private var attachedModel: Model? = null

    /** Undo actions for every emitter registered in [registerEmitters], run on detach. */
    private val unregisterActions = mutableListOf<() -> Unit>()

    /** Per-agent-model coordinators, snapshotted at each replication start. */
    private val agentCoordinators = mutableListOf<AgentAnimationCoordinator>()

    override fun onAttach(model: Model, scope: CoroutineScope) {
        attachedModel = model

        // Write the authored layout (the static .lay.json half of the two-file format) if supplied.
        if (layout != null && layoutFile != null) {
            layout.writeToFile(layoutFile)
        }

        val out = JsonLinesAnimationOutput.toFile(traceFile)
        out.writeHeader(
            AnimationTraceHeader(
                baseTimeUnit = model.baseTimeUnit.name,
                description = description ?: model.name
            )
        )
        output = out

        val base: AnimationSink = when (mode) {
            Mode.MEMORY -> MemoryBufferedAnimationSink { _, batch -> out.writeAll(batch) }
            Mode.ASYNC -> AsyncAnimationSink(capacity = asyncCapacity) { event -> out.write(event) }
        }
        // Gate by the capture window (9A.4) when one is set, then by replication. The opening-frame
        // keyframe at the window start is the snapshotter's job (9B).
        var sink: AnimationSink = base
        captureSpec.captureWindow?.let { w ->
            sink = WindowedAnimationSink(sink, w.startTime, w.endTime, currentTime = { model.time })
        }
        if (capturedReplications != null) sink = ReplicationSelectingSink(sink, capturedReplications)
        // Exclude entity types / processes from capture (10.1d). The excluded set is the manifest's
        // include=false entries (@KSLAnimatedEntity / @KSLAnimatedProcess) merged with any explicit
        // CaptureSpec excludes; processes are keyed by the composite "Type.process" identity. Exclusion-only,
        // so the default (nothing excluded) captures everything and structural SELECTED mode is unaffected.
        val inv = model.animationInventory()
        val excludedTypes = (inv.entityTypes.filterNot { it.include }.map { it.typeName } +
            captureSpec.exclude.filter { it.kind == ElementKind.ENTITY_TYPE }.map { it.name }).toSet()
        val excludedProcesses = (inv.entityTypes.flatMap { t ->
            t.processes.filterNot { it.include }.map { "${t.typeName}.${it.name}" }
        } + captureSpec.exclude.filter { it.kind == ElementKind.PROCESS }.map { it.name }).toSet()
        if (excludedTypes.isNotEmpty() || excludedProcesses.isNotEmpty()) {
            sink = EntityCaptureFilteringSink(sink, excludedTypes, excludedProcesses)
        }
        model.animationSink = sink

        val obs = LifecycleObserver(model)
        model.attachModelElementObserver(obs)
        observer = obs

        registerEmitters(model)
    }

    /**
     * Registers the automatic emitters across the model so a trace captures real activity,
     * not just lifecycle markers. Process view: a [QueueAnimationEmitter] on every queue, a
     * [ResourceAnimationEmitter] on every process-view resource, and a shared
     * [ResponseAnimationEmitter] on every response and counter. Station: an
     * [SResourceAnimationEmitter] on every station resource and a [NetworkAnimationEmitter]
     * on every station network. Agent: an [AgentRegistryAnimationEmitter] on every agent model
     * (capturing agents registered during the run). Each registration records its own undo
     * action for [onDetach]. This is the zero-DSL, self-describing default; the DSL (later) will
     * allow selecting specific elements.
     *
     * Not yet auto-wired (attach explicitly for now): per-agent statechart/mailbox/position
     * emitters and a snapshot of permanent agents (those need a replication-start snapshot and
     * per-agent observer lifecycle).
     */
    @Suppress("UNCHECKED_CAST")
    private fun registerEmitters(model: Model) {
        val stationEmitter = StationAnimationEmitter() // shared across all stations (stateless)
        for (element in model.animatableModelElements()) {
            // Selective capture (9A.4): skip non-structural elements and any the CaptureSpec excludes.
            val kind = elementKindOf(element) ?: continue
            if (!captureSpec.captures(kind, element.name)) continue
            when (element) {
                is Queue<*> -> {
                    val queue = element as Queue<ModelElement.QObject>
                    val emitter = QueueAnimationEmitter<ModelElement.QObject>()
                    queue.addQueueListener(emitter)
                    unregisterActions.add { queue.removeQueueListener(emitter) }
                }
                is Resource -> {
                    val emitter = ResourceAnimationEmitter(element)
                    element.addAllocationListener(emitter)
                    unregisterActions.add { element.removeAllocationListener(emitter) }
                }
                is SResource -> {
                    // Station resource: observe its busy-count response (seize/release) and failures.
                    val emitter = SResourceAnimationEmitter(element)
                    val busyResponse = element.numBusyUnits as ModelElement
                    busyResponse.attachModelElementObserver(emitter)
                    element.attachResourceFailureListener(emitter)
                    // SResource has no failure-listener removal; the observer detach below stops
                    // events, and after detach the restored null sink makes any leftover a no-op.
                    unregisterActions.add { busyResponse.detachModelElementObserver(emitter) }
                }
                is StationNetwork -> {
                    val emitter = NetworkAnimationEmitter(element)
                    element.attachNetworkObserver(emitter)
                    unregisterActions.add { element.detachNetworkObserver(emitter) }
                }
                is Station -> {
                    // Per-station entity flow (the shared, stateless emitter observes all stations).
                    element.attachStationObserver(stationEmitter)
                    unregisterActions.add { element.detachStationObserver(stationEmitter) }
                }
                is AgentModel -> {
                    // Coordinates per-agent animation: announces each agent and wires its
                    // statechart/mailbox emitters. Transient agents are caught as they register;
                    // permanent agents are snapshotted at replication start (see the lifecycle
                    // observer's beforeReplication). Position sampling is still opt-in (D6).
                    val coordinator = AgentAnimationCoordinator(element, overlays)
                    coordinator.attach()
                    agentCoordinators.add(coordinator)
                    unregisterActions.add { coordinator.detach() }
                }
            }
        }
        // Responses and counters come from the model's curated statistical-variable lists.
        val responseEmitter = ResponseAnimationEmitter()
        for (response in model.responses) {
            if (!captureSpec.captures(ElementKind.RESPONSE, response.name)) continue
            val element = response as ModelElement
            element.attachModelElementObserver(responseEmitter)
            unregisterActions.add { element.detachModelElementObserver(responseEmitter) }
        }
        for (counter in model.counters) {
            if (!captureSpec.captures(ElementKind.COUNTER, counter.name)) continue
            val element = counter as ModelElement
            element.attachModelElementObserver(responseEmitter)
            unregisterActions.add { element.detachModelElementObserver(responseEmitter) }
        }
    }

    override fun onDetach() {
        val model = attachedModel
        if (model != null) {
            // Unregister every automatic emitter, then the lifecycle observer.
            unregisterActions.forEach { runCatching { it() } }
            unregisterActions.clear()
            agentCoordinators.clear()
            observer?.let { model.detachModelElementObserver(it) }
            // The sink's onExperimentEnd already ran in afterExperiment (drains async,
            // flushes any trailing memory-buffered events); now release the model's sink.
            model.animationSink = NullAnimationSink
        }
        observer = null
        output?.close()
        output = null
        attachedModel = null
    }

    /**
     * Emits experiment/replication marker events and drives the sink lifecycle. All
     * callbacks fire on the simulation thread at the corresponding model boundaries.
     */
    private inner class LifecycleObserver(private val model: Model) : ModelElementObserver() {

        private fun emit(event: AnimationEvent) {
            // Lifecycle markers are structural framing, not windowed content: hand them straight to the
            // sink chain, which decides retention by replication scope (ReplicationSelectingSink) and lets
            // them bypass the time window (WindowedAnimationSink). This observer only exists while animation
            // is on, so there is no "free when off" concern here; content emitters keep their isActive guard.
            model.animationSink.emit(event)
        }

        override fun beforeExperiment(modelElement: ModelElement) {
            emit(AnimationEvent.ExperimentStarted(model.time, model.experimentName, model.numberOfReplications))
        }

        override fun beforeReplication(modelElement: ModelElement) {
            val r = model.currentReplicationNumber
            model.animationSink.onReplicationStart(r) // set capture state before emitting markers
            emit(AnimationEvent.ReplicationStarted(model.time, r))
            // Snapshot permanent agents now that the sink is capturing (they registered at
            // construction, before this attachment was installed).
            agentCoordinators.forEach { it.snapshotExistingAgents() }
        }

        override fun afterReplication(modelElement: ModelElement) {
            val r = model.currentReplicationNumber
            emit(AnimationEvent.ReplicationEnded(model.time, r))
            model.animationSink.onReplicationEnd(r) // flush this replication's batch
        }

        override fun afterExperiment(modelElement: ModelElement) {
            emit(AnimationEvent.ExperimentEnded(model.time, model.experimentName))
            model.animationSink.onExperimentEnd() // drain async / flush trailing events
            // Flush bytes to disk now, while still inside the run. Runner resolves
            // RunHandle.result before it calls onDetach (which closes the file), so a
            // consumer awaiting the result can read a complete trace without racing close.
            output?.flush()
        }
    }

    companion object {
        /**
         * A post-processing/replay trace: buffers each replication in memory and flushes
         * it to [traceFile]. Captures one [replication] by default.
         */
        fun replay(
            traceFile: Path,
            replication: Int = 1,
            description: String? = null,
            captureSpec: CaptureSpec = CaptureSpec(),
            overlays: ksl.animation.OverlaySpec = ksl.animation.OverlaySpec.OFF
        ): AnimationTraceAttachment =
            AnimationTraceAttachment(traceFile, Mode.MEMORY, setOf(replication), description, captureSpec = captureSpec, overlays = overlays)

        /**
         * A replay trace that also writes the authored [layout] to [layoutFile], producing the
         * complete two-file animation output for one [replication].
         */
        fun replay(
            traceFile: Path,
            layout: AnimationLayout,
            layoutFile: Path,
            replication: Int = 1,
            description: String? = null,
            captureSpec: CaptureSpec = CaptureSpec(),
            overlays: ksl.animation.OverlaySpec = ksl.animation.OverlaySpec.OFF
        ): AnimationTraceAttachment =
            AnimationTraceAttachment(traceFile, Mode.MEMORY, setOf(replication), description, layout = layout, layoutFile = layoutFile, captureSpec = captureSpec, overlays = overlays)

        /**
         * A live trace: streams events off the simulation thread to [traceFile] via a
         * background writer. Captures all replications by default.
         */
        fun live(
            traceFile: Path,
            capacity: Int = AsyncAnimationSink.DEFAULT_CAPACITY,
            description: String? = null,
            captureSpec: CaptureSpec = CaptureSpec()
        ): AnimationTraceAttachment =
            AnimationTraceAttachment(traceFile, Mode.ASYNC, null, description, capacity, captureSpec = captureSpec)
    }
}
