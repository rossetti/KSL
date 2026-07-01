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

import ksl.modeling.agent.AgentLike
import ksl.modeling.entity.Resource
import ksl.modeling.entity.ResourceWithQCIfc
import ksl.modeling.queue.Queue
import ksl.modeling.spatial.DistancesModel
import ksl.modeling.spatial.MovableResource
import ksl.modeling.station.Station
import ksl.simulation.Model

/**
 * One problem found by [validateAgainst]: a layout binding whose name is not present in the model
 * (8K.2). Because the layout keys to the trace **by name string**, an unmatched binding renders
 * nothing with no runtime error — this surfaces those silent mismatches at author time.
 */
data class ValidationIssue(val kind: Kind, val name: String, val message: String) {
    enum class Kind {
        UNMATCHED_QUEUE,
        UNMATCHED_RESOURCE,
        UNMATCHED_MOVABLE_RESOURCE,
        UNMATCHED_RESPONSE,
        UNMATCHED_SELECTOR,
        /** A type/process observed in a produced trace but absent from the manifest (10.1f nudge). */
        UNDECLARED_ENTITY_TYPE,
        UNDECLARED_PROCESS
    }
}

/** The result of validating an [AnimationLayout] against a [Model] (8K.2). */
data class ValidationReport(val issues: List<ValidationIssue>) {
    /** True when no unmatched binding was found. */
    val isValid: Boolean get() = issues.isEmpty()

    override fun toString(): String {
        if (issues.isEmpty()) return "Animation layout validation: OK (no unmatched bindings)."
        return buildString {
            appendLine("Animation layout validation: ${issues.size} unmatched binding(s):")
            for (i in issues) appendLine("  - [${i.kind}] ${i.message}")
        }
    }
}

/**
 * Validates this layout against [model] by delegating to the [AnimationInventory] path
 * ([validateAgainst]`(inventory)`) — the inventory is the single identifier space (9A.3/9A.5) that both
 * capture selection and layout binding key off, so validation can't drift from what the emitters expose.
 */
fun AnimationLayout.validateAgainst(model: Model): ValidationReport =
    validateAgainst(model.animationInventory())

/**
 * Validates this layout against [inventory]: every queue/resource/movableResource/response binding is
 * checked against the names the model exposes (the same element kinds the animation emitters attach to),
 * and unmatched names are reported with a nearest-name "did you mean?" hint (8K.2a). Response-style
 * bindings (bar/plot/value/summary/histogram) match against responses ∪ counters (both emit response
 * values).
 *
 * **Not checked here:** `station(...)` markers, `space(...)` backdrops, and `objectClass(...)` type
 * names. A station marker is a free-form position/label anchor — most aren't `Station` *model elements*
 * (they label Euclidean points, conveyor anchors, `DistancesModel` locations, etc.), so name-based
 * checking false-positives; space names aren't structural keys. Station names and entity/agent type
 * names are validated against a produced **trace** instead (8K.2b, deferred), since the trace records
 * the names that actually emitted.
 */
fun AnimationLayout.validateAgainst(inventory: AnimationInventory): ValidationReport {
    val queueNames = inventory.queues.toSet()
    val resourceNames = inventory.resources.toSet()
    val movableNames = inventory.movableResources.toSet()
    val responseNames = (inventory.responses + inventory.counters).toSet()
    val issues = mutableListOf<ValidationIssue>()

    fun check(name: String, known: Set<String>, kind: ValidationIssue.Kind, what: String) {
        if (name !in known) {
            issues += ValidationIssue(kind, name, "$what '$name' has no match in the model.${didYouMean(name, known)}")
        }
    }

    queues.forEach { check(it.queueName, queueNames, ValidationIssue.Kind.UNMATCHED_QUEUE, "queue") }
    resources.forEach { check(it.resourceName, resourceNames, ValidationIssue.Kind.UNMATCHED_RESOURCE, "resource") }
    movableResources.forEach {
        check(it.name, movableNames, ValidationIssue.Kind.UNMATCHED_MOVABLE_RESOURCE, "movable resource")
    }
    bars.forEach { check(it.responseName, responseNames, ValidationIssue.Kind.UNMATCHED_RESPONSE, "bar response") }
    plots.forEach { check(it.responseName, responseNames, ValidationIssue.Kind.UNMATCHED_RESPONSE, "plot response") }
    values.forEach { check(it.responseName, responseNames, ValidationIssue.Kind.UNMATCHED_RESPONSE, "value response") }
    summaries.forEach { check(it.responseName, responseNames, ValidationIssue.Kind.UNMATCHED_RESPONSE, "summary response") }
    histograms.forEach { check(it.responseName, responseNames, ValidationIssue.Kind.UNMATCHED_RESPONSE, "histogram response") }

    return ValidationReport(issues)
}

/**
 * Validates this capture spec against [inventory] (9A.5): every `include`/`exclude` [ElementSelector]
 * must name an element the inventory actually exposes for its [ElementKind]. A selector that names a
 * non-existent element (or names one of the wrong kind) silently captures nothing, so this surfaces the
 * typo at author time with a nearest-name hint drawn from that kind's names.
 */
