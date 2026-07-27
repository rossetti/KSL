#!/usr/bin/env python3
"""Produce the polished showcase layout for Example03GridEpidemic.

Input:  build/showcase/Example03GridEpidemic.lay.json  (the auto-layout, via showcaseCapture)
        build/showcase/Example03GridEpidemic.atf       (state names and response ranges)
Output: docs/animations/layouts/Example03GridEpidemic.lay.json

An agent model needs almost none of the placement work a process model does: the grid comes from the model,
the agents carry their own cell coordinates, and the generator already frames both. What it does need is
*meaning* — the generated layout colours the SIR states from a palette in alphabetical order, so Infected
comes out blue and Susceptible green, which is not merely arbitrary but actively misleading. The rest is
composition: an agent field on its own is a picture with no scale and no clock attached to it.
"""
import json, pathlib, collections

SRC = pathlib.Path("build/showcase/Example03GridEpidemic.lay.json")
ATF = pathlib.Path("build/showcase/Example03GridEpidemic.atf")
OUT = pathlib.Path("docs/animations/layouts/Example03GridEpidemic.lay.json")

d = json.loads(SRC.read_text())

# ── 0. What the trace says ──────────────────────────────────────────────────────────────────────────
# The bar scale is the population, and the population is how many agents there are -- not the largest value
# any one response happened to reach. Susceptible peaks at 46 here because four people start out infected, so
# scaling to that would draw a full bar for a susceptible population that was never everyone.
states = set()
agents = set()
for line in ATF.read_text().splitlines():
    if '"AgentStateEntered"' in line:
        e = json.loads(line)
        states.add(e["stateName"]); agents.add(e["agentName"])
    elif '"AgentRegistered"' in line:
        agents.add(json.loads(line)["agentName"])
POPULATION = float(len(agents))
assert POPULATION > 0, "no agents in the trace"

grid = next(s for s in d["spaces"] if s["type"] == "Grid")
GRID = float(grid["cols"])
assert grid["cols"] == grid["rows"], "the panel geometry below assumes a square grid"

# ── 1. Colour is the whole point ────────────────────────────────────────────────────────────────────
# The generator assigns state colours from a categorical palette in sorted-name order, which is
# deterministic and meaningless: it produced blue Infected, red Recovered and green Susceptible. On an SIR
# model that is worse than no colouring at all, because red already reads as "bad" and the reader will trust
# it. These are the conventional assignments, and every other coloured thing in the frame is keyed to them.
SIR = {"Susceptible": "#1f77b4", "Infected": "#d62728", "Recovered": "#2ca02c"}
assert states == set(SIR), f"the model reports {sorted(states)}, not the SIR states this layout colours"
d["agentStateColors"] = SIR
for oc in d["objectClasses"]:
    oc["size"] = 0.62          # a little under a cell, so neighbouring agents stay distinct
    oc["color"] = SIR["Susceptible"]

# ── 2. A panel beside the field, not a strip under it ───────────────────────────────────────────────
# The grid has to keep its origin: an agent's drawn position is its cell coordinate plus a fixed
# half-cell centring offset, and does NOT follow the grid space's origin, so shifting the space to make
# room above would slide every agent off the grid it is meant to be standing on.
#
# So the chrome goes to the right. That also gives the frame a landscape shape, which suits a square field
# with a legend better than stacking a strip underneath does.
PANEL_X = GRID + 1.4
PANEL_W = 10.4
# The panel starts below the legend rather than at the top. The legend is drawn in screen space at the
# top-right corner, so it is not something a layout can move or size -- but it is something a layout can
# leave room for, and the bars label every colour in it anyway.
PANEL_TOP = 4.6
W, H = PANEL_X + PANEL_W + 1.2, GRID + 1.4
d["title"] = "SIR epidemic on a 20x20 grid torus"
d["width"], d["height"] = round(W, 1), round(H, 1)

d["clocks"] = [{"position": {"x": PANEL_X, "y": PANEL_TOP, "z": 0.0}, "format": "0.0", "label": "Day",
                "fontSize": 1.0}]

# The bars carry the same colours as the agents they count, so the field and the panel are one reading
# rather than two. Population is fixed and known, so every bar shares a scale and their lengths can be
# compared directly -- which is what makes the crossover visible as the epidemic burns through.
BARS = [
    ("NumSusceptible", "Susceptible", PANEL_TOP + 3.6),
    ("NumInfected", "Infected", PANEL_TOP + 5.9),
    ("NumRecovered", "Recovered", PANEL_TOP + 8.2),
]
d["bars"] = [
    {"responseName": name, "position": {"x": PANEL_X, "y": y, "z": 0.0},
     "width": PANEL_W, "height": 0.95, "maxValue": POPULATION, "color": SIR[state], "label": state}
    for name, state, y in BARS
]

def text(s, x, y, size=0.62, color="#666666"):
    return {"kind": "TEXT", "points": [{"x": x, "y": y, "z": 0.0}], "text": s, "color": color,
            "strokeWidth": 1.0, "imageRef": None, "fontSize": size, "fontFamily": None}

d["background"] = [
    text("Each dot is a person wandering a torus", PANEL_X, PANEL_TOP + 1.4, 0.56),
    text("grid, infecting the neighbours they meet.", PANEL_X, PANEL_TOP + 2.3, 0.56),
    text(f"One scale, all three bars: {int(POPULATION)} people.", PANEL_X, PANEL_TOP + 10.2, 0.52, "#999999"),
    text("Their crossover is the epidemic peaking.", PANEL_X, PANEL_TOP + 11.0, 0.52, "#999999"),
]

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(d, indent=1))
print(f"wrote {OUT}  ({d['width']}x{d['height']}, {int(GRID)}x{int(GRID)} grid, "
      f"population {int(POPULATION)}, states {sorted(states)})")
