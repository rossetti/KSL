#!/usr/bin/env python3
"""Polished showcase layout for Example14AnnotatedClinic — the one about making YOUR model animate well.

The clinic itself is small on purpose: one nurse, one queue. What it demonstrates is upstream of the
layout — the model declares its entity types and annotates its processes, so the app knows about `Patient`
and `VipPatient` before a single event is captured, and the two classes can be styled apart.

So the polish leans on exactly that: two visibly different patient classes, and a caption saying where the
distinction came from.
"""
import polishkit as kit

d, facts = kit.load("Example14AnnotatedClinic")

SIZE, ROW_Y = 52.0, 210.0
W, H = 900.0, 450.0
d["title"] = "Annotated clinic — declared entity types, styled apart"
d["width"], d["height"] = W, H

kit.station_row(d, facts, {"Nurse": (620.0, ROW_Y)}, SIZE)

# The whole reason this example exists: two declared classes, told apart at a glance. A VIP is the same
# size as anyone else -- it is priority, not importance -- so only the colour and shape differ.
STYLE = {"Patient": ("#1f77b4", "CIRCLE"), "VipPatient": ("#d62728", "TRIANGLE")}
for oc in d["objectClasses"]:
    colour, shape = STYLE.get(oc["typeName"], ("#1f77b4", "CIRCLE"))
    oc["color"], oc["shape"], oc["size"] = colour, shape, round(SIZE * 0.42, 1)

d["labels"] = [
    kit.rename("RESOURCE", "Nurse", "Nurse", -(SIZE / 2 + 14)),
    kit.count_only("QUEUE", "Nurse:Q", dx=-6.0, dy=SIZE * 0.62),
]
d["clocks"] = [kit.clock(36.0, 52.0, 22.0)]
d["background"] = [
    kit.text("Blue circles are ordinary patients, red triangles are VIPs, and they queue together.",
             36.0, 96.0, 15.0),
    kit.text("The app knew both classes existed before the model was ever run: they are declared with",
             36.0, 132.0, 13.0, "#999999"),
    kit.text("entityType<T>() and the processes carry @KSLAnimatedProcess, so they appear in the",
             36.0, 153.0, 13.0, "#999999"),
    kit.text("Object Styles list ready to be given a look, rather than being discovered from a trace.",
             36.0, 174.0, 13.0, "#999999"),
]
d["bars"] = [
    kit.bar("Nurse:WIP", 36.0, 330.0, 330.0, 24.0, facts.scale_for("Nurse:WIP"), "Patients in the clinic"),
]
kit.save(d, "Example14AnnotatedClinic")
