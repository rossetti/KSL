# Releasing the KSL Book MCP Server

A plain-language guide to how this gets built and published. The moving parts:

- **`develop` branch** — where work happens. Pushing here never publishes anything.
- **`main` branch** — the published book (`docs/`) and the source of student releases.
- **The workflow** (`.github/workflows/build-mcp.yml`) — GitHub runs it automatically;
  it builds the jar, runs all tests, and (only from `main`) publishes releases.
- **Releases** — the repo's Releases page, where students download `ksl-book-mcp.jar`.

## Day to day (nothing publishes)

Work on `develop` and push. No release happens. To check that CI is happy with your
changes before merging, open a pull request from `develop` to `main` on GitHub — the
workflow runs build + tests on the PR and attaches the jar to the run (Actions tab →
click the run → "Artifacts") so you can download and try it without publishing anything.

## Publishing the rolling build (first release and every update)

1. On GitHub, open a pull request: base `main`, compare `develop`. Review, then
   click **Merge**. (Command line alternative:
   `git checkout main && git merge develop && git push`.)
2. That push to `main` triggers the workflow automatically. When it finishes
   (Actions tab, green check), the **`book-mcp-latest`** release exists/updates:
   `https://github.com/rossetti/KSLBook/releases/download/book-mcp-latest/ksl-book-mcp.jar`
3. That's it. Every later push to `main` that touches `docs/` or `mcp-server/`
   refreshes this rolling release — including your normal book-HTML updates.

The rolling link is for your own testing. Don't put it in a syllabus; it changes
whenever the book changes.

## Cutting a semester release (the syllabus link)

When you're happy with the rolling build:

1. GitHub → **Actions** tab → select **"Build KSL Book MCP JAR"** in the left sidebar.
2. Click **"Run workflow"** (right side), type a tag like `book-mcp-f26` into the
   `release_tag` box, click the green **Run workflow** button.
3. When the run goes green, the pinned release exists:
   `https://github.com/rossetti/KSLBook/releases/download/book-mcp-f26/ksl-book-mcp.jar`
4. Put that URL in the syllabus. It never changes underneath students — later pushes
   to `main` only touch `book-mcp-latest`, not semester tags.

To update a semester release mid-semester (e.g. a bug fix you want students to get),
run the workflow again with the same tag — it replaces the jar at the same URL.
Students re-download and re-run `--setup`.

## Fixing mistakes

- **Delete a release:** Releases page → click the release → Delete (trash icon), then
  delete its tag under Tags. Re-run the workflow to recreate it.
- **A red X on the workflow run:** click the run → click the failed step to read the
  log. Nothing was published if the build or tests failed — releases only happen after
  both pass.
- **Released too early:** delete the release as above; the jar is gone from the
  download URL immediately.
