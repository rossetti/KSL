# Installing the KSL Applications

The KSL desktop apps, the KSL Server, and the `kslpkg` command-line tool ship
as a single **suite** that runs on your own **Java 21** — no build, no Gradle, no
IntelliJ. One command installs everything into one `KSLWork` folder, where the apps
share a single library folder so the whole suite is about 150 MB, not 150 MB per app.

> **You will need:** **Java 21** — the same JDK you use in IntelliJ. That's the only
> prerequisite. New to the apps? Once installed, read
> [Common UI & concepts](common-ui.md), then the guide for the app you want.

## What you'll be able to do

- Install all the KSL apps, the KSL Server, and `kslpkg` with one command.
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

The installer verifies Java 21, then installs the software into `~/Applications/KSL`
(Windows: `%LOCALAPPDATA%\Programs\KSL`) and creates the app entry points. Your work stays
in a separate folder — see §3. Re-running it later updates the software in place.

> **Before the first release is published**, these URLs won't have a payload to
> download yet. Install from a `ksl-suite.zip` you build yourself instead — see
> [§6, Install from a local payload](#6-install-from-a-local-payload).

---

## 3. What gets installed — two folders, kept apart

**The software** — where the apps live. The installer owns this folder; deleting it
uninstalls KSL.

```
~/Applications/KSL/                 (Windows: %LOCALAPPDATA%\Programs\KSL)
├── KSL Single.app   KSL Scenario.app   …      ← the apps you double-click
├── bin/ksl                                     ← the suite manager (§5)
└── .support/   (hidden — you never need to open it)
      Apps/     each app's jar + the raw launcher behind it
      lib/      the shared libraries (~150 MB) — ONE copy, used by every app and the server
      bundles/  the example models that ship with KSL (see below)
      Servers/  suite/ (the KSL Server)
      Tools/    kslpkg/
      manifest.json, VERSIONS.txt
```

**You already have models.** The suite ships the **KSL Book Examples** (the textbook's models)
and the **KSL Animation Examples** (the animated gallery), so every app has something to run
the moment it opens — no building required. They live with the software, so updates
refresh them and uninstalling removes them; they never clutter your work folder. If you want
to change one, drop your own copy into `KSLWork/bundles/` — yours takes precedence over the
shipped one of the same name.

**Your work** — where *you* keep things. The apps own this folder; the installer only ever
creates `bundles/` in it, and updates never touch it.

```
~/Documents/KSLWork/
├── bundles/      ← drop your model bundle JARs here
└── KSLSingle/  KSLResults/  …    ← each app's configs and output
```

You can move the work folder anywhere from **File ▸ Set Working Directory…** in any app;
the choice is remembered in `~/.ksl/settings.toml`.

Because the apps resolve their own installation *relative to themselves*, you can also
rename or move `~/Applications/KSL` and everything keeps working.

---

## 4. Run an app

The installer creates a real, double-clickable entry point for every app, in the place your
platform expects. **That's what you use:**

| Platform | Where |
|---|---|
| macOS | **Launchpad → KSL Single** (the bundles live in `~/Applications/KSL/`) |
| Windows | **Start Menu → KSL → KSL Single** |
| Linux | **KSL Single** in your applications menu |

Everything else is deliberately out of your way inside `.support/`: `Single.jar` holds only
this app's own classes (all its dependencies are the shared `lib/`), so it isn't runnable on
its own, and `Apps/Single/Single` is just the raw launcher the app bundle calls.

The apps and what each is for:

| App | Guide |
|---|---|
| Single | [Single-Model](single.md) — run one model, read a report |
| Scenario | [Scenario](scenario.md) — compare configurations |
| Experiment | [Experiment](experiment.md) — designed experiments |
| Simopt | [Simopt](simopt.md) — optimize inputs |
| Animation | [Animation](animation.md) — visual, replayable runs |
| Results | [Results](results.md) — browse & compare a results database |
| Distribution | [Distribution](distribution.md) — fit distributions to data |
| Bundle | [Bundle Workbench](bundle-workbench.md) — package models as bundles |

To load a model, drop its bundle JAR into `~/Documents/KSLWork/bundles/` — see
[Common UI & concepts](common-ui.md) for how the apps discover bundles and set the
workspace. The `kslpkg` CLI and the KSL Server round out the suite; both live under the
software's `.support/` folder (`.support/Tools/kslpkg/kslpkg`,
`.support/Servers/suite/`). To connect an AI assistant, use the
**[KSL Server](ksl-server.md)** — one server for all three tool surfaces (models, textbook,
source code), set up with one click from its web console, no config editing.

---

## 5. Manage the suite with `ksl`

`~/Applications/KSL/bin/ksl` (macOS/Linux) — or `bin\ksl` on Windows, via the bundled
`ksl.cmd` shim — adds, removes, and updates individual pieces without a full reinstall:

```
ksl list                 # the catalog, and what's installed
ksl uninstall simopt     # remove one app
ksl install simopt       # add it back
ksl update               # refresh the whole suite (keeps bundles/)
ksl update single        # refresh just one item
```

The catalog `<id>`s are: `single`, `scenario`, `experiment`, `simopt`,
`distribution`, `results`, `bundle`, `animation` (apps); `suite` — the KSL Server (server);
and `kslpkg`. Add `--from <ksl-suite.zip>` to install or update
from a local payload instead of downloading (useful offline, or before a release
exists).

---

## 6. Install from a local payload

No published release yet, or you want to install offline? Both installers accept a
`ksl-suite.zip` you already have, instead of downloading one:

```
# macOS / Linux
./install.sh --from build/ksl-suite.zip

# Windows (PowerShell)
powershell -ExecutionPolicy Bypass -File install.ps1 -From build\ksl-suite.zip
```

`ksl install`/`ksl update` accept the same `--from build/ksl-suite.zip`.

Building that payload from the source tree is a maintainer task — see
[Releasing the KSL suite](../../releasing-suite.md) for the current Gradle recipe.

---

## 7. Update & uninstall

- **Update:** re-run the installer, or `ksl update`. Only the software is replaced — your
  work folder is never touched.
- **Remove one piece:** `ksl uninstall <id>`.
- **Remove everything:** delete `~/Applications/KSL` (Windows: `%LOCALAPPDATA%\Programs\KSL`).
  Your work folder survives; delete it separately if you really want it gone.
- **Just stop the server** (no uninstall needed): quit **KSL Server** from its menu-bar / tray
  icon, or click **Disconnect** in its console — see the [KSL Server guide](ksl-server.md).

---

## 8. See also

- [Common UI & concepts](common-ui.md) — bundles, the workspace, themes, reports
- [Single-Model](single.md) — the best first app to try
- [KSL Server](ksl-server.md) — drive KSL from an AI assistant (one server, one-click setup)
- [Bundle Tools](bundle-tools.md) — build your own model bundles with `kslpkg`
- [KSL Book](https://rossetti.github.io/KSLBook/)
