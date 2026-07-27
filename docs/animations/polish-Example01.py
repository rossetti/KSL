#!/usr/bin/env python3
"""Polished showcase layout for Example01DriveThroughPharmacy — the simplest animation there is.

One queue, one resource of two pharmacists, and nothing moves. That is the point: it is where a reader
should start, so the job here is not arrangement (the generator gets a single station right) but making
the three things a process view can show unmistakable — who is waiting, who is being served, and what
that adds up to over time.
"""
import polishkit as kit

d, facts = kit.load("Example01DriveThroughPharmacy")

SIZE, ROW_Y = 52.0, 200.0
W, H = 900.0, 470.0
d["title"] = "Drive-through pharmacy — one queue, one server"
d["width"], d["height"] = W, H

kit.station_row(d, facts, {"Pharmacists": (620.0, ROW_Y)}, SIZE)
for oc in d["objectClasses"]:
    oc["size"] = round(SIZE * 0.42, 1)
    oc["color"] = "#1f77b4"

# Two pharmacists, so the resource draws as two cells and either can be busy independently -- the clearest
# possible picture of "capacity" before any of the harder examples introduce movement.
d["labels"] = [
    kit.rename("RESOURCE", "Pharmacists", "Pharmacists (2)", -(SIZE / 2 + 14)),
    kit.count_only("QUEUE", "Pharmacists:Q", dx=-6.0, dy=SIZE * 0.62),
]

d["clocks"] = [kit.clock(36.0, 52.0, 22.0)]
d["background"] = [
    kit.text("Cars arrive, wait in one line, and are served by whichever pharmacist is free.", 36.0, 96.0, 15.0),
    kit.text("A dot is a car; a red cell is a pharmacist serving one; green is free.", 36.0, 120.0, 15.0),
    kit.text("Nothing moves here — position carries no meaning, only occupancy.",
             36.0, ROW_Y + 68.0, 13.0, "#999999"),
]
d["bars"] = [
    kit.bar("DriveThrough:NumInSystem", 36.0, 330.0, 330.0, 24.0,
            facts.scale_for("DriveThrough:NumInSystem"), "Cars in the system"),
]
d["plots"] = [
    kit.plot("DriveThrough:NumInSystem", 470.0, 300.0, 390.0, 92.0, "Cars in the system over time"),
]
kit.save(d, "Example01DriveThroughPharmacy")
