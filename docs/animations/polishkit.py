"""Shared pieces for the polish scripts in this directory.

Every polished layout starts from the same place — the auto-layout in `build/showcase` — reads the same
kinds of fact out of the same trace, and ends up writing the same kinds of chrome. Doing all of that
inline nine times buried each model's actual decisions in boilerplate, so the mechanics live here and each
`polish-ExampleNN.py` is left saying only what is true of its own model.

What is deliberately NOT here: anything that decides how a model should look. Sizes, positions, colours and
captions are per-model judgements, and a shared default for them would be the auto-layout again.
"""
import json
import pathlib
from collections import Counter, defaultdict

SHOWCASE = pathlib.Path("build/showcase")
# The scripts' own output, in JSON because that is what Python writes naturally. It is an intermediate:
# `./gradlew :KSLExamples:publishAnimationLayouts` converts these into the .lay.toml files that ship, using
# the same codec the animation app reads, so the shipped form cannot drift from what the app understands.
POLISHED = pathlib.Path("build/showcase/polished")


def load(name):
    """The auto-layout to polish, and the facts its run reported."""
    src = SHOWCASE / f"{name}.lay.json"
    if not src.is_file():
        raise SystemExit(
            f"no {src}. Capture it first:\n"
            f"  ./gradlew :KSLExamples:showcaseCapture -PmodelName={name} -Pout=build/showcase"
        )
    return json.loads(src.read_text()), TraceFacts(SHOWCASE / f"{name}.atf")


def save(layout, name):
    POLISHED.mkdir(parents=True, exist_ok=True)
    out = POLISHED / f"{name}.lay.json"
    out.write_text(json.dumps(layout, indent=1))
    print(f"wrote {out}  ({layout['width']:g}x{layout['height']:g})")
    return out


class TraceFacts:
    """What the run reported, for the decisions that must not be guessed.

    A layout that guesses these is wrong in ways nobody notices: a bar scaled to a number the run never
    reaches sits pinned full, a queue drawn to a length it never grows to owns the widest line on the
    canvas, and a resource whose capacity is assumed to be one has its queue tucked under its own block.
    """

    def __init__(self, atf):
        self.capacity, self.queue_peak, self.response_peak = {}, Counter(), defaultdict(float)
        self.states, self.agents, self.entity_types = set(), set(), set()
        self.t_max, self.conveyor = 0.0, None
        self._delays = defaultdict(list)
        for line in atf.read_text().splitlines():
            e = json.loads(line)
            kind = e.get("event")
            self.t_max = max(self.t_max, e.get("simTime", 0.0))
            if kind == "ResourceStateChanged":
                self.capacity[e["resourceName"]] = max(self.capacity.get(e["resourceName"], 1), max(1, e["capacity"]))
            elif kind == "QueueLengthChanged":
                self.queue_peak[e["queueName"]] = max(self.queue_peak[e["queueName"]], e["length"])
            elif kind == "ResponseObserved":
                self.response_peak[e["responseName"]] = max(self.response_peak[e["responseName"]], e["value"])
            elif kind == "AgentStateEntered":
                self.states.add(e["stateName"]); self.agents.add(e["agentName"])
            elif kind == "AgentPositionChanged":
                self.agents.add(e["agentName"])
            elif kind == "EntityCreated":
                self.entity_types.add(e["entityType"])
            elif kind == "DelayStarted" and e.get("suspensionName"):
                self._delays[e["suspensionName"]].append((e["simTime"], e["arrivalTime"]))
            elif kind == "ConveyorDefined" and self.conveyor is None:
                self.conveyor = e

    def half_width(self, resource, size):
        """A resource draws one cell per unit of capacity, centred, so this is not `size / 2`."""
        return self.capacity.get(resource, 1) * size / 2

    def max_shown(self, queue, headroom=2, cap=30):
        """A queue's extent line is `spacing x maxShown`; bound it by the length actually reached."""
        return min(cap, max(3, self.queue_peak[queue] + headroom))

    def scale_for(self, response, headroom=1.1):
        """A bar's scale, from the largest value the run produced rather than from a guess."""
        peak = self.response_peak.get(response, 0.0)
        return max(1.0, round(peak * headroom)) if peak else 1.0

    def busiest(self, key):
        """The most members a named delay ever held at once — what its storage box has to show."""
        spans = self._delays[key]
        if not spans:
            return 0
        return max(sum(1 for a, b in spans if a <= t < b) for t in range(0, int(self.t_max) + 1))


# ── layout pieces ───────────────────────────────────────────────────────────────────────────────────
# Positions and sizes are always the caller's, in the layout's own world units. These only spare each
# script from restating the JSON shape.

def text(s, x, y, size, color="#666666"):
    return {"kind": "TEXT", "points": [{"x": x, "y": y, "z": 0.0}], "text": s, "color": color,
            "strokeWidth": 1.0, "imageRef": None, "fontSize": size, "fontFamily": None}


def line(x1, y1, x2, y2, color="#c8c8c8"):
    return {"kind": "LINE", "points": [{"x": x1, "y": y1, "z": 0.0}, {"x": x2, "y": y2, "z": 0.0}],
            "text": None, "color": color, "strokeWidth": 1.0, "imageRef": None,
            "fontSize": 12.0, "fontFamily": None}


