# KSL

This folder is the **KSL software**. The installer owns it: everything here is replaced when you
update, and deleting the folder uninstalls KSL. Nothing you create should live here.

**Your work lives somewhere else — `~/Documents/KSLWork`** (Windows: `Documents\KSLWork`). Your own
model bundles, saved configurations, and every run's output go there, and updates never touch it.
That separation is the one thing worth remembering about the layout: *software here, work there.*

## What is in this folder

| | What it is |
|---|---|
| **The apps** | Double-click to run. On macOS these are the `KSL *.app` bundles beside this file; on Windows they are Start-Menu entries and shortcuts; on Linux, `.desktop` entries. Nine of them: Single, Scenario, Experiment, Simopt, Distribution, Results, Bundle, Animation, and Server. |
| `bin/ksl` | The manager. `ksl list` shows what is installed; `ksl install <id>` / `ksl uninstall <id>` add or remove an app or server; `ksl update` fetches the latest release; `ksl refresh` rebuilds the desktop entry points. On Windows use `ksl.cmd`. |
| `examples/` | Content to learn from — `examples/bundles/` holds the ready-to-run model bundles from the KSL book, `examples/layouts/` the polished animation layouts. **Replaced wholesale on every update**, so treat them as read-only and copy anything you want to modify into your work folder. |
| `skills/` | Optional instructions that help an AI assistant drive the KSL MCP server correctly. See `skills/README.md`. |
| `.support/` | Plumbing: the shared ~150 MB library every app runs on, the servers, and the command-line tools. Hidden on purpose (on Windows it carries the hidden attribute, since a leading dot means nothing there). You should not need to open it — the exception is the MCP server path, which the KSL Server app and the installer both print when you need it. |

## Running

KSL runs on **your own Java 21** — no runtime is bundled. If an app does not start, check that
`java -version` reports 21 or later; that is the same JDK you would use in IntelliJ.

Every app shares one copy of the library in `.support/lib/`, which is why the whole suite installs
in about the space of a single copy.

## Updating and removing

- **Update:** `bin/ksl update`, or re-run the installer. Your work folder is untouched.
- **Remove one app:** `bin/ksl uninstall <id>` (see `bin/ksl list` for the ids).
- **Remove everything:** delete this folder. `~/Documents/KSLWork` survives — delete that separately
  if you also want your own work gone.

## Learning more

- The KSL book: <https://rossetti.github.io/KSLBook/>
- API documentation: <https://rossetti.github.io/KSLDocs/>
- Start with the **Single** app and a bundle from `examples/bundles/`, or open the **Animation** app
  and pick one of the shipped layouts.
