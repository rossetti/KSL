#!/usr/bin/env python3
"""Polished showcase layout for Example15DroneDelivery — three dimensions, drawn in two.

Drones route around no-fly zones through a voxel graph in a 300x300x100 m airspace. The app draws in two
dimensions, so what is on screen is the plan view: altitude is in the trace and is not drawn. Saying that
plainly is most of the polish, because a reader who does not know it will read two drones passing over one
another as a near miss.
"""
import polishkit as kit

d, facts = kit.load("Example15DroneDelivery")

space = next(s for s in d["spaces"] if s["type"] == "Continuous")
SIDE = float(space["xMax"]) - float(space["xMin"])
PANEL_X, PANEL_W, PANEL_TOP = SIDE + 24.0, 150.0, 58.0
W, H = PANEL_X + PANEL_W + 18.0, SIDE + 24.0
d["title"] = "Drone delivery — a 3D airspace in plan view"
d["width"], d["height"] = round(W, 1), round(H, 1)

for oc in d["objectClasses"]:
    oc["size"] = 6.5
    oc["color"] = "#1f77b4"
# A label is drawn rightward from its own position, so one on a drop point near the right-hand edge of the
# airspace runs off the canvas. Flip those to sit on the left of their marker instead.
EDGE = float(space["xMax"]) * 0.8
d["labels"] = [
    {"kind": "LOCATION", "name": l["locationName"], "text": l["locationName"],
     "dx": -56.0 if l["position"]["x"] > EDGE else 0.0, "dy": -10.0}
    for l in d["locations"]
]

d["clocks"] = [kit.clock(PANEL_X, PANEL_TOP, 15.0)]
d["background"] = [
    kit.text("Drones fly between a depot and delivery", PANEL_X, PANEL_TOP + 22.0, 8.2),
    kit.text("points, routing around no-fly zones.", PANEL_X, PANEL_TOP + 33.0, 8.2),
    kit.text("This is the plan view of a 300 x 300 x 100 m", PANEL_X, PANEL_TOP + 128.0, 6.8, "#999999"),
    kit.text("airspace. Altitude is carried in the trace and", PANEL_X, PANEL_TOP + 138.0, 6.8, "#999999"),
    kit.text("is not drawn, so two drones crossing here are", PANEL_X, PANEL_TOP + 148.0, 6.8, "#999999"),
    kit.text("not necessarily near one another.", PANEL_X, PANEL_TOP + 158.0, 6.8, "#999999"),
]
d["bars"] = [
    kit.bar("NumDeliveries", PANEL_X, PANEL_TOP + 56.0, PANEL_W, 13.0,
            facts.scale_for("NumDeliveries"), "Deliveries made", color="#2ca02c"),
    kit.bar("NumIdleDrones", PANEL_X, PANEL_TOP + 82.0, PANEL_W, 13.0,
            facts.scale_for("NumIdleDrones", 1.0), "Drones idle"),
]
kit.save(d, "Example15DroneDelivery")
