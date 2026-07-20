# Releasing the KSL suite

How to cut a `ksl-suite.zip` release so the one-command installers (`install.sh` /
`install.ps1`) have something to download. This is the **only** way the KSL apps, servers
and `kslpkg` are distributed — the old per-app `jpackage` installers, and the `release.yml`
workflow that built them, were removed once this replaced them.

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
