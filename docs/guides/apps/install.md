# Installing the KSL Applications

The KSL desktop apps, the MCP/REST servers, and the `kslpkg` command-line tool ship
as a single **suite** that runs on your own **Java 21** — no build, no Gradle, no
IntelliJ. One command installs everything into one `KSLWork` folder, where the apps
share a single library folder so the whole suite is about 150 MB, not 150 MB per app.

> **You will need:** **Java 21** — the same JDK you use in IntelliJ. That's the only
> prerequisite. New to the apps? Once installed, read
> [Common UI & concepts](common-ui.md), then the guide for the app you want.

## What you'll be able to do

- Install all the KSL apps, servers, and `kslpkg` with one command.
- Run any app from your `KSLWork` folder.
- Add, remove, and update individual pieces with the `ksl` helper.
- Update later without losing your model bundles or results.

---

## 1. Check Java 21

```
java -version
```

If it reports version 21 (or newer), you're set. If not, install a JDK 21 — the same
one you selected in IntelliJ works — and re-run the check. Nothing else (Gradle,
IntelliJ, the KSL source) is required to run the apps.

---

## 2. Install

**macOS / Linux**

```
curl -fsSL https://raw.githubusercontent.com/rossetti/KSL/main/install.sh | bash
```

**Windows (PowerShell)**

```
irm https://raw.githubusercontent.com/rossetti/KSL/main/install.ps1 -OutFile "$env:TEMP\ksl-install.ps1"; powershell -ExecutionPolicy Bypass -File "$env:TEMP\ksl-install.ps1"
```

The installer verifies Java 21, then unpacks the suite into your `KSLWork` folder:
`$KSLWORK` if set, otherwise `~/Documents/KSLWork` (or `~/KSLWork` if you have no
`Documents`). Re-running it later updates the suite in place.

> **Before the first release is published**, these URLs won't have a payload to
> download yet. Build one yourself and install from it — see
> [§6, Build the payload yourself](#6-build-the-payload-yourself).

---

## 3. What gets installed — the `KSLWork` layout

```
KSLWork/
├── Apps/         Single/ Scenario/ Experiment/ Simopt/
│                 Distribution/ Results/ Bundle/ Animation/
│                 └─ plumbing: each app's jar + the raw launcher behind it
├── Servers/      mcp/ rest/ code/ book/
├── Tools/        kslpkg/
├── lib/          shared libraries (~150 MB) — used by every app and server
├── bin/          ksl — the suite manager (§5)
├── bundles/      ← drop your model bundle JARs here (preserved across updates)
├── manifest.json
└── VERSIONS.txt  what was installed, and when
```

Only `Apps/`, `Servers/`, `Tools/`, `lib/`, and `bin/` are replaced on an update —
your `bundles/` folder and any results you've saved under `KSLWork` are never touched.

---

## 4. Run an app

The installer creates a real, double-clickable entry point for every app, in the place your
platform expects. **That's what you use:**

| Platform | Where |
|---|---|
| macOS | **Launchpad → KSL Single** (the bundles live in `~/Applications/KSL/`) |
| Windows | **Start Menu → KSL → KSL Single** |
| Linux | **KSL Single** in your applications menu |

You don't need to go into `Apps/` at all — that folder is plumbing. `Single.jar` holds only
this app's own classes (everything it depends on is the shared `lib/`), so double-clicking the
jar does nothing; and `Apps/Single/Single` is the raw launcher the entry point calls, which
you *can* run from a terminal, but double-clicking it in a file manager just opens a terminal
window that then has to stay open.

The apps and what each is for:

| App (`Apps/…`) | Guide |
|---|---|
| Single | [Single-Model](single.md) — run one model, read a report |
| Scenario | [Scenario](scenario.md) — compare configurations |
| Experiment | [Experiment](experiment.md) — designed experiments |
| Simopt | [Simopt](simopt.md) — optimize inputs |
| Animation | [Animation](animation.md) — visual, replayable runs |
| Results | [Results](results.md) — browse & compare a results database |
| Distribution | [Distribution](distribution.md) — fit distributions to data |
| Bundle | [Bundle Workbench](bundle-workbench.md) — package models as bundles |

To load a model, drop its bundle JAR into `KSLWork/bundles/` — see
[Common UI & concepts](common-ui.md) for how the apps discover bundles and set the
workspace. The `kslpkg` CLI (`Tools/kslpkg/kslpkg`) and the servers under `Servers/`
round out the suite: point an MCP client at `Servers/mcp/` (or `code`/`book`) as
described in the [MCP Server](mcp-server.md) guide.

---

## 5. Manage the suite with `ksl`

`bin/ksl` (macOS/Linux) — or `bin\ksl` on Windows, via the bundled `ksl.cmd` shim —
adds, removes, and updates individual pieces without a full reinstall:

```
ksl list                 # the catalog, and what's installed
ksl uninstall simopt     # remove one app
ksl install simopt       # add it back
ksl update               # refresh the whole suite (keeps bundles/)
ksl update mcp           # refresh just one item
```

The catalog `<id>`s are: `single`, `scenario`, `experiment`, `simopt`,
`distribution`, `results`, `bundle`, `animation` (apps); `mcp`, `rest`, `code`,
`book` (servers); and `kslpkg`. Add `--from <ksl-suite.zip>` to install or update
from a local payload instead of downloading (useful offline, or before a release
exists).

---

## 6. Build the payload yourself

No published release yet, or you want to install offline from the source tree? Build
the suite payload with Gradle, then hand it to the installer with `--from`:

```
./gradlew assembleKSLWork          # -> build/ksl-suite.zip
```

```
# macOS / Linux
./install.sh --from build/ksl-suite.zip

# Windows (PowerShell)
powershell -ExecutionPolicy Bypass -File install.ps1 -From build\ksl-suite.zip
```

`ksl install`/`ksl update` accept the same `--from build/ksl-suite.zip`.

---

## 7. Update & uninstall

- **Update:** re-run the installer, or `ksl update`. Your `bundles/` and saved
  results are preserved.
- **Remove one piece:** `ksl uninstall <id>`.
- **Remove everything:** delete the `KSLWork` folder.

---

## 8. See also

- [Common UI & concepts](common-ui.md) — bundles, the workspace, themes, reports
- [Single-Model](single.md) — the best first app to try
- [MCP Server](mcp-server.md) — drive KSL from an AI assistant
- [Bundle Tools](bundle-tools.md) — build your own model bundles with `kslpkg`
- [KSL Book](https://rossetti.github.io/KSLBook/)
