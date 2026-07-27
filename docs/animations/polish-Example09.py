#!/usr/bin/env python3
"""Polished showcase layout for Example09DistancesTandem — the same walk, without coordinates.

Example 02's parts walk a plane and their moves carry real x/y. This model's spatial model is a
DistancesModel: it knows only that Enter is so far from Station 1, and its moves carry NaN. The animation
is nevertheless identical in kind, because the places are named and the layout says where the names are.

That is the whole lesson, and it is why this sits next to Example 02 rather than replacing it. The polish
keeps the generator's placement -- derived from the distance matrix, then turned so the process reads left
to right -- and adds what says so.
"""
import polishkit as kit

d, facts = kit.load("Example09DistancesTandem")

SIZE = 34.0
W, H = 1000.0, 470.0
d["title"] = "Tandem queue on a distance model — named places, no coordinates"
d["width"], d["height"] = W, H

# Down, to clear the header band; a translation keeps the distance matrix's placement intact.
kit.shift(d, 0.0, 60.0)
place = {r["resourceName"]: (r["position"]["x"], r["position"]["y"]) for r in d["resources"]}
kit.station_row(d, facts, place, SIZE)
for oc in d["objectClasses"]:
    oc["size"] = round(SIZE * 0.44, 1)
    oc["color"] = "#1f77b4"

SHORT = {"worker1": "Station 1", "worker2": "Station 2"}
d["labels"] = (
    [kit.rename("RESOURCE", n, t, -(SIZE / 2 + 13)) for n, t in SHORT.items()]
    + [kit.count_only("QUEUE", f"{n}:Q", dy=SIZE * 0.66) for n in SHORT]
    # The two working places are already named by the machine standing on them; Enter and Exit are not.
    + [kit.hide("LOCATION", n) for n in ("Station1", "Station2")]
)

d["clocks"] = [kit.clock(34.0, 50.0, 22.0)]
d["background"] = list(d.get("background", [])) + [
    kit.text("The same walk as the moving-parts example, on a model with no coordinates at all.",
             34.0, 94.0, 15.0),
    kit.text("Its moves report NaN positions and the names of the places they run between. The layout",
             34.0, 128.0, 13.0, "#999999"),
    kit.text("supplies a position for each name — here from the model's distance matrix, turned so the",
             34.0, 149.0, 13.0, "#999999"),
    kit.text("process reads left to right — and every move resolves against them.", 34.0, 170.0, 13.0, "#999999"),
]
d["bars"] = [
    kit.bar("WalkTQ:NumInSystem", 34.0, 380.0, 340.0, 24.0,
            facts.scale_for("WalkTQ:NumInSystem"), "Customers in the system"),
]
d["plots"] = [
    kit.plot("WalkTQ:NumInSystem", 520.0, 350.0, 400.0, 86.0, "Customers in the system over time"),
]
kit.save(d, "Example09DistancesTandem")
