# Releasing the KSL suite

How to cut a `ksl-suite.zip` release so the one-command installers (`install.sh` /
`install.ps1`) have something to download. This is the **only** way the KSL apps, servers
and `kslpkg` are distributed.

The suite is OS-independent (JARs + plain-text launchers), so **one build on any one
OS** produces the payload for macOS, Windows, and Linux — there are no per-OS runners.

## Prerequisites

- **Java 21**, and the `gh` CLI authenticated to `rossetti/KSL`.
- Run every command below **from the repo root** (not from this file's directory).
- **The distribution work must already be on `main`.** The one-liner fetches `install.sh` /
  `install.ps1`, and those in turn fetch `manifest.json`, from
  `raw.githubusercontent.com/rossetti/KSL/main`. If those files aren't on `main` the install
  404s before it ever reads the manifest — so this is a real gate for the *first* release,
  since the work was developed on a feature branch. Check with:

  ```
  git ls-tree --name-only origin/main -- install.sh install.ps1 manifest.json
  ```

  All three must be listed. Steps 4–5 then run **from `main`**, so the tag points at the
  merged commit and the manifest lands where the installers actually read it.
- If book content changed, the repo-root **`_book/`** must be freshly rendered before
  building — the **KSL Server** (`Servers/suite`, whose `book` capability backs
  `search_textbook`) bakes in that content and degrades *silently* to empty search if it was
  missing.
- **Build the animation web player before building the suite.** The Animation app's
  *Export to HTML…* action and the MCP `render_animation_html` tool both embed this bundle in
  the page they produce, and it is packaged into KSLApp as a resource. It is built by the
  standalone `KSLAnimationCore` project, which is deliberately **outside** the root build so
  that an ordinary `./gradlew build` needs no Node.js — which also means an ordinary build does
  not produce it, and a suite built without it ships an app whose export action is disabled.

  ```
  ./gradlew -p KSLAnimationCore jsBrowserProductionWebpack
  ./gradlew :KSLApp:checkAnimationPlayerPackaged
  ```

  The second command prints whether the bundle was found. It needs Node.js, which Gradle
  downloads on first use — so this step (unlike the rest of a release) needs network access the
  first time it runs on a machine.

## Steps

1. **Set the version.** `kslSuiteVersion` in `gradle.properties` is the **single source of
   truth** — the shipped jars stamp their `Implementation-Version` from it, so `/version`,
   `/health`, the console, and the MCP `serverInfo` all report exactly this. Bump it to
   `X.Y.Z` and commit it (on `main`, per the prerequisite) so the release tag names the
   version the binaries actually carry.

2. **Build + stamp.** From `main`, build `build/ksl-suite.zip` and write a stamped manifest to
   `build/release/manifest.json` — the version, the `suite-vX.Y.Z` asset URL, and the zip's
   SHA-256 (the `items` catalog is carried over unchanged):

   ```
   ./gradlew stampSuiteManifest
   ```

   The task builds the zip first (it depends on `packageKSLWork` → `assembleKSLWork`), then
   prints the exact `gh release create …` command for step 4.

   > **`-PreleaseVersion=X.Y.Z` restamps the manifest and tag *only*** — it does **not** change
   > the version baked into the jars (that is always `kslSuiteVersion`). Passing a value that
   > differs from `kslSuiteVersion` would ship a server that reports a different version than
   > its own release. For a real release, set the version in step 1; use `-PreleaseVersion`
   > only for a dry run.

3. **Verify the payload.**

   - **The desktop icon families are complete.** This also runs during assembly, but invoke
     it directly when reviewing an artwork or export change:

     ```
     ./gradlew validateKSLAppIcons
     ```

   - **The stamped manifest** — `build/release/manifest.json`'s `sha256` matches
     `shasum -a 256 build/ksl-suite.zip`, and the `items` catalog is intact.
   - **The KSL Server's book capability has content.** The suite bakes `_book/` in at build
     time and degrades *silently* to empty search if it was missing, so verify rather than
     assume: start the KSL Server and check its console (or `/status`) — the **book**
     capability should report its chunk count, not "not rendered." **0 chunks / "not
     rendered"** means `_book/` wasn't rendered; render it, copy it into the repo root, and
     rebuild before releasing.

4. **Publish the release** (uploads the zip):

   ```
   gh release create suite-vX.Y.Z build/ksl-suite.zip \
     --title "KSL Suite X.Y.Z" \
     --notes "KSL apps + servers + kslpkg sharing one lib/. Install: see the README."
   ```

5. **Commit the stamped manifest** so the installers see the new version:

   ```
   cp build/release/manifest.json manifest.json
   git commit -am "Release KSL Suite X.Y.Z"
   git push
   ```

6. **Smoke-test** on a clean machine (or a scratch `KSLWORK`):

   ```
   curl -fsSL https://raw.githubusercontent.com/rossetti/KSL/main/install.sh | bash
   ```

   Check more than successful startup:

   - **macOS:** all eight apps have distinct Launchpad and Finder icons; opening each app
     preserves that identity in the Dock. `plutil -p` reports a unique
     `io.github.rossetti.ksl.<app>` identifier and the expected `CFBundleIconFile`, and
     `codesign --verify --deep --strict` succeeds.
   - **Windows:** the KSL Start Menu folder shows eight distinct shortcut icons, and the
     matching icon remains on the running window and taskbar.
   - **Linux:** each generated desktop entry has the matching menu icon and launches normally.
   - On every available platform, run `ksl refresh`, a whole-suite `ksl update`, and one
     individual app update; icons must remain correct after each entry point is rebuilt.
   - **The KSL Server works end to end.** The suite ships `Servers/suite` (the **KSL Server**).
     Open **KSL Server** (Launchpad / Start Menu → KSL / applications menu) so its menu-bar /
     system-tray lamp turns green, choose **Open Console** — it opens at
     `http://127.0.0.1:3001/admin` — click **Connect** to configure a client with one click,
     restart that client, and confirm a first tool call (e.g. *"search the textbook for event
     scheduling"*, which exercises the book capability from step 3). The console's **Usage
     study** region exports the local study log as `.jsonl` (all fields) or `.csv` (15
     columns), with filenames `ksl-usage[-<label>]-<date>.<ext>`.

## Notes

- **Empty `sha256`** in the committed manifest is allowed — the installer just skips
  integrity verification. Stamping fills it so downloads are checked.
- **`ksl update`** on an already-installed machine re-reads the manifest and pulls the
  new zip; no reinstall needed.
- **Release publishing stays manual.** Steps 2–5 are not automated: the release bakes in the
  git-ignored `_book/` (see the prerequisite above), so a CI runner would silently ship an empty
  textbook unless it first renders `_book/` and guards on a non-zero chunk count. Until that's
  solved, the manual path here is the source of truth. (A separate `build.yml` CI verifies
  compilation + tests on every push / PR to `main`, but it does not publish.)

## The animation pack (a second, optional asset)

`ksl-animations.zip` is a **separate** release asset. It is not part of `ksl-suite.zip` and the installer
never looks at it: most people installing the apps will never open these, and a download nobody asked for
is the wrong place for 29 MB of simulation traces. Anyone who wants them takes the zip from the release
page.

Inside are fourteen self-contained pages — one per bundled animation model — plus an `index.html` that
links them. Each page carries its player, its trace and its polished layout inside it, so it plays by
double-clicking with nothing installed and nothing served, and a single page can be sent to a student on
its own. Compressed, the set is about 4 MB.

```bash
./gradlew -p KSLAnimationCore jsBrowserProductionWebpack   # the browser player
./gradlew packageAnimations                                # -> build/ksl-animations.zip
```

Two commands, from a fresh clone. `packageAnimations` runs any model whose trace is not already in
`build/showcase` — fourteen captures take about fifteen seconds — and uses the `.lay.toml` layouts committed
under `docs/animations/layouts`, so it needs nothing that is not in the repository. It deliberately does
**not** regenerate those layouts: that is the polish workflow (`polish-<model>.py` then
`publishAnimationLayouts`), and it depends on script output that is not committed.

If it reports a model with **no polished layout**, the bundle has gained a model nobody has polished. That
is a person's job, not something the release should paper over.

The flocking model is deliberately left out of the **download**: eighty agents stepping at a small interval
write 130,000 position events, so its trace alone would be a third of the pack. It still ships in the suite,
and its trace is still captured — everything downstream of a trace needs one, so the polish workflow works
straight after this task with nothing captured by hand. The exclusion list is `AnimationsPackage.excluded`,
with the reason beside it.

Upload `ksl-animations.zip` alongside `ksl-suite.zip` when publishing the release. Unlike the suite asset
it is not referenced by `manifest.json`, so nothing needs stamping and nothing breaks if it is absent.
