/*
 *     The KSL provides a discrete-event simulation library for the Kotlin programming language.
 *     Copyright (C) 2026  Manuel D. Rossetti, rossetti@uark.edu
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
package ksl.modeling.guidedpath

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import ksl.modeling.guidedpath.exceptions.GuidedPathNetworkException
import ksl.modeling.guidedpath.exceptions.GuidedPathRoutingException
import ksl.modeling.guidedpath.spec.GuidedPathNetworkData
import ksl.modeling.guidedpath.spec.IntersectionData
import ksl.modeling.guidedpath.routing.Route
import ksl.modeling.guidedpath.routing.RoutePlanner
import ksl.modeling.guidedpath.rules.RouteSelectionRuleIfc
import ksl.modeling.guidedpath.rules.ShortestPathRouteRule
import ksl.modeling.guidedpath.spec.LinkData
import ksl.modeling.spatial.LocationIfc
import ksl.modeling.spatial.SpatialModel

/**
 * The static topology of a guide path: its intersections, links, zones, station aliases, and the
 * shortest-path distances between every reachable pair of intersections.
 *
 * A network is a spatial model, so its intersections are locations that the rest of the library
 * already understands: an entity's current location may be an intersection, and the animation
 * inventory can enumerate the named places before a run starts. It answers "how far is A from B"
 * by shortest path through the guide path rather than in a straight line, which is the only answer
 * that means anything on a fixed path.
 *
 * A network is **immutable once built** and holds no reference to a model, a model element, or a
 * transporter. It can therefore be constructed, validated, and queried with no simulation running,
 * which is what makes the topology testable on its own. Everything that changes during a run --
 * which transporter holds which zone -- belongs to the runtime that attaches to the network, not
 * to the network itself.
 *
 * That split is not merely tidy: a spatial model and a model element are both abstract classes, and
 * one class cannot be both, so the topology and the runtime have to be separate objects. Making the
 * topology the immutable half means nothing about the guide path can differ between replications.
 *
 * Build one with the step-wise builder, which refuses to produce an inconsistent network:
 *
 *     val net = GuidedPathNetwork.builder("AGVNet")
 *         .link("L1", "I1", "I2", length = 48.0, zoneLength = 12.0)
 *         .link("L2", "I2", "I3", length = 36.0, zoneLength = 12.0)
 *         .station("EntryStation", "I1")
 *         .build()
 *
 * Intersections are created implicitly the first time a link names them, so a network of point
 * junctions needs no intersection declarations at all.
 */
