#!/usr/bin/env python3
"""Polished showcase layout for Example02MovingParts — movement, on its own.

A part is created at an entry, walks to a station, is served, walks to the next, and leaves. Nothing else
happens, which is the point: it is the smallest model in which position means something, and the step up
from Example 01 is exactly one idea.

The geometry is the model's own. These are real coordinates on a Euclidean plane, not a placement anyone
chose, so the polish does not move anything -- moving it would throw away the one thing the example is
demonstrating. What it adds is the chrome that says what is being watched.
"""
import polishkit as kit

d, facts = kit.load("Example02MovingParts")

SIZE = 34.0
W, H = 1000.0, 560.0
d["title"] = "Moving parts — walking between two stations"
d["width"], d["height"] = W, H

# Down, to clear the header band. A translation preserves every distance, so the model's own geometry
# survives it untouched.
kit.shift(d, 0.0, 78.0)
place = {r["resourceName"]: (r["position"]["x"], r["position"]["y"]) for r in d["resources"]}
kit.station_row(d, facts, place, SIZE)
for oc in d["objectClasses"]:
    oc["size"] = round(SIZE * 0.44, 1)
    oc["color"] = "#1f77b4"

SHORT = {"Worker1": "Station 1", "Worker2": "Station 2"}
d["labels"] = (
    [kit.rename("RESOURCE", n, t, -(SIZE / 2 + 13)) for n, t in SHORT.items()]
    + [kit.count_only("QUEUE", f"{n}:Q", dy=SIZE * 0.66) for n in SHORT]
    + [kit.hide("LOCATION", n) for n in ("Station1", "Station2")]
)

d["clocks"] = [kit.clock(34.0, 50.0, 22.0)]
d["background"] = list(d.get("background", [])) + [
    kit.text("A part walks from the entrance to each station in turn, and out.", 34.0, 94.0, 15.0),
    kit.text("The dots on the grey lines are parts in transit — the renderer interpolates each one along",
             34.0, 128.0, 13.0, "#999999"),
    kit.text("the straight line between the coordinates the move reported. The lines themselves are the",
             34.0, 149.0, 13.0, "#999999"),
    kit.text("routes parts actually took, read off the trace.", 34.0, 170.0, 13.0, "#999999"),
]
d["bars"] = [
    kit.bar("MovingParts:NumInSystem", 34.0, 470.0, 340.0, 24.0,
            facts.scale_for("MovingParts:NumInSystem"), "Parts in the system"),
]
d["plots"] = [
    kit.plot("MovingParts:NumInSystem", 520.0, 440.0, 400.0, 86.0, "Parts in the system over time"),
]
kit.save(d, "Example02MovingParts")