fun CaptureSpec.validateAgainst(inventory: AnimationInventory): ValidationReport {
    val issues = mutableListOf<ValidationIssue>()

    fun check(selector: ElementSelector, where: String) {
        val known = inventory.namesOf(selector.kind)
        if (selector.name !in known) {
            issues += ValidationIssue(
                ValidationIssue.Kind.UNMATCHED_SELECTOR,
                selector.name,
                "$where ${selector.kind} '${selector.name}' has no match in the model.${didYouMean(selector.name, known)}"
            )
        }
    }

    include.forEach { check(it, "included") }
    exclude.forEach { check(it, "excluded") }

    return ValidationReport(issues)
}

/**
 * Cross-checks a **produced trace** against this manifest (10.1f) — the trace-side validation the inventory
 * doc defers (8K.2b). Walks [events], collecting every entity **type** (`EntityCreated.entityType`) and every
 * **process** (`ProcessActivated`, keyed by the composite `"Type.process"` via the `entityId → type` join),
 * and reports each that the manifest does not declare. These are **nudges**, not errors: capture defaults to
 * all, so an undeclared type/process still animates — the warning simply tells the author to declare it
 * (`entityType<T>()` / `@KSLAnimatedEntity`, `@KSLAnimatedProcess`) so it appears in the editor *before* a run.
 *
 * Processes whose entity has no `EntityCreated` (e.g. station-network QObjects) are skipped, since they carry
 * no type to qualify the composite name.
 */
fun AnimationInventory.validateTrace(events: Iterable<AnimationEvent>): ValidationReport {
    val knownTypes = entityTypes.mapTo(HashSet()) { it.typeName }
    val knownProcesses = entityTypes.flatMapTo(HashSet()) { t -> t.processes.map { "${t.typeName}.${it.name}" } }
    val typeOf = HashMap<Long, String>()
    val observedTypes = LinkedHashSet<String>()
    val observedProcesses = LinkedHashSet<String>()
    for (e in events) when (e) {
        is AnimationEvent.EntityCreated -> { typeOf[e.entityId] = e.entityType; observedTypes += e.entityType }
        is AnimationEvent.ProcessActivated -> typeOf[e.entityId]?.let { observedProcesses += "$it.${e.processName}" }
        else -> {}
    }
    val issues = mutableListOf<ValidationIssue>()
    for (t in observedTypes) if (t !in knownTypes) {
        issues += ValidationIssue(
            ValidationIssue.Kind.UNDECLARED_ENTITY_TYPE, t,
            "entity type '$t' appeared in the trace but is not declared; add entityType<$t>() or @KSLAnimatedEntity to surface it in the editor before a run."
        )
    }
    for (p in observedProcesses) if (p !in knownProcesses) {
        val procName = p.substringAfter('.', p)
        issues += ValidationIssue(
            ValidationIssue.Kind.UNDECLARED_PROCESS, p,
            "process '$p' appeared in the trace but is not declared; annotate its property with @KSLAnimatedProcess(name=\"$procName\") to surface it in the editor before a run."
        )
    }
    return ValidationReport(issues)
}

/**
 * Builds a starter [AnimationLayout] for this model with the resources, queues and stations auto-placed on
 * a simple grid (8K.2c), so authoring begins from a populated, self-consistent file instead of a blank one.
 * Responses and counters are deliberately *not* placed — a model can expose dozens, which overwhelm the
 * starter layout; the author adds the stats they want (value/bar/plot displays).
 * Movable/transport resources are placed as `movableResource(...)` glyphs
 * (not static boxes), and when the model moves them over a [DistancesModel] its named locations are
 * placed as station anchors (via `placeStations`) so the transporters animate between them. The
 * placement is deliberately rough — the author then drags elements into their final positions. No
 * `objectClass` styling is emitted (entity/agent types are runtime; the renderer falls back to
 * defaults). By construction `scaffoldLayout().validateAgainst(model).isValid`.
 */
