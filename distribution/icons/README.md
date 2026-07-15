# KSL desktop icons

The eight SVG files under `source/` are the canonical artwork for the KSL desktop
application family. Derived PNG, ICNS, and ICO files are committed under `export/`
so the OS-independent suite build never depends on platform-specific graphics tools.

Each icon uses the same rounded-square silhouette, white task glyph, optical padding,
and small-size-safe stroke weight. Do not edit an exported bitmap or native container
directly; update its SVG source and regenerate every derivative together.

The application-to-file mapping is deliberately identical to the stable distribution
target names: `Single`, `Scenario`, `Experiment`, `Simopt`, `Distribution`, `Results`,
`Bundle`, and `Animation`.