def clock(x, y, size, label="Time", fmt="0.0"):
    return {"position": {"x": x, "y": y, "z": 0.0}, "format": fmt, "label": label, "fontSize": size}


def bar(response, x, y, width, height, max_value, label, color="#1f77b4"):
    return {"responseName": response, "position": {"x": x, "y": y, "z": 0.0}, "width": width,
            "height": height, "maxValue": max_value, "color": color, "label": label}


def plot(response, x, y, width, height, label, color="#1f77b4", window=None):
    return {"responseName": response, "position": {"x": x, "y": y, "z": 0.0}, "width": width,
            "height": height, "windowDuration": window, "color": color, "label": label}


def rename(kind, name, shown, dy, dx=0.0):
    return {"kind": kind, "name": name, "text": shown, "dx": dx, "dy": dy}


def hide(kind, name):
    return {"kind": kind, "name": name, "visible": False}


def count_only(kind, name, dx=-6.0, dy=20.0):
    """Hide an element's name but keep its live value — the informative half of a queue's annotation."""
    return {"kind": kind, "name": name, "visible": False, "valueVisible": True,
            "valueDx": dx, "valueDy": dy}


def shift(layout, dx, dy):
    """Move every placed element by ([dx], [dy]).

    A pure translation, so it preserves every distance between elements and cannot make a placement derived
    from real coordinates or a distance matrix any less faithful. It is how a layout makes room for a header
    band without anyone having to re-choose where things go.
    """
    for section in ("locations", "resources", "queues", "stations", "storages", "movableResources"):
        for element in layout.get(section, []):
            position = element.get("position")
            if position:
                position["x"] = round(position["x"] + dx, 1)
                position["y"] = round(position["y"] + dy, 1)
    for path in layout.get("paths", []):
        for point in path.get("points", []):
            point["x"] = round(point["x"] + dx, 1)
            point["y"] = round(point["y"] + dy, 1)


def station_row(layout, facts, place, size, gap_factor=0.5, spacing_factor=0.62):
    """Put each resource at its given point with its queue head clear to the left, growing away.

    "Station = queue + resource, reading left to right" is the arrangement nearly every process view wants,
    and getting the head's clearance right needs the resource's capacity, so it is worth doing in one place.
    """
    for resource in layout["resources"]:
        name = resource["resourceName"]
        if name not in place:
            continue
        x, y = place[name]
        resource["position"] = {"x": x, "y": y, "z": 0.0}
        resource["size"] = size
        resource["showValue"] = False
    for queue in layout["queues"]:
        owner = queue["queueName"].removesuffix(":Q")
        if owner not in place:
            continue
        x, y = place[owner]
        queue["position"] = {"x": round(x - facts.half_width(owner, size) - size * gap_factor, 1), "y": y, "z": 0.0}
        queue["growthDegrees"] = 180.0
        queue["spacing"] = round(size * spacing_factor, 1)
        queue["maxShown"] = facts.max_shown(queue["queueName"])


# ── vehicle models ──────────────────────────────────────────────────────────────────────────────────
# A guide path's geometry is the model's, not the layout's: the layout only places it, by an offset, a
# scale and a separation per floor (`offset + scale * (x, y) + z * floorOffsetPerZ`). So framing a vehicle
# model means translating the path into the canvas and moving everything the auto-layout placed relative to
# it by the same amount, never re-drawing the path.

# Cart hues the state rings do not use: red marks blocked and grey marks halted, so neither may be a cart.
CART_COLORS = ["#1f77b4", "#ff7f0e", "#9467bd", "#17becf", "#bcbd22", "#e377c2", "#8c564b", "#2ca02c"]


class PathFacts:
    """A guide path as the model defined it, from the trace's `GuidedPathDefined` events."""

    def __init__(self, atf):
        self.paths = {}
        for line in pathlib.Path(atf).read_text().splitlines():
            if '"GuidedPathDefined"' in line:
                e = json.loads(line)
                self.paths[e["spaceName"]] = e

    def box(self, space, style):
        """The path's drawn extent under [style]: (min x, min y, max x, max y)."""
        g = self.paths[space]
        scale = style.get("scale", 1.0)
        floor = style.get("floorOffsetPerZ") or {"x": 0.0, "y": 0.0}
        ox, oy = style["offset"]["x"], style["offset"]["y"]
        xs = [ox + scale * i["x"] + i.get("z", 0.0) * floor["x"] for i in g["intersections"]]
        ys = [oy + scale * i["y"] + i.get("z", 0.0) * floor["y"] for i in g["intersections"]]
        return min(xs), min(ys), max(xs), max(ys)

    def mean_zone(self, space):
        """The mean zone length, which is what a cart's glyph should be sized against."""
        links = self.paths[space]["links"]
        zones = [l["length"] / max(1, l.get("numZones", 1)) for l in links if l.get("length")]
        return sum(zones) / len(zones) if zones else 10.0


