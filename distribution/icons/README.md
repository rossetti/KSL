# KSL desktop icons

The eight SVG files under `source/` are the canonical artwork for the KSL desktop
application family, and `server.svg` is the shared icon for the three setup-GUI server
entry points (MCP / Code / Book). Derived PNG, ICNS, and ICO files are committed under
`export/` so the OS-independent suite build never depends on platform-specific graphics
tools.

Each icon uses the same rounded-square silhouette, white task glyph, optical padding,
and small-size-safe stroke weight. Do not edit an exported bitmap or native container
directly; update its SVG source and regenerate every derivative together.

The application-to-file mapping is deliberately identical to the stable distribution
target names: `Single`, `Scenario`, `Experiment`, `Simopt`, `Distribution`, `Results`,
`Bundle`, and `Animation`. The shared server icon is `server` — one family reused by all
three GUI server entry points.

## Regenerating exports

1. Edit the canonical SVG, preserving its `1024 × 1024` document size and
   `viewBox="0 0 260 260"` coordinate system.
2. Render RGBA PNGs at `16`, `24`, `32`, `48`, `64`, `128`, `256`, `512`, and
   `1024` pixels. Inspect at least the 32-pixel result without smoothing it larger.
3. Build the ICO from the 16-through-256 PNGs. Build the ICNS with PNG chunks
   `icp4`, `icp5`, `icp6`, `ic07`, `ic08`, `ic09`, and `ic10` so it covers
   16 through 1024 pixels.
4. Replace the complete app directory under `export/`; never update only one
   derived size or native container.
5. Run:

   ```bash
   ./gradlew validateKSLAppIcons :KSLAppSwingCommon:test
   ```

`validateKSLAppIcons` checks source presence, PNG dimensions and alpha, ICO directory
sizes, and ICNS chunks. It runs automatically during both `check` and suite assembly.

## Consumers

- `assembleKSLWork` copies every exported asset beside its app launcher under
  `Apps/<Target>/`.
- `bin/ksl` installs ICNS artwork and stable bundle metadata on macOS and writes
  PNG-backed Linux desktop entries.
- `bin/ksl.ps1` assigns the ICO to each Windows Start Menu shortcut.
- `KSLAppSwingCommon` embeds one shared copy of the 16-through-512 PNGs for Swing
  windows and supported taskbar or Dock APIs.
