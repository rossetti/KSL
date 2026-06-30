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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/** A 2D/3D point used by layout elements (z defaults to 0 for 2D layouts). */
@Serializable
data class LayoutPoint(val x: Double, val y: Double, val z: Double = 0.0)

/** A drawing primitive for an entity/agent class. */
enum class LayoutShape { CIRCLE, SQUARE, TRIANGLE, DIAMOND, IMAGE }

/**
 * How a [StorageLayoutElement] arranges the entities currently delaying in it (8K.4). A storage is
 * unordered, so position carries no rank. `PROGRESS_BELT` drifts each entity from entry to exit as its
 * delay elapses (needs a known duration; falls back to `PACKED_REGION` otherwise); `PACKED_REGION`
 * grid-packs into a box; `LINE` is queue-like along the growth angle; `PILE` is a jittered cluster;
 * `COUNT` is an aggregate badge.
 */
enum class StorageStyle { PROGRESS_BELT, PACKED_REGION, LINE, PILE, COUNT }

/** Kinds of static background geometry. */
enum class BackgroundKind { LINE, POLYLINE, RECT, TEXT, IMAGE }

/**
 * The visual template for an entity or agent class, keyed by [typeName] (which matches
 * `EntityCreated.entityType` / `AgentRegistered.agentType` in the trace).
 */
@Serializable
data class ObjectClassDefinition(
    val typeName: String,
    val shape: LayoutShape = LayoutShape.CIRCLE,
    val color: String = "#1f77b4",
    val size: Double = 10.0,
    val imageRef: String? = null,
    val label: String? = null
)

/**
 * Where and how to draw a queue's waiting members. Keyed by [queueName] from the trace. [position] is
 * the **head** (front of the line, nearest service); members extend away from it along [growthDegrees]
 * (0° = right, increasing clockwise on screen: 90° = down, 180° = left, 270° = up). Member *i* is drawn
 * at `head + i·spacing·(cos θ, sin θ)` (8I.6).
 */
@Serializable
data class QueueLayoutElement(
    val queueName: String,
    val position: LayoutPoint,
    val growthDegrees: Double = 0.0,
    val spacing: Double = 12.0,
    val maxShown: Int = 25
)

/**
 * A holding area showing the entities currently in a named delay (8K.4). [suspensionName] matches a
 * delay's `suspensionName`; when a delay is unnamed the renderer keys it by the entity's **type name**
 * (a stable, shared default), so a storage can bind to either. [position] anchors the element (entry of the belt
 * / corner of the box); [style] chooses the arrangement. Beyond [maxShown] members the element
 * degrades to a count badge + capacity gauge (driven by [capacity]; 0 = unbounded).
 */
@Serializable
data class StorageLayoutElement(
    val suspensionName: String,
    val position: LayoutPoint,
    val style: StorageStyle = StorageStyle.PROGRESS_BELT,
    val width: Double = 160.0,
    val height: Double = 48.0,
    val growthDegrees: Double = 0.0,
    val spacing: Double = 14.0,
    val capacity: Int = 0,
    val maxShown: Int = 30,
    val byType: Boolean = true,
    val label: String? = null
)

/**
 * Where to draw a resource and the colors for its states. Keyed by [resourceName]. Each state may also
 * carry an optional image ([idleImage]/[busyImage]/[failedImage]/[inactiveImage]); when present the
 * renderer draws the image for that state and the matching color is the fallback (10.7).
 */
@Serializable
data class ResourceLayoutElement(
    val resourceName: String,
    val position: LayoutPoint,
    val size: Double = 20.0,
    val idleColor: String = "#2ca02c",
    val busyColor: String = "#d62728",
    val failedColor: String = "#7f7f7f",
    val inactiveColor: String = "#cccccc",
    val idleImage: String? = null,
    val busyImage: String? = null,
    val failedImage: String? = null,
    val inactiveImage: String? = null,
    /** When true, draw a live "busy/capacity" read-out next to the glyph (P4), positionable via its value label. */
    val showValue: Boolean = false
)

/**
 * A movable/transport resource drawn as a glyph at its **interpolated** position while moving (8K.5). When at
 * rest it is drawn at the layout position of its [homeBase] station when known (so the editor preview matches
 * what the replay shows for a coordinate-free spatial model such as a `DistancesModel`), otherwise at its
 * optional [position] (home/parked anchor; null ⇒ only shown while moving). [busyColor]/[busyImage] style it
 * while transporting, [idleImage] when at rest/empty (10.8/C4). Keyed by [name].
 */
