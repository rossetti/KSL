# Managing KSL MCP Server Processes

KSL can connect Claude Desktop or Codex to three local MCP servers:

- **KSL MCP Server** (`ksl`): runs KSL models and simulations
- **KSL Code MCP Server** (`ksl-code`): answers questions about the KSL source code
- **KSL Book MCP Server** (`ksl-book`): answers questions about the KSL textbook

These servers are Java programs. In your operating system's process viewer they show
up as `java` (sometimes under the JDK vendor's name — for example **Liberica Platform
binary** on Windows):

| Platform | Process viewer | What to look for |
|---|---|---|
| macOS | **Activity Monitor** | `java` |
| Windows | **Task Manager** | `java.exe` / **Liberica Platform binary** |
| Linux | `top`, `ps`, System Monitor | `java` |

This is a short operational guide for **all three** servers: why you may see several of
these Java processes, and how to turn the KSL MCP servers off when you are not using them
(and back on). For what each server *does*, see its own guide — [MCP Server](mcp-server.md),
[Code MCP Server](mcp-server-code.md), [Book MCP Server](mcp-server-book.md).

## Why you may see many Java processes

Claude Desktop and Codex currently start configured stdio MCP servers eagerly. That means
a server can be launched when a chat/session starts, even before you ask to use a KSL tool.

Each configured KSL MCP server is a separate Java process. In some client/session
arrangements, a new chat can start more than one process for the same configured server.

This is expected behavior for the current stdio MCP setup, but it can use noticeable memory
and CPU if many chats are open.

## Recommended practice

Only keep KSL MCP servers configured in Claude Desktop or Codex when you are actively using
KSL tools.

When you are done with KSL MCP work, use the KSL setup applications to remove the KSL MCP
entries from your coding-agent configuration. Restart Claude Desktop or Codex afterward so
the existing Java processes exit.

## Removing KSL MCP entries with the setup applications

KSL installs three setup applications — one per server — that appear in the place your
platform expects:

| Platform | Where to find them |
|---|---|
| macOS | **Launchpad** (also `~/Applications/KSL/`; Spotlight finds them too) |
| Windows | **Start Menu → KSL** folder |
| Linux | your applications menu |

On every platform they are named the same, and each controls one MCP server entry:

| Setup application | Button to click | Removes this MCP entry |
| --- | --- | --- |
| **KSL MCP Setup** | Remove KSL | `ksl` |
| **KSL Code Setup** | Remove KSL Code | `ksl-code` |
| **KSL Book Setup** | Remove KSL Book | `ksl-book` |

Each setup window has three buttons — **Configure my coding agent**, **Self-test**, and the
**Remove** button above. To manage processes you only need the **Remove** button; *Self-test*
just reports the server's status.

**Open the setup application for your platform:**

- **macOS** — Open **Launchpad** and click **KSL MCP Setup**.
- **Windows** — Open the Start Menu, go to the **KSL** folder, and open **KSL MCP Setup**.
- **Linux** — Open **KSL MCP Setup** from your applications menu.

**To remove all KSL MCP servers from Claude Desktop and Codex:**

1. Close or save any active Claude Desktop or Codex work.
2. Open **KSL MCP Setup** (see above) and click **Remove KSL**.
3. Open **KSL Code Setup** and click **Remove KSL Code**.
4. Open **KSL Book Setup** and click **Remove KSL Book**.
5. Restart Claude Desktop and Codex.

After the restart, new chats should no longer launch the KSL MCP Java processes.

## Adding KSL MCP entries again

When you want to use the KSL tools again:

1. Open the relevant KSL setup application (see the table above).
2. Click **Configure my coding agent**.
3. Restart Claude Desktop or Codex.

Use only the setup applications you need:

- For simulation/model tools, configure **KSL MCP Setup**.
- For KSL source-code questions, configure **KSL Code Setup**.
- For KSL textbook questions, configure **KSL Book Setup**.

