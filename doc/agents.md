# Setting up coding agents

There are two ways to give an agent neorepl, and you can use both:

- The skill, [`skills/neorepl/SKILL.md`](../skills/neorepl/SKILL.md), teaches
  the agent to run the `neorepl` commands from its shell. It costs nothing
  until the agent works on Clojure code, and the agent sees exactly what you
  would.
- `neorepl mcp` serves the same features as MCP tools, for agents that
  would rather call tools than run commands, or that can't run commands at all.

Either way, start a server in the project first, with your editor or
`neorepl server`, so there's something to talk to. Both find it through its
port file, so the agent has to run in the project's directory.

## The skill

Codex, Gemini CLI, Cursor and GitHub Copilot all read skills from
`~/.agents/skills`, and Claude Code from `~/.claude/skills`:

```shell
mkdir -p ~/.agents/skills/neorepl
curl -fsSL -o ~/.agents/skills/neorepl/SKILL.md \
  https://raw.githubusercontent.com/nrepl/neorepl/main/skills/neorepl/SKILL.md
```

For Claude Code, the same with `~/.claude/skills/neorepl`. Cursor and
Copilot read that one too, so it's enough when those are the agents you use.
To give everyone working on a project the skill, commit it to the project as
`.agents/skills/neorepl/SKILL.md`, or `.claude/skills/neorepl/SKILL.md` for
Claude Code.

## MCP

### Claude Code

```shell
claude mcp add neorepl -- neorepl mcp
```

That's for you, in this project. `--scope user` makes it yours in every
project, and `--scope project` writes it to the project's `.mcp.json`, for
everyone working on it.

### Codex

```shell
codex mcp add neorepl -- neorepl mcp
```

Codex gives up on a tool call after 60 seconds, which is also how long
neorepl lets an evaluation run before interrupting it. Give Codex longer, so
neorepl gets to interrupt the evaluation and say so, by adding
`tool_timeout_sec` to what that wrote to `~/.codex/config.toml`:

```toml
[mcp_servers.neorepl]
command = "neorepl"
args = ["mcp"]
tool_timeout_sec = 120
```

### Gemini CLI

```shell
gemini mcp add neorepl neorepl mcp
```

That adds it to the project's `.gemini/settings.json`, and `-s user` to
yours, `~/.gemini/settings.json`.

### Cursor

In the project's `.cursor/mcp.json`, or `~/.cursor/mcp.json` for every
project:

```json
{
  "mcpServers": {
    "neorepl": {
      "type": "stdio",
      "command": "neorepl",
      "args": ["mcp"]
    }
  }
}
```

### VS Code

In the project's `.mcp.json`, which Claude Code reads as well:

```json
{
  "mcpServers": {
    "neorepl": {
      "type": "stdio",
      "command": "neorepl",
      "args": ["mcp"]
    }
  }
}
```

### Options

`neorepl mcp --timeout 300` lets evaluations run for 5 minutes instead of 1,
for test suites that take a while, and `neorepl mcp --max-output 50000` cuts
values and output at 50,000 characters instead of 10,000. Put them in the
args, and raise the agent's own timeout to match where it has one, as with
Codex above.

If the agent can't find `neorepl`, which happens to editors started from a
desktop rather than a shell, give the full path that `which neorepl` prints
as the command.