@Serializable
data class MovableResourceLayoutElement(
    val name: String,
    val shape: LayoutShape = LayoutShape.SQUARE,
    val color: String = "#8c564b",
    val size: Double = 16.0,
    val imageRef: String? = null,
    val label: String? = null,
    val position: LayoutPoint? = null,
    val busyColor: String? = null,
    val idleImage: String? = null,
    val busyImage: String? = null,
    val homeBase: String? = null
)

/**
 * One conveyor segment's authored route (10.5, §6.7): the belt from [entryLocation] to [exitLocation] runs
 * straight through any [waypoints] in order. Anchors resolve to placed station/location positions; an empty
 * [waypoints] list ⇒ a straight entry→exit belt (today's behavior).
 */
@Serializable
data class SegmentRoute(
    val entryLocation: String,
    val exitLocation: String,
    val waypoints: List<LayoutPoint> = emptyList()
)

/**
 * Author-controlled drawing of a conveyor (10.5, §6.7). Keyed by [conveyorName]; its [segments] route the belt
 * (chained entry→waypoints→exit). [width]/[color] style the belt and [showDirection] draws travel arrows. When
 * absent the renderer falls back to straight anchor-to-anchor interpolation.
 */
@Serializable
data class ConveyorLayoutElement(
    val conveyorName: String,
    val segments: List<SegmentRoute> = emptyList(),
    val width: Double = 8.0,
    val color: String = "#888888",
    val showDirection: Boolean = true,
    val label: String? = null
)

/**
 * Per-element text overrides for the element identified by [kind] + [name] (10.8/C3, batch 4). An element has
 * two independent text annotations: the **name label** ([text] retitles it; [dx]/[dy] offset it from the glyph
 * in screen px; [visible] hides it) and the live **value/state** (e.g. a queue's count) with its own
 * [valueDx]/[valueDy]/[valueVisible]. Either can be moved, retitled (the name), or hidden independently.
 */
@Serializable
data class ElementLabel(
    val kind: ElementKind,
    val name: String,
    val text: String? = null,
    val dx: Double = 0.0,
    val dy: Double = -12.0,
    val visible: Boolean = true,
    val valueDx: Double = 0.0,
    val valueDy: Double = 14.0,
    val valueVisible: Boolean = true
)

/** Where to draw a station (flow-network node). Keyed by [stationName]. */
@Serializable
data class StationLayoutElement(
    val stationName: String,
    val position: LayoutPoint,
    val label: String? = null
)

/** A static background element (lines, rectangles, text, images). */
@Serializable
data class BackgroundElement(
    val kind: BackgroundKind,
    val points: List<LayoutPoint> = emptyList(),
    val text: String? = null,
    val color: String = "#000000",
    val strokeWidth: Double = 1.0,
    val imageRef: String? = null,
    /** Text size in layout units (scales with zoom); applies to [BackgroundKind.TEXT]. Appended with a default
     *  so older layouts (and positional callers) are unaffected. */
    val fontSize: Double = 12.0,
    /** Text font family (e.g. "SansSerif", "Serif", "Monospaced"); null = the renderer default. */
    val fontFamily: String? = null
)

/** A named path (poly-line) entities can be shown moving along. */
@Serializable
data class PathDefinition(
    val name: String,
    val points: List<LayoutPoint>
)

/** A live bar bound to a response/counter (by [responseName]). */
@Serializable
data class BarDisplayElement(
    val responseName: String,
    val position: LayoutPoint,
    val width: Double = 120.0,
    val height: Double = 20.0,
    val maxValue: Double = 100.0,
    val color: String = "#1f77b4",
    val label: String? = null
)

/** A live time-series plot bound to a response/counter (by [responseName]). */
@Serializable
data class PlotDisplayElement(
    val responseName: String,
    val position: LayoutPoint,
    val width: Double = 220.0,
    val height: Double = 110.0,
    val windowDuration: Double? = null,
    val color: String = "#1f77b4",
    val label: String? = null
)

/** A clock display showing the current simulated time. */
@Serializable
data class ClockDisplayElement(
    val position: LayoutPoint,
    val format: String = "0.0",
    val label: String? = "Time",
    /** Text size in layout units (scales with zoom). Appended with a default so older layouts are unaffected. */
    val fontSize: Double = 12.0
)

