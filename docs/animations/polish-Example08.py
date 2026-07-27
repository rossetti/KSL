#!/usr/bin/env python3
"""Polished showcase layout for Example08ConveyorTandem — a conveyor at its simplest.

One belt, four anchors, two workers: the introduction to the paradigm that Example 18 then bends into a
loop. The generator now places the workers on the anchors they serve (a part getting off a belt counts as
arriving), so the polish is scale, chrome, and making the belt look like a belt.
"""
import polishkit as kit

d, facts = kit.load("Example08ConveyorTandem")

kit.shift(d, 0.0, 96.0)
SIZE = 40.0
W, H = 1160.0, 560.0
d["title"] = "Tandem queue with a conveyor — parts ride between stations"
d["width"], d["height"] = W, H

place = {r["resourceName"]: (r["position"]["x"], r["position"]["y"]) for r in d["resources"]}
kit.station_row(d, facts, place, SIZE)
for oc in d["objectClasses"]:
    oc["size"] = round(SIZE * 0.42, 1)
    oc["color"] = "#1f77b4"

# A belt draws one square per cell at half its declared width, so left at the default the cells are specks
# with gaps between them. Sized from the pitch, they nearly touch and read as a conveyor.
belt = d["conveyors"][0]
anchors = dict(zip(facts.conveyor["anchorLocations"], facts.conveyor["anchorCells"]))
span_x = max(l["position"]["x"] for l in d["locations"]) - min(l["position"]["x"] for l in d["locations"])
belt["width"] = round(span_x / max(1, max(anchors.values())) * 1.75, 1)
belt["color"] = "#5b6470"
belt["showDirection"] = True

SHORT = {"worker1": "Station 1", "worker2": "Station 2"}
d["labels"] = (
    [kit.rename("RESOURCE", n, t, -(SIZE / 2 + 13)) for n, t in SHORT.items()]
    + [kit.count_only("QUEUE", f"{n}:Q", dy=SIZE * 0.62) for n in SHORT]
    + [kit.hide("LOCATION", n) for n in ("Station1", "Station2")]
)

d["clocks"] = [kit.clock(36.0, 52.0, 22.0)]
d["background"] = list(d.get("background", [])) + [
    kit.text("Parts ride a belt between an entrance, two work stations and an exit.", 36.0, 96.0, 15.0),
    kit.text("Each square is one cell of the conveyor and fills when a part occupies it, so the belt shows",
             36.0, 130.0, 13.0, "#999999"),
    kit.text("its own load. Cells are a resource in their own right: a part that cannot get on waits at the",
             36.0, 151.0, 13.0, "#999999"),
    kit.text("entry, and the queue at station 2 is what happens when the far end cannot keep up.",
             36.0, 172.0, 13.0, "#999999"),
]
d["bars"] = [
    kit.bar("ConveyorTQ:NumInSystem", 36.0, 470.0, 340.0, 24.0,
            facts.scale_for("ConveyorTQ:NumInSystem"), "Parts in the system"),
    kit.bar("Conveyor:NumOccupiedCells", 470.0, 470.0, 340.0, 24.0,
            facts.scale_for("Conveyor:NumOccupiedCells"), "Belt cells occupied", color="#5b6470"),
]
kit.save(d, "Example08ConveyorTandem")