class GuidedPathNetwork private constructor(
    networkName: String
) : SpatialModel(), ksl.modeling.spatial.FleetSpaceIfc {

    init {
        require(networkName.isNotBlank()) { "The network name must not be blank." }
        name = networkName
    }

    private val myIntersections = mutableListOf<Intersection>()
    private val myIntersectionsByName = LinkedHashMap<String, Intersection>()
    private val myLinks = mutableListOf<Link>()
    private val myLinksByName = LinkedHashMap<String, Link>()
    private val myZones = mutableListOf<Zone>()
    private val myStationAliases = LinkedHashMap<String, Intersection>()
    private var myZoneCount: Int = 0
    private var myIsBuilt: Boolean = false

    /** Shortest paths, computed once when the network is built. */
    private lateinit var myPlanner: RoutePlanner

    /** Chooses which way a transporter goes. Replaceable through the builder. */
    private var myRouteSelectionRule: RouteSelectionRuleIfc = ShortestPathRouteRule()

    /** The rule that decides which way transporters go on this network. */
    val routeSelectionRule: RouteSelectionRuleIfc
        get() = myRouteSelectionRule

    /** The intersections, in declaration order, which is the index order of the distance matrix. */
    val intersections: List<Intersection>
        get() = myIntersections

    /** The links, in declaration order. */
    val links: List<Link>
        get() = myLinks

    /**
     * Every zone in the network: the intersection zones in intersection order, then the link zones
     * in link order. A zone's identifier is its index in this list.
     */
    val zones: List<Zone>
        get() = myZones

    /** Additional names by which process code may address intersections. */
    val stationAliases: Map<String, Intersection>
        get() = myStationAliases

    private var myDefaultLocation: Intersection? = null

    /**
     * The location the spatial model uses when none is given. Set to the first declared
     * intersection when the network is built, and settable afterwards to any intersection of this
     * network.
     */
    override var defaultLocation: LocationIfc
        get() = myDefaultLocation
            ?: throw IllegalStateException("Network ${'$'}name has no locations until it is built.")
        set(value) {
            require(isValid(value)) {
                "The default location must be an intersection of network ${'$'}{this.name}."
            }
            myDefaultLocation = value as Intersection
        }

    /**
     * The intersections and their station aliases, so that the animation layer can discover the
     * addressable places before a run begins.
     */
    override val namedLocations: List<LocationIfc>
        get() = myIntersections.toList()

    private var myAttachedSystemName: String? = null

    /**
     * The runtime operating this network, or null when none has attached.
     *
     * A network holds the zones whose coverage a runtime owns, so two runtimes on one network
     * would share that state and corrupt each other. The name is recorded so the refusal can say
     * which system got there first.
     */
    val attachedSystemName: String?
        get() = myAttachedSystemName

    /**
     * Records that a runtime has taken this network, refusing a second one.
     *
     * @throws GuidedPathNetworkException when another system is already attached
     */
    internal fun attachTo(systemName: String) {
        val existing = myAttachedSystemName
        if (existing != null) {
            throw GuidedPathNetworkException.networkAlreadyAttached(name, existing)
        }
        myAttachedSystemName = systemName
    }

    // ---- lookup -------------------------------------------------------------------------------

    /** The intersection with this name, or null. Station aliases are not consulted. */
    fun intersection(name: String): Intersection? = myIntersectionsByName[name]

    /** The link with this name, or null. */
    fun link(name: String): Link? = myLinksByName[name]

    /** The zone with this name, or null. */
    fun zone(name: String): Zone? = myZones.firstOrNull { it.name == name }

    /**
     * The intersection this name addresses, whether it is an intersection name or a station alias,
     * or null when the name addresses neither.
     */
    override fun location(name: String): Intersection? =
        myIntersectionsByName[name] ?: myStationAliases[name]

    /**
     * The intersection this name addresses.
     *
     * @throws IllegalArgumentException when the name is neither an intersection nor a station alias
     */
    override fun requireLocation(name: String): Intersection = location(name)
        ?: throw IllegalArgumentException(
            "($name) is neither an intersection nor a station alias of network ${this.name}. " +
                    "Known intersections: ${myIntersectionsByName.keys.joinToString()}."
        )

    // ---- distance -----------------------------------------------------------------------------

    /**
     * The shortest-path distance from one intersection to another through the guide path.
     *
     * The distance accumulated along a path is the sum, over each link traversed, of that link's
     * length plus the length of the intersection it enters. Counting the entered intersection is
     * what keeps this figure equal to the total length of the zones a transporter actually crosses;
     * with the usual point junctions, whose length is zero, it reduces to the sum of link lengths.
     *
     * @param fromLocation an intersection of this network
     * @param toLocation an intersection of this network
     * @return the distance, or zero when the two are the same intersection
     * @throws IllegalArgumentException when either location belongs to another spatial model
     * @throws GuidedPathRoutingException when no path exists
     */
    override fun distance(fromLocation: LocationIfc, toLocation: LocationIfc): Double {
        val from = asIntersection(fromLocation)
        val to = asIntersection(toLocation)
        val d = myPlanner.distance(from, to)
        if (d == Double.POSITIVE_INFINITY) {
            throw GuidedPathRoutingException.unreachable(from.name, to.name)
        }
        return d
    }

    /** True when some path runs from one intersection to the other. */
    override fun isReachable(fromLocation: LocationIfc, toLocation: LocationIfc): Boolean =
        myPlanner.isReachable(asIntersection(fromLocation), asIntersection(toLocation))

    // ---- routing ------------------------------------------------------------------------------

    /**
     * The junction a zone belongs to, for measuring how far a transporter standing there has to go.
     *
     * A junction's own zone is that junction. A zone on a link belongs to the junction that link
     * leads to, because that is where a transporter standing on it will next be able to turn: on a
     * one-way path there is nowhere else it can go.
     */
    fun intersectionOf(zone: Zone): Intersection = when (zone) {
        is IntersectionZone -> zone.intersection
        is LinkZone -> zone.link.endIntersection
    }

    /**
     * The zones of the shortest path, ignoring the route selection rule.
     *
     * This is what the default rule returns, and it is public so that a rule of the modeler's own
     * can build on it -- taking the shortest path except where it is congested, for instance,
     * rather than reimplementing path finding.
     *
     * @param fromZone the zone the transporter holds now, which is not part of the result
     * @param travellingForward the direction it faces on a link zone, ignored at an intersection
     * @param toIntersection where it is going
     * @throws GuidedPathRoutingException when the destination cannot be reached
     */
    fun shortestPathZones(
        fromZone: Zone,
        travellingForward: Boolean,
        toIntersection: Intersection
    ): List<Zone> = myPlanner.shortestPathZones(fromZone, travellingForward, toIntersection)

    /**
     * Plans a route from one intersection to another.
     *
     * @throws IllegalArgumentException when either location belongs to another network
     * @throws GuidedPathRoutingException when the destination cannot be reached, or when the route
     *   selection rule returns a sequence a transporter could not follow
     */
    fun route(fromLocation: LocationIfc, toLocation: LocationIfc): Route {
        val from = asIntersection(fromLocation)
        return routeFrom(from.zone, asIntersection(toLocation), true)
    }

    /**
     * Plans a route from wherever a transporter currently stands, which may be part way along a
     * link.
     *
     * A transporter on a link cannot turn round inside it, so a route planned from a link zone
     * finishes that link before going anywhere else.
     *
     * @param fromZone the zone the transporter holds now
     * @param toIntersection where it is going
     * @param travellingForward the direction it faces on a link zone, ignored at an intersection
     * @throws GuidedPathRoutingException when the destination cannot be reached, or when the route
     *   selection rule returns a sequence a transporter could not follow
     */
    @JvmOverloads
    fun routeFrom(
        fromZone: Zone,
        toIntersection: Intersection,
        travellingForward: Boolean = true
    ): Route {
        require(myZones.getOrNull(fromZone.id) === fromZone) {
            "Zone (${fromZone.name}) does not belong to network ${this.name}."
        }
        require(isValid(toIntersection)) {
            "Intersection (${toIntersection.name}) does not belong to network ${this.name}."
        }
        val zones = myRouteSelectionRule.selectRoute(this, fromZone, travellingForward, toIntersection)
        validateRoute(fromZone, travellingForward, toIntersection, zones)
        return Route(fromZone, toIntersection, zones)
    }

    /**
     * The zones a transporter could enter in one step from this one, given the direction it faces.
     *
     * From a link zone that is the direction of travel along the link, and from the last zone of a
     * link the junction it leads to. From an intersection zone it is the first zone of every link
     * that may be traversed away from that junction, including a bidirectional link approached from
     * its far end.
     *
     * @param zone the zone the transporter holds
     * @param travellingForward the direction it faces on a link zone, ignored at an intersection
     */
    fun successorsOf(zone: Zone, travellingForward: Boolean = true): List<Zone> = when (zone) {
        is LinkZone -> {
            val link = zone.link
            if (travellingForward) {
                if (zone.positionOnLink < link.numZones) listOf(link.zones[zone.positionOnLink])
                else listOf(link.endIntersection.zone)
            } else if (!link.isTraversableInReverse) {
                emptyList()
            } else {
                if (zone.positionOnLink > 1) listOf(link.zones[zone.positionOnLink - 2])
                else listOf(link.beginIntersection.zone)
            }
        }

        is IntersectionZone -> {
            val here = zone.intersection
            val out = mutableListOf<Zone>()
            for (link in here.incidentLinks) {
                if (link.beginIntersection === here) {
                    out.add(link.zones.first())
                } else if (link.isTraversableInReverse && link.endIntersection === here) {
                    out.add(link.zones.last())
                }
            }
            out
        }
    }

    /**
     * Checks that a proposed route is one a transporter could follow, and names the rule that
     * produced it when it is not.
     *
     * A rule is user code, so a sequence that skips a zone, jumps between links, or stops short of
     * the destination is a defect in the extension rather than in the subsystem. Catching it here,
     * before the transporter starts moving, is what turns it into a message naming the rule and the
     * offending pair instead of a transporter stranded mid-network with no explanation.
     */
    private fun validateRoute(
        fromZone: Zone,
        travellingForward: Boolean,
        toIntersection: Intersection,
        zones: List<Zone>
    ) {
        val ruleName = myRouteSelectionRule::class.simpleName ?: "route selection rule"
        if (zones.isEmpty()) {
            if (fromZone === toIntersection.zone) return
            throw GuidedPathRoutingException(
                "Route selection rule ($ruleName) returned an empty sequence, but the transporter " +
                        "at (${fromZone.name}) is not at its destination (${toIntersection.name})."
            )
        }
        val firstSteps = successorsOf(fromZone, travellingForward)
        if (zones.first() !in firstSteps) {
            throw GuidedPathRoutingException.routeDoesNotStartAtOrigin(
                ruleName,
                firstSteps.joinToString(prefix = "one of ") { it.name },
                zones.first().name
            )
        }
        for (i in 0 until zones.size - 1) {
            val here = zones[i]
            val next = zones[i + 1]
            if (next !in successorsOf(here, travellingForwardAfter(fromZone, zones, i))) {
                throw GuidedPathRoutingException.nonAdjacentRoute(ruleName, here.name, next.name)
            }
        }
        if (zones.last() !== toIntersection.zone) {
            throw GuidedPathRoutingException.routeDoesNotReachDestination(
                ruleName, toIntersection.name, zones.last().name
            )
        }
    }

    /**
     * The direction of travel on the link zone at the given index of a proposed route.
     *
     * Once a route leaves the transporter's starting link, direction is implied by the sequence
     * itself: a link is entered at one end, so the zone that follows the junction says which way
     * the transporter is going. Only the starting link inherits the caller's direction.
     */
    private fun travellingForwardAfter(fromZone: Zone, zones: List<Zone>, index: Int): Boolean {
        val here = zones[index]
        if (here !is LinkZone) return true
        val previous = if (index == 0) fromZone else zones[index - 1]
        if (previous is LinkZone && previous.link === here.link) {
            // Still on the same link: the positions say which way.
            return here.positionOnLink > previous.positionOnLink
        }
        if (previous is IntersectionZone) {
            // Just entered from a junction: the direction is decided by which end of the link that
            // junction is, never by the position of the zone entered. A link of a single zone has
            // its first and last zone in the same place, so position cannot tell them apart.
            return here.link.beginIntersection === previous.intersection
        }
        // Adjacent zones on different links with no junction between them are not adjacent at all,
        // which the caller's successor check reports. Direction is immaterial here.
        return true
    }

    /**
     * For this model, two locations are the same only when they are the same intersection.
     */
    override fun compareLocations(firstLocation: LocationIfc, secondLocation: LocationIfc): Boolean {
        val f = asIntersection(firstLocation)
        val s = asIntersection(secondLocation)
        return f === s
    }

    private fun asIntersection(location: LocationIfc): Intersection {
        require(isValid(location)) {
            "The location (${location.name}) is not a location of network ${this.name}."
        }
        return location as Intersection
    }

    // ---- specification ------------------------------------------------------------------------

    // Deliberately NOT a JsonSettingsIfc<GuidedPathNetworkData>, though two of that interface's
    // three members are here with matching signatures. The third, `configureFromJson`, reconfigures
    // an object in place, and a built network cannot be reconfigured: its zones are cut when the
    // links are declared, and every route, every zone identity and every vehicle placement in a
    // running model refers to them. DistancesModel implements the interface because a distance
    // table is a map that can be emptied and refilled; there is no corresponding operation here.
    //
    // A network is therefore configured from JSON at construction, through `fromJson`/`fromData`,
    // and reports itself through the two members below. Claiming the interface and throwing from
    // `configureFromJson` would advertise a capability that does not exist, which is worse than
    // not claiming it.

    /**
     * This network expressed as data, which reconstructs an equivalent network through
     * `fromData`. Derived quantities are not carried: they are recomputed on reconstruction.
     *
     * Named to match [ksl.utilities.io.JsonSettingsIfc], whose reconfiguring half this class
     * deliberately does not implement -- see the note above.
     */
    fun currentSettings(): GuidedPathNetworkData = GuidedPathNetworkData(
        name = name,
        links = myLinks.map {
            LinkData(
                name = it.name,
                fromIntersection = it.beginIntersection.name,
                toIntersection = it.endIntersection.name,
                length = it.length,
                zoneLength = it.zoneLength,
                numZones = it.numZones,
                type = it.type,
                velocityFactor = it.velocityFactor,
                beginDirection = it.beginDirection
            )
        },
        intersections = myIntersections.map {
            IntersectionData(it.name, it.length, it.velocityFactor, it.x, it.y, it.z)
        },
        stationAliases = myStationAliases.mapValues { (_, v) -> v.name }
    )

    /** This network as JSON, which reconstructs an equivalent network through `fromJson`. */
    fun settingsToJson(): String = currentSettings().toJson()

    override fun toString(): String = buildString {
        appendLine("GuidedPathNetwork : $name")
        appendLine("intersections = ${myIntersections.size}, links = ${myLinks.size}, zones = ${myZones.size}")
        appendLine("Intersections:")
        for (i in myIntersections) appendLine("  $i")
        appendLine("Links:")
        for (l in myLinks) appendLine("  $l")
        if (myStationAliases.isNotEmpty()) {
            appendLine("Station aliases:")
            for ((a, i) in myStationAliases) appendLine("  $a -> ${i.name}")
        }
    }

    // ---- intersection -------------------------------------------------------------------------

    /**
     * A named point where links meet: the unit of addressing and of routing, and a location in this
     * spatial model.
     *
     * An intersection carries two roles at once. It is a place, which is what a route is planned
     * between and what a station attaches to; and it is space, which is what a transporter must
     * hold to pass through. The two are kept apart deliberately -- the place is this object, the
     * space is its zone -- so that nothing in the routing layer is able to claim or release
     * anything.
     */
    inner class Intersection internal constructor(
        aName: String,
        val length: Double,
        val velocityFactor: Double,
        override val x: Double,
        override val y: Double,
        override val z: Double
    ) : AbstractLocation(aName) {

        override val spatialModel: SpatialModel = this@GuidedPathNetwork

        /** Position in the network's intersection list, and the distance matrix index. */
        internal val index: Int = myIntersections.size

        /** The space of this junction, held by one transporter at a time. */
        val zone: IntersectionZone = IntersectionZone(nextZoneId(), this, length, velocityFactor)

        internal val myIncident = mutableListOf<Link>()

        /** Every link that meets here, in declaration order. */
        val incidentLinks: List<Link>
            get() = myIncident

        /** Links a transporter may traverse away from here. */
        val outboundLinks: List<Link>
            get() = myIncident.filter {
                it.beginIntersection === this || (it.isTraversableInReverse && it.endIntersection === this)
            }

        /** Links a transporter may arrive here on. */
        val inboundLinks: List<Link>
            get() = myIncident.filter {
                it.endIntersection === this || (it.isTraversableInReverse && it.beginIntersection === this)
            }

        /** True when exactly one link meets here and that link is a spur: a dead end. */
        val isSpurTerminal: Boolean
            get() = myIncident.size == 1 && myIncident[0].type == LinkType.SPUR &&
                    myIncident[0].endIntersection === this

        /** The names by which process code may also address this intersection. */
        val aliases: List<String>
            get() = myStationAliases.filterValues { it === this }.keys.toList()

        override fun toString(): String =
            "Intersection($name, length=$length, velocityFactor=$velocityFactor, " +
                    "degree=${myIncident.size}${if (isSpurTerminal) ", spur terminal" else ""})"
    }

    private fun nextZoneId(): Int = myZoneCount++

    // ---- construction, used only by the builder -----------------------------------------------

    private fun requireNotBuilt() {
        check(!myIsBuilt) {
            "Network $name is built and immutable. Build a new network rather than altering this one."
        }
    }

    private fun addIntersection(data: IntersectionData): Intersection {
        requireNotBuilt()
        myIntersectionsByName[data.name]?.let { return it }
        val i = Intersection(data.name, data.length, data.velocityFactor, data.x, data.y, data.z)
        myIntersections.add(i)
        myIntersectionsByName[data.name] = i
        myZones.add(i.zone)
        return i
    }

    private fun addLink(data: LinkData) {
        requireNotBuilt()
        if (myLinksByName.containsKey(data.name)) {
            throw GuidedPathNetworkException.duplicateName("link", data.name)
        }
        val begin = myIntersectionsByName[data.fromIntersection]
            ?: addIntersection(IntersectionData(data.fromIntersection))
        val end = myIntersectionsByName[data.toIntersection]
            ?: addIntersection(IntersectionData(data.toIntersection))
        if (begin === end) {
            throw GuidedPathNetworkException.selfLoop(data.name, begin.name)
        }
        val link = Link(
            data.name, begin, end, data.length, data.zoneLength, data.numZones,
            data.type, data.velocityFactor, data.beginDirection, ::nextZoneId
        )
        myLinks.add(link)
        myLinksByName[data.name] = link
        myZones.addAll(link.zones)
        begin.myIncident.add(link)
        end.myIncident.add(link)
    }

    private fun addAlias(alias: String, intersectionName: String) {
        requireNotBuilt()
        if (myIntersectionsByName.containsKey(alias)) {
            throw GuidedPathNetworkException.aliasCollision(alias, alias)
        }
        if (myStationAliases.containsKey(alias)) {
            throw GuidedPathNetworkException.duplicateName("station alias", alias)
        }
        val target = myIntersectionsByName[intersectionName]
            ?: throw GuidedPathNetworkException(
                "Station alias ($alias) names intersection ($intersectionName), which no link " +
                        "declares. Aliases may only name intersections that exist."
            )
        myStationAliases[alias] = target
    }

    /**
     * Completes construction: checks what could not be checked link by link, computes the distance
     * matrix, and freezes the network.
     */
    private fun finish() {
        check(!myIsBuilt) { "The network has already been built." }
        if (myIntersections.size < 2) {
            throw GuidedPathNetworkException(
                "Network ($name): a guided path network needs at least two intersections, but has " +
                        "${myIntersections.size}."
            )
        }
        // A spur must genuinely end at a dead end, or its exit reservation is meaningless.
        for (link in myLinks) {
            if (link.type != LinkType.SPUR) continue
            val terminal = link.endIntersection
            if (terminal.incidentLinks.size != 1) {
                throw GuidedPathNetworkException.spurTerminalDegree(
                    link.name, terminal.name, terminal.incidentLinks.size,
                    terminal.incidentLinks.map { it.name }
                )
            }
        }
        // Zone names reach the modeler through messages and statistics, so they must identify.
        val seen = HashSet<String>(myZones.size)
        for (z in myZones) {
            if (!seen.add(z.name)) {
                throw GuidedPathNetworkException.duplicateName("zone", z.name)
            }
        }
        myPlanner = RoutePlanner(this)
        defaultLocation = myIntersections.first()
        myIsBuilt = true
        warnAboutDeadlockRisks()
        logger.info {
            "GuidedPathNetwork ($name) built: ${myIntersections.size} intersections, " +
                    "${myLinks.size} links, ${myZones.size} zones, " +
                    "${myLinks.count { it.type == LinkType.SPUR }} spurs, " +
                    "${myLinks.count { it.type == LinkType.BIDIRECTIONAL }} bidirectional links."
        }
    }

    /**
     * Reports the two topology choices that make deadlock reachable, so that a modeler meets them
     * at construction rather than in a stalled run. Neither is an error: both are legal and
     * sometimes necessary.
     */
    private fun warnAboutDeadlockRisks() {
        val bidirectional = myLinks.filter { it.type == LinkType.BIDIRECTIONAL }
        if (bidirectional.isNotEmpty()) {
            logger.warn {
                "GuidedPathNetwork ($name): ${bidirectional.size} bidirectional link(s) " +
                        "(${bidirectional.joinToString { it.name }}). Two transporters meeting head on " +
                        "cannot pass, so these are the usual source of deadlock. Prefer a pair of " +
                        "unidirectional links where the layout allows it."
            }
        }
        val unreachable = mutableListOf<String>()
        for (i in myIntersections.indices) {
            for (j in myIntersections.indices) {
                if (!myPlanner.isReachable(myIntersections[i], myIntersections[j])) {
                    unreachable.add("${myIntersections[i].name} -> ${myIntersections[j].name}")
                }
            }
        }
        if (unreachable.isNotEmpty()) {
            logger.warn {
                "GuidedPathNetwork ($name): ${unreachable.size} ordered intersection pair(s) have " +
                        "no path. Requesting a route between one of them raises. First few: " +
                        unreachable.take(5).joinToString()
            }
        }
    }

    // ---- builder ------------------------------------------------------------------------------

    /**
     * Adds links, intersections, and station aliases, then produces the network.
     *
     * A builder validates each piece as it is added, so a mistake is reported against the line that
     * made it rather than against the finished network.
     */
    class Builder internal constructor(networkName: String) {

        private val myNetwork = GuidedPathNetwork(networkName)

        /**
         * Gives a junction a length, a velocity factor, or layout coordinates.
         *
         * [z] is layout only, exactly as [x] and [y] are. Putting a junction on another floor does
         * not make anything take longer to reach: this is a **network** spatial model, so every
         * distance comes from the routing matrix and every travel time from a link's length. A lift
         * is a link whose length is the height of the shaft, and giving its two ends different
         * heights is what makes a renderer draw the cart climbing rather than sliding.
         */
        @JvmOverloads
        fun intersection(
            name: String,
            length: Double = 0.0,
            velocityFactor: Double = 1.0,
            x: Double = Double.NaN,
            y: Double = Double.NaN,
            z: Double = 0.0
        ): Builder {
            if (myNetwork.myIntersectionsByName.containsKey(name)) {
                throw GuidedPathNetworkException.duplicateName("intersection", name)
            }
            myNetwork.addIntersection(IntersectionData(name, length, velocityFactor, x, y, z))
            return this
        }

        /**
         * Adds a link whose zone size is known, deriving how many zones divide it. Intersections
         * are created on first mention.
         */
        @JvmOverloads
        fun link(
            name: String,
            from: String,
            to: String,
            length: Double,
            zoneLength: Double,
            type: LinkType = LinkType.UNIDIRECTIONAL,
            velocityFactor: Double = 1.0,
            beginDirection: Double = 0.0
        ): Builder {
            myNetwork.addLink(
                LinkData.byZoneLength(name, from, to, length, zoneLength, type, velocityFactor, beginDirection)
            )
            return this
        }

        /**
         * Adds a link whose zone count is known, deriving the zone size. This form cannot produce a
         * geometry mismatch and is the safer choice when the count is what the modeler knows.
         */
        @JvmOverloads
        fun linkWithZoneCount(
            name: String,
            from: String,
            to: String,
            length: Double,
            numZones: Int,
            type: LinkType = LinkType.UNIDIRECTIONAL,
            velocityFactor: Double = 1.0,
            beginDirection: Double = 0.0
        ): Builder {
            myNetwork.addLink(
                LinkData.byZoneCount(name, from, to, length, numZones, type, velocityFactor, beginDirection)
            )
            return this
        }

        /** Adds a fully specified link. */
        fun link(data: LinkData): Builder {
            myNetwork.addLink(data)
            return this
        }

        /**
         * Replaces the rule that decides which way transporters go. The default sends every
         * transporter along the shortest path.
         */
        fun routeSelectionRule(rule: RouteSelectionRuleIfc): Builder {
            myNetwork.requireNotBuilt()
            myNetwork.myRouteSelectionRule = rule
            return this
        }

        /** Gives an intersection an additional name that process code may address it by. */
        fun station(alias: String, intersectionName: String): Builder {
            myNetwork.addAlias(alias, intersectionName)
            return this
        }

        /**
         * Validates the whole specification, computes the distance matrix, and returns the finished
         * network.
         *
         * @throws GuidedPathNetworkException when the specification describes a network that cannot
         *   exist
         */
        fun build(): GuidedPathNetwork {
            if (myNetwork.myLinks.isEmpty()) {
                throw GuidedPathNetworkException(
                    "Network (${myNetwork.name}): a guided path network must have at least one link."
                )
            }
            myNetwork.finish()
            return myNetwork
        }
    }

    companion object {

        val logger: KLogger = KotlinLogging.logger {}

        /** Starts building a network. */
        @JvmStatic
        fun builder(networkName: String): Builder = Builder(networkName)

        /**
         * Builds a network from a specification, validating it exactly as the builder does, so a
         * specification that round-trips through JSON fails in the same place and with the same
         * message it would have if it had been typed.
         */
        @JvmStatic
        fun fromData(data: GuidedPathNetworkData): GuidedPathNetwork {
            val b = Builder(data.name)
            for (i in data.intersections) {
                b.intersection(i.name, i.length, i.velocityFactor, i.x, i.y, i.z)
            }
            for (l in data.links) {
                b.link(l)
            }
            for ((alias, target) in data.stationAliases) {
                b.station(alias, target)
            }
            return b.build()
        }

        /** Builds a network from a JSON specification. */
        @JvmStatic
        fun fromJson(json: String): GuidedPathNetwork = fromData(GuidedPathNetworkData.fromJson(json))
    }
}
