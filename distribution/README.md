# distribution/

Source assets that ship **inside** `ksl-suite.zip`. These are build inputs — nobody fetches
them from this repo, and they are not run from here.

`assembleKSLWork` copies each file below into `bin/` in the zip. The installer unpacks the
payload into the software root's hidden `.support/`, then lifts `bin/` up next to the app
bundles — so `distribution/bin/ksl` is the source of the `ksl` command a student runs at
`~/Applications/KSL/bin/ksl` (Windows: `%LOCALAPPDATA%\Programs\KSL\bin\ksl`).

| File | Ships as | Role |
|---|---|---|
| `bin/ksl` | `<KSL_HOME>/bin/ksl` | suite manager (bash, macOS/Linux) — `list` / `install` / `uninstall` / `update` / `refresh` |
| `bin/ksl.ps1` | `<KSL_HOME>\bin\ksl.ps1` | the same, in PowerShell (Windows) |
| `bin/ksl.cmd` | `<KSL_HOME>\bin\ksl.cmd` | shim so plain `ksl <cmd>` runs `ksl.ps1` under any execution policy |

The helpers also build each platform's **entry points** (macOS `.app` bundles via
`osacompile`, Windows Start-Menu `.lnk`s, Linux `.desktop` files) — see `ksl refresh`. The
installer calls it, so a fresh install and `ksl update` cannot drift apart.

## What is deliberately *not* here

- **`install.sh` / `install.ps1` / `manifest.json` stay at the repo root.** They are fetched
  over HTTPS (`raw.githubusercontent.com/rossetti/KSL/main/…`) by a student who has nothing
  installed yet, so they need a short, stable URL. They never ship inside the zip — they are
  how you *get* the zip.
- **The per-app launchers are generated, not stored.** `assembleKSLWork` writes them from
  string templates in the root `build.gradle.kts`, since each needs per-app interpolation.
  The files here are full programs, so they live as reviewable source instead.

See [docs/releasing-suite.md](../docs/releasing-suite.md) for how a release is cut.
