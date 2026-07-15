# distribution/

Source assets that ship **inside** `ksl-suite.zip`. These are build inputs — nobody fetches
them from this repo, and they are not run from here.

`bin/` mirrors the payload layout: `assembleKSLWork` copies each file below into `bin/` in the
zip, which becomes `KSLWork/bin/` once a student installs. So `distribution/bin/ksl` is the
source of the `ksl` command a student actually runs.

| File | Ships as | Role |
|---|---|---|
| `bin/ksl` | `KSLWork/bin/ksl` | suite manager (bash, macOS/Linux) — `list` / `install` / `uninstall` / `update` |
| `bin/ksl.ps1` | `KSLWork/bin/ksl.ps1` | the same, in PowerShell (Windows) |
| `bin/ksl.cmd` | `KSLWork/bin/ksl.cmd` | shim so plain `ksl <cmd>` runs `ksl.ps1` under any execution policy |

## What is deliberately *not* here

- **`install.sh` / `install.ps1` / `manifest.json` stay at the repo root.** They are fetched
  over HTTPS (`raw.githubusercontent.com/rossetti/KSL/main/…`) by a student who has nothing
  installed yet, so they need a short, stable URL. They never ship inside the zip — they are
  how you *get* the zip.
- **The per-app launchers are generated, not stored.** `assembleKSLWork` writes them from
  string templates in the root `build.gradle.kts`, since each needs per-app interpolation.
  The files here are full programs, so they live as reviewable source instead.

See [docs/releasing-suite.md](../docs/releasing-suite.md) for how a release is cut.