## Command-line alternatives

The setup applications use the same logic as the command-line `--remove` mode. Advanced
users can remove entries from a terminal. (`.support` is hidden in a file browser on
purpose — it holds the plumbing — but you can still reference its path in a command.)

**macOS / Linux**

```bash
~/Applications/KSL/.support/Servers/mcp/ksl-mcp --remove
~/Applications/KSL/.support/Servers/code/ksl-code-mcp --remove
~/Applications/KSL/.support/Servers/book/ksl-book-mcp --remove
```

**Windows (PowerShell)**

```powershell
& "$env:LOCALAPPDATA\Programs\KSL\.support\Servers\mcp\ksl-mcp.cmd" --remove
& "$env:LOCALAPPDATA\Programs\KSL\.support\Servers\code\ksl-code-mcp.cmd" --remove
& "$env:LOCALAPPDATA\Programs\KSL\.support\Servers\book\ksl-book-mcp.cmd" --remove
```

Then restart Claude Desktop or Codex.

## What the remove option changes

The remove option edits the supported coding-agent configuration files and removes only the
selected KSL MCP server entry.

For Claude Desktop, it removes entries from the `mcpServers` section, such as:

```json
"ksl-book": {
  "command": "java",
  "args": ["-jar", ".../ksl-book-mcp.jar", "--stdio"]
}
```

For Codex, it removes tables such as:

```toml
[mcp_servers.ksl-book]
command = 'java'
args = ['-jar', '...\ksl-book-mcp.jar', '--stdio']
```

The remove option does not uninstall KSL itself, does not delete your KSL workspace, and
does not remove unrelated MCP servers.

## Optional: uninstalling server components

Removing the MCP entries is usually enough. If you also want to remove the installed KSL
server files from the KSL software installation, use the KSL suite manager.

**macOS / Linux**

```bash
~/Applications/KSL/bin/ksl list                # what's installed
~/Applications/KSL/bin/ksl uninstall mcp
~/Applications/KSL/bin/ksl uninstall code
~/Applications/KSL/bin/ksl uninstall book
```

**Windows (PowerShell)**

```powershell
& "$env:LOCALAPPDATA\Programs\KSL\bin\ksl.cmd" list
& "$env:LOCALAPPDATA\Programs\KSL\bin\ksl.cmd" uninstall mcp
& "$env:LOCALAPPDATA\Programs\KSL\bin\ksl.cmd" uninstall code
& "$env:LOCALAPPDATA\Programs\KSL\bin\ksl.cmd" uninstall book
```

This removes the installed server folders and their setup-application shortcuts. It is
separate from the setup application's **Remove** buttons, which remove entries from Claude
Desktop and Codex configurations. See the [installation guide](install.md) for managing the
whole suite.

## Checking whether KSL MCP processes are still running

After removing the entries and restarting Claude Desktop or Codex, your process viewer
should no longer show new KSL MCP Java processes.

- **macOS** — Open **Activity Monitor** and search for `java`. From a terminal:
  `pgrep -fl ksl` or `ps aux | grep -i ksl`.
- **Windows** — Open **Task Manager** and look for `java.exe` / **Liberica Platform binary**.
- **Linux** — `pgrep -fl ksl`, or `ps aux | grep java`.

If Java processes remain, they may belong to:

- an older Claude Desktop or Codex session that is still open;
- Gradle or Kotlin build daemons;
- unrelated Java software.

Closing all Claude Desktop and Codex windows, then reopening only the sessions you need, is
the safest first cleanup step.

## See also

- [MCP Server](mcp-server.md) — the `ksl` server that **runs** models for an assistant.
- [Code MCP Server](mcp-server-code.md) — the `ksl-code` server for **KSL source / API**.
- [Book MCP Server](mcp-server-book.md) — the `ksl-book` server for the **textbook**.
- [Installing the KSL Applications](install.md) — install, update, and uninstall the suite.
