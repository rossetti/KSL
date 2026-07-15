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
  building — the bundled `Servers/book` server bakes in that content. See
  [KSLBookServer/RELEASING.md](../KSLBookServer/RELEASING.md).

## Steps

1. **Set the version.** Bump `kslSuiteVersion` in `gradle.properties` (or pass
   `-PreleaseVersion=X.Y.Z` in step 2 to override for this cut only).

2. **Build + stamp.** This builds `build/ksl-suite.zip` and writes a stamped manifest to
   `build/release/manifest.json` — the version, the `suite-vX.Y.Z` asset URL, and the
   zip's SHA-256 (the `items` catalog is carried over unchanged):

   ```
   ./gradlew stampSuiteManifest -PreleaseVersion=X.Y.Z
   ```

3. **Verify the payload.**

   - **The stamped manifest** — `build/release/manifest.json`'s `sha256` matches
     `shasum -a 256 build/ksl-suite.zip`, and the `items` catalog is intact.
   - **The bundled book server has content.** The book jar bakes in `_book/` at build time
     and degrades *silently* if it was missing, so check it rather than assume:

     ```
     java -jar build/kslwork/Servers/book/ksl-book-mcp.jar --doctor
     ```

     A report of **0 chunks** means `_book/` wasn't rendered — render it, copy it into the
     repo root, and rebuild before releasing. See
     [KSLBookServer/RELEASING.md](../KSLBookServer/RELEASING.md).

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

## Notes

- **Empty `sha256`** in the committed manifest is allowed — the installer just skips
  integrity verification. Stamping fills it so downloads are checked.
- **`ksl update`** on an already-installed machine re-reads the manifest and pulls the
  new zip; no reinstall needed.
- To automate steps 2–5 on a `suite-v*` tag, see the optional release workflow (added
  separately); the manual path here is the source of truth and always works.