def frame_path(layout, facts, space, left, top, scale=None):
    """Put guide path [space]'s top-left corner at ([left], [top]) and move every placed element with it.

    Returns the path's new extent. Everything the auto-layout placed against the path (its stations'
    locations, queues beside them, movers parked on it) moves by the same translation, so it stays where it
    was relative to the path.
    """
    style = next(s for s in layout["guidedPaths"] if s["spaceName"] == space)
    if scale is not None:
        old = style.get("scale", 1.0)
        style["scale"] = scale
        # The floor separation is added after scaling, so it has to be scaled with the path or the floors
        # stay at their old distance apart while everything else grows.
        floor = style.get("floorOffsetPerZ")
        if floor:
            style["floorOffsetPerZ"] = {"x": floor["x"] * scale / old, "y": floor["y"] * scale / old, "z": 0.0}
        for section in ("locations", "resources", "queues", "stations", "storages", "movableResources"):
            for element in layout.get(section, []):
                p = element.get("position")
                if p:
                    p["x"] = round(style["offset"]["x"] + (p["x"] - style["offset"]["x"]) * scale / old, 1)
                    p["y"] = round(style["offset"]["y"] + (p["y"] - style["offset"]["y"]) * scale / old, 1)
    x0, y0, _, _ = facts.box(space, style)
    dx, dy = round(left - x0, 1), round(top - y0, 1)
    style["offset"] = {"x": round(style["offset"]["x"] + dx, 1), "y": round(style["offset"]["y"] + dy, 1), "z": 0.0}
    shift(layout, dx, dy)
    return facts.box(space, style)


def style_carts(layout, size, colors=None, labels=None, loaded=None):
    """Give each cart its own hue (never a state ring's), a size, and optionally a label."""
    colors = colors or CART_COLORS
    for i, cart in enumerate(sorted(layout["guidedTransporters"], key=lambda c: c["name"])):
        cart["size"] = size
        cart["color"] = colors[i % len(colors)]
        cart["loadedColor"] = loaded
        if labels is not None:
            cart["label"] = labels.get(cart["name"], cart["name"])


def drop_types(layout, *names):
    """Remove object classes that are not things a reader should look for (an arrival generator, say)."""
    layout["objectClasses"] = [c for c in layout["objectClasses"] if c["typeName"] not in names]


def drop_queues(layout, *names):
    layout["queues"] = [q for q in layout["queues"] if q["queueName"] not in names]


def ring_key(x, y, size, gap=None):
    """A key for the rings the cart glyphs carry: red is blocked, grey is halted, no ring is idle or moving."""
    gap = gap or size * 1.6
    return [
        text("Red ring: blocked by another cart", x, y, size, "#d62728"),
        text("Grey ring: halted (breakdown, battery, gate)", x, y + gap, size, "#7f7f7f"),
    ]


def waiting_queue(layout, facts, queue, at, zone, below=0.0):
    """Draw [queue] at location [at], growing away from the path to the left: where its members wait.

    [below] drops it by that many zones, for a second queue at the same station.
    """
    loc = next(l for l in layout["locations"] if l["locationName"] == at)
    x, y = loc["position"]["x"] - zone * 0.6, loc["position"]["y"] + below * zone
    entry = next((q for q in layout["queues"] if q["queueName"] == queue), None)
    if entry is None:
        entry = {"queueName": queue}
        layout["queues"].append(entry)
    entry.update({"position": {"x": round(x, 1), "y": round(y, 1), "z": 0.0}, "growthDegrees": 180.0,
                  "spacing": round(zone * 0.5, 1), "maxShown": facts.max_shown(queue)})
    return x, y


def cart_key(layout, x, y, size, names=None, gap=None):
    """A key naming each cart in its own colour. Carts queue nose to tail on a guide path, so labels drawn on
    the glyphs collide exactly when the picture is most interesting; a key in the panel never does."""
    gap = gap or size * 1.6
    carts = sorted(layout["guidedTransporters"], key=lambda c: c["name"])
    return [text("■ " + (names or {}).get(c["name"], c["name"]), x, y + i * gap, size, c["color"])
            for i, c in enumerate(carts)]


def snap_locations(layout, facts, space):
    """Re-place every location that names one of [space]'s intersections (or one of its aliases) exactly where
    the path now draws it. Use after changing a path's floor separation, which moves upper-floor stations by
    an amount no translation captures."""
    style = next(s for s in layout["guidedPaths"] if s["spaceName"] == space)
    scale = style.get("scale", 1.0)
    floor = style.get("floorOffsetPerZ") or {"x": 0.0, "y": 0.0}
    where = {}
    for i in facts.paths[space]["intersections"]:
        point = (style["offset"]["x"] + scale * i["x"] + i.get("z", 0.0) * floor["x"],
                 style["offset"]["y"] + scale * i["y"] + i.get("z", 0.0) * floor["y"])
        for name in [i["name"], *i.get("aliases", [])]:
            where[name] = point
    for loc in layout["locations"]:
        if loc["locationName"] in where:
            x, y = where[loc["locationName"]]
            loc["position"] = {"x": round(x, 1), "y": round(y, 1), "z": 0.0}
