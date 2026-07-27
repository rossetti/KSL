#!/usr/bin/env python3
"""Polished showcase layout for Example04BuildingEvacuation — evacuation, on a grid.

The grid counterpart to Example 05's continuous crowd, and worth having beside it: same situation, two
spatial paradigms. Here people step cell to cell down a distance field; there they move continuously under
forces. The walls come from the model's own grid graph, so the picture cannot drift from what it blocks.
"""
import polishkit as kit

d, facts = kit.load("Example04BuildingEvacuation")

grid = next(s for s in d["spaces"] if s["type"] == "Grid")
SIDE = float(grid["cols"]) * float(grid["cellSize"])
PANEL_X, PANEL_W, PANEL_TOP = SIDE + 1.6, 10.5, 3.8
W, H = PANEL_X + PANEL_W + 1.0, SIDE + 1.6
d["title"] = "Building evacuation on a grid"
d["width"], d["height"] = round(W, 1), round(H, 1)

for oc in d["objectClasses"]:
    oc["size"] = 0.62
    oc["color"] = "#1f77b4"
# Both exits sit in corners of the grid, and a label is drawn rightward from its own position, so the
# default offset runs each one off the edge of the canvas. Push them back inside instead: the offsets are
# screen pixels, so down-and-right at the top-left corner and up-and-left at the bottom-right.
d["labels"] = [
    {"kind": "LOCATION", "name": "Exit 1", "text": "Exit 1", "dx": 12.0, "dy": 18.0},
    {"kind": "LOCATION", "name": "Exit 2", "text": "Exit 2", "dx": -48.0, "dy": -12.0},
]

d["clocks"] = [kit.clock(PANEL_X, PANEL_TOP, 0.82)]
d["background"] = [
    kit.text("People step from cell to cell down a distance", PANEL_X, PANEL_TOP + 1.15, 0.46),
    kit.text("field toward whichever exit is nearer.", PANEL_X, PANEL_TOP + 1.85, 0.46),
    kit.text("The grey cells are walls, taken from the", PANEL_X, PANEL_TOP + 6.4, 0.42, "#999999"),
    kit.text("model's own grid graph rather than drawn", PANEL_X, PANEL_TOP + 7.0, 0.42, "#999999"),
    kit.text("to match it.", PANEL_X, PANEL_TOP + 7.6, 0.42, "#999999"),
    kit.text("Compare Example 05: the same emptying room", PANEL_X, PANEL_TOP + 8.6, 0.42, "#999999"),
    kit.text("under continuous social forces instead.", PANEL_X, PANEL_TOP + 9.2, 0.42, "#999999"),
]
d["bars"] = [
    kit.bar("PopulationInBuilding", PANEL_X, PANEL_TOP + 3.4, PANEL_W, 0.9,
            facts.scale_for("PopulationInBuilding", 1.0), "Still inside"),
]
kit.save(d, "Example04BuildingEvacuation")
