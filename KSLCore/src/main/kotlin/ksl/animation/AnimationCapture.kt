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

package ksl.animation

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
import java.io.Closeable
import java.nio.file.Path

/**
 *  Captures an animation trace for a run of [model] and writes it to [output] (a `.atf` writer).
 *  This is the self-contained, renderer-agnostic capture mechanism of the animation core: it wires
 *  a sink onto the model, drives the sink's replication/experiment lifecycle, emits the
 *  experiment/replication marker events a renderer uses to delimit the trace, registers the automatic
 *  per-element emitters so the trace captures real activity, and cleans everything up on [close].
 *
 *  It self-wires against a [Model] and depends only on the simulation core, so it is usable directly
 *  from the published KSLCore library — no application layer required. Construct it just before a run
 *  (its `init` installs the sink and observers) and [close] it after the run (releases the sink and
 *  closes the writer); the app-tier `RunAttachmentIfc` adapter does exactly this around a run's
 *  lifecycle.
 *
 *  Sink composition (outside-in): an optional [ReplicationSelectingSink] (to capture only chosen
 *  replications) wrapping an optional [WindowedAnimationSink] (a time window) wrapping the base sink
 *  chosen by [mode] — [Mode.MEMORY] (buffer a replication, flush the batch to the writer; best for
 *  post-processing replay) or [Mode.ASYNC] (stream events off the simulation thread via a writer
 *  thread; best for watching long runs).
 *
 *  Lifecycle (all on the simulation thread, via a [ModelElementObserver] on the model):
 *   - `beforeExperiment` emits [AnimationEvent.ExperimentStarted]
 *   - `beforeReplication` calls `sink.onReplicationStart` then emits [AnimationEvent.ReplicationStarted]
 *   - `afterReplication` emits [AnimationEvent.ReplicationEnded] then `sink.onReplicationEnd` (flush)
 *   - `afterExperiment` emits [AnimationEvent.ExperimentEnded] then `sink.onExperimentEnd` (drain/flush)
 *
 * @param model the model to capture
 * @param output the destination writer; its header is written on construction and it is closed by [close]
 * @param mode the base sink strategy; defaults to [Mode.MEMORY]
 * @param capturedReplications replication numbers to capture; `null` captures all. Defaults to
 *        replication 1 — the usual choice for an animation run.
 * @param captureSpec selective-capture spec (elements to include/exclude, and an optional time window)
 * @param overlays overlay-capture spec passed to the agent coordinators
 * @param asyncCapacity bounded-queue capacity used in [Mode.ASYNC]
 * @param description optional label written into the trace header (defaults to the model name)
 */
class AnimationCapture(
    private val model: Model,
    private val output: JsonLinesAnimationOutput,
    private val mode: Mode = Mode.MEMORY,
    private val capturedReplications: Set<Int>? = setOf(1),
    private val captureSpec: CaptureSpec = CaptureSpec(),
    private val overlays: OverlaySpec = OverlaySpec.OFF,
    private val asyncCapacity: Int = AsyncAnimationSink.DEFAULT_CAPACITY,
    private val description: String? = null
) : Closeable {

    /** The base-sink strategy. */
    enum class Mode { MEMORY, ASYNC }

    private var observer: ModelElementObserver? = null

    /** Undo actions for every emitter registered in [registerEmitters], run on [close]. */
    private val unregisterActions = mutableListOf<() -> Unit>()

    /** Per-agent-model coordinators, snapshotted at each replication start. */
    private val agentCoordinators = mutableListOf<AgentAnimationCoordinator>()

    init {
        output.writeHeader(
            AnimationTraceHeader(
                baseTimeUnit = model.baseTimeUnit.name,
                description = description ?: model.name
            )
        )

        val base: AnimationSink = when (mode) {
            Mode.MEMORY -> MemoryBufferedAnimationSink { _, batch -> output.writeAll(batch) }
            Mode.ASYNC -> AsyncAnimationSink(capacity = asyncCapacity) { event -> output.write(event) }
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
     * on every station network. Agent: an [AgentAnimationCoordinator] on every agent model
     * (capturing agents registered during the run). Each registration records its own undo
     * action for [close]. This is the zero-DSL, self-describing default.
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

    /**
     * Runs every emitter undo action, detaches the lifecycle observer, restores the model's null
     * sink, and closes the [output] writer. Idempotent: a second call is a no-op.
     */
    override fun close() {
        unregisterActions.forEach { runCatching { it() } }
        unregisterActions.clear()
        agentCoordinators.clear()
        observer?.let { model.detachModelElementObserver(it) }
        observer = null
        // The sink's onExperimentEnd already ran in afterExperiment (drains async, flushes any
        // trailing memory-buffered events); now release the model's sink and close the writer.
        model.animationSink = NullAnimationSink
        output.close()
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
            // construction, before this capture was installed).
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
            // Flush bytes to disk now, while still inside the run, so a consumer awaiting the
            // run result can read a complete trace without racing the writer close in close().
            output.flush()
        }
    }

    companion object {
        /**
         * Creates a [JsonLinesAnimationOutput] for [traceFile] and captures [model] into it — the
         * common case, so a caller needs only a model and a destination path.
         */
        fun toFile(
            model: Model,
            traceFile: Path,
            mode: Mode = Mode.MEMORY,
            capturedReplications: Set<Int>? = setOf(1),
            captureSpec: CaptureSpec = CaptureSpec(),
            overlays: OverlaySpec = OverlaySpec.OFF,
            asyncCapacity: Int = AsyncAnimationSink.DEFAULT_CAPACITY,
            description: String? = null
        ): AnimationCapture =
            AnimationCapture(
                model, JsonLinesAnimationOutput.toFile(traceFile), mode, capturedReplications,
                captureSpec, overlays, asyncCapacity, description
            )
    }
}
