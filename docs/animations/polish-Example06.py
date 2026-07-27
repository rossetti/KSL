#!/usr/bin/env python3
"""Polished showcase layout for Example06WarehouseAGV — the richest agent model here.

Four AGVs bid against each other for pallet tasks, route around racks, and peel off to charge. The
generator already draws all of it: the racks from the model's grid graph, the congested aisle beside them,
the planned routes, the heading of each vehicle, and a colour per statechart state. What it cannot know is which colour should mean what,
or that a reader needs to be told the colours are states rather than identities.
"""
import polishkit as kit

d, facts = kit.load("Example06WarehouseAGV")

# The warehouse floor is a *continuous* space with a grid obstacle overlay laid over it — the AGVs travel
# to cell centres rather than hopping cells, so the space they move in is continuous and only the racks are
# on a grid.
floor = next(s for s in d["spaces"] if s["type"] == "Continuous")
SIDE = float(floor["xMax"]) - float(floor["xMin"])
PANEL_X, PANEL_W, PANEL_TOP = SIDE + 2.4, 17.0, 6.4
W, H = PANEL_X + PANEL_W + 2.0, SIDE + 2.4
d["title"] = "Warehouse AGVs — bidding, fetching, charging"
d["width"], d["height"] = round(W, 1), round(H, 1)

# The palette is assigned by sorted state name, which is deterministic and meaningless: it gave Charging the
# red that a reader takes for trouble. These say what an AGV is doing -- waiting for work, competing for it,
# doing it, or out of service to recharge -- and the last is the only one that stops it being useful.
STATES = {"Idle": "#9aa5b1", "Bidding": "#1f77b4", "Working": "#ff7f0e", "Charging": "#d62728"}
assert facts.states == set(STATES), f"model reports {sorted(facts.states)}, not {sorted(STATES)}"
d["agentStateColors"] = STATES
for oc in d["objectClasses"]:
    oc["size"] = 1.15
    oc["color"] = STATES["Idle"]
d["labels"] = [kit.rename("LOCATION", l["locationName"], l["locationName"], -10.0) for l in d["locations"]]

d["clocks"] = [kit.clock(PANEL_X, PANEL_TOP, 1.5)]
d["background"] = [
    kit.text("Four AGVs share one floor. A task is announced,", PANEL_X, PANEL_TOP + 2.0, 0.82),
    kit.text("they bid, and the winner fetches the pallet.", PANEL_X, PANEL_TOP + 3.2, 0.82),
] + [
    kit.text(f"■  {state}", PANEL_X, PANEL_TOP + 5.4 + i * 1.15, 0.78, colour)
    for i, (state, colour) in enumerate(STATES.items())
] + [
    kit.text("Colour is what a vehicle is doing, not which", PANEL_X, PANEL_TOP + 15.6, 0.7, "#999999"),
    kit.text("one it is — the agent counterpart of a", PANEL_X, PANEL_TOP + 16.6, 0.7, "#999999"),
    kit.text("resource going busy and idle. The blue trails", PANEL_X, PANEL_TOP + 17.6, 0.7, "#999999"),
    kit.text("are planned routes around the racks, which", PANEL_X, PANEL_TOP + 18.6, 0.7, "#999999"),
    kit.text("come from the model's own grid graph.", PANEL_X, PANEL_TOP + 19.6, 0.7, "#999999"),
    kit.text("The amber aisle is congested — passable, but", PANEL_X, PANEL_TOP + 21.0, 0.7, "#999999"),
    kit.text("four times the cost to cross. Watch the routes", PANEL_X, PANEL_TOP + 22.0, 0.7, "#999999"),
    kit.text("prefer the western cross-aisle: that is the", PANEL_X, PANEL_TOP + 23.0, 0.7, "#999999"),
    kit.text("grid's cell costs deciding, not the drawing.", PANEL_X, PANEL_TOP + 24.0, 0.7, "#999999"),
]
d["bars"] = [
    kit.bar("NumTasksCompleted", PANEL_X, PANEL_TOP + 11.0, PANEL_W, 1.4,
            facts.scale_for("NumTasksCompleted"), "Pallets delivered", color="#ff7f0e"),
    kit.bar("NumChargingEvents", PANEL_X, PANEL_TOP + 13.4, PANEL_W, 1.4,
            facts.scale_for("NumChargingEvents"), "Charging stops", color="#d62728"),
]
kit.save(d, "Example06WarehouseAGV")
