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


def rename(kind, name, shown, dy):
    return {"kind": kind, "name": name, "text": shown, "dy": dy}


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