/**
 * A labeled numeric readout of a response/counter's value (no geometry) — the display *primitive*
 * that [BarDisplayElement] composes (a bar = a value readout + a value-proportional rectangle).
 * Bound to a response by [responseName]; rendered as "[label]: value".
 */
@Serializable
data class ValueDisplayElement(
    val responseName: String,
    val position: LayoutPoint,
    val label: String? = null,
    val decimals: Int = 1
)

/**
 * A within-replication statistics summary (count, mean, min, max) for a response — the engine emits
 * the statistics (D11), this just shows them. Bound by [responseName] (8A.4).
 */
@Serializable
data class SummaryDisplayElement(
    val responseName: String,
    val position: LayoutPoint,
    val label: String? = null,
    val decimals: Int = 2
)

/**
 * A live histogram / frequency chart of a response's observed values, **computed in the viewer**
 * from the raw value stream (decision D12 — not emitted as bin snapshots). When [discrete] is true
 * it tallies by integer value (an integer-frequency chart); otherwise it bins the observed range
 * into [bins] equal-width bins. Bound by [responseName] (8D.1).
 */
@Serializable
data class HistogramDisplayElement(
    val responseName: String,
    val position: LayoutPoint,
    val width: Double = 220.0,
    val height: Double = 120.0,
    val bins: Int = 10,
    val discrete: Boolean = false,
    val color: String = "#1f77b4",
    val label: String? = null
)

/** A node of a network spatial space (e.g. an agent NetworkProjection). */
@Serializable
data class NetworkNode(val id: String, val position: LayoutPoint)

/** A weighted edge between two [NetworkNode]s. */
@Serializable
data class NetworkEdge(val from: String, val to: String, val weight: Double = 1.0)

/**
 * Describes a spatial space (an agent projection or a station network) so the renderer can
 * draw its background. The initial set covers continuous, grid, and network spaces
 * (decision D8); voxel/flow-field spaces can be added without breaking the format.
 */
@Serializable
sealed class SpatialSpaceDescriptor {
    abstract val name: String

    /** A 2D continuous (Euclidean) space with the given bounds. [torus] wraps motion at the edges. */
    @Serializable
    @SerialName("Continuous")
    data class Continuous(
        override val name: String,
        val xMin: Double, val xMax: Double,
        val yMin: Double, val yMax: Double,
        val torus: Boolean = false
    ) : SpatialSpaceDescriptor()

    /** A rectangular grid space. [torus] wraps motion at the edges. */
    @Serializable
    @SerialName("Grid")
    data class Grid(
        override val name: String,
        val cols: Int, val rows: Int,
        val cellSize: Double,
        val originX: Double = 0.0, val originY: Double = 0.0,
        val torus: Boolean = false
    ) : SpatialSpaceDescriptor()

    /** A network space of nodes and weighted edges. */
    @Serializable
    @SerialName("Network")
    data class Network(
        override val name: String,
        val nodes: List<NetworkNode> = emptyList(),
        val edges: List<NetworkEdge> = emptyList()
    ) : SpatialSpaceDescriptor()
}

/**
 * The static layout that, together with a `.atf` trace, lets a renderer draw an animation.
 * Written once (before `simulate()`) to a `.lay.json` file. The format is self-describing
 * (NF6): a renderer can produce a basic animation from only the layout and the trace.
 */
