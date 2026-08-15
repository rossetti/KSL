# skills/

Agent **skills** for the KSL MCP server — extra instructions a Claude-family assistant loads when
your request matches, so it drives the simulation tools correctly the first time instead of
guessing.

You do not need these to use KSL. The server already tells any connected assistant which tools
exist and how to route between them. A skill adds the layer underneath that: how to author a run
configuration, which inputs are controls and which are distribution parameters, and how a proper
scenario comparison is set up. That guidance is longer than the server's always-on instructions can
afford, so it lives here and is pulled in only when it is relevant.

## What is here

```
skills/
  README.md                          this file
  ksl-simulation/
    SKILL.md                         the instructions themselves
    examples/
      scenario-compare.toml          a working two-scenario comparison to copy
```

| File | What it is |
|---|---|
| `ksl-simulation/SKILL.md` | The skill. Its front matter carries a `name` and a `description`; the assistant reads the description to decide whether the skill applies, then follows the body. |
| `ksl-simulation/examples/scenario-compare.toml` | A complete, runnable `RunConfiguration`: two staffing levels of the drive-through pharmacy, compared in one batch with the database on. Copying a known-good document beats authoring one from scratch, which is where most first-try errors come from. |

## Using it

**Claude Code / Claude Desktop.** Copy the `ksl-simulation` folder into the skills directory your
client reads (Claude Code: `~/.claude/skills/`), then start a new session. Ask a simulation question
normally — the skill loads itself when the request matches its description.

**Other assistants.** Skills are a Claude-family feature. Cursor reads `.cursor/rules/*.mdc`, and
several tools read an `AGENTS.md`; the contents of `SKILL.md` work in those formats too if you paste
them in. Every MCP client, whatever the harness, already receives the server's built-in instructions
without any of this.

**Just reading it.** `SKILL.md` is plain Markdown and is worth a look on its own — it is the
shortest description of how the pieces fit together: discover the model, scaffold a config,
validate, run, compare, fetch the report.

## Where the pieces it mentions live

- Model bundles that ship with KSL: `../examples/bundles/`
- Animation layouts: `../examples/layouts/`
- Your own work (bundles, configs, output): `~/Documents/KSLWork`
- The server itself and how to connect a client to it: see the KSL Server app.