fun Model.scaffoldLayout(
    title: String = name,
    width: Double = 1000.0,
    height: Double = 700.0,
    originX: Double = 80.0,
    originY: Double = 80.0,
    columnGap: Double = 240.0,
    rowGap: Double = 70.0
): AnimationLayout {
    val elements = getModelElements()
    // Movable/transport resources and agent-resources (AgentResource/MovableAgentResource) are Resources, but they
    // animate as moving glyphs (movers) or agents — not static boxes — so keep them out of the auto-placed resource
    // column (movers are placed as movableResource(...) below; agent-resources animate from the trace). A modeler
    // can still add either from the editor's Resource/Queue tools.
    val movableList = elements.filterIsInstance<MovableResource>()
    val resourceList = elements.filterIsInstance<Resource>().filterNot { it is MovableResource || it is AgentLike }
    val allQueues = elements.filterIsInstance<Queue<*>>()
    val stationList = elements.filterIsInstance<Station>()
    // The DistancesModel (if any) whose named locations the movers travel between; used to place those
    // locations as station anchors so name-resolved movement (8H.3) has positions to interpolate against.
    val distances: DistancesModel? = (spatialModel as? DistancesModel)
        ?: movableList.firstNotNullOfOrNull { it.spatialModel as? DistancesModel }
        ?: elements.firstNotNullOfOrNull { it.spatialModel as? DistancesModel }
    // Queues owned by a ResourceWithQ are placed by resourceWithQ(); don't also place them standalone.
    val consumed = resourceList.filterIsInstance<ResourceWithQCIfc>().map { it.waitingQ.name }.toSet()
    // Agent-resources' request queues aren't auto-placed either (their owners animate as agents); still editor-placeable.
    val agentQueues = elements.filterIsInstance<ResourceWithQCIfc>().filter { it is AgentLike }.map { it.waitingQ.name }.toSet()
    // Honor each queue's reporting intent (P5): non-reporting queues (e.g. a movable resource's internal
    // :HomeBaseQ) are still captured, but not auto-placed — they otherwise clutter the starter layout.
    val standaloneQueues = allQueues.filter { it.name !in consumed && it.name !in agentQueues && it.defaultReportingOption }
    val resTitle = title

    return animation {
        this.title = resTitle
        size(width, height)
        // No auto-placed clock: the clock is an opt-in element the user adds from the Layout palette.

        // Column 1 — resources (with their queues when ResourceWithQ).
        val resColX = originX + 160.0
        var ry = originY
        for (r in resourceList) {
            if (r is ResourceWithQCIfc) resourceWithQ(r, resColX, ry) else resource(r, resColX, ry)
            ry += rowGap
        }

        // Column 2 — standalone queues (request/hold/blocking/station queues).
        val qColX = resColX + columnGap
        var qy = originY
        for (q in standaloneQueues) { queue(q, qColX, qy); qy += rowGap }

        // Responses and counters are intentionally NOT placed: a model exposes dozens, which overwhelm the
        // starter layout. The author adds the stats they want as value/bar/plot displays.

        // Bottom row — flow-network stations.
        var sx = originX
        val sy = height - 100.0
        for (s in stationList) { station(s, sx, sy, label = s.name); sx += 140.0 }

        // Spatial layer — place the DistancesModel's named locations (MDS) and declare the movable/transport
        // resources, so transporters animate between resolved locations (Regime A / Phase 5: these are locations).
        distances?.let { placeLocations(it) }
        for (mr in movableList) movableResource(mr.name)
    }.withScaffoldOverlapsNudged()
}

/**
 * Separates scaffold-placed glyphs that landed on top of each other — e.g. a resource-column position that
 * coincides with an MDS-placed `DistancesModel` station, which made the resource impossible to find (item 5).
 * Stations keep their positions (they anchor agent/transporter rendering); colliding resources and queues are
 * nudged out along a golden-angle spiral until clear.
 */
fun AnimationLayout.withScaffoldOverlapsNudged(minDist: Double = 48.0): AnimationLayout {
    val placed = ArrayList<LayoutPoint>()
    fun adjust(p: LayoutPoint): LayoutPoint {
        var q = p; var k = 1
        while (placed.any { kotlin.math.hypot(it.x - q.x, it.y - q.y) < minDist } && k <= 60) {
            val ang = k * 2.39996323 // golden angle, so successive nudges fan out instead of stacking
            val rr = minDist * (1.0 + 0.12 * k)
            q = LayoutPoint(p.x + rr * kotlin.math.cos(ang), p.y + rr * kotlin.math.sin(ang))
            k++
        }
        placed.add(q); return q
    }
    // Stations first (authoritative anchors), then resources, then queues — so the generic glyphs move, not the
    // station markers that agents/transporters render against.
    val sts = stations.map { it.copy(position = adjust(it.position)) }
    val res = resources.map { it.copy(position = adjust(it.position)) }
    val qs = queues.map { it.copy(position = adjust(it.position)) }
    return copy(stations = sts, resources = res, queues = qs)
}

/** A "Did you mean 'X'?" suffix for [target] using nearest [candidates] (mirrors ModelCatalogBuilder). */
private fun didYouMean(target: String, candidates: Collection<String>): String {
    if (candidates.isEmpty()) return ""
    val threshold = maxOf(2, target.length / 3)
    val ranked = candidates
        .map { it to levenshtein(target, it) }
        .filter { it.second <= threshold }
        .sortedBy { it.second }
        .take(3)
        .map { it.first }
    return if (ranked.isEmpty()) "" else " Did you mean ${ranked.joinToString(", ") { "'$it'" }}?"
}

private fun levenshtein(a: String, b: String): Int {
    val prev = IntArray(b.length + 1) { it }
    val curr = IntArray(b.length + 1)
    for (i in 1..a.length) {
        curr[0] = i
        for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
        }
        System.arraycopy(curr, 0, prev, 0, curr.size)
    }
    return prev[b.length]
}
