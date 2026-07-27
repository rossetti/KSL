#!/usr/bin/env python3
"""Polished showcase layout for Example11Flocking — boids on a torus.

Eighty birds steering by three local rules, in a space whose edges join. The one thing this example must
get across is the wrap: a boid leaving the right edge reappears on the left, and the renderer draws it
taking the short way across the seam rather than streaking back over the whole world. Everything else is
emergent and speaks for itself, so the panel says what to watch for and gets out of the way.
"""
import polishkit as kit

d, facts = kit.load("Example11Flocking")

space = next(s for s in d["spaces"] if s["type"] == "Continuous")
SIDE = float(space["xMax"]) - float(space["xMin"])
PANEL_X, PANEL_W, PANEL_TOP = SIDE + 8.0, 52.0, 20.0
W, H = PANEL_X + PANEL_W + 6.0, SIDE + 8.0
d["title"] = "Flocking on a torus"
d["width"], d["height"] = round(W, 1), round(H, 1)

for oc in d["objectClasses"]:
    oc["size"] = 1.9
    oc["color"] = "#1f77b4"

d["clocks"] = [kit.clock(PANEL_X, PANEL_TOP, 5.0)]
d["background"] = [
    kit.text("Eighty boids, three local rules: keep apart,", PANEL_X, PANEL_TOP + 7.0, 2.7),
    kit.text("match your neighbours, steer toward them.", PANEL_X, PANEL_TOP + 11.0, 2.7),
    kit.text("The space is a torus, so the edges join. Watch", PANEL_X, PANEL_TOP + 40.0, 2.3, "#999999"),
    kit.text("a boid leave one side and arrive on the other:", PANEL_X, PANEL_TOP + 43.5, 2.3, "#999999"),
    kit.text("it is drawn taking the short way across the", PANEL_X, PANEL_TOP + 47.0, 2.3, "#999999"),
    kit.text("seam, not streaking back over the whole world.", PANEL_X, PANEL_TOP + 50.5, 2.3, "#999999"),
]
# Polarization is the flocking measure -- how nearly the birds agree on a direction -- and it runs 0 to 1,
# so its bar is a proportion rather than a count and needs no scaling from the run.
d["bars"] = [
    kit.bar("Polarization", PANEL_X, PANEL_TOP + 18.0, PANEL_W, 4.6, 1.0, "Alignment (0 to 1)"),
    kit.bar("AvgNeighborCount", PANEL_X, PANEL_TOP + 27.0, PANEL_W, 4.6,
            facts.scale_for("AvgNeighborCount"), "Neighbours in view", color="#2ca02c"),
]
kit.save(d, "Example11Flocking")