@Serializable
data class AnimationLayout(
    val title: String? = null,
    val baseTimeUnit: String? = null,
    val width: Double = 1000.0,
    val height: Double = 700.0,
    val objectClasses: List<ObjectClassDefinition> = emptyList(),
    val background: List<BackgroundElement> = emptyList(),
    val paths: List<PathDefinition> = emptyList(),
    val queues: List<QueueLayoutElement> = emptyList(),
    val resources: List<ResourceLayoutElement> = emptyList(),
    val stations: List<StationLayoutElement> = emptyList(),
    val bars: List<BarDisplayElement> = emptyList(),
    val plots: List<PlotDisplayElement> = emptyList(),
    val clocks: List<ClockDisplayElement> = emptyList(),
    val spaces: List<SpatialSpaceDescriptor> = emptyList(),
    val values: List<ValueDisplayElement> = emptyList(),
    /** Maps an agent statechart state name (matched by substring) to a hex color, for state-based
     *  agent styling (8F.1); e.g. "Working" -> "#2ca02c". */
    val agentStateColors: Map<String, String> = emptyMap(),
    val summaries: List<SummaryDisplayElement> = emptyList(),
    val histograms: List<HistogramDisplayElement> = emptyList(),
    val storages: List<StorageLayoutElement> = emptyList(),
    val movableResources: List<MovableResourceLayoutElement> = emptyList(),
    /** Maps an entity's current process name (matched by substring) to a hex color, for process/activity
     *  styling (10.1e); e.g. "Triage" -> "#ff7f0e". The entity analogue of [agentStateColors]. Appended at
     *  the end of the constructor so positional `AnimationLayout(...)` callers are unaffected. */
    val processColors: Map<String, String> = emptyMap(),
    /** Author-controlled conveyor belt routes (10.5): per-segment polylines anchored at stations/locations.
     *  Appended last so positional `AnimationLayout(...)` callers are unaffected. */
    val conveyors: List<ConveyorLayoutElement> = emptyList(),
    /** Per-element text-label overrides (10.8/C3): retitle, reposition (dx,dy from the glyph), or hide a label.
     *  Appended last so positional `AnimationLayout(...)` callers are unaffected. */
    val labels: List<ElementLabel> = emptyList(),
    /** Grid obstacle/cost overlays extracted from (or authored for) the model's geometry, keyed by space name
     *  (P5a/G2). Appended last so positional `AnimationLayout(...)` callers are unaffected. */
    val spaceGeometry: List<ksl.modeling.agent.GridGeometrySpec> = emptyList()
) {
    /** The grid obstacle/cost overlay for the space named [spaceName], or null — the consume-side lookup (P5b/G2). */
    fun gridGeometry(spaceName: String): ksl.modeling.agent.GridGeometrySpec? =
        spaceGeometry.firstOrNull { it.spaceName == spaceName }

    /** Serializes this layout to pretty-printed JSON (the `.lay.json` content). */
    fun toJson(): String = format.encodeToString(this)

    /** Writes this layout to [path] (typically a `.lay.json` file), UTF-8, pretty-printed. */
    fun writeToFile(path: Path) {
        Files.newBufferedWriter(path).use { it.write(toJson()) }
    }

    /** Serializes this layout to TOML (an alternate, human-friendly layout format, 8E.2). */
    fun toToml(): String = tomlFormat.encodeToString(serializer(), this)

    /** Writes this layout to [path] (typically a `.lay.toml` file) as TOML. */
    fun writeTomlToFile(path: Path) {
        Files.newBufferedWriter(path).use { it.write(toToml()) }
    }

    companion object {
        /**
         * JSON configuration for the layout file: pretty-printed and self-describing,
         * with `"type"` naming the spatial-space discriminator.
         */
        val format: Json = Json {
            prettyPrint = true
            encodeDefaults = true
            classDiscriminator = "type"
            // Tolerate keys this version no longer models (e.g. the retired `dashboards`), so a layout saved by
            // an older build still loads — the unknown section is simply dropped.
            ignoreUnknownKeys = true
        }

        /** Parses a `.lay.json` string into an [AnimationLayout]. */
        fun fromJson(json: String): AnimationLayout = format.decodeFromString(json)

        /** Reads an [AnimationLayout] from [path]. */
        fun readFromFile(path: Path): AnimationLayout = fromJson(Files.readString(path))

        /** Reads a layout from [path], picking the codec by extension: `.toml` -> TOML, else JSON. */
        fun read(path: Path): AnimationLayout =
            if (path.fileName.toString().endsWith(".toml", ignoreCase = true)) readTomlFromFile(path)
            else readFromFile(path)

        /** TOML codec for the layout (handles the sealed spatial-space hierarchy via its serial names). Tolerates
         *  keys this version no longer models (e.g. the retired `dashboards`) so older files still load. */
        val tomlFormat: net.peanuuutz.tomlkt.Toml = net.peanuuutz.tomlkt.Toml { ignoreUnknownKeys = true }

        /** Parses a TOML layout string into an [AnimationLayout] (8E.2). */
        fun fromToml(text: String): AnimationLayout = tomlFormat.decodeFromString(serializer(), text)

        /** Reads an [AnimationLayout] from a `.lay.toml` [path]. */
        fun readTomlFromFile(path: Path): AnimationLayout = fromToml(Files.readString(path))
    }
}
